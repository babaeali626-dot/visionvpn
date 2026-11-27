package com.hiddify.hiddify.tor

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.hiddify.hiddify.Settings
import org.torproject.jni.TorService
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the embedded Tor Android service, Pluggable Transport configuration (Automatic, Snowflake,
 * obfs4), SOCKS5 handshake verification, continuous health watchdog, and Fail-Closed notifications.
 *
 * It never creates a second Android VPN service; sing-box/VPNService consumes the verified
 * loopback SOCKS5 listener and enforces strict routing + fail-closed blackhole protection.
 */
internal class TorEngineManager private constructor(private val context: Context) {
    enum class State {
        STOPPED,
        STARTING,
        BOOTSTRAPPING,
        VERIFYING_SOCKS,
        CONNECTED,
        FAIL_CLOSED,
        RECOVERING,
        STOPPING,
        ERROR,
    }

    data class Snapshot(
        val state: String,
        val bootstrap: Int,
        val bootstrapSummary: String?,
        val socksHost: String?,
        val socksPort: Int?,
        val transportMode: String,
        val activeTransport: String,
        val transportStatus: String?,
        val transportError: String?,
        val snowflakeAvailable: Boolean,
        val obfs4Available: Boolean,
        val failClosed: Boolean,
        val error: String?,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "state" to state,
            "bootstrap" to bootstrap,
            "bootstrapSummary" to bootstrapSummary,
            "socksHost" to socksHost,
            "socksPort" to socksPort,
            "transportMode" to transportMode,
            "activeTransport" to activeTransport,
            "transportStatus" to transportStatus,
            "transportError" to transportError,
            "snowflakeAvailable" to snowflakeAvailable,
            "obfs4Available" to obfs4Available,
            "failClosed" to failClosed,
            "error" to error,
        )
    }

    interface FailClosedListener {
        fun onTorFailClosed(reason: String)
        fun onTorRecovered(socksHost: String, socksPort: Int)
    }

    private var service: TorService? = null
    private var bound = false
    private var receiverRegistered = false
    private val localBroadcasts by lazy { LocalBroadcastManager.getInstance(context) }

    private var state = State.STOPPED
    private var bootstrap = 0
    private var bootstrapSummary: String? = null
    private var error: String? = null
    private var transportMode: String = TorTransportConfig.normalizeMode(Settings.torTransportMode)
    private var activeTransport: String = "direct"
    private var transportStatus: String? = null
    private var transportError: String? = null
    private var failClosed: Boolean = false
    private var socksPort: Int? = null

    private var lastBridges: List<String> = emptyList()
    private var autoFallbackAttempted = false
    private var bootstrapStartedAtMs = 0L
    private var consecutiveProbeFailures = 0

    private var callback: ((Snapshot) -> Unit)? = null
    private val failClosedListeners = CopyOnWriteArraySet<FailClosedListener>()
    private val starting = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "VisionVPN-TorWatchdog").apply { isDaemon = true }
    }

    private val bootstrapPoll = object : Runnable {
        override fun run() {
            if (state != State.BOOTSTRAPPING && state != State.RECOVERING) return
            ioExecutor.execute {
                pollBootstrapPhase()
            }
            mainHandler.postDelayed(this, BOOTSTRAP_POLL_MS)
        }
    }

    private val healthWatchdog = object : Runnable {
        override fun run() {
            if (state != State.CONNECTED) return
            val port = socksPort
            if (port == null || service == null) {
                triggerFailClosed("Tor daemon or SOCKS5 listener is no longer active")
                return
            }
            ioExecutor.execute {
                val ok = probeSocks5Loopback(port, PROBE_TIMEOUT_MS)
                synchronized(this@TorEngineManager) {
                    if (state != State.CONNECTED) return@execute
                    if (ok) {
                        consecutiveProbeFailures = 0
                    } else {
                        consecutiveProbeFailures++
                        Log.w(TAG, "Tor SOCKS5 health probe failed ($consecutiveProbeFailures/$MAX_PROBE_FAILURES)")
                        if (consecutiveProbeFailures >= MAX_PROBE_FAILURES) {
                            triggerFailClosed("Tor SOCKS5 listener 127.0.0.1:$port stopped responding")
                            return@execute
                        }
                    }
                }
                mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == TorService.ACTION_ERROR) {
                val msg = intent.getStringExtra(TorService.EXTRA_STATUS)
                    ?: "Tor reported a startup or transport error"
                handleTorRuntimeFailure(msg)
                return
            }
            if (action != TorService.ACTION_STATUS) return
            when (intent.getStringExtra(TorService.EXTRA_STATUS)) {
                TorService.STATUS_STARTING -> {
                    synchronized(this@TorEngineManager) {
                        if (state != State.STOPPING) {
                            bootstrapStartedAtMs = System.currentTimeMillis()
                            transportStatus = "Bootstrapping ($activeTransport)"
                            setState(if (failClosed) State.RECOVERING else State.BOOTSTRAPPING)
                        }
                    }
                }
                TorService.STATUS_ON -> {
                    val reportedPort = service?.socksPort ?: 0
                    if (reportedPort !in 1..65535) {
                        handleTorRuntimeFailure("Tor reported ON without a valid SOCKS5 port")
                        return
                    }
                    // Delete temporary torrc now that Tor has loaded it.
                    runCatching { TorService.getTorrc(context).delete() }
                    synchronized(this@TorEngineManager) {
                        bootstrap = 100
                        bootstrapSummary = "Done"
                        setState(State.VERIFYING_SOCKS)
                    }
                    verifySocksAndPromote(reportedPort)
                }
                TorService.STATUS_STOPPING, TorService.STATUS_OFF -> {
                    synchronized(this@TorEngineManager) {
                        if (state == State.CONNECTED) {
                            triggerFailClosed("Tor service stopped unexpectedly")
                        } else if (state != State.STOPPING && state != State.FAIL_CLOSED) {
                            socksPort = null
                            Settings.torSocksPort = 0
                            setState(State.STOPPED)
                        }
                    }
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? TorService.LocalBinder)?.service
            if (service == null) {
                handleTorRuntimeFailure("Tor service binder unavailable")
                return
            }
            synchronized(this@TorEngineManager) {
                bootstrapStartedAtMs = System.currentTimeMillis()
                setState(if (failClosed) State.RECOVERING else State.BOOTSTRAPPING)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(this@TorEngineManager) {
                service = null
                bound = false
                socksPort = null
                Settings.torSocksPort = 0
                if (state == State.CONNECTED) {
                    triggerFailClosed("Tor service process disconnected unexpectedly")
                } else if (state != State.STOPPING && state != State.FAIL_CLOSED) {
                    setState(State.ERROR, "Tor service disconnected")
                }
            }
        }
    }

    @Synchronized
    fun setListener(listener: ((Snapshot) -> Unit)?) {
        callback = listener
        listener?.invoke(snapshot())
    }

    fun addFailClosedListener(listener: FailClosedListener) {
        failClosedListeners.add(listener)
    }

    fun removeFailClosedListener(listener: FailClosedListener) {
        failClosedListeners.remove(listener)
    }

    fun capabilities(): TorTransportConfig.Capabilities = TorTransportConfig.capabilities(context)

    @Synchronized
    fun setTransportMode(mode: String) {
        transportMode = TorTransportConfig.normalizeMode(mode)
        Settings.torTransportMode = transportMode
        callback?.invoke(snapshot())
    }

    @Synchronized
    fun start(bridgeLines: List<String>, requestedTransportMode: String? = null) {
        if (state in listOf(State.STARTING, State.BOOTSTRAPPING, State.VERIFYING_SOCKS, State.CONNECTED)) {
            return
        }
        if (!starting.compareAndSet(false, true)) return
        if (requestedTransportMode != null) {
            transportMode = TorTransportConfig.normalizeMode(requestedTransportMode)
            Settings.torTransportMode = transportMode
        }
        lastBridges = bridgeLines
        autoFallbackAttempted = false
        failClosed = false
        Settings.torFailClosedActive = false
        startInternal(bridgeLines, forceSnowflakeFallback = false)
    }

    @Synchronized
    fun recover(bridgeLines: List<String> = lastBridges) {
        if (state == State.STARTING || state == State.BOOTSTRAPPING || state == State.VERIFYING_SOCKS) return
        stopDaemonOnly()
        starting.set(true)
        setState(State.RECOVERING)
        startInternal(bridgeLines, forceSnowflakeFallback = autoFallbackAttempted)
    }

    @Synchronized
    private fun startInternal(bridgeLines: List<String>, forceSnowflakeFallback: Boolean) {
        error = null
        transportError = null
        bootstrap = 0
        bootstrapSummary = "Starting Tor daemon"
        consecutiveProbeFailures = 0
        setState(if (failClosed) State.RECOVERING else State.STARTING)

        try {
            val built = TorTransportConfig.buildTorrc(
                context = context,
                requestedMode = transportMode,
                customBridgeLines = bridgeLines,
                forceSnowflakeFallback = forceSnowflakeFallback,
            )
            activeTransport = built.activeTransport
            transportStatus = "Initializing transport: ${built.activeTransport}"

            TorService.getTorrc(context).writeText(built.content)

            if (!receiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(TorService.ACTION_STATUS)
                    addAction(TorService.ACTION_ERROR)
                }
                localBroadcasts.registerReceiver(receiver, filter)
                receiverRegistered = true
            }

            val intent = Intent(context, TorService::class.java)
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                throw IllegalStateException("Could not bind embedded TorService")
            }
        } catch (t: Throwable) {
            runCatching { TorService.getTorrc(context).delete() }
            transportError = t.message
            transportStatus = "Transport error"
            setState(State.ERROR, t.message ?: "Tor failed to start")
            starting.set(false)
        }
    }

    @Synchronized
    fun stop() {
        if (state == State.STOPPED && !failClosed) return
        setState(State.STOPPING)
        stopDaemonOnly()
        failClosed = false
        Settings.torFailClosedActive = false
        bootstrap = 0
        bootstrapSummary = null
        transportStatus = null
        transportError = null
        error = null
        setState(State.STOPPED)
    }

    @Synchronized
    private fun stopDaemonOnly() {
        mainHandler.removeCallbacks(bootstrapPoll)
        mainHandler.removeCallbacks(healthWatchdog)
        runCatching { service?.torControlConnection?.shutdownTor("SHUTDOWN") }
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        service = null
        socksPort = null
        Settings.torSocksPort = 0
        starting.set(false)
        runCatching { TorService.getTorrc(context).delete() }
        if (receiverRegistered) runCatching { localBroadcasts.unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    /**
     * Triggers an immediate Fail-Closed transition (used both by the watchdog when Tor drops and
     * by device acceptance tests to prove fail-closed enforcement).
     */
    @Synchronized
    fun triggerFailClosed(reason: String) {
        mainHandler.removeCallbacks(bootstrapPoll)
        mainHandler.removeCallbacks(healthWatchdog)
        socksPort = null
        Settings.torSocksPort = 0
        failClosed = true
        Settings.torFailClosedActive = true
        error = reason
        transportError = reason
        transportStatus = "Fail-Closed active"
        starting.set(false)
        state = State.FAIL_CLOSED
        Log.e(TAG, "FAIL-CLOSED triggered: $reason")
        callback?.invoke(snapshot())
        for (listener in failClosedListeners) {
            runCatching { listener.onTorFailClosed(reason) }
                .onFailure { Log.e(TAG, "FailClosedListener error", it) }
        }
    }

    private fun handleTorRuntimeFailure(reason: String) {
        synchronized(this) {
            if (state == State.CONNECTED || Settings.torModeEnabled && failClosed) {
                triggerFailClosed(reason)
            } else {
                transportError = reason
                transportStatus = "Failed ($activeTransport)"
                setState(State.ERROR, reason)
            }
        }
    }

    private fun verifySocksAndPromote(reportedPort: Int) {
        ioExecutor.execute {
            var verified = false
            for (attempt in 1..5) {
                if (probeSocks5Loopback(reportedPort, PROBE_TIMEOUT_MS)) {
                    verified = true
                    break
                }
                Thread.sleep(250L)
            }
            synchronized(this@TorEngineManager) {
                if (state != State.VERIFYING_SOCKS) return@execute
                if (!verified) {
                    handleTorRuntimeFailure("Tor SOCKS5 handshake verification failed on 127.0.0.1:$reportedPort")
                    return@execute
                }
                val wasFailClosed = failClosed
                socksPort = reportedPort
                Settings.torSocksPort = reportedPort
                failClosed = false
                Settings.torFailClosedActive = false
                error = null
                transportError = null
                transportStatus = "Connected via $activeTransport"
                consecutiveProbeFailures = 0
                starting.set(false)
                setState(State.CONNECTED)
                mainHandler.removeCallbacks(healthWatchdog)
                mainHandler.postDelayed(healthWatchdog, WATCHDOG_INTERVAL_MS)
                if (wasFailClosed) {
                    for (listener in failClosedListeners) {
                        runCatching { listener.onTorRecovered("127.0.0.1", reportedPort) }
                    }
                }
            }
        }
    }

    private fun pollBootstrapPhase() {
        val svc = synchronized(this) { service } ?: return
        val phaseText = runCatching { svc.getInfo("status/bootstrap-phase") ?: "" }.getOrDefault("")
        val progress = Regex("PROGRESS=([0-9]{1,3})").find(phaseText)?.groupValues?.get(1)?.toIntOrNull()
        val summary = Regex("SUMMARY=\"([^\"]+)\"").find(phaseText)?.groupValues?.get(1)
        val warning = Regex("WARNING=\"([^\"]+)\"").find(phaseText)?.groupValues?.get(1)

        var triggerAutoSnowflake = false
        synchronized(this) {
            if (state != State.BOOTSTRAPPING && state != State.RECOVERING) return
            if (progress != null) bootstrap = progress.coerceIn(0, 100)
            if (!summary.isNullOrBlank()) bootstrapSummary = summary
            if (!warning.isNullOrBlank()) {
                transportError = warning
                transportStatus = "Warning ($activeTransport): $warning"
            } else {
                transportStatus = "Bootstrapping $bootstrap% ($activeTransport)"
            }

            // In Automatic mode, if direct Tor stalls below 50% for >45s and Snowflake binary is
            // packaged, automatically retry using Snowflake pluggable transport.
            val elapsed = System.currentTimeMillis() - bootstrapStartedAtMs
            if (
                transportMode == TorTransportConfig.MODE_AUTOMATIC &&
                activeTransport == "direct" &&
                !autoFallbackAttempted &&
                elapsed > AUTO_SNOWFLAKE_FALLBACK_MS &&
                bootstrap < 50 &&
                capabilities().snowflakeAvailable
            ) {
                autoFallbackAttempted = true
                triggerAutoSnowflake = true
            }
            callback?.invoke(snapshot())
        }

        if (triggerAutoSnowflake) {
            Log.i(TAG, "Automatic mode: direct Tor stalled, switching to Snowflake transport")
            synchronized(this) {
                stopDaemonOnly()
                starting.set(true)
                startInternal(lastBridges, forceSnowflakeFallback = true)
            }
        }
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val caps = capabilities()
        return Snapshot(
            state = state.name,
            bootstrap = bootstrap,
            bootstrapSummary = bootstrapSummary,
            socksHost = if (socksPort != null) "127.0.0.1" else null,
            socksPort = socksPort,
            transportMode = transportMode,
            activeTransport = activeTransport,
            transportStatus = transportStatus,
            transportError = transportError,
            snowflakeAvailable = caps.snowflakeAvailable,
            obfs4Available = caps.obfs4Available,
            failClosed = failClosed,
            error = error,
        )
    }

    private fun setState(next: State, message: String? = null) {
        state = next
        if (message != null) error = message
        if (next == State.BOOTSTRAPPING || next == State.RECOVERING) {
            mainHandler.removeCallbacks(bootstrapPoll)
            mainHandler.postDelayed(bootstrapPoll, BOOTSTRAP_POLL_MS)
        } else {
            mainHandler.removeCallbacks(bootstrapPoll)
        }
        if (next != State.CONNECTED) {
            mainHandler.removeCallbacks(healthWatchdog)
        }
        if (next == State.STOPPED || next == State.ERROR || next == State.FAIL_CLOSED) {
            starting.set(false)
        }
        callback?.invoke(snapshot())
    }

    companion object {
        private const val TAG = "A/TorEngineManager"
        private const val BOOTSTRAP_POLL_MS = 1000L
        private const val WATCHDOG_INTERVAL_MS = 3000L
        private const val PROBE_TIMEOUT_MS = 2000
        private const val MAX_PROBE_FAILURES = 2
        private const val AUTO_SNOWFLAKE_FALLBACK_MS = 45_000L

        @Volatile
        private var instance: TorEngineManager? = null

        fun get(context: Context): TorEngineManager = instance ?: synchronized(this) {
            instance ?: TorEngineManager(context.applicationContext).also { instance = it }
        }

        /**
         * Performs a real SOCKS5 greeting handshake (RFC 1928: 0x05 0x01 0x00 -> 0x05 0x00)
         * against loopback to verify the Tor SOCKS5 listener is genuinely alive.
         */
        fun probeSocks5Loopback(port: Int, timeoutMs: Int = PROBE_TIMEOUT_MS): Boolean {
            if (port !in 1..65535) return false
            return runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                    socket.soTimeout = timeoutMs
                    val out = socket.getOutputStream()
                    out.write(byteArrayOf(0x05, 0x01, 0x00))
                    out.flush()
                    val resp = ByteArray(2)
                    val read = socket.getInputStream().read(resp)
                    read == 2 && resp[0] == 0x05.toByte() && resp[1] == 0x00.toByte()
                }
            }.getOrDefault(false)
        }
    }
}
