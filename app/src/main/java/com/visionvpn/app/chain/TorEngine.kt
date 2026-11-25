package com.visionvpn.app.chain

import android.content.Context
import com.visionvpn.app.data.model.TorBridgeMode
import com.visionvpn.app.data.model.TorChainConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class TorEngine(
    private val context: Context,
    private val config: TorChainConfig,
    private val upstreamSocksPort: Int? = null,
    private val nativeDirOverride: File? = null
) : LocalEngine {

    override val engineId: String = "tor"

    private val _state = MutableStateFlow(
        LocalEngineState(
            engineId = engineId,
            status = LocalEngineStatus.STOPPED,
            localSocksPort = config.socksPort,
            phaseLabel = "Stopped"
        )
    )
    override val state: StateFlow<LocalEngineState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionGeneration = AtomicLong(0L)
    private var process: Process? = null
    private var logReaderJob: Job? = null
    private var exitWatcherJob: Job? = null

    @Volatile
    private var bootstrapped: Boolean = false

    @Volatile
    private var lastConnectProbeOkMs: Long = 0L

    companion object {
        private const val CONNECT_PROBE_CACHE_MS = 20_000L

        const val OFFICIAL_SNOWFLAKE_BRIDGE =
            "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
                "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
                "url=https://1098762253.rsc.cdn77.org/ " +
                "fronts=www.cdn77.com,www.phpmyadmin.net " +
                "ice=stun:stun.l.google.com:19302,stun:stun.antisip.com:3478 " +
                "utls-imitate=hellorandomizedalpn"
    }

    override suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            startInternal()
            Result.success(Unit)
        } catch (ce: CancellationException) {
            withContext(NonCancellable) { stop() }
            throw ce
        } catch (e: Exception) {
            withContext(NonCancellable) { stop() }
            Result.failure(e)
        }
    }

    private fun startInternal() {
            val gen = sessionGeneration.incrementAndGet()
            bootstrapped = false
            lastConnectProbeOkMs = 0L

            _state.value = _state.value.copy(
                status = LocalEngineStatus.INITIALIZING,
                bootstrapProgress = 0,
                phaseLabel = "Initializing Tor",
                message = "Validating Tor binaries and bridge configuration..."
            )

            val nativeDir = nativeDirOverride ?: File(context.applicationInfo.nativeLibraryDir)
            val torBinary = locateTorBinary(nativeDir)
                ?: error("Tor binary (libtor.so) is missing for this device ABI.")

            val ptAvailability = TorBridgeValidator.detectTransportBinaries(nativeDir)
            val dataDir = File(context.filesDir, "tor_data").apply { mkdirs() }
            val torrcContent = buildTorrc(dataDir, ptAvailability).getOrThrow()
            val torrcFile = File(dataDir, "torrc")
            torrcFile.writeText(torrcContent)

            val bridgeMode = config.effectiveBridgeMode()
            _state.value = LocalEngineState(
                engineId = engineId,
                status = if (bridgeMode != TorBridgeMode.DIRECT) {
                    LocalEngineStatus.CONNECTING
                } else {
                    LocalEngineStatus.STARTING
                },
                localSocksPort = config.socksPort,
                bootstrapProgress = 0,
                phaseLabel = if (bridgeMode != TorBridgeMode.DIRECT) "Connecting to Bridge" else "Starting Tor",
                message = if (bridgeMode != TorBridgeMode.DIRECT) {
                    "Connecting via ${bridgeMode.labelEn}..."
                } else {
                    "Starting Tor daemon..."
                }
            )

            val pb = ProcessBuilder(torBinary.absolutePath, "-f", torrcFile.absolutePath)
                .redirectErrorStream(true)
            pb.environment()["HOME"] = dataDir.absolutePath

            val proc = pb.start()
            process = proc

            logReaderJob = scope.launch {
                runCatching {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (!isActive || sessionGeneration.get() != gen) return@forEach
                            parseTorLogLine(line, gen)
                        }
                    }
                }
            }

            exitWatcherJob = scope.launch {
                val exitCode = runCatching { proc.waitFor() }.getOrDefault(-1)
                if (isActive && sessionGeneration.get() == gen && process === proc) {
                    bootstrapped = false
                    lastConnectProbeOkMs = 0L
                    _state.value = _state.value.copy(
                        status = LocalEngineStatus.FAILED,
                        phaseLabel = "Process Exited ($exitCode)",
                        message = "Tor daemon terminated unexpectedly (exit code $exitCode)."
                    )
                }
            }
    }

    internal fun buildTorrc(
        dataDir: File,
        ptAvailability: PluggableTransportAvailability
    ): Result<String> = runCatching {
        val bridgeMode = config.effectiveBridgeMode()
        val bridgeLines = resolveBridgeLines(bridgeMode).getOrThrow()

        val sb = StringBuilder()
        sb.appendLine("DataDirectory ${dataDir.absolutePath}")
        sb.appendLine("SocksPort 127.0.0.1:${config.socksPort}")
        sb.appendLine("DNSPort 127.0.0.1:${config.dnsPort}")
        sb.appendLine("AutomapHostsOnResolve 1")
        sb.appendLine("AvoidDiskWrites 1")
        sb.appendLine("Log notice stdout")
        sb.appendLine("ClientUseIPv6 0")

        if (upstreamSocksPort != null) {
            sb.appendLine("Socks5Proxy 127.0.0.1:$upstreamSocksPort")
        }

        if (bridgeMode != TorBridgeMode.DIRECT) {
            require(bridgeLines.isNotEmpty()) {
                "Bridge Mode is enabled (${bridgeMode.labelEn}), but no valid bridge lines are configured. Refusing to fall back to direct Tor."
            }
            sb.appendLine("UseBridges 1")

            val neededTransports = bridgeLines.map { line ->
                TorBridgeValidator.validateLine(line).also { res ->
                    require(res.isValid) { "Invalid Tor bridge configuration: ${res.errorMessage}" }
                }.transport
            }.toSet()

            if (neededTransports.any { it in setOf("obfs4", "meek_lite", "webtunnel") }) {
                val lyrebird = ptAvailability.lyrebirdBinary
                    ?: error("Required Tor pluggable transport binary (liblyrebird.so) is not packaged for this device ABI.")
                sb.appendLine("ClientTransportPlugin obfs4,meek_lite,webtunnel exec ${lyrebird.absolutePath}")
            }
            if ("snowflake" in neededTransports) {
                val snowflake = ptAvailability.snowflakeBinary
                    ?: error("Required Snowflake pluggable transport binary (libsnowflake.so) is not packaged for this device ABI.")
                sb.appendLine("ClientTransportPlugin snowflake exec ${snowflake.absolutePath}")
            }

            bridgeLines.forEach { bridge ->
                sb.appendLine("Bridge $bridge")
            }
        }

        sb.toString()
    }

    internal fun parseTorLogLine(line: String, expectedGen: Long = sessionGeneration.get()) {
        if (expectedGen != sessionGeneration.get()) return

        val bootstrapRegex = Regex("Bootstrapped (\\d+)% \\(([^)]+)\\): (.+)")
        val match = bootstrapRegex.find(line)
        if (match != null) {
            val pct = match.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: 0
            val phase = when {
                pct >= 100 -> "Verifying Circuit"
                pct >= 80 -> "Establishing Tor Circuit"
                pct >= 10 -> "Bootstrapping Tor"
                else -> "Connecting to Bridge"
            }
            bootstrapped = pct >= 100
            _state.value = _state.value.copy(
                status = if (pct >= 100) LocalEngineStatus.CONNECTING else LocalEngineStatus.BOOTSTRAPPING,
                bootstrapProgress = pct,
                phaseLabel = phase,
                message = "Tor $pct% • $phase"
            )
            return
        }

        if (line.contains("Problem bootstrapping", ignoreCase = true) ||
            line.contains("Pluggable Transport process terminated", ignoreCase = true) ||
            line.contains("Failing because we have", ignoreCase = true)
        ) {
            bootstrapped = false
            lastConnectProbeOkMs = 0L
            val bridgeMode = config.effectiveBridgeMode()
            val errLabel = if (bridgeMode != TorBridgeMode.DIRECT) "Bridge Unavailable" else "Connection Failed"
            _state.value = _state.value.copy(
                status = LocalEngineStatus.DEGRADED,
                phaseLabel = errLabel,
                message = errLabel
            )
        }
    }

    /**
     * Requires: process alive, 100% bootstrap, SOCKS5 handshake, AND a rate-limited end-to-end
     * SOCKS5 CONNECT probe (remote ATYP=DOMAIN resolution) before reporting healthy/CONNECTED.
     */
    override suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        val alive = process?.isAlive == true
        if (!alive || !bootstrapped) return@withContext false

        if (Socks5Probe.verifyHandshake(config.socksPort) !is Socks5Probe.ProbeResult.Success) {
            return@withContext false
        }

        val now = System.currentTimeMillis()
        if (now - lastConnectProbeOkMs >= CONNECT_PROBE_CACHE_MS) {
            val probe = Socks5Probe.verifyTlsOverSocks5(config.socksPort)
            if (probe !is Socks5Probe.ProbeResult.Success) {
                return@withContext false
            }
            lastConnectProbeOkMs = now
        }

        if (_state.value.status != LocalEngineStatus.CONNECTED) {
            _state.value = _state.value.copy(
                status = LocalEngineStatus.CONNECTED,
                phaseLabel = "Connected",
                message = "Tor 100% • Circuit TLS-Verified"
            )
        }
        true
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        sessionGeneration.incrementAndGet()
        _state.value = _state.value.copy(status = LocalEngineStatus.STOPPING, phaseLabel = "Stopping")
        exitWatcherJob?.cancel()
        exitWatcherJob = null
        logReaderJob?.cancel()
        logReaderJob = null
        process?.let { proc ->
            runCatching {
                proc.destroy()
                if (!proc.waitFor(2_000L, TimeUnit.MILLISECONDS) && proc.isAlive) {
                    proc.destroyForcibly()
                }
            }
        }
        process = null
        bootstrapped = false
        lastConnectProbeOkMs = 0L
        _state.value = LocalEngineState(
            engineId = engineId,
            status = LocalEngineStatus.STOPPED,
            localSocksPort = config.socksPort,
            bootstrapProgress = 0,
            phaseLabel = "Stopped",
            message = "Stopped"
        )
    }

    private fun resolveBridgeLines(mode: TorBridgeMode): Result<List<String>> = runCatching {
        when (mode) {
            TorBridgeMode.DIRECT -> emptyList()
            TorBridgeMode.SNOWFLAKE_TRANSPORT -> listOf(OFFICIAL_SNOWFLAKE_BRIDGE)
            TorBridgeMode.OBFS4_TRANSPORT -> {
                val obfs4 = config.activeCustomBridgeLines().filter { it.startsWith("obfs4 ", ignoreCase = true) }
                require(obfs4.isNotEmpty()) {
                    "Obfs4 transport selected, but no Obfs4 bridge addresses are saved. Add official bridges from bridges.torproject.org."
                }
                obfs4
            }
            TorBridgeMode.WEBTUNNEL_TRANSPORT -> {
                val wt = config.activeCustomBridgeLines().filter { it.startsWith("webtunnel ", ignoreCase = true) }
                require(wt.isNotEmpty()) {
                    "WebTunnel transport selected, but no WebTunnel bridge addresses are saved. Add official bridges from bridges.torproject.org."
                }
                wt
            }
            TorBridgeMode.CUSTOM -> {
                val lines = config.activeCustomBridgeLines()
                require(lines.isNotEmpty()) {
                    "Custom Bridge Mode is enabled, but no enabled bridges were found."
                }
                lines
            }
        }
    }

    private fun locateTorBinary(nativeDir: File): File? {
        return listOf(File(nativeDir, "libtor.so"), File(nativeDir, "tor"))
            .firstOrNull { it.exists() && it.canExecute() }
    }
}
