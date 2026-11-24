package com.visionvpn.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.visionvpn.app.data.model.ChainExitType
import com.visionvpn.app.data.model.ProxyProfile
import com.visionvpn.app.data.model.PsiphonChainConfig
import com.visionvpn.app.data.model.VpnConnectionState
import com.visionvpn.app.ui.components.ConnectionOrb
import com.visionvpn.app.ui.components.GlassCard
import com.visionvpn.app.ui.components.LatencyPill
import com.visionvpn.app.ui.components.ProtocolBadge
import com.visionvpn.app.ui.components.SectionHeader
import com.visionvpn.app.ui.components.TrafficGraphCard
import com.visionvpn.app.ui.theme.VisionColors
import com.visionvpn.app.ui.viewmodel.VisionUiState
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: VisionUiState,
    onToggleConnection: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onOpenChainManager: () -> Unit,
    onOpenProfiles: () -> Unit
) {
    val isPersian = uiState.isPersian
    var showServerSheet by remember { mutableStateOf(false) }

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
        VpnConnectionState.CONNECTED -> if (isPersian) "متصل و ایمن" else "Protected"
        VpnConnectionState.CONNECTING -> if (isPersian) "در حال اتصال…" else "Connecting…"
        VpnConnectionState.DISCONNECTING -> if (isPersian) "در حال قطع…" else "Disconnecting…"
        VpnConnectionState.ERROR -> if (isPersian) "خطای اتصال" else "Error"
        VpnConnectionState.DISCONNECTED -> if (isPersian) "قطع شده" else "Disconnected"
    }
    val hintText = when (uiState.connectionState) {
        VpnConnectionState.CONNECTED -> formatDuration(elapsedSeconds)
        VpnConnectionState.CONNECTING -> if (isPersian) "برقراری زنجیره…" else "Starting Sing-box…"
        else -> if (isPersian) "برای اتصال لمس کنید" else "Tap to Connect"
    }

    val exitProvider = uiState.activeExitProvider
    val exitLabel = if (isPersian) exitProvider.labelFa else exitProvider.labelEn

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Storage decryption warning if Keystore error occurred
        uiState.storageError?.let { err ->
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                borderColor = VisionColors.CoralError
            ) {
                Text(text = err, style = MaterialTheme.typography.bodyMedium, color = VisionColors.CoralError)
            }
        }

        // Header with Active Exit Provider pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isPersian) "ویژن وی‌پی‌ان" else "VisionVPN",
                    style = MaterialTheme.typography.headlineLarge
                )
                Text(
                    text = if (isPersian) "خروجی فعال: $exitLabel" else "Exit: $exitLabel",
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

        if (uiState.statusMessage.isNotBlank()) {
            Text(
                text = uiState.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = if (uiState.connectionState == VpnConnectionState.ERROR) {
                    VisionColors.CoralError
                } else VisionColors.CyanHighlight
            )
        }

        // Live Tor Bootstrap Card
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

        // Psiphon Status Card (distinguishing requested vs observed region)
        if (exitProvider == ChainExitType.PSIPHON) {
            val req = PsiphonChainConfig.findRegionOrNull(uiState.chainConfig.psiphonConfig.regionCode)
            val psiState = uiState.engineStates["psiphon"]
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                val reqDisplay = if (req != null) {
                    "${req.flag} ${if (isPersian) req.nameFa else req.nameEn}"
                } else {
                    "Invalid (${uiState.chainConfig.psiphonConfig.regionCode})"
                }
                val obsDisplay = psiState?.observedRegion?.let { " • Observed: $it" }.orEmpty()
                Text(
                    text = "Psiphon: $reqDisplay • ${psiState?.phaseLabel ?: "Idle"}$obsDisplay",
                    style = MaterialTheme.typography.bodyLarge,
                    color = VisionColors.CyanHighlight
                )
            }
        }

        // Quick Server Selector Card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (uiState.profiles.isEmpty()) onOpenProfiles() else showServerSheet = true
            }
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
                        imageVector = Icons.Rounded.Public,
                        contentDescription = null,
                        tint = VisionColors.TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isPersian) "سرور ورودی فعال" else "Entry Server",
                        style = MaterialTheme.typography.labelSmall
                    )
                    val standalone = exitProvider != ChainExitType.NONE && !uiState.chainConfig.useEntryProxy
                    Text(
                        text = if (standalone) {
                            if (isPersian) "حالت مستقل (${exitProvider.labelFa})" else "Standalone (${exitProvider.labelEn})"
                        } else {
                            uiState.activeProfile?.name
                                ?: if (isPersian) "سروری انتخاب نشده (برای افزودن لمس کنید)" else "No server selected (Tap to select)"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                    if (!standalone) {
                        uiState.activeProfile?.let { active ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ProtocolBadge(active.protocol.displayName)
                                LatencyPill(active.latencyMs)
                            }
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

        // Active Routing Chain Visualization
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
                    if (isPersian) "مسیر فعال تأییدشده" else "Verified Active Routing Chain"
                } else {
                    if (isPersian) "مسیر زنجیره اتصال (Entry → Exit)" else "Configured Routing Chain"
                },
                subtitle = validationError ?: if (uiState.chainConfig.enabled) {
                    if (isPersian) "زنجیره فعال است" else "Exit provider routing active"
                } else {
                    if (isPersian) "اتصال مستقیم از سرور ورودی" else "Direct entry proxy (Tap to customize)"
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
                ChainNodeChip(
                    label = if (isPersian) "دستگاه" else "Device",
                    accentColor = VisionColors.TextSecondary
                )
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
            history = uiState.trafficHistory,
            latest = uiState.latestTraffic,
            isPersian = isPersian
        )

        Spacer(modifier = Modifier.height(12.dp))
    }

    if (showServerSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showServerSheet = false },
            sheetState = sheetState,
            containerColor = VisionColors.CardSurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(
                    text = if (isPersian) "انتخاب سریع سرور" else "Select Entry Server",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.height(340.dp)
                ) {
                    items(uiState.profiles, key = { it.id }) { profile ->
                        QuickServerRow(
                            profile = profile,
                            selected = profile.id == uiState.activeProfile?.id,
                            onClick = {
                                onSelectProfile(profile.id)
                                showServerSheet = false
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
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
            LatencyPill(profile.latencyMs)
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
