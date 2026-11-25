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
import com.visionvpn.app.data.model.AppLanguage
import com.visionvpn.app.data.model.AppSettings
import com.visionvpn.app.data.model.ChainConfig
import com.visionvpn.app.data.model.ChainExitType
import com.visionvpn.app.data.model.InstalledAppInfo
import com.visionvpn.app.data.model.ProfileGroup
import com.visionvpn.app.data.model.ProxyProfile
import com.visionvpn.app.data.model.SavedTorBridge
import com.visionvpn.app.data.model.SubscriptionProfileManager
import com.visionvpn.app.data.model.SubscriptionSource
import com.visionvpn.app.data.model.TorBridgeMode
import com.visionvpn.app.data.model.TrafficSample
import com.visionvpn.app.data.model.VisionThemeVariant
import com.visionvpn.app.data.model.VpnConnectionState
import com.visionvpn.app.data.parser.SubscriptionFormatParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    val activeProfileId: String? = null,
    val chainConfig: ChainConfig = ChainConfig(),
    val settings: AppSettings = AppSettings(),
    val connectionState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val statusMessage: String = "",
    val activeExitProvider: ChainExitType = ChainExitType.NONE,
    val activeChainLabels: List<String> = emptyList(),
    val connectedSinceMs: Long? = null,
    val engineStates: Map<String, LocalEngineState> = emptyMap(),
    val ptAvailability: PluggableTransportAvailability = PluggableTransportAvailability(null, null),
    val storageError: String? = null,
    val isTestingLatency: Boolean = false,
    val testingSubscriptionId: String? = null,
    val isFetchingSubscription: Boolean = false,
    val refreshingSubscriptionIds: Set<String> = emptySet(),
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
    private val proxyLatencyProbe = PingTester.createLibboxProbe(
        context = app,
        configBuilder = controller.configBuilder,
        settingsProvider = { uiState.value.settings }
    )

    private val detectedPtAvailability: PluggableTransportAvailability =
        TorBridgeValidator.detectTransportBinaries(File(app.applicationInfo.nativeLibraryDir))

    private val _isTestingLatency = MutableStateFlow(false)
    private val _testingSubscriptionId = MutableStateFlow<String?>(null)
    private val _isFetchingSub = MutableStateFlow(false)
    private val _refreshingSubIds = MutableStateFlow<Set<String>>(emptySet())
    private val _isRegisteringWarp = MutableStateFlow(false)
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private var latencyTestJob: Job? = null
    private val latencyRequestCounter = AtomicLong(0L)
    private val activeImportUrls = mutableSetOf<String>()

    /**
     * Scoped language + theme flow so root [MainActivity] only recomposes on actual
     * language or theme changes rather than on 1-second traffic ticks or status updates.
     */
    val themeLanguageState: StateFlow<Pair<AppLanguage, VisionThemeVariant>> =
        repository.appSettingsFlow
            .map { it.language to it.themeVariant }
            .distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AppLanguage.ENGLISH to VisionThemeVariant.VISION_NAVY
            )

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
            _isFetchingSub,
            _refreshingSubIds
        ) { engines, testing, testingSubId, fetching, refreshingIds ->
            ActivitySlice(engines, testing, testingSubId, fetching, refreshingIds)
        },
        combine(_isRegisteringWarp, _snackbarMessage, repository.storageErrorFlow) { warpBusy, snackbar, storageErr ->
            Triple(warpBusy, snackbar, storageErr)
        }
    ) { persisted, core, activity, (warpBusy, snackbar, storageErr) ->
        val groups = SubscriptionProfileManager.groupProfiles(persisted.profiles, persisted.subs)
        VisionUiState(
            profiles = persisted.profiles,
            subscriptions = persisted.subs,
            profileGroups = groups,
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
            engineStates = activity.engines,
            ptAvailability = detectedPtAvailability,
            storageError = storageErr,
            isTestingLatency = activity.testing,
            testingSubscriptionId = activity.testingSubId,
            isFetchingSubscription = activity.fetching,
            refreshingSubscriptionIds = activity.refreshingIds,
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

    private data class ActivitySlice(
        val engines: Map<String, LocalEngineState>,
        val testing: Boolean,
        val testingSubId: String?,
        val fetching: Boolean,
        val refreshingIds: Set<String>
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

    fun updatePsiphonProvisioning(
        channelId: String,
        sponsorId: String,
        serverEntries: String,
        clientVersion: String = uiState.value.chainConfig.psiphonConfig.clientVersion
    ) {
        viewModelScope.launch {
            val updated = uiState.value.chainConfig.copy(
                psiphonConfig = uiState.value.chainConfig.psiphonConfig.copy(
                    clientVersion = clientVersion.trim(),
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
            val parsedResult = withContext(Dispatchers.Default) {
                SubscriptionFormatParser.parse(rawText, subscriptionId = null)
            }
            parsedResult.onFailure { err ->
                postMessage(
                    err.message ?: if (uiState.value.isPersian) "پروفایل معتبری یافت نشد."
                    else "No valid proxy configuration found."
                )
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
            postMessage(
                if (uiState.value.isPersian) {
                    if (skipped > 0) "${mutation.addedCount} پروفایل اضافه شد ($skipped تکراری نادیده گرفته شد)."
                    else "${mutation.addedCount} پروفایل با موفقیت اضافه شد."
                } else {
                    if (skipped > 0) "Imported ${mutation.addedCount} profile(s) ($skipped duplicate(s) skipped)."
                    else "Imported ${mutation.addedCount} profile(s)."
                }
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
                    postMessage(
                        if (snapshot.isPersian) "اشتراک همگام شد: ${mutation.updatedCount} سرور"
                        else "Subscription synced (${fetched.format.label}): ${mutation.updatedCount} nodes"
                    )
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
                    postMessage(
                        if (snapshot.isPersian) "اشتراک «${sub.name}» به‌روزرسانی شد (${mutation.updatedCount} سرور)"
                        else "Refreshed '${sub.name}': ${mutation.updatedCount} nodes"
                    )
                }.onFailure { err ->
                    // Preserve last-known-good profiles; record error only on this subscription
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
                postMessage(
                    if (snapshot.isPersian) "اشتراک «$subName» حذف شد."
                    else "Removed subscription '$subName'."
                )
            }
        }
    }

    fun deleteProfile(profileId: String) {
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
        latencyTestJob?.cancel()
        val requestId = latencyRequestCounter.incrementAndGet()
        latencyTestJob = viewModelScope.launch {
            _isTestingLatency.value = true
            _testingSubscriptionId.value = scopeSubscriptionId
            try {
                val measurements = PingTester.testProfiles(
                    profiles = targets,
                    probe = proxyLatencyProbe
                )
                // Drop stale results if a newer test or cancellation superseded this request
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
                postMessage(
                    if (snapshot.isPersian) {
                        "تست واقعی پروکسی انجام شد: $okCount از ${targets.size} موفق (مرتب‌سازی درون هر اشتراک)"
                    } else {
                        "Proxy test complete: $okCount/${targets.size} reachable (sorted per subscription)"
                    }
                )
            } catch (ce: CancellationException) {
                throw ce
            } finally {
                if (latencyRequestCounter.get() == requestId) {
                    _isTestingLatency.value = false
                    _testingSubscriptionId.value = null
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
