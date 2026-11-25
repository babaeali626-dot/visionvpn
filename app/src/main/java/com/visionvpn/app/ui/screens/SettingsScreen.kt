package com.visionvpn.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.visionvpn.app.chain.TorBridgeValidator
import com.visionvpn.app.data.model.AppLanguage
import com.visionvpn.app.data.model.AppSettings
import com.visionvpn.app.data.model.ChainExitType
import com.visionvpn.app.data.model.DnsStrategy
import com.visionvpn.app.data.model.InstalledAppInfo
import com.visionvpn.app.data.model.Ipv6Policy
import com.visionvpn.app.data.model.PsiphonChainConfig
import com.visionvpn.app.data.model.SavedTorBridge
import com.visionvpn.app.data.model.SplitTunnelMode
import com.visionvpn.app.data.model.TorBridgeMode
import com.visionvpn.app.data.model.VisionThemeVariant
import com.visionvpn.app.ui.components.GlassCard
import com.visionvpn.app.ui.components.GradientPrimaryButton
import com.visionvpn.app.ui.components.SectionHeader
import com.visionvpn.app.ui.theme.VisionColors
import com.visionvpn.app.ui.viewmodel.VisionUiState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: VisionUiState,
    installedApps: List<InstalledAppInfo>,
    onLoadInstalledApps: () -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onSelectExitProvider: (ChainExitType) -> Unit,
    onSetUseEntryProxy: (Boolean) -> Unit,
    onSelectPsiphonRegion: (String) -> Unit,
    onUpdatePsiphonProvisioning: (clientVersion: String, channelId: String, sponsorId: String, serverEntries: String, remotePubKey: String, remoteUrls: String) -> Unit,
    onSetTorBridgesEnabled: (Boolean) -> Unit,
    onSelectTorBridgeMode: (TorBridgeMode) -> Unit,
    onAddTorBridges: (String) -> Unit,
    onEditSavedTorBridge: (id: String, label: String, line: String) -> Unit,
    onToggleSavedTorBridge: (String, Boolean) -> Unit,
    onDeleteSavedTorBridge: (String) -> Unit,
    onClearAllTorBridges: () -> Unit,
    onReconnectVpn: () -> Unit
) {
    val isPersian = uiState.isPersian
    val settings = uiState.settings
    val chain = uiState.chainConfig

    var showAppPicker by remember { mutableStateOf(false) }
    var showPsiphonCountryPicker by remember { mutableStateOf(false) }
    var showPsiphonProvisionDialog by remember { mutableStateOf(false) }
    var showTorBridgeSheet by remember { mutableStateOf(false) }
    var editingBridge by remember { mutableStateOf<SavedTorBridge?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionHeader(
            title = if (isPersian) "تنظیمات" else "Settings",
            subtitle = if (isPersian) "خروجی VPN، سایفون، پل‌های تور، امنیت و مسیریابی" else "VPN exit provider, Psiphon region, Tor bridges, security & routing"
        )

        // Diagnostics & profile-test timeout (independent of native engine startup timeout).
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            var probeTimeoutDraft by remember(settings.latencyProbeTimeoutSeconds) {
                mutableStateOf(settings.latencyProbeTimeoutSeconds.coerceIn(1, 30))
            }
            val probeTimeout = probeTimeoutDraft
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isPersian) "آزمایش و عیب‌یابی" else "Diagnostics & Testing",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (isPersian) "$probeTimeout ثانیه" else "$probeTimeout seconds",
                    style = MaterialTheme.typography.titleSmall,
                    color = VisionColors.CyanHighlight
                )
            }
            Text(
                text = if (isPersian) {
                    "مهلت هر درخواست HTTPS برای آزمون پروفایل؛ این مقدار مستقل از مهلت راه‌اندازی موتور است."
                } else {
                    "Per-request HTTPS timeout for profile tests. This is separate from the native engine startup timeout."
                },
                style = MaterialTheme.typography.bodySmall,
                color = VisionColors.TextSecondary
            )
            Slider(
                value = probeTimeout.toFloat(),
                onValueChange = { value ->
                    probeTimeoutDraft = value.roundToInt().coerceIn(1, 30)
                },
                onValueChangeFinished = {
                    onUpdateSettings { it.copy(latencyProbeTimeoutSeconds = probeTimeoutDraft) }
                },
                valueRange = 1f..30f,
                steps = 28
            )
            Text(
                text = if (isPersian) {
                    "آزمون‌های بومی Libbox در این نسخه برای ایمنی چرخه‌عمر، یکی‌یکی اجرا می‌شوند (هم‌زمانی مؤثر: ۱)."
                } else {
                    "Native Libbox profile probes remain serialized for process-lifecycle safety (effective concurrency: 1)."
                },
                style = MaterialTheme.typography.labelSmall,
                color = VisionColors.TextSecondary
            )
        }

        // A. VPN Routing & Exit Provider (Synced with Chain Manager)
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = VisionColors.ElectricBlue.copy(alpha = 0.65f)
        ) {
            Text(
                text = if (isPersian) "مسیریابی و ارائه‌دهنده خروجی VPN" else "VPN Routing & Exit Provider",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = if (isPersian) {
                    "انتخاب مستقیم خروجی نهایی (همگام با بخش زنجیره اتصال)"
                } else {
                    "Select final VPN exit mode (synced with Chain Manager)"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ChainExitType.NONE,
                    ChainExitType.TOR,
                    ChainExitType.PSIPHON,
                    ChainExitType.CLOUDFLARE_WARP,
                    ChainExitType.CUSTOM_SERVER
                ).forEach { provider ->
                    val selected = chain.activeExitProvider == provider
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) VisionColors.ElectricBlue.copy(alpha = 0.22f)
                                else VisionColors.CardSurfaceDark
                            )
                            .border(
                                1.dp,
                                if (selected) VisionColors.CyanHighlight else VisionColors.CardBorder,
                                RoundedCornerShape(14.dp)
                            )
                            .clickable { onSelectExitProvider(provider) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isPersian) provider.labelFa else provider.labelEn,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
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

            // Explicit Use Entry Proxy toggle for Tor / Psiphon / WARP modes
            if (chain.activeExitProvider == ChainExitType.TOR ||
                chain.activeExitProvider == ChainExitType.PSIPHON ||
                chain.activeExitProvider == ChainExitType.CLOUDFLARE_WARP
            ) {
                Spacer(modifier = Modifier.height(10.dp))
                SettingsSwitchRow(
                    title = if (isPersian) {
                        "استفاده از سرور ورودی (خاموش = حالت مستقل)"
                    } else {
                        "Use Entry Proxy (Off = Standalone Mode)"
                    },
                    checked = chain.useEntryProxy,
                    onCheckedChange = onSetUseEntryProxy
                )
            }
        }

        // B. Psiphon Settings (Country Selector, Provisioning & Status)
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            val selectedRegion = PsiphonChainConfig.findRegionOrNull(chain.psiphonConfig.regionCode)
            val psiphonState = uiState.engineStates["psiphon"]

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Public,
                    contentDescription = null,
                    tint = VisionColors.CyanHighlight
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isPersian) "تنظیمات سایفون (Psiphon)" else "Psiphon Settings",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (selectedRegion != null) {
                            "${selectedRegion.flag} ${if (isPersian) selectedRegion.nameFa else selectedRegion.nameEn} (${selectedRegion.code})"
                        } else {
                            "Invalid Region (${chain.psiphonConfig.regionCode})"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selectedRegion != null) VisionColors.CyanHighlight else VisionColors.CoralError
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (chain.psiphonConfig.isProvisioned) {
                    if (isPersian) "شناسه رسمی Psiphon تنظیم شده است" else "Official Psiphon credentials provisioned"
                } else {
                    if (isPersian) "نیازمند تنظیم PropagationChannelId و SponsorId رسمی" else "Requires official PropagationChannelId & SponsorId to connect"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (chain.psiphonConfig.isProvisioned) VisionColors.EmeraldConnected else VisionColors.AmberWarning
            )

            if (psiphonState != null && psiphonState.message.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = psiphonState.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = VisionColors.TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GradientPrimaryButton(
                    text = if (isPersian) "انتخاب کشور" else "Exit Country",
                    onClick = { showPsiphonCountryPicker = true },
                    modifier = Modifier.weight(1f)
                )
                GradientPrimaryButton(
                    text = if (isPersian) "پیکربندی رسمی" else "Config IDs",
                    onClick = { showPsiphonProvisionDialog = true },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // C. Tor Settings & Bridge Configuration
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            val torCfg = chain.torConfig
            val torState = uiState.engineStates["tor"]
            val effectiveBridge = torCfg.effectiveBridgeMode()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    tint = VisionColors.NeonPurple
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isPersian) "تنظیمات تور و پل‌ها (Tor Bridges)" else "Tor & Bridge Configuration",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (isPersian) effectiveBridge.labelFa else effectiveBridge.labelEn,
                        style = MaterialTheme.typography.bodyMedium,
                        color = VisionColors.CyanHighlight
                    )
                }
                IconButton(onClick = onReconnectVpn) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Reconnect",
                        tint = VisionColors.TextSecondary
                    )
                }
            }

            if (torState != null && (torState.bootstrapProgress > 0 || torState.message.isNotBlank())) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (torState.bootstrapProgress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = VisionColors.EmeraldConnected,
                    trackColor = VisionColors.CardBorder
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = torState.message.ifBlank { "${torState.phaseLabel} (${torState.bootstrapProgress}%)" },
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            GradientPrimaryButton(
                text = if (isPersian) {
                    "مدیریت پل‌های تور (${torCfg.savedBridges.size} ذخیره شده)"
                } else {
                    "Manage Tor Bridges (${torCfg.savedBridges.size} saved)"
                },
                onClick = { showTorBridgeSheet = true },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // D. Privacy & Leak Prevention
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (isPersian) "حریم خصوصی و جلوگیری از نشت" else "Privacy & Leak Protection",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(6.dp))
            SettingsSwitchRow(
                title = "Fail-Closed Mode (No Direct Fallback)",
                checked = settings.failClosedMode,
                onCheckedChange = { v -> onUpdateSettings { it.copy(failClosedMode = v) } }
            )
            Text(
                text = if (settings.failClosedMode) {
                    "Active: On mid-session engine/chain failure, VisionVPN holds a blocking TUN (dropping traffic) until you explicitly tap Disconnect."
                } else {
                    "Warning: Fail-Closed is OFF. If an engine or chain hop fails, the tunnel closes and device traffic returns to your direct physical network."
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (settings.failClosedMode) VisionColors.TextSecondary else VisionColors.AmberWarning
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Android OS Always-on: ${if (uiState.osAlwaysOnEnabled) "Enabled" else "Off"} • OS Lockdown (Block without VPN): ${if (uiState.osLockdownEnabled) "Active" else "Off (enable in Android VPN Settings for boot/crash lockdown)"}",
                style = MaterialTheme.typography.labelSmall,
                color = if (uiState.osLockdownEnabled) VisionColors.EmeraldConnected else VisionColors.TextSecondary
            )
            SettingsSwitchRow(
                title = if (isPersian) "محافظت سخت‌گیرانه نشت DNS" else "Strict DNS Leak Protection",
                checked = settings.strictDnsLeakProtection,
                onCheckedChange = { v -> onUpdateSettings { it.copy(strictDnsLeakProtection = v) } }
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isPersian) "سیاست IPv6" else "IPv6 Protection Policy",
                style = MaterialTheme.typography.labelSmall
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Ipv6Policy.entries.forEach { policy ->
                    FilterChip(
                        selected = settings.ipv6Policy == policy,
                        onClick = { onUpdateSettings { it.copy(ipv6Policy = policy) } },
                        label = { Text(if (isPersian) policy.labelFa else policy.labelEn) }
                    )
                }
            }
        }

        // E. Split Tunneling (Per-App)
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (isPersian) "تفکیک برنامه‌ها (Split Tunneling)" else "Per-App Split Tunneling",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SplitTunnelMode.entries.forEach { mode ->
                    val label = when (mode) {
                        SplitTunnelMode.DISABLED -> if (isPersian) "غیرفعال" else "Off"
                        SplitTunnelMode.BYPASS_SELECTED -> if (isPersian) "عبور مستقیم" else "Bypass"
                        SplitTunnelMode.ONLY_SELECTED -> if (isPersian) "فقط انتخاب‌شده‌ها" else "Only Apps"
                    }
                    FilterChip(
                        selected = settings.splitTunnelMode == mode,
                        onClick = { onUpdateSettings { it.copy(splitTunnelMode = mode) } },
                        label = { Text(label) }
                    )
                }
            }
            if (settings.splitTunnelMode != SplitTunnelMode.DISABLED) {
                Spacer(modifier = Modifier.height(10.dp))
                GradientPrimaryButton(
                    text = if (isPersian) {
                        "انتخاب برنامه‌ها (${settings.splitTunnelPackages.size})"
                    } else {
                        "Choose Apps (${settings.splitTunnelPackages.size} selected)"
                    },
                    onClick = {
                        onLoadInstalledApps()
                        showAppPicker = true
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // F. Routing Rules & DNS
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (isPersian) "قوانین مسیریابی و DNS" else "Routing Rules & DNS",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(6.dp))
            SettingsSwitchRow(
                title = if (isPersian) "عبور مستقیم شبکه محلی (Bypass LAN)" else "Bypass LAN & Private IPs",
                checked = settings.bypassLan,
                onCheckedChange = { v -> onUpdateSettings { it.copy(bypassLan = v) } }
            )
            SettingsSwitchRow(
                title = if (isPersian) "عبور مستقیم سایت‌های ایران (Bypass Iran)" else "Bypass Iran (.ir domains)",
                checked = settings.bypassIran,
                onCheckedChange = { v -> onUpdateSettings { it.copy(bypassIran = v) } }
            )
            SettingsSwitchRow(
                title = if (isPersian) "مسدودسازی تبلیغات" else "Block Ad Trackers",
                checked = settings.blockAds,
                onCheckedChange = { v -> onUpdateSettings { it.copy(blockAds = v) } }
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.remoteDns,
                onValueChange = { v -> onUpdateSettings { it.copy(remoteDns = v) } },
                label = { Text(if (isPersian) "DNS ریموت (DoH / DoT)" else "Remote DNS (DoH / DoT)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.directDns,
                onValueChange = { v -> onUpdateSettings { it.copy(directDns = v) } },
                label = { Text(if (isPersian) "DNS بوت‌استرپ" else "Bootstrap DNS") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingsSwitchRow(
                title = if (isPersian) "فعال‌سازی FakeIP DNS" else "Enable FakeIP DNS",
                checked = settings.enableFakeDns,
                onCheckedChange = { v -> onUpdateSettings { it.copy(enableFakeDns = v) } }
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DnsStrategy.entries.take(3).forEach { strategy ->
                    FilterChip(
                        selected = settings.dnsStrategy == strategy,
                        onClick = { onUpdateSettings { it.copy(dnsStrategy = strategy) } },
                        label = { Text(strategy.label) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
    }

    // Psiphon Official Provisioning Dialog (exposes all required official fields)
    if (showPsiphonProvisionDialog) {
        var clientVersion by remember { mutableStateOf(chain.psiphonConfig.clientVersion) }
        var channelId by remember { mutableStateOf(chain.psiphonConfig.propagationChannelId) }
        var sponsorId by remember { mutableStateOf(chain.psiphonConfig.sponsorId) }
        var serverEntries by remember { mutableStateOf(chain.psiphonConfig.embeddedServerEntries) }
        var remotePubKey by remember { mutableStateOf(chain.psiphonConfig.remoteServerListSignaturePublicKey) }
        var remoteUrls by remember {
            mutableStateOf(chain.psiphonConfig.remoteServerListUrls.joinToString("\n") { it.url })
        }

        AlertDialog(
            onDismissRequest = { showPsiphonProvisionDialog = false },
            containerColor = VisionColors.CardSurfaceDark,
            title = {
                Text(
                    text = "Psiphon Official Provisioning",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Supply official Psiphon Inc. provisioning values: ClientVersion, PropagationChannelId, SponsorId, and either TargetServerEntry or signed HTTPS RemoteServerListURLs.",
                        style = MaterialTheme.typography.labelSmall,
                        color = VisionColors.TextSecondary
                    )
                    OutlinedTextField(
                        value = clientVersion,
                        onValueChange = { clientVersion = it },
                        label = { Text("ClientVersion (required)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = channelId,
                        onValueChange = { channelId = it },
                        label = { Text("PropagationChannelId (required)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sponsorId,
                        onValueChange = { sponsorId = it },
                        label = { Text("SponsorId (required)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = serverEntries,
                        onValueChange = { serverEntries = it },
                        label = { Text("TargetServerEntry (optional if remote list used)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = remotePubKey,
                        onValueChange = { remotePubKey = it },
                        label = { Text("RemoteServerListSignaturePublicKey (Base64)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = remoteUrls,
                        onValueChange = { remoteUrls = it },
                        label = { Text("RemoteServerListURLs (https://, one per line)") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                GradientPrimaryButton(
                    text = "Save",
                    enabled = clientVersion.isNotBlank() && channelId.isNotBlank() && sponsorId.isNotBlank(),
                    onClick = {
                        onUpdatePsiphonProvisioning(
                            clientVersion,
                            channelId,
                            sponsorId,
                            serverEntries,
                            remotePubKey,
                            remoteUrls
                        )
                        showPsiphonProvisionDialog = false
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showPsiphonProvisionDialog = false }) {
                    Text("Cancel", color = VisionColors.TextSecondary)
                }
            }
        )
    }

    // Searchable Psiphon Country Picker Modal
    if (showPsiphonCountryPicker) {
        var countryQuery by remember { mutableStateOf("") }
        val liveList = uiState.engineStates["psiphon"]?.availableRegions.orEmpty()
        val liveAvailable = liveList.toSet()
        val mergedRegions = remember(liveList) {
            PsiphonChainConfig.buildMergedRegionList(liveList)
        }
        val filteredRegions = remember(countryQuery, mergedRegions) {
            mergedRegions.filter { r ->
                countryQuery.isBlank() ||
                    r.nameEn.contains(countryQuery, ignoreCase = true) ||
                    r.nameFa.contains(countryQuery, ignoreCase = true) ||
                    r.code.contains(countryQuery, ignoreCase = true)
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showPsiphonCountryPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VisionColors.CardSurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (isPersian) "انتخاب کشور خروجی سایفون" else "Select Psiphon Exit Region",
                    style = MaterialTheme.typography.titleLarge
                )
                OutlinedTextField(
                    value = countryQuery,
                    onValueChange = { countryQuery = it },
                    label = { Text(if (isPersian) "جستجوی کشور یا کد…" else "Search country or ISO code…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(
                    modifier = Modifier.height(380.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredRegions, key = { it.code }) { region ->
                        val isSelected = chain.psiphonConfig.regionCode.equals(region.code, ignoreCase = true)
                        val isLiveAvailable = liveAvailable.isEmpty() || region.code == "AUTO" || region.code in liveAvailable
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSelected) VisionColors.ElectricBlue.copy(alpha = 0.22f)
                                    else VisionColors.CardSurfaceElevated.copy(alpha = 0.6f)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) VisionColors.CyanHighlight else VisionColors.CardBorder,
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    onSelectPsiphonRegion(region.code)
                                    showPsiphonCountryPicker = false
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = region.flag, style = MaterialTheme.typography.titleLarge)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isPersian) region.nameFa else region.nameEn,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = if (!isLiveAvailable) "${region.code} • Reported unavailable" else region.code,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (!isLiveAvailable) VisionColors.AmberWarning else VisionColors.TextSecondary
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = VisionColors.EmeraldConnected
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    // Complete Tor Bridge Configuration Modal
    if (showTorBridgeSheet) {
        var bridgeDraft by remember { mutableStateOf("") }
        val validationPreview = remember(bridgeDraft) {
            if (bridgeDraft.isBlank()) null else TorBridgeValidator.validateMultiple(bridgeDraft)
        }
        val torCfg = chain.torConfig
        val pt = uiState.ptAvailability

        ModalBottomSheet(
            onDismissRequest = { showTorBridgeSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VisionColors.CardSurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (isPersian) "تنظیمات پل‌های تور (Tor Bridges)" else "Tor Bridge Configuration",
                    style = MaterialTheme.typography.titleLarge
                )

                SettingsSwitchRow(
                    title = if (isPersian) "فعال‌سازی حالت پل (Bridge Mode)" else "Enable Bridge Mode (No Direct Fallback)",
                    checked = torCfg.bridgesEnabled,
                    onCheckedChange = onSetTorBridgesEnabled
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TorBridgeMode.entries.forEach { mode ->
                        val binaryReady = when (mode) {
                            TorBridgeMode.DIRECT, TorBridgeMode.CUSTOM -> true
                            TorBridgeMode.OBFS4_TRANSPORT, TorBridgeMode.WEBTUNNEL_TRANSPORT -> pt.supportsObfs4
                            TorBridgeMode.SNOWFLAKE_TRANSPORT -> pt.supportsSnowflake
                        }
                        FilterChip(
                            selected = torCfg.effectiveBridgeMode() == mode,
                            enabled = binaryReady,
                            onClick = { onSelectTorBridgeMode(mode) },
                            label = {
                                val base = if (isPersian) mode.labelFa else mode.labelEn
                                Text(if (binaryReady) base else "$base (Missing binary)")
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = bridgeDraft,
                    onValueChange = { bridgeDraft = it },
                    label = {
                        Text(
                            if (isPersian) "ورود پل سفارشی (هر خط یک پل obfs4 / snowflake / webtunnel)"
                            else "Paste Custom Bridge(s) — one per line"
                        )
                    },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )

                validationPreview?.let { (valid, errors) ->
                    if (errors.isNotEmpty()) {
                        Text(
                            text = errors.first(),
                            style = MaterialTheme.typography.labelSmall,
                            color = VisionColors.CoralError
                        )
                    } else if (valid.isNotEmpty()) {
                        Text(
                            text = if (isPersian) "${valid.size} پل معتبر شناسایی شد" else "${valid.size} valid bridge line(s) ready",
                            style = MaterialTheme.typography.labelSmall,
                            color = VisionColors.EmeraldConnected
                        )
                    }
                }

                GradientPrimaryButton(
                    text = if (isPersian) "ذخیره پل‌ها" else "Validate & Save Bridges",
                    enabled = bridgeDraft.isNotBlank(),
                    onClick = {
                        onAddTorBridges(bridgeDraft)
                        bridgeDraft = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (torCfg.savedBridges.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isPersian) "پل‌های ذخیره‌شده" else "Saved Bridges",
                            style = MaterialTheme.typography.titleMedium
                        )
                        TextButton(onClick = onClearAllTorBridges) {
                            Text(
                                text = if (isPersian) "حذف همه" else "Clear All",
                                color = VisionColors.CoralError
                            )
                        }
                    }

                    torCfg.savedBridges.forEach { bridge ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(VisionColors.CardSurfaceElevated.copy(alpha = 0.65f))
                                .border(1.dp, VisionColors.CardBorder, RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = bridge.enabled,
                                onCheckedChange = { onToggleSavedTorBridge(bridge.id, it) },
                                colors = CheckboxDefaults.colors(checkedColor = VisionColors.ElectricBlue)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(bridge.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(
                                    text = TorBridgeValidator.redactForLog(bridge.bridgeLine),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                            IconButton(onClick = { editingBridge = bridge }) {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = "Edit Bridge",
                                    tint = VisionColors.CyanHighlight
                                )
                            }
                            IconButton(onClick = { onDeleteSavedTorBridge(bridge.id) }) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete Bridge",
                                    tint = VisionColors.TextSecondary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Edit Existing Tor Bridge Dialog
    editingBridge?.let { target ->
        var editLabel by remember(target.id) { mutableStateOf(target.label) }
        var editLine by remember(target.id) { mutableStateOf(target.bridgeLine) }
        val validation = remember(editLine) { TorBridgeValidator.validateLine(editLine) }

        AlertDialog(
            onDismissRequest = { editingBridge = null },
            containerColor = VisionColors.CardSurfaceDark,
            title = {
                Text(
                    text = if (isPersian) "ویرایش پل تور" else "Edit Tor Bridge",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editLabel,
                        onValueChange = { editLabel = it },
                        label = { Text(if (isPersian) "عنوان پل" else "Bridge Label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editLine,
                        onValueChange = { editLine = it },
                        label = { Text(if (isPersian) "آدرس پل" else "Bridge Line") },
                        minLines = 3,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!validation.isValid) {
                        Text(
                            text = validation.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = VisionColors.CoralError
                        )
                    }
                }
            },
            confirmButton = {
                GradientPrimaryButton(
                    text = if (isPersian) "ذخیره تغییرات" else "Save",
                    enabled = validation.isValid,
                    onClick = {
                        onEditSavedTorBridge(target.id, editLabel, editLine)
                        editingBridge = null
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { editingBridge = null }) {
                    Text(if (isPersian) "انصراف" else "Cancel", color = VisionColors.TextSecondary)
                }
            }
        )
    }

    // Per-App Split Tunneling Picker Modal
    if (showAppPicker) {
        LaunchedEffect(Unit) { onLoadInstalledApps() }
        var searchQuery by remember { mutableStateOf("") }
        var showSystemApps by remember { mutableStateOf(false) }

        val filtered = remember(installedApps, searchQuery, showSystemApps) {
            installedApps.filter { app ->
                (showSystemApps || !app.isSystemApp) &&
                    (searchQuery.isBlank() ||
                        app.appName.contains(searchQuery, ignoreCase = true) ||
                        app.packageName.contains(searchQuery, ignoreCase = true))
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showAppPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VisionColors.CardSurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (isPersian) "انتخاب برنامه‌ها" else "Select Applications",
                    style = MaterialTheme.typography.titleLarge
                )
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(if (isPersian) "جستجوی برنامه…" else "Search apps…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                SettingsSwitchRow(
                    title = if (isPersian) "نمایش برنامه‌های سیستمی" else "Show system apps",
                    checked = showSystemApps,
                    onCheckedChange = { showSystemApps = it }
                )
                LazyColumn(
                    modifier = Modifier.height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filtered, key = { it.packageName }) { appInfo ->
                        val checked = settings.splitTunnelPackages.contains(appInfo.packageName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    onUpdateSettings { curr ->
                                        val next = if (isChecked) {
                                            curr.splitTunnelPackages + appInfo.packageName
                                        } else {
                                            curr.splitTunnelPackages - appInfo.packageName
                                        }
                                        curr.copy(splitTunnelPackages = next)
                                    }
                                },
                                colors = CheckboxDefaults.colors(checkedColor = VisionColors.ElectricBlue)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(appInfo.appName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(appInfo.packageName, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VisionColors.TextPrimary,
                checkedTrackColor = VisionColors.ElectricBlue
            )
        )
    }
}
