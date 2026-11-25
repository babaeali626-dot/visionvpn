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
import com.visionvpn.app.chain.WarpManager
import com.visionvpn.app.core.ExitIpInfo
import com.visionvpn.app.core.LibboxRuntime
import com.visionvpn.app.core.PingTester
import com.visionvpn.app.core.VpnCoreController
import com.visionvpn.app.data.model.AppLanguage
import com.visionvpn.app.data.model.AppSettings
import com.visionvpn.app.data.model.BuiltInProfilesCatalog
import com.visionvpn.app.data.model.ChainConfig
import com.visionvpn.app.data.model.ChainExitType
import com.visionvpn.app.data.model.InstalledAppInfo
import com.visionvpn.app.data.model.LocalEngineProfileType
import com.visionvpn.app.data.model.ProfileGroup
import com.visionvpn.app.data.model.ProxyProfile
import com.visionvpn.app.data.model.PsiphonTransferUrl
import com.visionvpn.app.data.model.SavedTorBridge
import com.visionvpn.app.data.model.SelectableProfile
import com.visionvpn.app.data.model.SubscriptionProfileManager
import com.visionvpn.app.data.model.SubscriptionSource
import com.visionvpn.app.data.model.TorBridgeMode
import com.visionvpn.app.data.model.TrafficSample
import com.visionvpn.app.data.model.VisionThemeVariant
import com.visionvpn.app.data.model.VpnConnectionState
import com.visionvpn.app.data.parser.SubscriptionFormatParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.util.concurrent.atomic.AtomicLong

data class TelemetryUiState(
    val latestTraffic: TrafficSample = TrafficSample(),
    val trafficHistory: List<TrafficSample> = emptyList()
)

data class VisionUiState(
    val profiles: List<ProxyProfile> = emptyList(),
    val subscriptions: List<SubscriptionSource> = emptyList(),
    val profileGroups: List<ProfileGroup> = emptyList(),
    val builtInProfiles: List<SelectableProfile.BuiltInEngine> = emptyList(),
    val selectedProfile: SelectableProfile? = null,
    val activeProfileId: String? = null,
    val chainConfig: ChainConfig = ChainConfig(),
    val settings: AppSettings = AppSettings(),
    val connectionState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val statusMessage: String = "",
    val activeExitProvider: ChainExitType = ChainExitType.NONE,
    val activeChainLabels: List<String> = emptyList(),
    val connectedSinceMs: Long? = null,
    val exitIpInfo: ExitIpInfo = ExitIpInfo(),
    val isFailClosedBlackholeActive: Boolean = false,
    val osAlwaysOnEnabled: Boolean = false,
    val osLockdownEnabled: Boolean = false,
    val engineStates: Map<String, LocalEngineState> = emptyMap(),
    val ptAvailability: PluggableTransportAvailability = PluggableTransportAvailability(null, null),
    val storageError: String? = null,
    val isTestingLatency: Boolean = false,
    val testingSubscriptionId: String? = null,
    val latencyProgress: Pair<Int, Int>? = null,
    val isFetchingSubscription: Boolean = false,
    val refreshingSubscriptionIds: Set<String> = emptySet(),
    val snackbarMessage: String? = null
) {
    /**
     * Explicit selected remote proxy profile (null when a built-in local engine or nothing is selected).
     */
    val activeProfile: ProxyProfile?
        get() = activeProfileId
            ?.takeUnless { LocalEngineProfileType.isReservedId(it) }
            ?.let { id -> profiles.firstOrNull { it.id == id } }

    /**
     * Locked to English (LTR) for this release regardless of legacy stored settings.
     */
    val isPersian: Boolean
        get() = false

    fun previewChainHops(): List<String> {
        if (connectionState == VpnConnectionState.CONNECTED && activeChainLabels.isNotEmpty()) {
            return activeChainLabels
        }
        val entry = VpnCoreController.resolveEntryProfile(profiles, activeProfileId, chainConfig).getOrNull()
        val graph = RoutingGraph.build(entry, chainConfig, profiles).getOrNull()
        if (graph != null) return graph.describeChain()
        val entryName = selectedProfile?.displayName ?: "No Profile Selected"
        return listOf(entryName)
    }

    fun chainValidationError(): String? {
        val entryRes = VpnCoreController.resolveEntryProfile(profiles, activeProfileId, chainConfig)
        if (entryRes.isFailure) return entryRes.exceptionOrNull()?.message
        val graphRes = RoutingGraph.build(entryRes.getOrNull(), chainConfig, profiles)
        if (graphRes.isFailure) return graphRes.exceptionOrNull()?.message
        val graph = graphRes.getOrThrow()
        if (graph.includesPsiphon && !chainConfig.psiphonConfig.isProvisioned) {
            return "Needs official Psiphon provisioning (ClientVersion, PropagationChannelId, SponsorId, and server discovery)."
        }
        return null
    }
}

class VisionViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VisionVpnApplication
    private val repository = app.preferencesRepository
    private val controller = app.vpnCoreController
    private val subscriptionFetcher = app.subscriptionFetcher
    private val proxyLatencyProbe = PingTester.createLibboxProbe(
        context = app,
        configBuilder = controller.configBuilder,
        settingsProvider = { uiState.value.settings }
    )

    private val detectedPtAvailability: PluggableTransportAvailability =
        TorBridgeValidator.detectTransportBinaries(File(app.applicationInfo.nativeLibraryDir))

    private val _isTestingLatency = MutableStateFlow(false)
    private val _testingSubscriptionId = MutableStateFlow<String?>(null)
    private val _latencyProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    private val _isFetchingSub = MutableStateFlow(false)
    private val _refreshingSubIds = MutableStateFlow<Set<String>>(emptySet())
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private var latencyTestJob: Job? = null
    private var vpnStartJob: Job? = null
    private val latencyRequestCounter = AtomicLong(0L)
    private val activeImportUrls = mutableSetOf<String>()

    /**
     * Fixed to English LTR + Vision UI Dark (`VISION_NAVY`) for this release.
     * Legacy persisted values in DataStore remain readable without affecting the active UI.
     */
    val themeLanguageState: StateFlow<Pair<AppLanguage, VisionThemeVariant>> =
        MutableStateFlow(AppLanguage.ENGLISH to VisionThemeVariant.VISION_NAVY).asStateFlow()

    /**
     * Separate 1-second traffic telemetry stream so only the TrafficGraphCard recomposes on ticks.
     */
    val telemetryState: StateFlow<TelemetryUiState> = combine(
        controller.latestTraffic,
        controller.trafficHistory
    ) { latest, history ->
        TelemetryUiState(latestTraffic = latest, trafficHistory = history)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TelemetryUiState()
    )

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
            controller.engineStates,
            _isTestingLatency,
            _testingSubscriptionId,
            _latencyProgress,
            _isFetchingSub
        ) { engines, testing, testingSubId, progress, fetching ->
            ActivitySlice(engines, testing, testingSubId, progress, fetching)
        },
        combine(
            combine(_refreshingSubIds, _snackbarMessage) { refreshing, snackbar -> refreshing to snackbar },
            repository.storageErrorFlow,
            controller.exitIpInfo,
            combine(
                controller.isFailClosedBlackholeActive,
                controller.osAlwaysOnEnabled,
                controller.osLockdownEnabled
            ) { bh, ao, ld -> Triple(bh, ao, ld) }
        ) { (refreshingIds, snackbar), storageErr, exitIp, (blackhole, alwaysOn, lockdown) ->
            ExtraSlice(refreshingIds, snackbar, storageErr, exitIp, blackhole, alwaysOn, lockdown)
        }
    ) { persisted, core, activity, extra ->
        val groups = SubscriptionProfileManager.groupProfiles(persisted.profiles, persisted.subs)
        val builtIns = BuiltInProfilesCatalog.buildBuiltInProfiles(persisted.chain, detectedPtAvailability)
        val selected = BuiltInProfilesCatalog.resolveSelectedProfile(
            profiles = persisted.profiles,
            subscriptions = persisted.subs,
            activeProfileId = persisted.activeId,
            chainConfig = persisted.chain,
            ptAvailability = detectedPtAvailability
        )
        VisionUiState(
            profiles = persisted.profiles,
            subscriptions = persisted.subs,
            profileGroups = groups,
            builtInProfiles = builtIns,
            selectedProfile = selected,
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
            exitIpInfo = extra.exitIp,
            isFailClosedBlackholeActive = extra.blackhole,
            osAlwaysOnEnabled = extra.alwaysOn,
            osLockdownEnabled = extra.lockdown,
            engineStates = activity.engines,
            ptAvailability = detectedPtAvailability,
            storageError = extra.storageErr,
            isTestingLatency = activity.testing,
            testingSubscriptionId = activity.testingSubId,
            latencyProgress = activity.latencyProgress,
            isFetchingSubscription = activity.fetching,
            refreshingSubscriptionIds = extra.refreshingIds,
            snackbarMessage = extra.snackbar
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

    private data class ActivitySlice(
        val engines: Map<String, LocalEngineState>,
        val testing: Boolean,
        val testingSubId: String?,
        val latencyProgress: Pair<Int, Int>?,
        val fetching: Boolean
    )

    private data class ExtraSlice(
        val refreshingIds: Set<String>,
        val snackbar: String?,
        val storageErr: String?,
        val exitIp: ExitIpInfo,
        val blackhole: Boolean,
        val alwaysOn: Boolean,
        val lockdown: Boolean
    )

    override fun onCleared() {
        latencyTestJob?.cancel()
        latencyTestJob = null
        super.onCleared()
    }

    fun startVpn() {
        val validationError = uiState.value.chainValidationError()
        if (validationError != null) {
            postMessage(validationError)
            return
        }
        if (vpnStartJob?.isActive == true) return
        val startJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                // A native Headless service must finish its NonCancellable close() before the VPN
                // service is allowed to claim the process-wide Libbox lease.
                val activeTest = latencyTestJob
                if (activeTest != null) {
                    latencyRequestCounter.incrementAndGet()
                    latencyTestJob = null
                    activeTest.cancelAndJoin()
                    _isTestingLatency.value = false
                    _testingSubscriptionId.value = null
                    _latencyProgress.value = null
                }
                if (LibboxRuntime.currentMode() != com.visionvpn.app.core.LibboxLeaseMode.IDLE) {
                    postMessage("Libbox is still cleaning up or is quarantined. Disconnect safely or restart VisionVPN before reconnecting.")
                    return@launch
                }
                controller.requestConnect()
            } finally {
                // An older cancelled start must not clear a newer start request's Job reference.
                if (vpnStartJob === coroutineContext[Job]) vpnStartJob = null
            }
        }
        vpnStartJob = startJob
        startJob.start()
    }

    fun stopVpn() {
        // A pending start may still be waiting for a headless probe to finish closing. Cancel it
        // before dispatching Disconnect so it cannot issue a late ACTION_START after the user
        // has explicitly asked to stop.
        vpnStartJob?.cancel()
        vpnStartJob = null
        controller.requestDisconnect()
    }

    fun reconnectVpn() {
        controller.requestReconnect()
    }

    fun refreshExitIp() {
        controller.refreshExitIp()
    }

    /**
     * Selects a remote proxy profile. If the previous selection was a standalone local engine
     * (`enabled && !useEntryProxy`), switches back to direct proxy mode so the selected server
     * is used cleanly without silently attaching Tor/Psiphon.
     */
    fun selectProfile(profileId: String) {
        viewModelScope.launch {
            val currentChain = uiState.value.chainConfig
            if (currentChain.enabled && !currentChain.useEntryProxy) {
                repository.saveChainConfig(currentChain.withExitProvider(ChainExitType.NONE))
            }
            repository.setActiveProfileId(profileId)
        }
    }

    /**
     * Selects a built-in local engine (Tor, Psiphon, or Cloudflare WARP) as a first-class
     * standalone connection profile without modifying any saved remote profiles or subscriptions.
     */
    fun selectBuiltInProfile(engineType: LocalEngineProfileType) {
        viewModelScope.launch {
            if (engineType == LocalEngineProfileType.WARP && !uiState.value.chainConfig.warpConfig.isRegistered) {
                postMessage(WarpManager.PROVISIONING_BLOCKED_MESSAGE)
                return@launch
            }
            val updatedChain = uiState.value.chainConfig.copy(
                enabled = true,
                useEntryProxy = false,
                primaryExitType = engineType.exitType,
                secondaryExitTypes = emptyList()
            )
            repository.saveChainConfig(updatedChain)
            repository.setActiveProfileId(engineType.reservedProfileId)
            if (engineType == LocalEngineProfileType.PSIPHON && !updatedChain.psiphonConfig.isProvisioned) {
                postMessage("Psiphon selected — needs official Psiphon provisioning in Settings before connecting.")
            }
            if (uiState.value.connectionState == VpnConnectionState.CONNECTED) {
                controller.requestReconnect()
            }
        }
    }

    /**
     * Explicit user opt-in/opt-out for TLS certificate verification on a specific profile.
     * Untrusted imports never enable this silently.
     */
    fun setProfileAllowInsecure(profileId: String, allow: Boolean) {
        viewModelScope.launch {
            val updated = uiState.value.profiles.map { p ->
                if (p.id == profileId) p.copy(allowInsecure = allow) else p
            }
            repository.saveProfiles(updated)
        }
    }

    fun setExitProvider(provider: ChainExitType) {
        viewModelScope.launch {
            val updated = uiState.value.chainConfig.withExitProvider(provider)
            repository.saveChainConfig(updated)
            if (uiState.value.connectionState == VpnConnectionState.CONNECTED) {
                postMessage("Exit provider updated; reconnecting safely…")
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

    fun updatePsiphonProvisioning(
        clientVersion: String,
        channelId: String,
        sponsorId: String,
        serverEntries: String,
        remoteServerListSignaturePublicKey: String = "",
        remoteServerListUrlsText: String = ""
    ) {
        viewModelScope.launch {
            val parsedUrls = remoteServerListUrlsText
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapIndexed { idx, url ->
                    PsiphonTransferUrl(
                        url = url,
                        onlyAfterAttempts = if (idx == 0) 0 else idx,
                        skipVerify = false
                    )
                }
                .toList()

            val updatedPsiphon = uiState.value.chainConfig.psiphonConfig.copy(
                clientVersion = clientVersion.trim(),
                propagationChannelId = channelId.trim(),
                sponsorId = sponsorId.trim(),
                embeddedServerEntries = serverEntries.trim(),
                remoteServerListSignaturePublicKey = remoteServerListSignaturePublicKey.trim(),
                remoteServerListUrls = parsedUrls
            )
            val updated = uiState.value.chainConfig.copy(psiphonConfig = updatedPsiphon)
            repository.saveChainConfig(updated)
            if (updatedPsiphon.isProvisioned) {
                postMessage("Official Psiphon provisioning saved securely.")
            } else {
                postMessage("Psiphon config saved, but required official provisioning fields are still incomplete.")
            }
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
        viewModelScope.launch {
            val (valid, errors) = withContext(Dispatchers.Default) {
                TorBridgeValidator.validateMultiple(rawText)
            }
            if (valid.isEmpty()) {
                postMessage(errors.firstOrNull() ?: "No valid Tor bridge lines found.")
                return@launch
            }
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
            postMessage("Tor bridge updated.")
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
            postMessage("All saved Tor bridges removed.")
        }
    }

    fun importFromRawText(rawText: String) {
        viewModelScope.launch {
            val parsedResult = withContext(Dispatchers.Default) {
                SubscriptionFormatParser.parse(rawText, subscriptionId = null)
            }
            parsedResult.onFailure { err ->
                postMessage(err.message ?: "No valid proxy configuration found.")
                return@launch
            }
            val parsed = parsedResult.getOrThrow().profiles
            val current = uiState.value
            val mutation = withContext(Dispatchers.Default) {
                SubscriptionProfileManager.mergeManualImport(
                    existingProfiles = current.profiles,
                    incoming = parsed,
                    activeProfileId = current.activeProfileId
                )
            }
            repository.saveProfiles(mutation.profiles)
            if (mutation.activeProfileId != current.activeProfileId) {
                repository.setActiveProfileId(mutation.activeProfileId)
            }
            val skipped = parsed.size - mutation.addedCount
            val insecureBlockedCount = parsed.count { it.importedInsecureRequested }
            val securityNote = if (insecureBlockedCount > 0) {
                " ($insecureBlockedCount had insecure TLS flag ignored for safety)"
            } else ""
            postMessage(
                if (skipped > 0) "Imported ${mutation.addedCount} profile(s) ($skipped duplicate(s) skipped)$securityNote."
                else "Imported ${mutation.addedCount} profile(s)$securityNote."
            )
        }
    }

    fun importSubscription(name: String, url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        synchronized(activeImportUrls) {
            if (!activeImportUrls.add(cleanUrl.lowercase())) return
        }
        viewModelScope.launch {
            _isFetchingSub.value = true
            try {
                val tempId = UUID.randomUUID().toString()
                val result = subscriptionFetcher.fetchSubscriptionDetailed(cleanUrl, tempId)
                result.onSuccess { fetched ->
                    val snapshot = uiState.value
                    val mutation = withContext(Dispatchers.Default) {
                        SubscriptionProfileManager.upsertSubscription(
                            existingSubscriptions = snapshot.subscriptions,
                            existingProfiles = snapshot.profiles,
                            activeProfileId = snapshot.activeProfileId,
                            requestedName = name,
                            url = cleanUrl,
                            fetchedProfiles = fetched.profiles,
                            metadata = fetched.metadata
                        )
                    }
                    repository.saveSubscriptionsAndProfiles(
                        subscriptions = mutation.subscriptions,
                        profiles = mutation.profiles,
                        activeProfileId = mutation.activeProfileId
                    )
                    postMessage("Subscription synced (${fetched.format.label}): ${mutation.updatedCount} nodes")
                }.onFailure { err ->
                    postMessage(err.message ?: "Subscription fetch failed")
                }
            } finally {
                synchronized(activeImportUrls) { activeImportUrls.remove(cleanUrl.lowercase()) }
                _isFetchingSub.value = false
            }
        }
    }

    fun refreshSubscription(subscriptionId: String) {
        val sub = uiState.value.subscriptions.firstOrNull { it.id == subscriptionId } ?: return
        if (subscriptionId in _refreshingSubIds.value) return
        viewModelScope.launch {
            _refreshingSubIds.value = _refreshingSubIds.value + subscriptionId
            try {
                val result = subscriptionFetcher.fetchSubscriptionDetailed(sub.url, subscriptionId)
                result.onSuccess { fetched ->
                    val snapshot = uiState.value
                    val mutation = withContext(Dispatchers.Default) {
                        SubscriptionProfileManager.refreshSubscriptionSuccess(
                            existingSubscriptions = snapshot.subscriptions,
                            existingProfiles = snapshot.profiles,
                            activeProfileId = snapshot.activeProfileId,
                            subscriptionId = subscriptionId,
                            fetchedProfiles = fetched.profiles,
                            metadata = fetched.metadata
                        )
                    }
                    repository.saveSubscriptionsAndProfiles(
                        subscriptions = mutation.subscriptions,
                        profiles = mutation.profiles,
                        activeProfileId = mutation.activeProfileId
                    )
                    postMessage("Refreshed '${sub.name}': ${mutation.updatedCount} nodes")
                }.onFailure { err ->
                    val updatedSubs = SubscriptionProfileManager.refreshSubscriptionFailure(
                        existingSubscriptions = uiState.value.subscriptions,
                        subscriptionId = subscriptionId,
                        errorMessage = err.message ?: "Refresh failed"
                    )
                    repository.saveSubscriptions(updatedSubs)
                    postMessage(err.message ?: "Failed to refresh '${sub.name}'")
                }
            } finally {
                _refreshingSubIds.value = _refreshingSubIds.value - subscriptionId
            }
        }
    }

    fun deleteSubscription(subscriptionId: String) {
        val subName = uiState.value.subscriptions.firstOrNull { it.id == subscriptionId }?.name
        viewModelScope.launch {
            val snapshot = uiState.value
            val mutation = withContext(Dispatchers.Default) {
                SubscriptionProfileManager.deleteSubscription(
                    existingSubscriptions = snapshot.subscriptions,
                    existingProfiles = snapshot.profiles,
                    activeProfileId = snapshot.activeProfileId,
                    subscriptionId = subscriptionId
                )
            }
            repository.saveSubscriptionsAndProfiles(
                subscriptions = mutation.subscriptions,
                profiles = mutation.profiles,
                activeProfileId = mutation.activeProfileId
            )
            if (subName != null) {
                postMessage("Removed subscription '$subName'.")
            }
        }
    }

    fun deleteProfile(profileId: String) {
        if (LocalEngineProfileType.isReservedId(profileId)) return
        viewModelScope.launch {
            val snapshot = uiState.value
            val target = snapshot.profiles.firstOrNull { it.id == profileId }
            val remaining = snapshot.profiles.filterNot { it.id == profileId }
            val updatedSubs = if (target?.subscriptionId != null) {
                snapshot.subscriptions.map { sub ->
                    if (sub.id == target.subscriptionId) {
                        sub.copy(profileCount = remaining.count { it.subscriptionId == sub.id })
                    } else sub
                }
            } else {
                snapshot.subscriptions
            }
            val nextActive = if (snapshot.activeProfileId == profileId) null else snapshot.activeProfileId
            repository.saveSubscriptionsAndProfiles(updatedSubs, remaining, nextActive)
        }
    }

    fun cancelLatencyTests() {
        latencyRequestCounter.incrementAndGet()
        latencyTestJob?.cancel()
        latencyTestJob = null
        _isTestingLatency.value = false
        _testingSubscriptionId.value = null
        _latencyProgress.value = null
    }

    fun testSingleLatency(profile: ProxyProfile) {
        runLatencyTests(targets = listOf(profile), scopeSubscriptionId = profile.subscriptionId)
    }

    fun testSubscriptionLatencies(subscriptionId: String?) {
        val targets = uiState.value.profiles.filter { it.subscriptionId == subscriptionId }
        if (targets.isEmpty()) return
        runLatencyTests(targets = targets, scopeSubscriptionId = subscriptionId)
    }

    fun testAllLatencies() {
        val current = uiState.value.profiles
        if (current.isEmpty()) return
        runLatencyTests(targets = current, scopeSubscriptionId = null)
    }

    private fun runLatencyTests(targets: List<ProxyProfile>, scopeSubscriptionId: String?) {
        if (vpnStartJob?.isActive == true || !controller.canStartLatencyTests() ||
            uiState.value.connectionState.isActiveOrStarting ||
            LibboxRuntime.currentMode() != com.visionvpn.app.core.LibboxLeaseMode.IDLE
        ) {
            postMessage("Disconnect the active VPN and wait for Libbox cleanup before running proxy latency tests.")
            return
        }
        val previousJob = latencyTestJob
        val requestId = latencyRequestCounter.incrementAndGet()
        latencyTestJob = viewModelScope.launch {
            // Deterministically cancel and join any active bulk test before starting a new one
            previousJob?.cancel()
            runCatching { previousJob?.join() }
            if (latencyRequestCounter.get() != requestId) return@launch
            if (LibboxRuntime.currentMode() != com.visionvpn.app.core.LibboxLeaseMode.IDLE) {
                postMessage("Proxy tests stopped because Libbox cleanup is incomplete or quarantined. Explicitly Disconnect or restart VisionVPN.")
                return@launch
            }

            _isTestingLatency.value = true
            _testingSubscriptionId.value = scopeSubscriptionId
            _latencyProgress.value = 0 to targets.size
            try {
                val probeTimeoutMs = uiState.value.settings.latencyProbeTimeoutSeconds
                    .coerceIn(1, 30) * 1_000
                val measurements = PingTester.testProfiles(
                    profiles = targets,
                    probe = proxyLatencyProbe,
                    timeoutMs = probeTimeoutMs,
                    maxConcurrency = PingTester.DEFAULT_MAX_CONCURRENCY,
                    onProgress = { completed, total ->
                        if (latencyRequestCounter.get() == requestId) {
                            _latencyProgress.value = completed to total
                        }
                    }
                )
                if (latencyRequestCounter.get() != requestId) return@launch

                val snapshot = uiState.value
                val sortedProfiles = withContext(Dispatchers.Default) {
                    SubscriptionProfileManager.applyLatencyResultsAndSortPerSubscription(
                        profiles = snapshot.profiles,
                        subscriptions = snapshot.subscriptions,
                        measurements = measurements
                    )
                }
                repository.saveProfiles(sortedProfiles)

                val okCount = measurements.count { it.status == com.visionvpn.app.data.model.LatencyStatus.SUCCESS }
                if (LibboxRuntime.currentMode() == com.visionvpn.app.core.LibboxLeaseMode.FAULTED) {
                    postMessage(
                        "Proxy testing stopped early: native Libbox cleanup could not be confirmed. " +
                            "$okCount/${targets.size} profiles reached before quarantine. Explicitly Disconnect or restart VisionVPN."
                    )
                } else {
                    postMessage("Proxy test complete: $okCount/${targets.size} reachable (sorted per subscription)")
                }
            } catch (ce: CancellationException) {
                throw ce
            } finally {
                if (latencyRequestCounter.get() == requestId) {
                    _isTestingLatency.value = false
                    _testingSubscriptionId.value = null
                    _latencyProgress.value = null
                }
            }
        }
    }

    fun updateChainConfig(transform: (ChainConfig) -> ChainConfig) {
        viewModelScope.launch {
            val updated = transform(uiState.value.chainConfig)
            repository.saveChainConfig(updated)
        }
    }

    fun showWarpRequirements() {
        // No undocumented Cloudflare registration endpoint or client-identity spoofing.
        postMessage(WarpManager.PROVISIONING_BLOCKED_MESSAGE)
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
