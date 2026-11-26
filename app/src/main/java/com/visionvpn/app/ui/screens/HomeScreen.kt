package com.visionvpn.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.visionvpn.app.core.ExitIpStatus
import com.visionvpn.app.data.model.AppSettings
import com.visionvpn.app.data.model.BuiltInProfilesCatalog
import com.visionvpn.app.data.model.ChainExitType
import com.visionvpn.app.data.model.LocalEngineProfileType
import com.visionvpn.app.data.model.ProxyProfile
import com.visionvpn.app.data.model.PsiphonChainConfig
import com.visionvpn.app.data.model.SelectableProfile
import com.visionvpn.app.data.model.VpnConnectionState
import com.visionvpn.app.ui.components.ConnectionOrb
import com.visionvpn.app.ui.components.GlassCard
import com.visionvpn.app.ui.components.LatencyPill
import com.visionvpn.app.ui.components.ProtocolBadge
import com.visionvpn.app.ui.components.SectionHeader
import com.visionvpn.app.ui.components.TrafficGraphCard
import com.visionvpn.app.ui.theme.VisionColors
import com.visionvpn.app.ui.viewmodel.TelemetryUiState
import com.visionvpn.app.ui.viewmodel.VisionUiState
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: VisionUiState,
    telemetryState: TelemetryUiState,
    onToggleConnection: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onSelectBuiltInProfile: (LocalEngineProfileType) -> Unit,
    onRefreshExitIp: () -> Unit,
    onOpenChainManager: () -> Unit,
    onOpenProfiles: () -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onSetBalancerEnabled: (Boolean) -> Unit
) {
    var showProfileSheet by remember { mutableStateOf(false) }
    var showBalancerProfiles by remember { mutableStateOf(false) }

    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(uiState.connectedSinceMs, uiState.connectionState) {
        val since = uiState.connectedSinceMs
        if (since != null && uiState.connectionState == VpnConnectionState.CONNECTED) {
            while (true) {
                elapsedSeconds = ((System.currentTimeMillis() - since) / 1000L).coerceAtLeast(0L)
                delay(1000L)
            }
        } else {
            elapsedSeconds = 0L
        }
    }

    val statusText = when (uiState.connectionState) {
        VpnConnectionState.CONNECTED -> "Protected"
        VpnConnectionState.CONNECTING -> "Connecting…"
        VpnConnectionState.ENGINE_READY -> "Engine Ready…"
        VpnConnectionState.VERIFYING_CONNECTIVITY -> "Verifying…"
        VpnConnectionState.RECONNECTING -> "Reconnecting…"
        VpnConnectionState.DISCONNECTING -> "Disconnecting…"
        VpnConnectionState.ERROR -> "Error"
        VpnConnectionState.DISCONNECTED -> "Disconnected"
    }
    val hintText = when (uiState.connectionState) {
        VpnConnectionState.CONNECTED -> formatDuration(elapsedSeconds)
        VpnConnectionState.CONNECTING -> "Starting Hiddify Core…"
        VpnConnectionState.ENGINE_READY -> "Establishing Tunnel…"
        VpnConnectionState.VERIFYING_CONNECTIVITY -> "Checking Internet…"
        VpnConnectionState.RECONNECTING -> "Reconnecting…"
        else -> "Tap to Connect"
    }

    val exitProvider = uiState.activeExitProvider
    val exitLabel = exitProvider.labelEn
    val balancerAvailable = uiState.activeProfile != null &&
        uiState.chainConfig.activeExitProvider == ChainExitType.NONE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        uiState.storageError?.let { err ->
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                borderColor = VisionColors.CoralError
            ) {
                Text(text = err, style = MaterialTheme.typography.bodyMedium, color = VisionColors.CoralError)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "VisionVPN", style = MaterialTheme.typography.headlineLarge)
                Text(
                    text = "Final Exit: $exitLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VisionColors.CyanHighlight
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (uiState.connectionState == VpnConnectionState.CONNECTED) {
                            VisionColors.EmeraldConnected.copy(alpha = 0.2f)
                        } else {
                            VisionColors.CardSurfaceElevated
                        }
                    )
                    .border(
                        1.dp,
                        if (uiState.connectionState == VpnConnectionState.CONNECTED) {
                            VisionColors.EmeraldConnected
                        } else VisionColors.CardBorder,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (uiState.connectionState == VpnConnectionState.CONNECTED) {
                        VisionColors.EmeraldConnected
                    } else VisionColors.TextSecondary
                )
            }
        }

        ConnectionOrb(
            state = uiState.connectionState,
            statusLabel = statusText,
            actionHint = hintText,
            onClick = onToggleConnection
        )

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Balancer", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (uiState.settings.balancerEnabled) {
                            "${uiState.settings.balancerStrategy.label} • ${uiState.settings.balancerProfileIds.size} selected • ${uiState.settings.balancerIntervalSeconds}s checks"
                        } else {
                            "Choose trusted profiles and a Hiddify Core distribution strategy."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = VisionColors.TextSecondary
                    )
                }
                Switch(
                    checked = uiState.settings.balancerEnabled,
                    enabled = uiState.settings.balancerEnabled ||
                        (balancerAvailable && uiState.profiles.size >= 2 &&
                            uiState.settings.balancerProfileIds.count { id -> uiState.profiles.any { it.id == id } } >= 2),
                    onCheckedChange = onSetBalancerEnabled
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = { showBalancerProfiles = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Choose Balancer Profiles (${uiState.settings.balancerProfileIds.size})")
            }
            if (!balancerAvailable && !uiState.settings.balancerEnabled) {
                Text(
                    "Select an imported profile and turn off legacy Tor/Psiphon/WARP exit layers to use Balancer in this build.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VisionColors.AmberWarning
                )
            } else if (uiState.settings.balancerEnabled && uiState.connectionState == VpnConnectionState.CONNECTED) {
                Text(
                    "${uiState.settings.balancerStrategy.label} balancer is active for new connections.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VisionColors.EmeraldConnected
                )
            }
        }

        if (uiState.statusMessage.isNotBlank()) {
            Text(
                text = uiState.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = if (uiState.connectionState == VpnConnectionState.ERROR) {
                    VisionColors.CoralError
                } else VisionColors.CyanHighlight
            )
        }

        // Public Exit IP & Country Card (routed exclusively through the active VPN tunnel)
        ExitIpStatusCard(
            connectionState = uiState.connectionState,
            exitIpInfo = uiState.exitIpInfo,
            onRefresh = onRefreshExitIp
        )

        val torState = uiState.engineStates["tor"]
        if (exitProvider == ChainExitType.TOR && torState != null && torState.bootstrapProgress in 1..99) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = torState.message.ifBlank { "${torState.phaseLabel} (${torState.bootstrapProgress}%)" },
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { torState.bootstrapProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = VisionColors.EmeraldConnected,
                    trackColor = VisionColors.CardBorder
                )
            }
        }

        if (exitProvider == ChainExitType.PSIPHON) {
            val req = PsiphonChainConfig.findRegionOrNull(uiState.chainConfig.psiphonConfig.regionCode)
            val psiState = uiState.engineStates["psiphon"]
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                val reqDisplay = if (req != null) "${req.flag} ${req.nameEn}" else "Invalid (${uiState.chainConfig.psiphonConfig.regionCode})"
                val obsDisplay = psiState?.observedRegion?.let { " • Observed: $it" }.orEmpty()
                val provNote = if (!uiState.chainConfig.psiphonConfig.isProvisioned) {
                    " • Needs official Psiphon provisioning"
                } else ""
                Text(
                    text = "Psiphon: $reqDisplay • ${psiState?.phaseLabel ?: "Idle"}$obsDisplay$provNote",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (uiState.chainConfig.psiphonConfig.isProvisioned) VisionColors.CyanHighlight else VisionColors.AmberWarning
                )
            }
        }

        // Unified Connection Profile Selector Card (Built-in Local Engines, Subscriptions, Manual)
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = { showProfileSheet = true }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(VisionColors.PrimaryAccentGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (uiState.selectedProfile is SelectableProfile.BuiltInEngine) {
                            Icons.Rounded.Shield
                        } else {
                            Icons.Rounded.Public
                        },
                        contentDescription = null,
                        tint = VisionColors.TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (uiState.settings.balancerEnabled && uiState.connectionState == VpnConnectionState.CONNECTED) {
                        Text(
                            text = "Connection Profile • Balancer",
                            style = MaterialTheme.typography.labelSmall,
                            color = VisionColors.TextSecondary
                        )
                        Text(
                            text = "${uiState.settings.balancerStrategy.label}",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProtocolBadge("AUTO")
                            Text(
                                text = "${uiState.settings.balancerProfileIds.size} selected • ${uiState.settings.balancerIntervalSeconds}s checks",
                                style = MaterialTheme.typography.labelSmall,
                                color = VisionColors.CyanHighlight
                            )
                        }
                    } else when (val selected = uiState.selectedProfile) {
                        is SelectableProfile.BuiltInEngine -> {
                            Text(
                                text = "Connection Profile • Built-in Engine",
                                style = MaterialTheme.typography.labelSmall,
                                color = VisionColors.TextSecondary
                            )
                            Text(
                                text = selected.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ProtocolBadge("LOCAL ENGINE")
                                Text(
                                    text = selected.statusNote,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected.isReady) VisionColors.EmeraldConnected else VisionColors.AmberWarning,
                                    maxLines = 1
                                )
                            }
                        }
                        is SelectableProfile.RemoteProxy -> {
                            Text(
                                text = "Connection Profile • ${selected.ownerGroupName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = VisionColors.TextSecondary,
                                maxLines = 1
                            )
                            Text(
                                text = selected.profile.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ProtocolBadge(selected.profile.protocol.displayName)
                                LatencyPill(
                                    selected.profile.latencyMs,
                                    status = selected.profile.effectiveLatencyStatus()
                                )
                                if (selected.profile.hasSecurityWarning) {
                                    Text(
                                        text = if (selected.profile.isUnencryptedTransport) "Unencrypted" else "TLS Warning",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VisionColors.AmberWarning
                                    )
                                }
                            }
                        }
                        null -> {
                            Text(
                                text = "Connection Profile",
                                style = MaterialTheme.typography.labelSmall,
                                color = VisionColors.TextSecondary
                            )
                            Text(
                                text = "No profile selected (Tap to choose)",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1
                            )
                        }
                    }
                }
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = VisionColors.TextSecondary
                )
            }
        }

        val validationError = uiState.chainValidationError()
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = when {
                validationError != null -> VisionColors.CoralError
                uiState.chainConfig.enabled -> VisionColors.NeonPurple.copy(alpha = 0.7f)
                else -> VisionColors.CardBorder
            },
            onClick = onOpenChainManager
        ) {
            SectionHeader(
                title = if (uiState.connectionState == VpnConnectionState.CONNECTED) {
                    "Verified Active Routing Order"
                } else {
                    "Configured Routing Order"
                },
                subtitle = validationError ?: if (uiState.chainConfig.enabled) {
                    "Final exit: $exitLabel (Tap to customize chain)"
                } else {
                    "Direct connection profile (Tap to add chain hops)"
                },
                trailing = {
                    Icon(
                        imageVector = Icons.Rounded.Hub,
                        contentDescription = null,
                        tint = if (uiState.chainConfig.enabled) VisionColors.CyanHighlight else VisionColors.TextSecondary
                    )
                }
            )
            Spacer(modifier = Modifier.height(10.dp))
            val hops = uiState.previewChainHops()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChainNodeChip(label = "Device", accentColor = VisionColors.TextSecondary)
                hops.forEachIndexed { idx, hopLabel ->
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        tint = VisionColors.CyanHighlight,
                        modifier = Modifier.size(16.dp)
                    )
                    val color = when {
                        idx == 0 -> VisionColors.ElectricBlue
                        idx == hops.lastIndex -> VisionColors.EmeraldConnected
                        else -> VisionColors.NeonPurple
                    }
                    ChainNodeChip(label = hopLabel, accentColor = color)
                }
            }
        }

        TrafficGraphCard(
            history = telemetryState.trafficHistory,
            latest = telemetryState.latestTraffic,
            isPersian = false
        )

        Spacer(modifier = Modifier.height(12.dp))
    }

    if (showProfileSheet) {
        var searchQuery by remember { mutableStateOf("") }
        val collapsedGroups = remember { mutableStateMapOf<String, Boolean>() }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val q = searchQuery.trim()

        ModalBottomSheet(
            onDismissRequest = { showProfileSheet = false },
            sheetState = sheetState,
            containerColor = VisionColors.CardSurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Select Connection Profile", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "Manage",
                        style = MaterialTheme.typography.labelLarge,
                        color = VisionColors.CyanHighlight,
                        modifier = Modifier.clickable {
                            showProfileSheet = false
                            onOpenProfiles()
                        }
                    )
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = VisionColors.TextSecondary
                        )
                    },
                    label = { Text("Search profiles, subscriptions, or engines…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(400.dp)
                ) {
                    // Group 1: Built-in / Local Engines (Tor, Psiphon, Cloudflare WARP)
                    val matchingBuiltIns = uiState.builtInProfiles.filter { item ->
                        q.isEmpty() ||
                            item.displayName.contains(q, ignoreCase = true) ||
                            item.subtitle.contains(q, ignoreCase = true) ||
                            item.statusNote.contains(q, ignoreCase = true)
                    }
                    if (matchingBuiltIns.isNotEmpty()) {
                        val builtInCollapsed = collapsedGroups[BuiltInProfilesCatalog.BUILT_IN_GROUP_KEY] == true && q.isEmpty()
                        item(key = "sheet-header-${BuiltInProfilesCatalog.BUILT_IN_GROUP_KEY}") {
                            SelectorGroupHeader(
                                title = "Built-in / Local Engines",
                                count = matchingBuiltIns.size,
                                collapsed = builtInCollapsed,
                                onToggle = {
                                    collapsedGroups[BuiltInProfilesCatalog.BUILT_IN_GROUP_KEY] = !builtInCollapsed
                                }
                            )
                        }
                        if (!builtInCollapsed) {
                            items(matchingBuiltIns, key = { "sheet-builtin-${it.id}" }) { engine ->
                                val selected = uiState.selectedProfile?.id == engine.id
                                BuiltInSelectorRow(
                                    engine = engine,
                                    selected = selected,
                                    onClick = {
                                        onSelectBuiltInProfile(engine.engineType)
                                        showProfileSheet = false
                                    }
                                )
                            }
                        }
                    }

                    // Group 2+: Subscription groups and Manual / Local group (shared model with ProfilesScreen)
                    uiState.profileGroups.forEach { group ->
                        val filteredProfiles = if (q.isEmpty()) {
                            group.profiles
                        } else {
                            val subMatches = group.subscription?.name?.contains(q, ignoreCase = true) == true
                            if (subMatches) group.profiles
                            else group.profiles.filter { p ->
                                p.name.contains(q, ignoreCase = true) ||
                                    p.server.contains(q, ignoreCase = true) ||
                                    p.protocol.displayName.contains(q, ignoreCase = true)
                            }
                        }
                        if (filteredProfiles.isNotEmpty()) {
                            val groupCollapsed = collapsedGroups[group.groupKey] == true && q.isEmpty()
                            item(key = "sheet-header-${group.groupKey}") {
                                SelectorGroupHeader(
                                    title = group.subscription?.name ?: "Manual / Local",
                                    count = filteredProfiles.size,
                                    collapsed = groupCollapsed,
                                    onToggle = {
                                        collapsedGroups[group.groupKey] = !groupCollapsed
                                    }
                                )
                            }
                            if (!groupCollapsed) {
                                items(filteredProfiles, key = { "sheet-remote-${it.id}" }) { profile ->
                                    QuickServerRow(
                                        profile = profile,
                                        selected = uiState.selectedProfile?.id == profile.id,
                                        onClick = {
                                            onSelectProfile(profile.id)
                                            showProfileSheet = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
    if (showBalancerProfiles) {
        BalancerProfilesDialog(
            uiState = uiState,
            onDismiss = { showBalancerProfiles = false },
            onUpdateSettings = onUpdateSettings
        )
    }
}


@Composable
private fun BalancerProfilesDialog(
    uiState: VisionUiState,
    onDismiss: () -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit
) {
    // Keep edits local while the user checks several profiles. Persist once on Done;
    // saving each checkbox could trigger repeated VPN restarts while Balancer is active.
    var selectedIds by remember(uiState.settings.balancerProfileIds) {
        mutableStateOf(uiState.settings.balancerProfileIds.toSet())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Balancer Profiles") },
        text = {
            Column(modifier = Modifier.height(420.dp)) {
                Text(
                    "Only checked profiles can be included in the automatic Balancer group. Select at least two profiles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VisionColors.TextSecondary
                )
                LazyColumn {
                    items(uiState.profiles, key = { it.id }) { profile ->
                        val checked = profile.id in selectedIds
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { selected ->
                                    selectedIds = selectedIds.toMutableSet().apply {
                                        if (selected) add(profile.id) else remove(profile.id)
                                    }
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(profile.name, style = MaterialTheme.typography.bodyMedium)
                                Text("${profile.server}:${profile.port}", style = MaterialTheme.typography.labelSmall, color = VisionColors.TextSecondary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedIds.size >= 2,
                onClick = {
                    onUpdateSettings { current ->
                        current.copy(balancerProfileIds = selectedIds.intersect(uiState.profiles.map { it.id }.toSet()))
                    }
                    onDismiss()
                }
            ) { Text("Done (${selectedIds.size})") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = {
                    onUpdateSettings { it.copy(balancerEnabled = false) }
                    onDismiss()
                }) { Text("Turn Off") }
            }
        }
    )
}

@Composable
private fun ExitIpStatusCard(
    connectionState: VpnConnectionState,
    exitIpInfo: com.visionvpn.app.core.ExitIpInfo,
    onRefresh: () -> Unit
) {
    val isConnected = connectionState == VpnConnectionState.CONNECTED
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = when (exitIpInfo.status) {
            ExitIpStatus.VERIFIED -> VisionColors.EmeraldConnected.copy(alpha = 0.55f)
            ExitIpStatus.STALE -> VisionColors.AmberWarning.copy(alpha = 0.55f)
            else -> VisionColors.CardBorder
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Public Exit (Tunnel-Verified)",
                    style = MaterialTheme.typography.labelSmall,
                    color = VisionColors.TextSecondary
                )
                when {
                    !isConnected || exitIpInfo.status == ExitIpStatus.DISCONNECTED -> {
                        Text(
                            text = "IP: —  •  Country: Disconnected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VisionColors.TextSecondary
                        )
                    }
                    exitIpInfo.status == ExitIpStatus.LOADING -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = VisionColors.CyanHighlight
                            )
                            Text(
                                text = "Verifying tunnel exit IP & country…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = VisionColors.CyanHighlight
                            )
                        }
                    }
                    exitIpInfo.status == ExitIpStatus.VERIFIED -> {
                        Text(
                            text = "IP: ${exitIpInfo.ip ?: "—"}",
                            style = MaterialTheme.typography.titleMedium,
                            color = VisionColors.TextPrimary
                        )
                        Text(
                            text = "Country: ${exitIpInfo.countryFlag} ${exitIpInfo.formattedCountry}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VisionColors.EmeraldConnected
                        )
                    }
                    exitIpInfo.status == ExitIpStatus.STALE -> {
                        Text(
                            text = "IP: ${exitIpInfo.ip ?: "—"} (Stale — reconnecting)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VisionColors.AmberWarning
                        )
                        Text(
                            text = "Country: ${exitIpInfo.countryFlag} ${exitIpInfo.formattedCountry}",
                            style = MaterialTheme.typography.labelSmall,
                            color = VisionColors.AmberWarning
                        )
                    }
                    else -> {
                        Text(
                            text = "IP: Unavailable  •  Country: Unknown",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VisionColors.AmberWarning
                        )
                    }
                }
            }
            if (isConnected) {
                IconButton(
                    onClick = onRefresh,
                    enabled = exitIpInfo.status != ExitIpStatus.LOADING
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh Exit IP",
                        tint = VisionColors.CyanHighlight
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectorGroupHeader(
    title: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(VisionColors.CardSurfaceElevated.copy(alpha = 0.65f))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
            contentDescription = null,
            tint = VisionColors.CyanHighlight,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = VisionColors.CyanHighlight,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = VisionColors.TextSecondary
        )
    }
}

@Composable
private fun BuiltInSelectorRow(
    engine: SelectableProfile.BuiltInEngine,
    selected: Boolean,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = if (selected) VisionColors.ElectricBlue else VisionColors.CardBorder,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = engine.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(text = engine.subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(
                    text = engine.statusNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (engine.isReady) VisionColors.EmeraldConnected else VisionColors.AmberWarning,
                    maxLines = 1
                )
            }
            ProtocolBadge("LOCAL")
            if (selected) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = VisionColors.EmeraldConnected,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun formatDuration(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

@Composable
private fun ChainNodeChip(
    label: String,
    accentColor: androidx.compose.ui.graphics.Color
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(accentColor.copy(alpha = 0.16f))
            .border(1.dp, accentColor.copy(alpha = 0.55f), shape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = VisionColors.TextPrimary,
            maxLines = 1
        )
    }
}

@Composable
private fun QuickServerRow(
    profile: ProxyProfile,
    selected: Boolean,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = if (selected) VisionColors.ElectricBlue else VisionColors.CardBorder,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    text = "${profile.server}:${profile.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            }
            ProtocolBadge(profile.protocol.displayName)
            Spacer(modifier = Modifier.width(8.dp))
            LatencyPill(profile.latencyMs, status = profile.effectiveLatencyStatus())
            if (selected) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = VisionColors.EmeraldConnected,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
