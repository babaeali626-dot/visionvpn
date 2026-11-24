package com.visionvpn.app.ui.viewmodel

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.visionvpn.app.VisionVpnApplication
import com.visionvpn.app.chain.LocalEngineState
import com.visionvpn.app.chain.PluggableTransportAvailability
import com.visionvpn.app.chain.RoutingGraph
import com.visionvpn.app.chain.TorBridgeValidator
import com.visionvpn.app.core.PingTester
import com.visionvpn.app.core.VpnCoreController
import com.visionvpn.app.data.model.AppSettings
import com.visionvpn.app.data.model.ChainConfig
import com.visionvpn.app.data.model.ChainExitType
import com.visionvpn.app.data.model.InstalledAppInfo
import com.visionvpn.app.data.model.ProxyProfile
import com.visionvpn.app.data.model.SavedTorBridge
import com.visionvpn.app.data.model.SubscriptionSource
import com.visionvpn.app.data.model.TorBridgeMode
import com.visionvpn.app.data.model.TrafficSample
import com.visionvpn.app.data.model.VpnConnectionState
import com.visionvpn.app.data.parser.ProfileUriParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class VisionUiState(
    val profiles: List<ProxyProfile> = emptyList(),
    val subscriptions: List<SubscriptionSource> = emptyList(),
    val activeProfileId: String? = null,
    val chainConfig: ChainConfig = ChainConfig(),
    val settings: AppSettings = AppSettings(),
    val connectionState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val statusMessage: String = "",
    val activeExitProvider: ChainExitType = ChainExitType.NONE,
    val activeChainLabels: List<String> = emptyList(),
    val connectedSinceMs: Long? = null,
    val latestTraffic: TrafficSample = TrafficSample(),
    val trafficHistory: List<TrafficSample> = emptyList(),
    val engineStates: Map<String, LocalEngineState> = emptyMap(),
    val ptAvailability: PluggableTransportAvailability = PluggableTransportAvailability(null, null),
    val storageError: String? = null,
    val isTestingLatency: Boolean = false,
    val isFetchingSubscription: Boolean = false,
    val isRegisteringWarp: Boolean = false,
    val snackbarMessage: String? = null
) {
    /**
     * Explicit selected profile (never silently falls back to the first profile in standalone modes).
     */
    val activeProfile: ProxyProfile?
        get() = activeProfileId?.let { id -> profiles.firstOrNull { it.id == id } }

    val isPersian: Boolean
        get() = settings.language.isRtl

    fun previewChainHops(): List<String> {
        if (connectionState == VpnConnectionState.CONNECTED && activeChainLabels.isNotEmpty()) {
            return activeChainLabels
        }
        val entry = VpnCoreController.resolveEntryProfile(profiles, activeProfileId, chainConfig).getOrNull()
        val graph = RoutingGraph.build(entry, chainConfig, profiles).getOrNull()
        if (graph != null) return graph.describeChain()
        val entryName = activeProfile?.name ?: if (isPersian) "بدون سرور" else "No Entry Node"
        return listOf(entryName)
    }

    fun chainValidationError(): String? {
        val entryRes = VpnCoreController.resolveEntryProfile(profiles, activeProfileId, chainConfig)
        if (entryRes.isFailure) return entryRes.exceptionOrNull()?.message
        return RoutingGraph.build(entryRes.getOrNull(), chainConfig, profiles).exceptionOrNull()?.message
    }
}

class VisionViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VisionVpnApplication
    private val repository = app.preferencesRepository
    private val controller = app.vpnCoreController
    private val subscriptionFetcher = app.subscriptionFetcher

    private val detectedPtAvailability: PluggableTransportAvailability =
        TorBridgeValidator.detectTransportBinaries(File(app.applicationInfo.nativeLibraryDir))

    private val _isTestingLatency = MutableStateFlow(false)
    private val _isFetchingSub = MutableStateFlow(false)
    private val _isRegisteringWarp = MutableStateFlow(false)
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    val uiState: StateFlow<VisionUiState> = combine(
        combine(
            repository.profilesFlow,
            repository.subscriptionsFlow,
            repository.activeProfileIdFlow,
            repository.chainConfigFlow,
            repository.appSettingsFlow
        ) { profiles, subs, activeId, chain, settings ->
            PersistedSlice(profiles, subs, activeId, chain, settings)
        },
        combine(
            controller.connectionState,
            controller.statusMessage,
            controller.activeExitProvider,
            controller.activeChainLabels,
            controller.connectedSinceMs
        ) { state, msg, exitProvider, chainLabels, sinceMs ->
            RuntimeCoreSlice(state, msg, exitProvider, chainLabels, sinceMs)
        },
        combine(
            controller.latestTraffic,
            controller.trafficHistory,
            controller.engineStates,
            _isTestingLatency,
            _isFetchingSub
        ) { latest, history, engines, testing, fetching ->
            TelemetrySlice(latest, history, engines, testing, fetching)
        },
        combine(_isRegisteringWarp, _snackbarMessage, repository.storageErrorFlow) { warpBusy, snackbar, storageErr ->
            Triple(warpBusy, snackbar, storageErr)
        }
    ) { persisted, core, telemetry, (warpBusy, snackbar, storageErr) ->
        VisionUiState(
            profiles = persisted.profiles,
            subscriptions = persisted.subs,
            activeProfileId = persisted.activeId,
            chainConfig = persisted.chain,
            settings = persisted.settings,
            connectionState = core.state,
            statusMessage = core.msg,
            activeExitProvider = if (core.state == VpnConnectionState.CONNECTED) {
                core.exitProvider
            } else {
                val previewEntry = VpnCoreController.resolveEntryProfile(
                    persisted.profiles,
                    persisted.activeId,
                    persisted.chain
                ).getOrNull()
                RoutingGraph.build(previewEntry, persisted.chain, persisted.profiles)
                    .getOrNull()
                    ?.effectiveFinalExitProvider
                    ?: persisted.chain.activeExitProvider
            },
            activeChainLabels = core.chainLabels,
            connectedSinceMs = core.sinceMs,
            latestTraffic = telemetry.latest,
            trafficHistory = telemetry.history,
            engineStates = telemetry.engines,
            ptAvailability = detectedPtAvailability,
            storageError = storageErr,
            isTestingLatency = telemetry.testing,
            isFetchingSubscription = telemetry.fetching,
            isRegisteringWarp = warpBusy,
            snackbarMessage = snackbar
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = VisionUiState(ptAvailability = detectedPtAvailability)
    )

    private data class PersistedSlice(
        val profiles: List<ProxyProfile>,
        val subs: List<SubscriptionSource>,
        val activeId: String?,
        val chain: ChainConfig,
        val settings: AppSettings
    )

    private data class RuntimeCoreSlice(
        val state: VpnConnectionState,
        val msg: String,
        val exitProvider: ChainExitType,
        val chainLabels: List<String>,
        val sinceMs: Long?
    )

    private data class TelemetrySlice(
        val latest: TrafficSample,
        val history: List<TrafficSample>,
        val engines: Map<String, LocalEngineState>,
        val testing: Boolean,
        val fetching: Boolean
    )

    fun startVpn() {
        val validationError = uiState.value.chainValidationError()
        if (validationError != null) {
            postMessage(validationError)
            return
        }
        controller.requestConnect()
    }

    fun stopVpn() {
        controller.requestDisconnect()
    }

    fun reconnectVpn() {
        controller.requestReconnect()
    }

    fun selectProfile(profileId: String) {
        viewModelScope.launch {
            repository.setActiveProfileId(profileId)
        }
    }

    fun setExitProvider(provider: ChainExitType) {
        viewModelScope.launch {
            val updated = uiState.value.chainConfig.withExitProvider(provider)
            repository.saveChainConfig(updated)
            if (uiState.value.connectionState == VpnConnectionState.CONNECTED) {
                postMessage(
                    if (uiState.value.isPersian) "خروجی تغییر کرد؛ در حال اتصال مجدد…"
                    else "Exit provider updated; reconnecting safely…"
                )
                controller.requestReconnect()
            }
        }
    }

    fun setUseEntryProxy(enabled: Boolean) {
        viewModelScope.launch {
            val updated = uiState.value.chainConfig.copy(useEntryProxy = enabled)
            repository.saveChainConfig(updated)
            if (uiState.value.connectionState == VpnConnectionState.CONNECTED) {
                controller.requestReconnect()
            }
        }
    }

    fun setPsiphonRegion(regionCode: String) {
        viewModelScope.launch {
            val updated = uiState.value.chainConfig.copy(
                psiphonConfig = uiState.value.chainConfig.psiphonConfig.copy(regionCode = regionCode)
            )
            repository.saveChainConfig(updated)
            if (uiState.value.connectionState == VpnConnectionState.CONNECTED &&
                updated.orderedExitHops().contains(ChainExitType.PSIPHON)
            ) {
                controller.requestReconnect()
            }
        }
    }

    fun updatePsiphonProvisioning(channelId: String, sponsorId: String, serverEntries: String) {
        viewModelScope.launch {
            val updated = uiState.value.chainConfig.copy(
                psiphonConfig = uiState.value.chainConfig.psiphonConfig.copy(
                    propagationChannelId = channelId.trim(),
                    sponsorId = sponsorId.trim(),
                    embeddedServerEntries = serverEntries.trim()
                )
            )
            repository.saveChainConfig(updated)
            postMessage("Psiphon provisioning credentials saved securely.")
        }
    }

    fun setTorBridgesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val currentTor = uiState.value.chainConfig.torConfig
            val nextMode = if (enabled && currentTor.bridgeMode == TorBridgeMode.DIRECT) {
                if (currentTor.savedBridges.isNotEmpty()) TorBridgeMode.CUSTOM else TorBridgeMode.SNOWFLAKE_TRANSPORT
            } else if (!enabled) {
                TorBridgeMode.DIRECT
            } else {
                currentTor.bridgeMode
            }
            val updated = uiState.value.chainConfig.copy(
                torConfig = currentTor.copy(
                    bridgesEnabled = enabled,
                    useBridges = enabled,
                    bridgeMode = nextMode
                )
            )
            repository.saveChainConfig(updated)
        }
    }

    fun setTorBridgeMode(mode: TorBridgeMode) {
        viewModelScope.launch {
            val currentTor = uiState.value.chainConfig.torConfig
            val enabled = mode != TorBridgeMode.DIRECT
            val updated = uiState.value.chainConfig.copy(
                torConfig = currentTor.copy(
                    bridgesEnabled = enabled,
                    useBridges = enabled,
                    bridgeMode = mode
                )
            )
            repository.saveChainConfig(updated)
        }
    }

    fun addTorBridgesFromText(rawText: String, labelPrefix: String = "Bridge") {
        val (valid, errors) = TorBridgeValidator.validateMultiple(rawText)
        if (valid.isEmpty()) {
            postMessage(errors.firstOrNull() ?: "No valid Tor bridge lines found.")
            return
        }
        viewModelScope.launch {
            val currentTor = uiState.value.chainConfig.torConfig
            val existingLines = currentTor.savedBridges.map { it.bridgeLine }.toSet()
            val added = valid
                .filter { it.normalizedLine !in existingLines }
                .mapIndexed { idx, res ->
                    SavedTorBridge(
                        label = "$labelPrefix (${res.transport.uppercase()}) #${currentTor.savedBridges.size + idx + 1}",
                        bridgeLine = res.normalizedLine,
                        transport = res.transport,
                        enabled = true
                    )
                }
            val updated = uiState.value.chainConfig.copy(
                torConfig = currentTor.copy(
                    bridgesEnabled = true,
                    useBridges = true,
                    bridgeMode = TorBridgeMode.CUSTOM,
                    savedBridges = currentTor.savedBridges + added
                )
            )
            repository.saveChainConfig(updated)
            val errSuffix = if (errors.isNotEmpty()) " (${errors.size} invalid line(s) skipped)" else ""
            postMessage("Saved ${added.size} Tor bridge(s)$errSuffix")
        }
    }

    fun editSavedTorBridge(bridgeId: String, newLabel: String, newLine: String) {
        val validation = TorBridgeValidator.validateLine(newLine)
        if (!validation.isValid) {
            postMessage(validation.errorMessage ?: "Invalid Tor bridge syntax.")
            return
        }
        viewModelScope.launch {
            val currentTor = uiState.value.chainConfig.torConfig
            val updatedList = currentTor.savedBridges.map { bridge ->
                if (bridge.id == bridgeId) {
                    bridge.copy(
                        label = newLabel.trim().ifBlank { bridge.label },
                        bridgeLine = validation.normalizedLine,
                        transport = validation.transport
                    )
                } else bridge
            }
            repository.saveChainConfig(
                uiState.value.chainConfig.copy(torConfig = currentTor.copy(savedBridges = updatedList))
            )
            postMessage(
                if (uiState.value.isPersian) "پل تور به‌روزرسانی شد."
                else "Tor bridge updated."
            )
        }
    }

    fun toggleSavedTorBridge(bridgeId: String, enabled: Boolean) {
        viewModelScope.launch {
            val currentTor = uiState.value.chainConfig.torConfig
            val updatedList = currentTor.savedBridges.map {
                if (it.id == bridgeId) it.copy(enabled = enabled) else it
            }
            repository.saveChainConfig(
                uiState.value.chainConfig.copy(torConfig = currentTor.copy(savedBridges = updatedList))
            )
        }
    }

    fun deleteSavedTorBridge(bridgeId: String) {
        viewModelScope.launch {
            val currentTor = uiState.value.chainConfig.torConfig
            val remaining = currentTor.savedBridges.filterNot { it.id == bridgeId }
            repository.saveChainConfig(
                uiState.value.chainConfig.copy(torConfig = currentTor.copy(savedBridges = remaining))
            )
        }
    }

    fun clearAllTorBridges() {
        viewModelScope.launch {
            repository.clearSavedTorBridges()
            postMessage(
                if (uiState.value.isPersian) "همه پل‌های ذخیره شده حذف شدند."
                else "All saved Tor bridges removed."
            )
        }
    }

    fun importFromRawText(rawText: String) {
        viewModelScope.launch {
            val parsed = ProfileUriParser.parseBatch(rawText)
            if (parsed.isEmpty()) {
                postMessage(
                    if (uiState.value.isPersian) "پروفایل معتبری یافت نشد."
                    else "No valid proxy URI found (VLESS, VMess, Trojan, SS, HY2, TUIC, WG, SOCKS5)."
                )
                return@launch
            }
            val updated = uiState.value.profiles + parsed
            repository.saveProfiles(updated)
            if (uiState.value.activeProfileId == null) {
                repository.setActiveProfileId(parsed.first().id)
            }
            postMessage(
                if (uiState.value.isPersian) "${parsed.size} پروفایل با موفقیت اضافه شد."
                else "Imported ${parsed.size} profile(s)."
            )
        }
    }

    fun importSubscription(name: String, url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _isFetchingSub.value = true
            val subId = UUID.randomUUID().toString()
            val result = subscriptionFetcher.fetchSubscription(url, subId)
            _isFetchingSub.value = false

            result.onSuccess { fetched ->
                val sub = SubscriptionSource(
                    id = subId,
                    name = name.ifBlank { "Subscription" },
                    url = url.trim(),
                    lastUpdatedEpochMs = System.currentTimeMillis(),
                    profileCount = fetched.size
                )
                val newSubs = uiState.value.subscriptions + sub
                val newProfiles = uiState.value.profiles + fetched
                repository.saveSubscriptions(newSubs)
                repository.saveProfiles(newProfiles)
                if (uiState.value.activeProfileId == null && fetched.isNotEmpty()) {
                    repository.setActiveProfileId(fetched.first().id)
                }
                postMessage("Subscription synced: ${fetched.size} nodes")
            }.onFailure { err ->
                postMessage(err.message ?: "Subscription fetch failed")
            }
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            val remaining = uiState.value.profiles.filterNot { it.id == profileId }
            repository.saveProfiles(remaining)
            if (uiState.value.activeProfileId == profileId) {
                repository.setActiveProfileId(null)
            }
        }
    }

    fun testSingleLatency(profile: ProxyProfile) {
        viewModelScope.launch {
            val latency = PingTester.measureTcpLatency(profile.server, profile.port)
            val updated = uiState.value.profiles.map {
                if (it.id == profile.id) it.copy(latencyMs = latency) else it
            }
            repository.saveProfiles(updated)
        }
    }

    fun testAllLatencies() {
        val current = uiState.value.profiles
        if (current.isEmpty() || _isTestingLatency.value) return
        viewModelScope.launch {
            _isTestingLatency.value = true
            val results = mutableMapOf<String, Int>()
            PingTester.testAll(current) { id, ms ->
                synchronized(results) { results[id] = ms }
            }
            val updated = uiState.value.profiles.map { p ->
                val ms = results[p.id]
                if (ms != null) p.copy(latencyMs = ms) else p
            }
            repository.saveProfiles(updated)
            _isTestingLatency.value = false
            postMessage(
                if (uiState.value.isPersian) "تست پینگ همه سرورها انجام شد."
                else "Latency test completed for ${current.size} nodes."
            )
        }
    }

    fun updateChainConfig(transform: (ChainConfig) -> ChainConfig) {
        viewModelScope.launch {
            val updated = transform(uiState.value.chainConfig)
            repository.saveChainConfig(updated)
        }
    }

    fun registerWarpAccount() {
        if (_isRegisteringWarp.value) return
        viewModelScope.launch {
            _isRegisteringWarp.value = true
            val currentWarp = uiState.value.chainConfig.warpConfig
            val result = controller.warpManager.registerNewAccount(
                existingEndpointHost = currentWarp.endpointHost,
                existingEndpointPort = currentWarp.endpointPort
            )
            _isRegisteringWarp.value = false
            result.onSuccess { newWarp ->
                updateChainConfig { it.copy(warpConfig = newWarp) }
                postMessage("Cloudflare WARP keys registered successfully")
            }.onFailure { err ->
                postMessage(err.message ?: "WARP registration failed")
            }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            val updated = transform(uiState.value.settings)
            repository.saveAppSettings(updated)
        }
    }

    fun loadInstalledApps() {
        if (_installedApps.value.isNotEmpty()) return
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = app.packageManager
                pm.getInstalledApplications(0)
                    .map { info ->
                        InstalledAppInfo(
                            packageName = info.packageName,
                            appName = pm.getApplicationLabel(info).toString(),
                            isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    }
                    .sortedWith(compareBy<InstalledAppInfo> { it.isSystemApp }.thenBy { it.appName.lowercase() })
            }
            _installedApps.value = apps
        }
    }

    fun consumeSnackbar() {
        _snackbarMessage.value = null
    }

    private fun postMessage(msg: String) {
        _snackbarMessage.value = msg
    }
}
