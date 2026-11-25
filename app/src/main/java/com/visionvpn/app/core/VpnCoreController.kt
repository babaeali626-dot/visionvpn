package com.visionvpn.app.core

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.visionvpn.app.chain.ChainManager
import com.visionvpn.app.chain.LocalEngineManager
import com.visionvpn.app.chain.LocalEngineState
import com.visionvpn.app.chain.RoutingGraph
import com.visionvpn.app.chain.WarpManager
import com.visionvpn.app.data.local.VisionPreferencesRepository
import com.visionvpn.app.data.model.AppSettings
import com.visionvpn.app.data.model.ChainConfig
import com.visionvpn.app.data.model.ChainExitType
import com.visionvpn.app.data.model.ProxyProfile
import com.visionvpn.app.data.model.TrafficSample
import com.visionvpn.app.data.model.VpnConnectionState
import com.visionvpn.app.service.VisionVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class VpnCoreController(
    private val context: Context,
    private val repository: VisionPreferencesRepository,
    private val connectivityProbe: suspend (proxyPort: Int) -> LatencyTestOutcome = { port ->
        PingTester.probeThroughLocalHttpProxy(
            proxyPort = port,
            testUrl = PingTester.DEFAULT_TEST_URL,
            timeoutMs = 10_000
        )
    }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val wsClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    val localEngineManager = LocalEngineManager(
        onEngineUnrecoverableFailure = { engineId, reason ->
            handleFailClosedEngineFailure(engineId, reason)
        }
    )
    val warpManager = WarpManager()
    val chainManager = ChainManager(context, localEngineManager, warpManager)
    val configBuilder = ConfigBuilder()
    private val libboxBridge = LibboxBridge(context)

    private val _connectionState = MutableStateFlow(VpnConnectionState.DISCONNECTED)
    val connectionState: StateFlow<VpnConnectionState> = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _activeExitProvider = MutableStateFlow(ChainExitType.NONE)
    val activeExitProvider: StateFlow<ChainExitType> = _activeExitProvider.asStateFlow()

    private val _activeChainLabels = MutableStateFlow<List<String>>(emptyList())
    val activeChainLabels: StateFlow<List<String>> = _activeChainLabels.asStateFlow()

    private val _connectedSinceMs = MutableStateFlow<Long?>(null)
    val connectedSinceMs: StateFlow<Long?> = _connectedSinceMs.asStateFlow()

    private val _trafficHistory = MutableStateFlow<List<TrafficSample>>(emptyList())
    val trafficHistory: StateFlow<List<TrafficSample>> = _trafficHistory.asStateFlow()

    private val _latestTraffic = MutableStateFlow(TrafficSample())
    val latestTraffic: StateFlow<TrafficSample> = _latestTraffic.asStateFlow()

    private val _lastGeneratedConfig = MutableStateFlow("")
    val lastGeneratedConfig: StateFlow<String> = _lastGeneratedConfig.asStateFlow()

    val engineStates: StateFlow<Map<String, LocalEngineState>> = localEngineManager.engineStates

    private var trafficWebSocket: WebSocket? = null
    private var telemetryReconnectJob: Job? = null
    private var activeConnectJob: Job? = null

    companion object {
        fun resolveEntryProfile(
            profiles: List<ProxyProfile>,
            activeProfileId: String?,
            chainConfig: ChainConfig
        ): Result<ProxyProfile?> = runCatching {
            val exitProvider = chainConfig.activeExitProvider
            val requiresExplicitEntry = exitProvider == ChainExitType.NONE || chainConfig.useEntryProxy
            if (!requiresExplicitEntry) {
                return@runCatching null
            }
            val selected = activeProfileId?.let { id -> profiles.firstOrNull { it.id == id } }
            requireNotNull(selected) {
                "Select an Entry Proxy profile explicitly before connecting."
            }
            selected
        }

        /**
         * Pure state-machine verifier: transitions to CONNECTED only when the end-to-end
         * probe through the active tunnel succeeds; otherwise returns a diagnostic failure.
         */
        suspend fun verifyEndToEndConnectivity(
            probePort: Int,
            maxAttempts: Int = 2,
            retryDelayMs: Long = 800L,
            probe: suspend (Int) -> LatencyTestOutcome
        ): LatencyTestOutcome {
            var lastFailure: LatencyTestOutcome.Failure? = null
            repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
                when (val result = probe(probePort)) {
                    is LatencyTestOutcome.Success -> return result
                    is LatencyTestOutcome.Failure -> {
                        lastFailure = result
                        if (attempt + 1 < maxAttempts) {
                            delay(retryDelayMs)
                        }
                    }
                }
            }
            return lastFailure ?: LatencyTestOutcome.Failure(
                com.visionvpn.app.data.model.LatencyStatus.FAILED,
                "Connectivity probe failed"
            )
        }
    }

    fun requestConnect() {
        val intent = Intent(context, VisionVpnService::class.java).apply {
            action = VisionVpnService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun requestDisconnect() {
        activeConnectJob?.cancel()
        activeConnectJob = null
        val intent = Intent(context, VisionVpnService::class.java).apply {
            action = VisionVpnService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun requestReconnect() {
        scope.launch {
            if (_connectionState.value.isActiveOrStarting) {
                _connectionState.value = VpnConnectionState.RECONNECTING
                _statusMessage.value = "Reconnecting tunnel..."
                requestDisconnect()
                delay(600)
                requestConnect()
            }
        }
    }

    internal suspend fun startTunnelInternal(vpnService: VpnService): Result<RoutingGraph> =
        lifecycleMutex.withLock {
            if (_connectionState.value.isActiveOrStarting &&
                _connectionState.value != VpnConnectionState.RECONNECTING
            ) {
                return Result.failure(IllegalStateException("VPN is already active"))
            }

            _connectionState.value = VpnConnectionState.CONNECTING
            _statusMessage.value = "Validating routing chain..."

            try {
                val profiles = repository.profilesFlow.first()
                val activeId = repository.activeProfileIdFlow.first()
                val chainConfig: ChainConfig = repository.chainConfigFlow.first()
                val settings: AppSettings = repository.appSettingsFlow.first()

                val entryResult = resolveEntryProfile(profiles, activeId, chainConfig)
                if (entryResult.isFailure) {
                    return failAndCleanup(entryResult.exceptionOrNull()?.message ?: "Entry profile selection required")
                }
                val entryProfile = entryResult.getOrNull()

                val graphResult = chainManager.prepareRoutingGraph(
                    entryProfile = entryProfile,
                    rawChainConfig = chainConfig,
                    allProfiles = profiles,
                    onWarpConfigUpdated = { updated -> repository.saveChainConfig(updated) }
                )
                if (graphResult.isFailure) {
                    return failAndCleanup(graphResult.exceptionOrNull()?.message ?: "Invalid routing chain")
                }
                val graph = graphResult.getOrThrow()
                _activeExitProvider.value = graph.effectiveFinalExitProvider
                _activeChainLabels.value = graph.describeChain()

                val singBoxJson = configBuilder.buildSingBoxConfig(graph, settings)
                _lastGeneratedConfig.value = singBoxJson

                _statusMessage.value = "Starting Sing-box core..."
                val coreStart = libboxBridge.startCore(singBoxJson, vpnService, settings)
                if (coreStart.isFailure) {
                    return failAndCleanup(
                        coreStart.exceptionOrNull()?.message ?: "Sing-box libbox failed to start"
                    )
                }

                _connectionState.value = VpnConnectionState.ENGINE_READY

                if (graph.includesTor || graph.includesPsiphon) {
                    _statusMessage.value = "Establishing local exit engine..."
                    val enginesResult = chainManager.startLocalEnginesForGraph(graph)
                    if (enginesResult.isFailure) {
                        libboxBridge.stopCore()
                        return failAndCleanup(
                            enginesResult.exceptionOrNull()?.message ?: "Exit engine failed (Fail-Closed)"
                        )
                    }
                }

                // Stage 3: Require real end-to-end HTTPS connectivity through the active tunnel's
                // loopback `mixed-probe-in` (never through the app's VPN-excluded direct sockets).
                _connectionState.value = VpnConnectionState.VERIFYING_CONNECTIVITY
                _statusMessage.value = "Verifying internet connectivity through tunnel..."
                val probeOutcome = verifyEndToEndConnectivity(
                    probePort = ConfigBuilder.PROBE_PROXY_PORT,
                    maxAttempts = if (graph.includesTor) 3 else 2,
                    retryDelayMs = if (graph.includesTor) 1_500L else 700L,
                    probe = connectivityProbe
                )
                if (probeOutcome !is LatencyTestOutcome.Success) {
                    val failure = probeOutcome as LatencyTestOutcome.Failure
                    return failAndCleanup(
                        "Tunnel started but internet check failed (${failure.status.name}): ${failure.reason}"
                    )
                }

                _connectedSinceMs.value = System.currentTimeMillis()
                _connectionState.value = VpnConnectionState.CONNECTED
                _statusMessage.value = "${graph.describeChain().joinToString(" → ")} (${probeOutcome.latencyMs} ms)"
                startClashApiTelemetry()
                Result.success(graph)
            } catch (ce: CancellationException) {
                stopTelemetry()
                chainManager.stopLocalEngines()
                libboxBridge.stopCore()
                _connectedSinceMs.value = null
                _connectionState.value = VpnConnectionState.DISCONNECTED
                _statusMessage.value = ""
                throw ce
            }
        }

    internal suspend fun stopTunnelInternal() = lifecycleMutex.withLock {
        if (_connectionState.value == VpnConnectionState.DISCONNECTED) return@withLock
        _connectionState.value = VpnConnectionState.DISCONNECTING
        _statusMessage.value = "Stopping tunnel..."
        stopTelemetry()

        chainManager.stopLocalEngines()
        libboxBridge.stopCore()

        _connectedSinceMs.value = null
        _activeChainLabels.value = emptyList()
        _connectionState.value = VpnConnectionState.DISCONNECTED
        _statusMessage.value = ""
        _latestTraffic.value = TrafficSample()
    }

    private suspend fun handleFailClosedEngineFailure(engineId: String, reason: String) {
        lifecycleMutex.withLock {
            stopTelemetry()
            chainManager.stopLocalEngines()
            libboxBridge.stopCore()
            _connectedSinceMs.value = null
            _connectionState.value = VpnConnectionState.ERROR
            _statusMessage.value = "Fail-Closed ($engineId): $reason"
        }
        requestDisconnect()
    }

    private suspend fun failAndCleanup(reason: String): Result<RoutingGraph> {
        stopTelemetry()
        chainManager.stopLocalEngines()
        libboxBridge.stopCore()
        _connectedSinceMs.value = null
        _connectionState.value = VpnConnectionState.ERROR
        _statusMessage.value = reason
        return Result.failure(IllegalStateException(reason))
    }

    private fun startClashApiTelemetry() {
        stopTelemetry()
        _trafficHistory.value = emptyList()

        telemetryReconnectJob = scope.launch {
            var totalUp = 0L
            var totalDown = 0L
            val request = Request.Builder()
                .url("ws://127.0.0.1:${ConfigBuilder.CLASH_API_PORT}/traffic")
                .build()

            while (isActive && _connectionState.value == VpnConnectionState.CONNECTED) {
                var closed = false
                val ws = wsClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                        val up = obj["up"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(0L) ?: 0L
                        val down = obj["down"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(0L) ?: 0L
                        totalUp += up
                        totalDown += down
                        val sample = TrafficSample(
                            timestampMs = System.currentTimeMillis(),
                            uploadBytesPerSec = up,
                            downloadBytesPerSec = down,
                            totalUploadBytes = totalUp,
                            totalDownloadBytes = totalDown
                        )
                        _latestTraffic.value = sample
                        _trafficHistory.value = (_trafficHistory.value + sample).takeLast(45)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        closed = true
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closed = true
                    }
                })
                trafficWebSocket = ws

                while (isActive && !closed && _connectionState.value == VpnConnectionState.CONNECTED) {
                    delay(1_000L)
                }
                ws.cancel()
                delay(1_500L)
            }
        }
    }

    private fun stopTelemetry() {
        telemetryReconnectJob?.cancel()
        telemetryReconnectJob = null
        trafficWebSocket?.cancel()
        trafficWebSocket = null
    }
}
