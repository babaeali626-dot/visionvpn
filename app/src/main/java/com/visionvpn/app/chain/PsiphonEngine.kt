package com.visionvpn.app.chain

import android.content.Context
import com.visionvpn.app.data.model.PsiphonChainConfig
import com.visionvpn.app.data.model.PsiphonRegionValidation
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class PsiphonEngine(
    private val context: Context,
    private val config: PsiphonChainConfig,
    private val upstreamSocksPort: Int? = null,
    private val nativeDirOverride: File? = null
) : LocalEngine {

    override val engineId: String = "psiphon"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _state = MutableStateFlow(
        LocalEngineState(
            engineId = engineId,
            status = LocalEngineStatus.STOPPED,
            localSocksPort = config.localSocksPort,
            requestedRegion = config.regionCode,
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
    private var listenerReady: Boolean = false

    @Volatile
    private var tunnelEstablished: Boolean = false

    @Volatile
    private var observedRegion: String? = null

    @Volatile
    private var lastConnectProbeOkMs: Long = 0L

    companion object {
        private const val CONNECT_PROBE_CACHE_MS = 20_000L
    }

    fun validateConfiguration(): Result<Unit> = runCatching {
        require(config.clientVersion.isNotBlank()) {
            "Psiphon ClientVersion is required (no arbitrary fallback allowed)."
        }
        require(config.propagationChannelId.isNotBlank() && config.sponsorId.isNotBlank()) {
            "Psiphon requires official PropagationChannelId and SponsorId configuration."
        }
        require(config.hasServerDiscovery) {
            "Psiphon requires a valid server-discovery source: TargetServerEntry or HTTPS RemoteServerListURLs (OnlyAfterAttempts=0) plus Base64 RemoteServerListSignaturePublicKey."
        }
        val liveSet = _state.value.availableRegions.toSet()
        when (val regionCheck = PsiphonChainConfig.validateRegionCode(config.regionCode, liveSet)) {
            is PsiphonRegionValidation.InvalidFormat ->
                error("Invalid Psiphon region code '${regionCheck.rawCode}'. Expected AUTO or ISO 3166-1 alpha-2.")
            is PsiphonRegionValidation.UnsupportedRegion ->
                error("Requested Psiphon region '${regionCheck.code}' is not available.")
            else -> Unit
        }
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
            listenerReady = false
            tunnelEstablished = false
            observedRegion = null
            lastConnectProbeOkMs = 0L

            _state.value = _state.value.copy(
                status = LocalEngineStatus.INITIALIZING,
                phaseLabel = "Initializing",
                message = "Validating Psiphon provisioning and native binary..."
            )

            validateConfiguration().getOrThrow()

            val nativeDir = nativeDirOverride ?: File(context.applicationInfo.nativeLibraryDir)
            val binary = locatePsiphonBinary(nativeDir)
                ?: error("Psiphon binary (libpsiphon.so) is not packaged for this device ABI.")

            val workDir = File(context.filesDir, "psiphon_data").apply { mkdirs() }
            val configFile = File(workDir, "psiphon_config.json")
            configFile.writeText(buildPsiphonConfigJson(workDir).getOrThrow())

            val regionLabel = PsiphonChainConfig.findRegionOrNull(config.regionCode)?.nameEn ?: config.regionCode
            _state.value = LocalEngineState(
                engineId = engineId,
                status = LocalEngineStatus.CONNECTING,
                localSocksPort = config.localSocksPort,
                requestedRegion = config.regionCode.uppercase(),
                observedRegion = null,
                availableRegions = _state.value.availableRegions,
                phaseLabel = "Connecting",
                message = "Connecting Psiphon (Requested: $regionLabel)..."
            )

            val pb = ProcessBuilder(binary.absolutePath, "-config", configFile.absolutePath)
                .directory(workDir)
                .redirectErrorStream(true)

            val proc = pb.start()
            process = proc

            logReaderJob = scope.launch {
                runCatching {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (!isActive || sessionGeneration.get() != gen) return@forEach
                            parsePsiphonNoticeLine(line, gen)
                        }
                    }
                }
            }

            exitWatcherJob = scope.launch {
                val exitCode = runCatching { proc.waitFor() }.getOrDefault(-1)
                if (isActive && sessionGeneration.get() == gen && process === proc) {
                    tunnelEstablished = false
                    listenerReady = false
                    lastConnectProbeOkMs = 0L
                    _state.value = _state.value.copy(
                        status = LocalEngineStatus.FAILED,
                        phaseLabel = "Process Exited ($exitCode)",
                        message = "Psiphon process terminated unexpectedly (exit code $exitCode)."
                    )
                }
            }
    }

    internal fun buildPsiphonConfigJson(workDir: File): Result<String> = runCatching {
        validateConfiguration().getOrThrow()
        buildJsonObject {
            put("ClientVersion", config.clientVersion.trim())
            put("PropagationChannelId", config.propagationChannelId.trim())
            put("SponsorId", config.sponsorId.trim())
            put("DataRootDirectory", workDir.absolutePath)
            put("LocalSocksProxyPort", config.localSocksPort)
            put("LocalHttpProxyPort", config.localHttpPort)
            put("EstablishTunnelTimeoutSeconds", 30)
            put("DisableLocalSocksProxy", false)
            put("DisableLocalHTTPProxy", false)
            put("EmitDiagnosticNotices", false)

            if (config.hasValidRemoteServerList) {
                put("RemoteServerListSignaturePublicKey", config.remoteServerListSignaturePublicKey.trim())
                putJsonArray("RemoteServerListURLs") {
                    config.remoteServerListUrls.forEach { entry ->
                        add(buildJsonObject {
                            put("URL", entry.url.trim())
                            put("OnlyAfterAttempts", entry.onlyAfterAttempts)
                            put("SkipVerify", entry.skipVerify)
                        })
                    }
                }
            }
            if (config.embeddedServerEntries.isNotBlank()) {
                put("TargetServerEntry", config.embeddedServerEntries.trim())
            }
            val normalizedRegion = config.regionCode.trim().uppercase()
            if (normalizedRegion.isNotEmpty() && normalizedRegion != "AUTO") {
                put("EgressRegion", normalizedRegion)
            }
            if (upstreamSocksPort != null) {
                put("UpstreamProxyURL", "socks5://127.0.0.1:$upstreamSocksPort")
            }
        }.toString()
    }

    internal fun parsePsiphonNoticeLine(line: String, expectedGen: Long = sessionGeneration.get()) {
        if (expectedGen != sessionGeneration.get()) return
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return

        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return
        val noticeType = obj["noticeType"]?.jsonPrimitive?.contentOrNull ?: return
        val data = obj["data"]?.jsonObject

        when (noticeType) {
            "ListeningSocksProxyPort" -> {
                listenerReady = true
            }
            "AvailableEgressRegions" -> {
                val regions = data?.get("regions")?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.uppercase() }
                    ?.distinct()
                    .orEmpty()
                if (regions.isNotEmpty()) {
                    _state.value = _state.value.copy(availableRegions = regions)
                }
            }
            "Tunnels" -> {
                val count = data?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                if (count > 0) {
                    tunnelEstablished = true
                    _state.value = _state.value.copy(
                        status = LocalEngineStatus.CONNECTING,
                        phaseLabel = "Verifying Tunnel",
                        message = "Psiphon tunnel established; verifying end-to-end forwarding..."
                    )
                } else if (tunnelEstablished) {
                    tunnelEstablished = false
                    lastConnectProbeOkMs = 0L
                    _state.value = _state.value.copy(
                        status = LocalEngineStatus.DEGRADED,
                        phaseLabel = "Reconnecting",
                        message = "Psiphon tunnel lost; reconnecting..."
                    )
                }
            }
            "Homepage", "ActiveTunnel", "ServerRegion" -> {
                val region = data?.get("region")?.jsonPrimitive?.contentOrNull
                if (!region.isNullOrBlank()) {
                    observedRegion = region.uppercase()
                    _state.value = _state.value.copy(
                        observedRegion = observedRegion,
                        message = if (_state.value.status == LocalEngineStatus.CONNECTED) {
                            buildConnectedMessage()
                        } else _state.value.message
                    )
                }
            }
            "UnavailableEgressRegion", "Alert" -> {
                val msg = data?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
                if (noticeType == "UnavailableEgressRegion" ||
                    msg.contains("unavailable", ignoreCase = true)
                ) {
                    tunnelEstablished = false
                    lastConnectProbeOkMs = 0L
                    _state.value = _state.value.copy(
                        status = LocalEngineStatus.FAILED,
                        phaseLabel = "Region Unavailable",
                        message = "Requested Psiphon region (${config.regionCode}) is unavailable."
                    )
                }
            }
        }
    }

    private fun buildConnectedMessage(): String {
        val reqLabel = PsiphonChainConfig.findRegionOrNull(config.regionCode)?.nameEn ?: config.regionCode
        val obs = observedRegion
        return if (!obs.isNullOrBlank()) {
            "Psiphon TLS-verified (Reported Exit: $obs)"
        } else {
            "Psiphon TLS-verified (Requested: $reqLabel)"
        }
    }

    /**
     * Requires: process alive, listenerReady, tunnelEstablished, SOCKS5 handshake, AND a
     * rate-limited end-to-end SOCKS5 CONNECT probe via [Socks5Probe.verifyConnect].
     */
    override suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        val alive = process?.isAlive == true
        if (!alive || !listenerReady || !tunnelEstablished) return@withContext false

        if (Socks5Probe.verifyHandshake(config.localSocksPort) !is Socks5Probe.ProbeResult.Success) {
            return@withContext false
        }

        val now = System.currentTimeMillis()
        if (now - lastConnectProbeOkMs >= CONNECT_PROBE_CACHE_MS) {
            val probe = Socks5Probe.verifyTlsOverSocks5(config.localSocksPort)
            if (probe !is Socks5Probe.ProbeResult.Success) {
                return@withContext false
            }
            lastConnectProbeOkMs = now
        }

        if (_state.value.status != LocalEngineStatus.CONNECTED) {
            _state.value = _state.value.copy(
                status = LocalEngineStatus.CONNECTED,
                observedRegion = observedRegion,
                phaseLabel = "Connected",
                message = buildConnectedMessage()
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
        listenerReady = false
        tunnelEstablished = false
        observedRegion = null
        lastConnectProbeOkMs = 0L
        _state.value = LocalEngineState(
            engineId = engineId,
            status = LocalEngineStatus.STOPPED,
            localSocksPort = config.localSocksPort,
            requestedRegion = config.regionCode,
            observedRegion = null,
            availableRegions = _state.value.availableRegions,
            phaseLabel = "Stopped",
            message = "Stopped"
        )
    }

    private fun locatePsiphonBinary(nativeDir: File): File? {
        return listOf(
            File(nativeDir, "libpsiphon.so"),
            File(nativeDir, "libpsiphon_tunnel_core.so")
        ).firstOrNull { it.exists() && it.canExecute() }
    }
}
