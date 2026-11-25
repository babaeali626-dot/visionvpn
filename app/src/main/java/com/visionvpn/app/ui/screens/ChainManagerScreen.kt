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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.visionvpn.app.data.model.ChainConfig
import com.visionvpn.app.data.model.ChainExitType
import com.visionvpn.app.data.model.PsiphonChainConfig
import com.visionvpn.app.data.model.TorChainMode
import com.visionvpn.app.ui.components.GlassCard
import com.visionvpn.app.ui.components.GradientPrimaryButton
import com.visionvpn.app.ui.components.ProtocolBadge
import com.visionvpn.app.ui.components.SectionHeader
import com.visionvpn.app.ui.theme.VisionColors
import com.visionvpn.app.ui.viewmodel.VisionUiState

@Composable
fun ChainManagerScreen(
    uiState: VisionUiState,
    onUpdateChain: ((ChainConfig) -> ChainConfig) -> Unit,
    onShowWarpRequirements: () -> Unit
) {
    val isPersian = uiState.isPersian
    val chain = uiState.chainConfig
    val validationError = uiState.chainValidationError()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Master Chaining Toggle + Live Validated Pipeline Preview
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = when {
                validationError != null -> VisionColors.CoralError
                chain.enabled -> VisionColors.NeonPurple
                else -> VisionColors.CardBorder
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isPersian) "زنجیره اتصال (Multi-Hop)" else "Connection Chaining",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = if (isPersian) {
                            "همگام با تنظیمات خروجی VPN • مسیریابی ترتیبی واقعی"
                        } else {
                            "Synced with Settings • True sequential upstream chaining"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Switch(
                    checked = chain.enabled,
                    onCheckedChange = { enabled ->
                        onUpdateChain { current ->
                            val nextPrimary = if (enabled && current.primaryExitType == ChainExitType.NONE) {
                                ChainExitType.CLOUDFLARE_WARP
                            } else current.primaryExitType
                            current.copy(enabled = enabled, primaryExitType = nextPrimary)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = VisionColors.TextPrimary,
                        checkedTrackColor = VisionColors.ElectricBlue
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val hops = uiState.previewChainHops()
                hops.forEachIndexed { idx, label ->
                    if (idx > 0) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            tint = VisionColors.CyanHighlight,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(VisionColors.ElectricBlue.copy(alpha = 0.16f))
                            .border(1.dp, VisionColors.ElectricBlue.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(text = label, style = MaterialTheme.typography.labelSmall, color = VisionColors.TextPrimary)
                    }
                }
            }

            if (validationError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = validationError,
                    style = MaterialTheme.typography.labelSmall,
                    color = VisionColors.CoralError
                )
            }
        }

        // Primary Exit Outbound Selector
        SectionHeader(
            title = if (isPersian) "خروجی اصلی زنجیره" else "Primary Exit Outbound",
            subtitle = if (isPersian) "سرویس مقصد برای detour" else "Select primary exit hop"
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                ChainExitType.CLOUDFLARE_WARP,
                ChainExitType.TOR,
                ChainExitType.PSIPHON,
                ChainExitType.CUSTOM_SERVER
            ).forEach { exitType ->
                val selected = chain.primaryExitType == exitType
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = if (selected && chain.enabled) VisionColors.CyanHighlight else VisionColors.CardBorder,
                    onClick = {
                        onUpdateChain { it.withExitProvider(exitType) }
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isPersian) exitType.labelFa else exitType.labelEn,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = exitSubtitle(exitType, isPersian),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (selected) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = VisionColors.EmeraldConnected
                            )
                        }
                    }
                }
            }
        }

        // Optional Combinable Secondary Hop
        if (chain.enabled && chain.primaryExitType != ChainExitType.NONE) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (isPersian) "افزودن لایه دوم (Multi-Hop)" else "Combine Additional Hop (Optional)",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        ChainExitType.CLOUDFLARE_WARP,
                        ChainExitType.TOR,
                        ChainExitType.PSIPHON,
                        ChainExitType.CUSTOM_SERVER
                    ).filter { it != chain.primaryExitType }.forEach { hop ->
                        val active = chain.secondaryExitTypes.contains(hop)
                        FilterChip(
                            selected = active,
                            onClick = {
                                onUpdateChain { curr ->
                                    val next = if (active) curr.secondaryExitTypes - hop
                                    else curr.secondaryExitTypes + hop
                                    curr.copy(secondaryExitTypes = next)
                                }
                            },
                            label = {
                                Text(if (isPersian) hop.labelFa else hop.labelEn)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VisionColors.NeonPurple.copy(alpha = 0.35f),
                                selectedLabelColor = VisionColors.TextPrimary
                            )
                        )
                    }
                }
            }
        }

        // Detailed Configuration Panel for Selected Hops
        val activeHops = chain.orderedExitHops()

        if (activeHops.contains(ChainExitType.CLOUDFLARE_WARP)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (isPersian) "تنظیمات کلودفلر وارپ (WARP)" else "Cloudflare WARP WireGuard",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                val warp = chain.warpConfig
                Text(
                    text = if (warp.isRegistered) {
                        "Status: Registered • Reserved ${warp.reserved} • ${warp.ipv4Address}"
                    } else {
                        "Status: BLOCKED BY EXTERNAL REQUIREMENT — provider-supplied WireGuard configuration required"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (warp.isRegistered) VisionColors.EmeraldConnected else VisionColors.AmberWarning
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (isPersian) {
                        "ثبت خودکار و مقادیر نمونه غیرفعال است. برای اتصال، پیکربندی کامل و مجاز ارائه‌دهنده لازم است."
                    } else {
                        "Automatic enrollment and sample fallback values are disabled. A complete, authorized provider configuration is required before WARP can be selected."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = VisionColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                GradientPrimaryButton(
                    text = if (isPersian) "نمایش نیازمندی‌های راه‌اندازی" else "WARP setup requirements",
                    onClick = onShowWarpRequirements,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (activeHops.contains(ChainExitType.TOR)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (isPersian) "تنظیمات شبکه تور (Tor)" else "Tor Local SOCKS5 Bridge",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = chain.torConfig.mode == TorChainMode.ENTRY_TO_TOR_EXIT,
                        onClick = {
                            onUpdateChain {
                                it.copy(torConfig = it.torConfig.copy(mode = TorChainMode.ENTRY_TO_TOR_EXIT))
                            }
                        },
                        label = { Text("Entry → Tor Exit") }
                    )
                    FilterChip(
                        selected = chain.torConfig.mode == TorChainMode.TOR_ENTRY_TO_SERVER_EXIT,
                        onClick = {
                            onUpdateChain {
                                it.copy(torConfig = it.torConfig.copy(mode = TorChainMode.TOR_ENTRY_TO_SERVER_EXIT))
                            }
                        },
                        label = { Text("Tor Entry → Server") }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Local SOCKS5: 127.0.0.1:${chain.torConfig.socksPort} • Bridge: ${chain.torConfig.effectiveBridgeMode().labelEn}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VisionColors.CyanHighlight
                )
                val torStatus = uiState.engineStates["tor"]?.message
                if (!torStatus.isNullOrBlank()) {
                    Text(text = torStatus, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (activeHops.contains(ChainExitType.PSIPHON)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (isPersian) "تنظیمات سایفون (Psiphon)" else "Psiphon Egress Region",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PsiphonChainConfig.OFFICIAL_REGIONS.take(12).forEach { region ->
                        FilterChip(
                            selected = chain.psiphonConfig.regionCode.equals(region.code, ignoreCase = true),
                            onClick = {
                                onUpdateChain {
                                    it.copy(psiphonConfig = it.psiphonConfig.copy(regionCode = region.code))
                                }
                            },
                            label = { Text("${region.flag} ${if (isPersian) region.nameFa else region.nameEn}") }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "SOCKS5: 127.0.0.1:${chain.psiphonConfig.localSocksPort}  •  Full searchable list in Settings → Psiphon Settings",
                    style = MaterialTheme.typography.labelSmall,
                    color = VisionColors.CyanHighlight
                )
            }
        }

        if (activeHops.contains(ChainExitType.CUSTOM_SERVER)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (isPersian) "انتخاب سرور خروجی دوم" else "Select Second Profile as Exit Node",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                val candidates = uiState.profiles.filter { it.id != uiState.activeProfile?.id }
                if (candidates.isEmpty()) {
                    Text(
                        text = if (isPersian) {
                            "برای زنجیره سرور دلخواه، حداقل دو پروفایل وارد کنید."
                        } else {
                            "Import at least 2 profiles to select a separate exit server."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = VisionColors.AmberWarning
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        candidates.forEach { candidate ->
                            val isExit = candidate.id == chain.customExitProfileId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isExit) VisionColors.ElectricBlue.copy(alpha = 0.2f)
                                        else VisionColors.CardSurfaceDark
                                    )
                                    .border(
                                        1.dp,
                                        if (isExit) VisionColors.ElectricBlue else VisionColors.CardBorder,
                                        RoundedCornerShape(14.dp)
                                    )
                                    .clickable {
                                        onUpdateChain { it.copy(customExitProfileId = candidate.id) }
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(candidate.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                    Text(candidate.server, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                                ProtocolBadge(candidate.protocol.displayName)
                                if (isExit) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = VisionColors.EmeraldConnected,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
    }
}

private fun exitSubtitle(type: ChainExitType, isPersian: Boolean): String = when (type) {
    ChainExitType.CLOUDFLARE_WARP ->
        if (isPersian) "WireGuard خودکار + تغییر IP خروجی با کلودفلر" else "Built-in WireGuard outbound with auto key registration"
    ChainExitType.TOR ->
        if (isPersian) "تونل داخلی Tor SOCKS5 با پشتیبانی از پل‌ها" else "Local Tor daemon SOCKS5 bridge (Direct or Pluggable Transports)"
    ChainExitType.PSIPHON ->
        if (isPersian) "موتور Psiphon با انتخاب کشور خروجی" else "Local Psiphon tunnel-core with full ISO country selector"
    ChainExitType.CUSTOM_SERVER ->
        if (isPersian) "انتخاب هر سرور دیگر از لیست پروفایل‌ها" else "Chain through any second server from your profile list"
    ChainExitType.NONE -> ""
}
