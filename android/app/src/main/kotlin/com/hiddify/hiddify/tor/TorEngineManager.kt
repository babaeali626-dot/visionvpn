package com.hiddify.hiddify.tor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process as AndroidProcess
import android.util.Log
import com.hiddify.hiddify.Settings
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the embedded Tor daemon as an isolated OS child process (executing libtor.so from
 * nativeLibraryDir, inspired by modern Android native tunnel runners such as SlipNet/Orbot).
 *
 * Running Tor in an isolated child process completely avoids JNI/signal-handler conflicts with
 * Go's hiddify-core.aar runtime (which previously caused immediate crashes on Tor connect),
 * enables real-time stdout bootstrap & Pluggable Transport error parsing, and allows deterministic
 * Fail-Closed detection and clean process termination.
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

    @Volatile
    private var torProcess: Process? = null
    private val runGeneration = AtomicInteger(0)

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
    private var pendingSocksPort: Int? = null

    private var lastBridges: List<String> = emptyList()
    private var autoFallbackAttempted = false
    private var bootstrapStartedAtMs = 0L
    private var consecutiveProbeFailures = 0

    private var callback: ((Snapshot) -> Unit)? = null
    private val failClosedListeners = CopyOnWriteArraySet<FailClosedListener>()
    private val starting = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "VisionVPN-TorWorker").apply { isDaemon = true }
    }

    private val torRootDir: File
        get() = File(context.filesDir, "visionvpn_tor").apply { mkdirs() }
    private val torDataDir: File
        get() = File(torRootDir, "data").apply { mkdirs() }
    private val torCacheDir: File
        get() = File(torRootDir, "cache").apply { mkdirs() }
    private val torrcFile: File
        get() = File(torRootDir, "torrc")

    private val autoFallbackWatchdog = object : Runnable {
        override fun run() {
            var triggerSnowflake = false
            synchronized(this@TorEngineManager) {
                if (state != State.BOOTSTRAPPING && state != State.RECOVERING) return
                val elapsed = System.currentTimeMillis() - bootstrapStartedAtMs
                if (
                    transportMode == TorTransportConfig.MODE_AUTOMATIC &&
                    activeTransport == "direct" &&
                    !autoFallbackAttempted &&
                    elapsed >= AUTO_SNOWFLAKE_FALLBACK_MS &&
                    bootstrap < 50 &&
                    capabilities().snowflakeAvailable
                ) {
                    autoFallbackAttempted = true
                    triggerSnowflake = true
                } else {
                    mainHandler.postDelayed(this, 3000L)
                }
            }
            if (triggerSnowflake) {
                Log.i(TAG, "Automatic mode: direct Tor stalled below 50%, switching to Snowflake")
                synchronized(this@TorEngineManager) {
                    stopProcessOnly()
                    starting.set(true)
                    startInternal(lastBridges, forceSnowflakeFallback = true)
                }
            }
        }
    }

    private val healthWatchdog = object : Runnable {
        override fun run() {
            val port: Int?
            val proc: Process?
            synchronized(this@TorEngineManager) {
                if (state != State.CONNECTED) return
                port = socksPort
                proc = torProcess
            }
            if (port == null || proc == null || !isProcessAlive(proc)) {
                triggerFailClosed("Tor daemon process exited unexpectedly")
                return
            }
            ioExecutor.execute {
                val ok = probeSocks5Loopback(port, PROBE_TIMEOUT_MS)
                var reschedule = false
                synchronized(this@TorEngineManager) {
                    if (state != State.CONNECTED) return@execute
                    if (ok) {
                        consecutiveProbeFailures = 0
                        reschedule = true
                    } else {
                        consecutiveProbeFailures++
                        Log.w(TAG, "Tor SOCKS5 health probe failed ($consecutiveProbeFailures/$MAX_PROBE_FAILURES)")
                        if (consecutiveProbeFailures >= MAX_PROBE_FAILURES) {
                            triggerFailClosed("Tor SOCKS5 listener 127.0.0.1:$port stopped responding")
                            return@execute
                        }
                        reschedule = true
                    }
                }
                if (reschedule) {
                    mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
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
        stopProcessOnly()
        starting.set(true)
        setState(State.RECOVERING)
        startInternal(bridgeLines, forceSnowflakeFallback = autoFallbackAttempted)
    }

    @Synchronized
    private fun startInternal(bridgeLines: List<String>, forceSnowflakeFallback: Boolean) {
        val generation = runGeneration.incrementAndGet()
        error = null
        transportError = null
        bootstrap = 0
        bootstrapSummary = "Starting Tor daemon"
        consecutiveProbeFailures = 0
        socksPort = null
        pendingSocksPort = null
        Settings.torSocksPort = 0
        setState(if (failClosed) State.RECOVERING else State.STARTING)

        ioExecutor.execute {
            try {
                val caps = capabilities()
                val torBinPath = caps.torPath
                    ?: throw IllegalStateException(
                        "Tor native binary (libtor.so) is missing or not executable in nativeLibraryDir"
                    )

                val allocatedPort = allocateLoopbackPort()
                val built = TorTransportConfig.buildTorrc(
                    context = context,
                    requestedMode = transportMode,
                    customBridgeLines = bridgeLines,
                    socksPort = allocatedPort,
                    dataDir = torDataDir,
                    cacheDir = torCacheDir,
                    owningPid = AndroidProcess.myPid(),
                    forceSnowflakeFallback = forceSnowflakeFallback,
                )

                synchronized(this@TorEngineManager) {
                    if (generation != runGeneration.get()) return@execute
                    activeTransport = built.activeTransport
                    pendingSocksPort = allocatedPort
                    bootstrapStartedAtMs = System.currentTimeMillis()
                    transportStatus = "Bootstrapping via ${built.activeTransport}"
                }

                torrcFile.writeText(built.content)

                val pb = ProcessBuilder(torBinPath, "-f", torrcFile.absolutePath)
                pb.directory(torDataDir)
                pb.redirectErrorStream(true)
                val env = pb.environment()
                val nativeDir = context.applicationInfo.nativeLibraryDir
                env["LD_LIBRARY_PATH"] = nativeDir
                env["HOME"] = torDataDir.absolutePath
                env["TMPDIR"] = torCacheDir.absolutePath

                val proc = pb.start()
                synchronized(this@TorEngineManager) {
                    if (generation != runGeneration.get()) {
                        runCatching { proc.destroyForcibly() }
                        return@execute
                    }
                    torProcess = proc
                    setState(if (failClosed) State.RECOVERING else State.BOOTSTRAPPING)
                }

                mainHandler.removeCallbacks(autoFallbackWatchdog)
                mainHandler.postDelayed(autoFallbackWatchdog, 5000L)

                streamTorOutput(proc, allocatedPort, generation)
            } catch (t: Throwable) {
                Log.e(TAG, "Tor process start failed", t)
                runCatching { torrcFile.delete() }
                synchronized(this@TorEngineManager) {
                    if (generation != runGeneration.get()) return@execute
                    transportError = t.message
                    transportStatus = "Startup failed"
                    handleTorRuntimeFailure(t.message ?: "Tor failed to start")
                }
            }
        }
    }

    private fun streamTorOutput(proc: Process, allocatedPort: Int, generation: Int) {
        var lastErrorLine: String? = null
        try {
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (generation != runGeneration.get()) break
                    Log.d(TAG, "[tor] $line")
                    handleTorLogLine(line, allocatedPort, generation)
                    if (line.contains("[err]") || line.contains("[warn]")) {
                        lastErrorLine = line.substringAfter("] ").trim()
                    }
                    line = reader.readLine()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Tor output stream closed: ${t.message}")
        }

        val exitCode = runCatching { proc.waitFor() }.getOrDefault(-1)
        runCatching { torrcFile.delete() }

        synchronized(this@TorEngineManager) {
            if (generation != runGeneration.get()) return
            torProcess = null
            if (state == State.STOPPING || state == State.STOPPED) return
            val detail = lastErrorLine ?: "Tor process exited with code $exitCode"
            handleTorRuntimeFailure(detail)
        }
    }

    private fun handleTorLogLine(line: String, allocatedPort: Int, generation: Int) {
        val bootMatch = BOOTSTRAP_REGEX.find(line)
        if (bootMatch != null) {
            val pct = bootMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: return
            val summary = bootMatch.groupValues[3].trim()
            var promote = false
            synchronized(this) {
                if (generation != runGeneration.get()) return
                bootstrap = pct
                bootstrapSummary = summary
                transportStatus = "Bootstrapping $pct% ($activeTransport)"
                if (pct >= 100 && state != State.CONNECTED && state != State.VERIFYING_SOCKS) {
                    promote = true
                    setState(State.VERIFYING_SOCKS)
                } else {
                    callback?.invoke(snapshot())
                }
            }
            if (promote) {
                runCatching { torrcFile.delete() }
                verifySocksAndPromote(allocatedPort, generation)
            }
            return
        }

        if (line.contains("[err]") || line.contains("Managed proxy") || line.contains("Pluggable Transport")) {
            val msg = line.substringAfter("] ").trim().take(220)
            synchronized(this) {
                if (generation != runGeneration.get()) return
                if (line.contains("[err]") || line.contains("failed", ignoreCase = true) || line.contains("terminated", ignoreCase = true)) {
                    transportError = msg
                    transportStatus = "Transport warning ($activeTransport)"
                    callback?.invoke(snapshot())
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        if (state == State.STOPPED && !failClosed && torProcess == null) return
        setState(State.STOPPING)
        stopProcessOnly()
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
    private fun stopProcessOnly() {
        runGeneration.incrementAndGet()
        mainHandler.removeCallbacks(autoFallbackWatchdog)
        mainHandler.removeCallbacks(healthWatchdog)
        val proc = torProcess
        torProcess = null
        if (proc != null) {
            runCatching { proc.destroy() }
            runCatching {
                if (isProcessAlive(proc)) proc.destroyForcibly()
            }
        }
        socksPort = null
        pendingSocksPort = null
        Settings.torSocksPort = 0
        starting.set(false)
        runCatching { torrcFile.delete() }
    }

    @Synchronized
    fun triggerFailClosed(reason: String) {
        mainHandler.removeCallbacks(autoFallbackWatchdog)
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
            if (state == State.CONNECTED || (Settings.torModeEnabled && failClosed)) {
                triggerFailClosed(reason)
            } else {
                transportError = reason
                transportStatus = "Failed ($activeTransport)"
                setState(State.ERROR, reason)
            }
        }
    }

    private fun verifySocksAndPromote(reportedPort: Int, generation: Int) {
        ioExecutor.execute {
            var verified = false
            for (attempt in 1..8) {
                if (generation != runGeneration.get()) return@execute
                if (probeSocks5Loopback(reportedPort, PROBE_TIMEOUT_MS)) {
                    verified = true
                    break
                }
                Thread.sleep(250L)
            }
            synchronized(this@TorEngineManager) {
                if (generation != runGeneration.get() || state != State.VERIFYING_SOCKS) return@execute
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
        if (next != State.CONNECTED) {
            mainHandler.removeCallbacks(healthWatchdog)
        }
        if (next != State.BOOTSTRAPPING && next != State.RECOVERING) {
            mainHandler.removeCallbacks(autoFallbackWatchdog)
        }
        if (next == State.STOPPED || next == State.ERROR || next == State.FAIL_CLOSED) {
            starting.set(false)
        }
        callback?.invoke(snapshot())
    }

    companion object {
        private const val TAG = "A/TorEngineManager"
        private const val WATCHDOG_INTERVAL_MS = 3000L
        private const val PROBE_TIMEOUT_MS = 2000
        private const val MAX_PROBE_FAILURES = 2
        private const val AUTO_SNOWFLAKE_FALLBACK_MS = 45_000L
        private val BOOTSTRAP_REGEX = Regex("Bootstrapped\\s+([0-9]{1,3})%\\s*\\(([^)]*)\\):\\s*(.+)")

        @Volatile
        private var instance: TorEngineManager? = null

        fun get(context: Context): TorEngineManager = instance ?: synchronized(this) {
            instance ?: TorEngineManager(context.applicationContext).also { instance = it }
        }

        private fun allocateLoopbackPort(): Int =
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        private fun isProcessAlive(proc: Process): Boolean = try {
            proc.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
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
