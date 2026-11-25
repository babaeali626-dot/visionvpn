package com.visionvpn.app.core

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import com.visionvpn.app.chain.ChainManager
import com.visionvpn.app.chain.LocalEngineManager
import com.visionvpn.app.chain.LocalEngineState
import com.visionvpn.app.chain.RoutingGraph
import com.visionvpn.app.data.local.VisionPreferencesRepository
import com.visionvpn.app.data.model.AppSettings
import com.visionvpn.app.data.model.ChainConfig
import com.visionvpn.app.data.model.ChainExitType
import com.visionvpn.app.data.model.LocalEngineProfileType
import com.visionvpn.app.data.model.ProxyProfile
import com.visionvpn.app.data.model.TrafficSample
import com.visionvpn.app.data.model.VpnConnectionState
import com.visionvpn.app.service.VisionVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
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
    },
    private val exitIpFetcher: suspend (proxyPort: Int) -> ExitIpInfo = { port ->
        ExitIpLookup.fetchThroughTunnelProxy(proxyPort = port)
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
    val chainManager = ChainManager(context, localEngineManager)
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

    private val _exitIpInfo = MutableStateFlow(ExitIpInfo(status = ExitIpStatus.DISCONNECTED))
    val exitIpInfo: StateFlow<ExitIpInfo> = _exitIpInfo.asStateFlow()

    private val _isFailClosedBlackholeActive = MutableStateFlow(false)
    val isFailClosedBlackholeActive: StateFlow<Boolean> = _isFailClosedBlackholeActive.asStateFlow()

    private val _osAlwaysOnEnabled = MutableStateFlow(false)
    val osAlwaysOnEnabled: StateFlow<Boolean> = _osAlwaysOnEnabled.asStateFlow()

    private val _osLockdownEnabled = MutableStateFlow(false)
    val osLockdownEnabled: StateFlow<Boolean> = _osLockdownEnabled.asStateFlow()

    private val _trafficHistory = MutableStateFlow<List<TrafficSample>>(emptyList())
    val trafficHistory: StateFlow<List<TrafficSample>> = _trafficHistory.asStateFlow()

    private val _latestTraffic = MutableStateFlow(TrafficSample())
    val latestTraffic: StateFlow<TrafficSample> = _latestTraffic.asStateFlow()

    private val _lastGeneratedConfig = MutableStateFlow("")
    val lastGeneratedConfig: StateFlow<String> = _lastGeneratedConfig.asStateFlow()

    val engineStates: StateFlow<Map<String, LocalEngineState>> = localEngineManager.engineStates

    private var trafficWebSocket: WebSocket? = null
    private var telemetryReconnectJob: Job? = null
    private var exitIpLookupJob: Job? = null
    private var activeConnectJob: Job? = null
    @Volatile
    private var connectRequestPending: Boolean = false

    @Volatile
    private var lastSettings: AppSettings = AppSettings()

    @Volatile
    private var activeVpnService: VpnService? = null

    enum class EngineFailureAction {
        HOLD_BLACKHOLE_TUN,
        RETAIN_EXISTING_TUN,
        TEARDOWN_DIRECT_NETWORK
    }

    companion object {
        /**
         * Determines the exact network action when a mid-session local engine or chain hop
         * fails unrecoverably:
         * - When [failClosedMode] is `true` and the VPN service is attached, hold a blocking
         *   blackhole TUN until explicit user disconnect (no direct-network fallback).
         * - When [failClosedMode] is `false` (or no VPN service is attached), tear down the
         *   tunnel cleanly so device traffic returns to the physical network with an explicit warning.
         */
        fun decideEngineFailureAction(
            failClosedMode: Boolean,
            hasActiveVpnService: Boolean
        ): EngineFailureAction {
            return if (failClosedMode && hasActiveVpnService) {
                EngineFailureAction.HOLD_BLACKHOLE_TUN
            } else {
                EngineFailureAction.TEARDOWN_DIRECT_NETWORK
            }
        }

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
            if (LocalEngineProfileType.isReservedId(activeProfileId)) {
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
        if (connectRequestPending ||
            (_connectionState.value.isActiveOrStarting && _connectionState.value != VpnConnectionState.RECONNECTING)
        ) return
        connectRequestPending = true
        val intent = Intent(context, VisionVpnService::class.java).apply {
            action = VisionVpnService.ACTION_START
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (t: Throwable) {
            connectRequestPending = false
            _connectionState.value = VpnConnectionState.ERROR
            _statusMessage.value = "Unable to start VPN service: ${LogRedactor.redact(t.message.orEmpty())}"
        }
    }

    /** Admission check shared with bulk latency tests to close the service-start dispatch race. */
    fun canStartLatencyTests(): Boolean =
        !connectRequestPending && !_connectionState.value.isActiveOrStarting &&
            LibboxRuntime.currentMode() == LibboxLeaseMode.IDLE

    fun requestDisconnect() {
        connectRequestPending = false
        activeConnectJob?.cancel()
        activeConnectJob = null
        exitIpLookupJob?.cancel()
        exitIpLookupJob = null
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
                if (_exitIpInfo.value.status == ExitIpStatus.VERIFIED) {
                    _exitIpInfo.value = _exitIpInfo.value.copy(status = ExitIpStatus.STALE)
                }
                requestDisconnect()
                delay(600)
                requestConnect()
            }
        }
    }

    /**
     * Refreshes public exit IP and country exclusively through the active tunnel probe inbound.
     * Ignored when not connected; never falls back to direct device sockets.
     */
    fun refreshExitIp() {
        if (_connectionState.value != VpnConnectionState.CONNECTED) {
            _exitIpInfo.value = ExitIpInfo(status = ExitIpStatus.DISCONNECTED)
            return
        }
        exitIpLookupJob?.cancel()
        exitIpLookupJob = scope.launch {
            _exitIpInfo.value = ExitIpInfo(status = ExitIpStatus.LOADING)
            val result = try {
                exitIpFetcher(ConfigBuilder.PROBE_PROXY_PORT)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                ExitIpInfo(status = ExitIpStatus.UNAVAILABLE, errorNote = "Exit IP lookup failed")
            }
            // Never overwrite DISCONNECTED state if tunnel dropped mid-lookup
            if (_connectionState.value == VpnConnectionState.CONNECTED) {
                _exitIpInfo.value = result
            } else {
                _exitIpInfo.value = ExitIpInfo(status = ExitIpStatus.DISCONNECTED)
            }
        }
    }

    internal suspend fun startTunnelInternal(vpnService: VpnService): Result<RoutingGraph> =
        lifecycleMutex.withLock {
            connectRequestPending = false
            if (_connectionState.value.isActiveOrStarting &&
                _connectionState.value != VpnConnectionState.RECONNECTING
            ) {
                return Result.failure(IllegalStateException("VPN is already active"))
            }
            if (LibboxRuntime.currentMode() != LibboxLeaseMode.IDLE) {
                return Result.failure(
                    IllegalStateException("Libbox runtime is still protected, cleaning up, or quarantined. Disconnect/restart before connecting.")
                )
            }

            exitIpLookupJob?.cancel()
            exitIpLookupJob = null
            _exitIpInfo.value = ExitIpInfo(status = ExitIpStatus.DISCONNECTED)
            _connectionState.value = VpnConnectionState.CONNECTING
            _statusMessage.value = "Validating routing chain..."

            try {
                val profiles = repository.profilesFlow.first()
                val activeId = repository.activeProfileIdFlow.first()
                val chainConfig: ChainConfig = repository.chainConfigFlow.first()
                val settings: AppSettings = repository.appSettingsFlow.first()
                lastSettings = settings
                activeVpnService = vpnService
                _isFailClosedBlackholeActive.value = false
                refreshOsLockdownStatus(vpnService)

                val entryResult = resolveEntryProfile(profiles, activeId, chainConfig)
                if (entryResult.isFailure) {
                    return failAndCleanup(
                        "[Validation] ${LogRedactor.redact(entryResult.exceptionOrNull()?.message ?: "Entry profile selection required")}"
                    )
                }
                val entryProfile = entryResult.getOrNull()

                val graphResult = chainManager.prepareRoutingGraph(
                    entryProfile = entryProfile,
                    rawChainConfig = chainConfig,
                    allProfiles = profiles
                )
                if (graphResult.isFailure) {
                    return failAndCleanup(
                        "[Chain] ${LogRedactor.redact(graphResult.exceptionOrNull()?.message ?: "Invalid routing chain")}"
                    )
                }
                val graph = graphResult.getOrThrow()
                _activeExitProvider.value = graph.effectiveFinalExitProvider
                _activeChainLabels.value = graph.describeChain()

                val singBoxJson = try {
                    configBuilder.buildSingBoxConfig(graph, settings)
                } catch (e: Exception) {
                    return failAndCleanup(
                        "[Config] ${LogRedactor.redact(e.message ?: "Failed to build Sing-box configuration")}"
                    )
                }
                _lastGeneratedConfig.value = singBoxJson

                // Stage 1a: Start standalone/entry local engines (upstreamLoopbackPort == null) BEFORE TUN
                val hasPreCoreEngines = graph.nodes.any {
                    (it is com.visionvpn.app.chain.RoutingNode.TorNode || it is com.visionvpn.app.chain.RoutingNode.PsiphonNode) &&
                        it.upstreamLoopbackPort == null
                }
                if (hasPreCoreEngines) {
                    _statusMessage.value = "Starting entry local engine..."
                    val preResult = chainManager.startPreCoreLocalEngines(graph)
                    if (preResult.isFailure) {
                        return failAndCleanup(
                            "[Local Engine] ${LogRedactor.redact(preResult.exceptionOrNull()?.message ?: "Entry engine failed (Fail-Closed)")}"
                        )
                    }
                }

                // Stage 1b: Start Sing-box core and establish Android TUN
                _statusMessage.value = "Starting Sing-box core..."
                val coreStart = libboxBridge.startCore(singBoxJson, vpnService, settings)
                if (coreStart.isFailure) {
                    return failAndCleanup(
                        "[Sing-box Core] ${LogRedactor.redact(coreStart.exceptionOrNull()?.message ?: "Sing-box libbox failed to start")}"
                    )
                }

                _connectionState.value = VpnConnectionState.ENGINE_READY

                // Stage 2: Start chained local engines (upstreamLoopbackPort != null) AFTER Sing-box opens mixed-upstream-N
                val hasPostCoreEngines = graph.nodes.any {
                    (it is com.visionvpn.app.chain.RoutingNode.TorNode || it is com.visionvpn.app.chain.RoutingNode.PsiphonNode) &&
                        it.upstreamLoopbackPort != null
                }
                if (hasPostCoreEngines) {
                    _statusMessage.value = "Establishing chained exit engine..."
                    val enginesResult = chainManager.startPostCoreLocalEngines(graph)
                    if (enginesResult.isFailure) {
                        return failAndCleanup(
                            "[Exit Engine] ${LogRedactor.redact(enginesResult.exceptionOrNull()?.message ?: "Exit engine failed (Fail-Closed)")}"
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
                        "[Connectivity] Tunnel started but HTTPS check failed (${failure.status.name}): ${LogRedactor.redact(failure.reason)}"
                    )
                }

                _connectedSinceMs.value = System.currentTimeMillis()
                _connectionState.value = VpnConnectionState.CONNECTED
                _statusMessage.value = "${graph.describeChain().joinToString(" → ")} (${probeOutcome.latencyMs} ms)"
                startClashApiTelemetry()
                refreshExitIp()
                Result.success(graph)
            } catch (ce: CancellationException) {
                stopTelemetry()
                exitIpLookupJob?.cancel()
                exitIpLookupJob = null
                // The caller may already be cancelled. Finish child-engine/native cleanup before
                // releasing this service's ownership, and retain the TUN if any child cannot stop.
                val cleanup = withContext(NonCancellable) {
                    val engineFailure = runCatching { chainManager.stopLocalEngines() }.exceptionOrNull()
                    if (engineFailure != null) Result.failure(engineFailure) else libboxBridge.stopCore()
                }
                _connectedSinceMs.value = null
                _activeExitProvider.value = ChainExitType.NONE
                _activeChainLabels.value = emptyList()
                _exitIpInfo.value = ExitIpInfo(status = ExitIpStatus.DISCONNECTED)
                if (cleanup.isSuccess) {
                    _isFailClosedBlackholeActive.value = false
                    activeVpnService = null
                    _connectionState.value = VpnConnectionState.DISCONNECTED
                    _statusMessage.value = ""
                } else {
                    _isFailClosedBlackholeActive.value = LibboxRuntime.isBlockingTunInstalled()
                    _connectionState.value = VpnConnectionState.ERROR
                    _statusMessage.value =
                        "Connection cancellation cleanup is incomplete. The VPN TUN/Libbox lease is retained for safety; tap Disconnect again or restart the app. " +
                            LogRedactor.redact(cleanup.exceptionOrNull()?.message.orEmpty())
                }
                throw ce
            }
        }

    /** Returns true only after the native service and all TUN descriptors are confirmed closed. */
    internal suspend fun stopTunnelInternal(): Boolean = lifecycleMutex.withLock {
        if (_connectionState.value == VpnConnectionState.DISCONNECTED &&
            LibboxRuntime.currentMode() == LibboxLeaseMode.IDLE && activeVpnService == null
        ) return@withLock true

        _connectionState.value = VpnConnectionState.DISCONNECTING
        _statusMessage.value = "Stopping tunnel..."
        stopTelemetry()
        exitIpLookupJob?.cancel()
        exitIpLookupJob = null

        val engineStopFailure = try {
            withContext(NonCancellable) { chainManager.stopLocalEngines() }
            null
        } catch (t: Throwable) {
            t
        }
        if (engineStopFailure != null) {
            _isFailClosedBlackholeActive.value = LibboxRuntime.isBlockingTunInstalled()
            _connectionState.value = VpnConnectionState.ERROR
            _statusMessage.value =
                "A local Tor/Psiphon engine could not be confirmed stopped: ${LogRedactor.redact(engineStopFailure.message.orEmpty())}. " +
                    "The existing VPN TUN and Libbox lease are retained; retry Disconnect."
            return@withLock false
        }

        val cleanup = withContext(NonCancellable) { libboxBridge.stopCore() }
        if (cleanup.isFailure) {
            _isFailClosedBlackholeActive.value = LibboxRuntime.isBlockingTunInstalled()
            _connectionState.value = VpnConnectionState.ERROR
            _statusMessage.value =
                "Native VPN cleanup could not be confirmed: ${LogRedactor.redact(cleanup.exceptionOrNull()?.message.orEmpty())}. " +
                    "The Libbox lease remains blocked; keep this service alive and retry Disconnect or restart the app."
            return@withLock false
        }

        _connectedSinceMs.value = null
        _activeExitProvider.value = ChainExitType.NONE
        _activeChainLabels.value = emptyList()
        _exitIpInfo.value = ExitIpInfo(status = ExitIpStatus.DISCONNECTED)
        _isFailClosedBlackholeActive.value = false
        activeVpnService = null
        _connectionState.value = VpnConnectionState.DISCONNECTED
        _statusMessage.value = ""
        _latestTraffic.value = TrafficSample()
        true
    }

    /**
     * Keep the foreground service alive while a VPN lease/TUN is retained OR a local child
     * process has not been confirmed stopped. The latter can occur before TUN establishment
     * (pre-core Tor/Psiphon startup) and still needs an explicit retryable cleanup path.
     */
    internal fun shouldKeepVpnServiceAlive(): Boolean =
        (activeVpnService != null && LibboxRuntime.currentMode() != LibboxLeaseMode.IDLE) ||
            localEngineManager.engineStates.value.values.any { it.phaseLabel == "Cleanup Required" }

    private suspend fun handleFailClosedEngineFailure(engineId: String, reason: String) {
        val action = lifecycleMutex.withLock {
            stopTelemetry()
            exitIpLookupJob?.cancel()
            exitIpLookupJob = null

            val svc = activeVpnService
            if (LibboxRuntime.isBlockingTunInstalled()) {
                val engineStopFailure = runCatching { chainManager.stopLocalEngines() }.exceptionOrNull()
                _isFailClosedBlackholeActive.value = true
                _connectionState.value = VpnConnectionState.ERROR
                _statusMessage.value = buildString {
                    append("Fail-Closed blocking TUN remains active after $engineId failure. Tap Disconnect to close the protected interface. ")
                    if (engineStopFailure != null) append("A local child engine still needs cleanup: ${LogRedactor.redact(engineStopFailure.message.orEmpty())}. ")
                    append(LogRedactor.redact(reason))
                }
                return@withLock EngineFailureAction.HOLD_BLACKHOLE_TUN
            }
            if (lastSettings.failClosedMode && svc != null &&
                LibboxRuntime.currentMode() != LibboxLeaseMode.IDLE &&
                LibboxRuntime.currentMode() != LibboxLeaseMode.VPN_ACTIVE
            ) {
                // A previous transition/cleanup failed. Never convert this retained interface
                // into a direct-network teardown from a second watchdog callback.
                _isFailClosedBlackholeActive.value = false
                _connectionState.value = VpnConnectionState.ERROR
                _statusMessage.value =
                    "Fail-Closed recovery is quarantined; the existing VPN interface is retained. Tap Disconnect to retry cleanup. ${LogRedactor.redact(reason)}"
                return@withLock EngineFailureAction.RETAIN_EXISTING_TUN
            }
            val decided = decideEngineFailureAction(
                failClosedMode = lastSettings.failClosedMode,
                hasActiveVpnService = svc != null
            )
            _connectedSinceMs.value = null
            _activeExitProvider.value = ChainExitType.NONE
            _activeChainLabels.value = emptyList()
            _exitIpInfo.value = ExitIpInfo(status = ExitIpStatus.DISCONNECTED)
            _connectionState.value = VpnConnectionState.ERROR

            if (decided == EngineFailureAction.HOLD_BLACKHOLE_TUN && svc != null &&
                LibboxRuntime.currentMode() == LibboxLeaseMode.VPN_ACTIVE
            ) {
                val transition = libboxBridge.enterFailClosedBlackhole(svc, lastSettings)
                if (LibboxRuntime.isBlockingTunInstalled()) {
                    // The replacement TUN is installed before stopping local engines/core.
                    val engineStopFailure = runCatching { chainManager.stopLocalEngines() }.exceptionOrNull()
                    _isFailClosedBlackholeActive.value = true
                    _statusMessage.value = buildString {
                        if (LibboxRuntime.currentMode() == LibboxLeaseMode.FAULTED) {
                            append("Traffic is blocked by the replacement TUN ($engineId), but native cleanup needs retry/restart. ")
                        } else {
                            append("Fail-Closed Lockdown Active ($engineId): traffic is blocked until you tap Disconnect. ")
                        }
                        if (engineStopFailure != null) append("A local child engine still needs cleanup: ${LogRedactor.redact(engineStopFailure.message.orEmpty())}. ")
                        append(LogRedactor.redact(reason))
                    }
                    EngineFailureAction.HOLD_BLACKHOLE_TUN
                } else {
                    // establish() failed: Android keeps the old interface intact. Do NOT close it,
                    // stop the service, or release its lease; expose a Disconnect-only recovery path.
                    _isFailClosedBlackholeActive.value = false
                    _statusMessage.value =
                        "Fail-Closed replacement failed ($engineId); the previous VPN interface is retained and Libbox is quarantined. Tap Disconnect to close it safely. ${LogRedactor.redact(transition.exceptionOrNull()?.message ?: reason)}"
                    EngineFailureAction.RETAIN_EXISTING_TUN
                }
            } else {
                val engineStopFailure = runCatching { chainManager.stopLocalEngines() }.exceptionOrNull()
                val cleanup = libboxBridge.stopCore()
                if (cleanup.isFailure) {
                    _isFailClosedBlackholeActive.value = LibboxRuntime.isBlockingTunInstalled()
                    _statusMessage.value =
                        "Engine failed ($engineId) and native cleanup is incomplete; the VPN service/lease is retained. Tap Disconnect to retry. " +
                            LogRedactor.redact(cleanup.exceptionOrNull()?.message.orEmpty())
                    EngineFailureAction.RETAIN_EXISTING_TUN
                } else {
                    _isFailClosedBlackholeActive.value = false
                    activeVpnService = null
                    _statusMessage.value = if (engineStopFailure != null) {
                        "Engine Lost ($engineId) — Fail-Closed Off: VPN TUN closed; local engine cleanup still needs retry. " +
                            LogRedactor.redact(engineStopFailure.message.orEmpty())
                    } else {
                        "Engine Lost ($engineId) — Fail-Closed Off: tunnel closed and traffic returned to direct network. ${LogRedactor.redact(reason)}"
                    }
                    EngineFailureAction.TEARDOWN_DIRECT_NETWORK
                }
            }
        }
        if (action == EngineFailureAction.TEARDOWN_DIRECT_NETWORK) {
            requestDisconnect()
        }
    }

    internal fun refreshOsLockdownStatus(vpnService: VpnService) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            _osAlwaysOnEnabled.value = runCatching { vpnService.isAlwaysOn }.getOrDefault(false)
            _osLockdownEnabled.value = runCatching { vpnService.isLockdownEnabled }.getOrDefault(false)
        } else {
            _osAlwaysOnEnabled.value = false
            _osLockdownEnabled.value = false
        }
    }

    private suspend fun failAndCleanup(reason: String): Result<RoutingGraph> {
        stopTelemetry()
        exitIpLookupJob?.cancel()
        exitIpLookupJob = null
        _connectedSinceMs.value = null
        _activeExitProvider.value = ChainExitType.NONE
        _activeChainLabels.value = emptyList()
        _exitIpInfo.value = ExitIpInfo(status = ExitIpStatus.DISCONNECTED)

        // Any failure after the native core has established a VPN interface must use an atomic
        // TUN handover when Fail-Closed is enabled. Never close the live TUN first.
        val svc = activeVpnService
        if (LibboxRuntime.isBlockingTunInstalled()) {
            val engineStopFailure = runCatching { chainManager.stopLocalEngines() }.exceptionOrNull()
            _isFailClosedBlackholeActive.value = true
            _connectionState.value = VpnConnectionState.ERROR
            _statusMessage.value = buildString {
                append("${LogRedactor.redact(reason)}. Fail-Closed blocking TUN remains active until explicit Disconnect. ")
                if (engineStopFailure != null) append("A local child engine still needs cleanup: ${LogRedactor.redact(engineStopFailure.message.orEmpty())}.")
            }
            return Result.failure(IllegalStateException(LogRedactor.redact(reason)))
        }
        if (lastSettings.failClosedMode && svc != null &&
            LibboxRuntime.currentMode() == LibboxLeaseMode.FAULTED &&
            libboxBridge.hasOpenTunInterface()
        ) {
            // Blackhole establish() failed after the prior VPN TUN was already live. Keep that
            // interface/service/lease rather than allowing cleanup to restore the physical route.
            _isFailClosedBlackholeActive.value = false
            _connectionState.value = VpnConnectionState.ERROR
            _statusMessage.value =
                "Failure: ${LogRedactor.redact(reason)}. The replacement blocking TUN could not be established; the previous VPN interface and Libbox lease remain retained. Tap Disconnect to retry cleanup."
            return Result.failure(IllegalStateException(LogRedactor.redact(reason)))
        }
        if (lastSettings.failClosedMode && svc != null &&
            LibboxRuntime.currentMode() == LibboxLeaseMode.VPN_ACTIVE
        ) {
            val transition = libboxBridge.enterFailClosedBlackhole(svc, lastSettings)
            if (LibboxRuntime.isBlockingTunInstalled()) {
                val engineStopFailure = runCatching { chainManager.stopLocalEngines() }.exceptionOrNull()
                _isFailClosedBlackholeActive.value = true
                _connectionState.value = VpnConnectionState.ERROR
                _statusMessage.value = buildString {
                    if (LibboxRuntime.currentMode() == LibboxLeaseMode.FAULTED) {
                        append("Failure: ${LogRedactor.redact(reason)}. Blocking TUN is active, but native cleanup needs retry/restart. ")
                    } else {
                        append("Failure: ${LogRedactor.redact(reason)}. Fail-Closed blocking TUN is active until explicit Disconnect. ")
                    }
                    if (engineStopFailure != null) append("A local child engine still needs cleanup: ${LogRedactor.redact(engineStopFailure.message.orEmpty())}.")
                }
                return Result.failure(IllegalStateException(LogRedactor.redact(reason)))
            }

            // The system leaves the previous interface untouched when establish() fails. Preserve
            // it and the native service/lease; do not create a direct-network gap in cleanup.
            _isFailClosedBlackholeActive.value = false
            _connectionState.value = VpnConnectionState.ERROR
            _statusMessage.value =
                "Failure: ${LogRedactor.redact(reason)}. Blocking TUN handover failed; previous VPN interface retained and Libbox quarantined. Tap Disconnect to retry cleanup. " +
                    LogRedactor.redact(transition.exceptionOrNull()?.message.orEmpty())
            return Result.failure(IllegalStateException(LogRedactor.redact(reason)))
        }

        val engineStopFailure = runCatching { chainManager.stopLocalEngines() }.exceptionOrNull()
        val cleanup = libboxBridge.stopCore()
        _connectionState.value = VpnConnectionState.ERROR
        if (cleanup.isFailure) {
            _isFailClosedBlackholeActive.value = LibboxRuntime.isBlockingTunInstalled()
            _statusMessage.value =
                "Failure: ${LogRedactor.redact(reason)}. Native cleanup is incomplete and the Libbox lease remains quarantined. Tap Disconnect to retry. " +
                    LogRedactor.redact(cleanup.exceptionOrNull()?.message.orEmpty())
        } else {
            _isFailClosedBlackholeActive.value = false
            // Native TUN/Libbox ownership is fully released even if an independent local child
            // process still needs retry. Do not retain a stale VpnService reference in this case.
            activeVpnService = null
            _statusMessage.value = if (engineStopFailure != null) {
                "Failure: ${LogRedactor.redact(reason)}. VPN TUN closed, but a local child engine still needs cleanup: " +
                    LogRedactor.redact(engineStopFailure.message.orEmpty())
            } else {
                LogRedactor.redact(reason)
            }
        }
        return Result.failure(IllegalStateException(LogRedactor.redact(reason)))
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
