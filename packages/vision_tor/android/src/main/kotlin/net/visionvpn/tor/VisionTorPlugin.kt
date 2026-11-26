package net.visionvpn.tor

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.torproject.jni.TorService
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject

/** Android Tor engine bridge. It starts Guardian Project's TorService inside
 * the application process and exposes only a loopback SOCKS5 endpoint. */
class VisionTorPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private lateinit var appContext: Context
    private lateinit var methods: MethodChannel
    private lateinit var events: EventChannel
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val starting = AtomicBoolean(false)
    @Volatile private var state = "stopped"
    @Volatile private var progress = 0
    @Volatile private var socksPort: Int? = null
    @Volatile private var statusMessage = ""
    @Volatile private var eventSink: EventChannel.EventSink? = null
    @Volatile private var torService: TorService? = null
    @Volatile private var isBound = false
    @Volatile private var serviceReportedOn = false
    private var statusReceiver: BroadcastReceiver? = null
    private var pollTask: ScheduledFuture<*>? = null
    @Volatile private var retryTask: ScheduledFuture<*>? = null
    @Volatile private var userRequestedStop = false
    @Volatile private var retryCount = 0
    @Volatile private var lastProfile: Map<String, Any?>? = null
    private val profileStore by lazy { EncryptedProfileStore(appContext) }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        methods = MethodChannel(binding.binaryMessenger, "vision_vpn/tor/methods")
        events = EventChannel(binding.binaryMessenger, "vision_vpn/tor/events")
        methods.setMethodCallHandler(this)
        events.setStreamHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methods.setMethodCallHandler(null)
        events.setStreamHandler(null)
        executor.execute { stopInternal() }
        executor.shutdown()
    }

    override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
        eventSink = sink
        emit()
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "start" -> {
                val profile = call.arguments as? Map<*, *>
                if (profile == null) {
                    result.error("INVALID_PROFILE", "A Tor profile is required.", null)
                    return
                }
                executor.execute {
                    try {
                        startInternal(profile)
                        complete(result, null)
                    } catch (error: Throwable) {
                        setState("error", 0, "Tor could not be started.")
                        completeError(result, "TOR_START_FAILED", safeError(error))
                    }
                }
            }
            "stop" -> executor.execute {
                try {
                    stopInternal()
                    complete(result, null)
                } catch (error: Throwable) {
                    completeError(result, "TOR_STOP_FAILED", safeError(error))
                }
            }
            "status" -> complete(result, statusMap())
            "capabilities" -> complete(result, capabilities())
            "socksOutbound" -> {
                try {
                    complete(result, socksOutbound(call.argument<String>("tag") ?: "vision-tor"))
                } catch (error: Throwable) {
                    completeError(result, "TOR_NOT_READY", safeError(error))
                }
            }
            "saveProfile" -> {
                val profile = call.arguments as? Map<*, *>
                if (profile == null) result.error("INVALID_PROFILE", "A profile is required.", null)
                else executor.execute {
                    try {
                        profileStore.save(profile)
                        complete(result, null)
                    } catch (error: Throwable) {
                        completeError(result, "PROFILE_SAVE_FAILED", safeError(error))
                    }
                }
            }
            "profiles" -> executor.execute {
                try { complete(result, profileStore.all()) }
                catch (error: Throwable) { completeError(result, "PROFILE_READ_FAILED", safeError(error)) }
            }
            "deleteProfile" -> {
                val id = call.argument<String>("id")
                if (id.isNullOrBlank()) result.error("INVALID_ID", "Profile id is required.", null)
                else executor.execute {
                    try { profileStore.delete(id); complete(result, null) }
                    catch (error: Throwable) { completeError(result, "PROFILE_DELETE_FAILED", safeError(error)) }
                }
            }
            else -> result.notImplemented()
        }
    }

    private fun startInternal(profile: Map<*, *>, isRetry: Boolean = false) {
        if (state == "connected" || state == "starting" || state == "bootstrapping") return
        if (!starting.compareAndSet(false, true)) return
        userRequestedStop = false
        serviceReportedOn = false
        if (!isRetry) {
            retryCount = 0
            lastProfile = profile.entries.associate { it.key.toString() to it.value }
        }
        try {
            val config = renderTorrc(profile)
            // torrc is app-private and is removed once the SOCKS port is known.
            // Saved profile data is encrypted separately with Android Keystore.
            TorService.getTorrc(appContext).writeText(config, Charsets.UTF_8)
            registerReceiver()
            setState("starting", 0, "Starting the Tor engine.")
            val intent = Intent(appContext, TorService::class.java)
            // Binding with BIND_AUTO_CREATE starts Guardian's TorService. Keep
            // this binding for the entire Tor session; no separate VPN service
            // and no unbound background-service start are required.
            isBound = appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            check(isBound) { "Unable to bind to the local Tor service." }
            pollTask?.cancel(true)
            pollTask = executor.scheduleAtFixedRate({ pollTor() }, 1, 1, TimeUnit.SECONDS)
        } catch (error: Throwable) {
            starting.set(false)
            unregisterReceiver()
            throw error
        }
    }

    private fun renderTorrc(profile: Map<*, *>): String {
        val transport = profile["transport"]?.toString() ?: "direct"
        val lines = mutableListOf(
            "ClientOnly 1",
            "SocksPort 127.0.0.1:auto IsolateSOCKSAuth",
            "SafeLogging 1",
            "AvoidDiskWrites 1"
        )
        when (transport) {
            "direct" -> lines += "UseBridges 0"
            "customBridges" -> {
                val bridges = (profile["bridgeLines"] as? List<*>)
                    ?.mapNotNull { it as? String }
                    ?.map { normalizeDirectBridge(it) }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()
                require(bridges.isNotEmpty()) { "Add at least one direct bridge line." }
                lines += "UseBridges 1"
                bridges.forEach { lines += "Bridge $it" }
            }
            "obfs4", "snowflake", "meek" -> throw UnsupportedOperationException(
                "This transport is disabled because its Android pluggable-transport binary is not bundled."
            )
            else -> throw IllegalArgumentException("Unknown Tor transport.")
        }
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun normalizeDirectBridge(raw: String): String {
        val value = raw.trim().removePrefix("Bridge ").trim()
        require(value.isNotEmpty()) { "Empty bridge entry." }
        require(!value.contains('\n') && !value.contains('\r') && !value.contains(';') &&
            !value.contains('"') && !value.contains('\\') && !value.contains('#')) {
            "Invalid bridge entry."
        }
        require(!value.startsWith("obfs4 ") && !value.startsWith("snowflake ") && !value.startsWith("meek ")) {
            "Pluggable-transport bridges require a bundled native transport binary."
        }
        val directBridgePattern = Pattern.compile(
            "^(\\[[0-9A-Fa-f:]+]|[A-Za-z0-9.-]+):([1-9][0-9]{0,4})(?:\\s+[A-Fa-f0-9]{40})?$"
        )
        require(directBridgePattern.matcher(value).matches()) {
            "Bridge must be host:port with an optional 40-character fingerprint."
        }
        val portText = value.substringAfterLast(':').substringBefore(' ')
        val port = portText.toIntOrNull() ?: 0
        require(port in 1..65535) { "Bridge port must be between 1 and 65535." }
        return value
    }

    private fun registerReceiver() {
        if (statusReceiver != null) return
        statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    TorService.ACTION_STATUS -> when (intent.getStringExtra(TorService.EXTRA_STATUS)) {
                        TorService.STATUS_STARTING -> setState("starting", progress, "Tor is starting.")
                        TorService.STATUS_ON -> {
                            serviceReportedOn = true
                            progress = 100
                            val port = torService?.getSocksPort()?.takeIf { it > 0 } ?: socksPort
                            if (port != null && port > 0 && loopbackReady(port)) {
                                socksPort = port
                                setState("connected", 100, "Tor circuit established.")
                                runCatching { TorService.getTorrc(appContext).delete() }
                            } else {
                                setState("bootstrapping", progress, "Checking the Tor SOCKS listener.")
                            }
                        }
                        TorService.STATUS_STOPPING -> setState("stopping", progress, "Stopping Tor.")
                        TorService.STATUS_OFF -> {
                            starting.set(false)
                            socksPort = null
                            if (userRequestedStop) {
                                setState("stopped", 0, "Tor stopped.")
                            } else {
                                scheduleRetry("Tor stopped unexpectedly.")
                            }
                        }
                    }
                    TorService.ACTION_ERROR -> {
                        // Do not forward raw Tor errors: they may include bridge configuration.
                        if (!userRequestedStop) scheduleRetry("Tor reported an internal startup error.")
                        else setState("error", progress, "Tor reported an internal startup error.")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(TorService.ACTION_STATUS)
            addAction(TorService.ACTION_ERROR)
        }
        ContextCompat.registerReceiver(appContext, statusReceiver!!, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? TorService.LocalBinder ?: return
            torService = local.getService()
            val port = torService?.getSocksPort() ?: -1
            if (port > 0) {
                socksPort = port
                runCatching { TorService.getTorrc(appContext).delete() }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            torService = null
            socksPort = null
            serviceReportedOn = false
            if (!userRequestedStop && state != "stopped") {
                scheduleRetry("Tor service disconnected.")
            }
        }
    }

    private fun pollTor() {
        val service = torService ?: return
        try {
            val info = service.getInfo("status/bootstrap-phase") ?: ""
            val match = Regex("PROGRESS=([0-9]{1,3})").find(info)
            val value = match?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100) ?: progress
            if (state != "connected" && state != "stopping") {
                setState("bootstrapping", value, "Tor is establishing a circuit.")
            }
            val port = service.getSocksPort()
            if (port > 0) {
                socksPort = port
                runCatching { TorService.getTorrc(appContext).delete() }
                if (value >= 100 && serviceReportedOn && loopbackReady(port) && state != "connected") {
                    // Guardian TorService reports 100% once the first circuit is
                    // established. Require the local SOCKS socket to be reachable
                    // as a second independent readiness check.
                    retryCount = 0
                    setState("connected", 100, "Tor circuit established.")
                }
            }
            if (state == "connected" && (port <= 0 || !loopbackReady(port))) {
                serviceReportedOn = false
                setState("reconnecting", value, "Tor SOCKS listener is unavailable.")
                scheduleRetry("Tor SOCKS listener is unavailable.")
            }
        } catch (_: Throwable) {
            if (state != "stopping" && state != "stopped") {
                setState("reconnecting", progress, "Checking Tor connection.")
            }
        }
    }

    private fun loopbackReady(port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 800)
            true
        }
    } catch (_: Throwable) { false }

    private fun socksOutbound(tag: String): Map<String, Any> {
        check(state == "connected") { "Tor has not completed bootstrap." }
        val port = socksPort ?: throw IllegalStateException("SOCKS port is unavailable.")
        check(port in 1..65535 && loopbackReady(port)) { "Local Tor SOCKS listener is not reachable." }
        return mapOf(
            "type" to "socks",
            "tag" to tag,
            "server" to "127.0.0.1",
            "server_port" to port,
            "version" to "5"
        )
    }

    private fun capabilities(): List<Map<String, Any>> = listOf(
        mapOf("transport" to "direct", "supported" to true, "reason" to "Bundled Tor engine."),
        mapOf("transport" to "customBridges", "supported" to true, "reason" to "Direct bridge relays only; no pluggable transport."),
        mapOf("transport" to "obfs4", "supported" to false, "reason" to "obfs4proxy binary is not bundled."),
        mapOf("transport" to "snowflake", "supported" to false, "reason" to "Snowflake client binary is not bundled."),
        mapOf("transport" to "meek", "supported" to false, "reason" to "Meek client binary is not bundled.")
    )

    private fun stopInternal() {
        userRequestedStop = true
        retryTask?.cancel(true)
        retryTask = null
        if (state == "stopped") return
        setState("stopping", progress, "Stopping Tor.")
        pollTask?.cancel(true)
        pollTask = null
        if (isBound) {
            runCatching { appContext.unbindService(serviceConnection) }
            isBound = false
        }
        unregisterReceiver()
        torService = null
        socksPort = null
        serviceReportedOn = false
        starting.set(false)
        setState("stopped", 0, "Tor stopped.")
    }

    private fun scheduleRetry(reason: String) {
        if (userRequestedStop) return
        if (retryTask?.isDone == false) return
        if (retryCount >= 3) {
            setState("error", progress, "$reason Retry limit reached.")
            return
        }
        val delays = longArrayOf(2, 5, 15)
        val delay = delays[retryCount.coerceIn(0, delays.lastIndex)]
        retryCount += 1
        setState("reconnecting", progress, "$reason Retrying in ${delay}s.")
        retryTask?.cancel(false)
        retryTask = executor.schedule({
            retryTask = null
            if (userRequestedStop) return@schedule
            val profile = lastProfile
            if (profile == null) {
                setState("error", progress, "No saved Tor profile is available for restart.")
                return@schedule
            }
            if (isBound) {
                runCatching { appContext.unbindService(serviceConnection) }
                isBound = false
            }
            unregisterReceiver()
            torService = null
            socksPort = null
            serviceReportedOn = false
            starting.set(false)
            try {
                startInternal(profile, isRetry = true)
            } catch (_: Throwable) {
                scheduleRetry("Tor restart failed.")
            }
        }, delay, TimeUnit.SECONDS)
    }

    private fun unregisterReceiver() {
        statusReceiver?.let { receiver -> runCatching { appContext.unregisterReceiver(receiver) } }
        statusReceiver = null
    }

    private fun setState(newState: String, newProgress: Int, message: String) {
        state = newState
        progress = newProgress.coerceIn(0, 100)
        statusMessage = message
        emit()
    }

    private fun statusMap(): Map<String, Any?> = mapOf(
        "state" to state,
        "bootstrapPercent" to progress,
        "socksHost" to "127.0.0.1",
        "socksPort" to socksPort,
        "message" to statusMessage
    )

    private fun emit() {
        val snapshot = statusMap()
        mainHandler.post { eventSink?.success(snapshot) }
    }

    private fun complete(result: MethodChannel.Result, value: Any?) {
        mainHandler.post { result.success(value) }
    }

    private fun completeError(result: MethodChannel.Result, code: String, message: String) {
        mainHandler.post { result.error(code, message, null) }
    }

    private fun safeError(error: Throwable): String = when (error) {
        is UnsupportedOperationException -> error.message ?: "Transport is unavailable."
        is IllegalArgumentException -> error.message ?: "Invalid Tor configuration."
        else -> "Tor operation failed. Check the app's diagnostic status."
    }

    private class EncryptedProfileStore(context: Context) {
        private val prefs = context.getSharedPreferences("vision_tor_profiles_v1", Context.MODE_PRIVATE)
        private val alias = "vision-vpn-tor-profile-aes-gcm-v1"

        @Synchronized fun save(profile: Map<*, *>) {
            val id = profile["id"]?.toString()?.trim().orEmpty()
            require(id.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Invalid profile id." }
            val json = JSONObject().apply {
                put("id", id)
                put("name", profile["name"]?.toString()?.take(80) ?: "Tor profile")
                put("transport", profile["transport"]?.toString() ?: "direct")
                put("bridgeLines", JSONArray((profile["bridgeLines"] as? List<*>)?.map { it.toString() } ?: emptyList<String>()))
            }.toString()
            prefs.edit().putString(id, encrypt(json)).apply()
        }

        @Synchronized fun all(): List<Map<String, Any>> {
            return prefs.all.values.mapNotNull { encoded ->
                val value = encoded as? String ?: return@mapNotNull null
                val json = JSONObject(decrypt(value))
                val bridges = json.optJSONArray("bridgeLines") ?: JSONArray()
                mapOf(
                    "id" to json.getString("id"),
                    "name" to json.optString("name", "Tor profile"),
                    "transport" to json.optString("transport", "direct"),
                    "bridgeLines" to (0 until bridges.length()).map { bridges.getString(it) }
                )
            }
        }

        @Synchronized fun delete(id: String) { prefs.edit().remove(id).apply() }

        private fun encrypt(value: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getKey())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        }

        private fun decrypt(value: String): String {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            require(bytes.size > 12) { "Encrypted profile data is invalid." }
            val iv = bytes.copyOfRange(0, 12)
            val ciphertext = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
            return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }

        private fun getKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = keyStore.getKey(alias, null) as? SecretKey
            if (existing != null) return existing
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            return generator.generateKey()
        }
    }
}
