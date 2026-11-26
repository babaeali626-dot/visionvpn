package com.visionvpn.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import com.visionvpn.app.data.model.ChainExitType
import com.visionvpn.app.data.model.LocalEngineProfileType
import com.visionvpn.app.data.model.ProfileGroup
import com.visionvpn.app.data.model.ProxyProfile
import com.visionvpn.app.data.model.SubscriptionSource
import com.visionvpn.app.data.model.TorBridgeMode
import com.visionvpn.app.ui.components.GlassCard
import com.visionvpn.app.ui.components.GradientPrimaryButton
import com.visionvpn.app.ui.components.LatencyPill
import com.visionvpn.app.ui.components.ProtocolBadge
import com.visionvpn.app.ui.components.QrScannerDialog
import com.visionvpn.app.ui.components.SectionHeader
import com.visionvpn.app.ui.theme.VisionColors
import com.visionvpn.app.ui.viewmodel.VisionUiState

@Composable
fun ProfilesScreen(
    uiState: VisionUiState,
    onSelectProfile: (String) -> Unit,
    onSelectBuiltInProfile: (LocalEngineProfileType) -> Unit,
    onImportRaw: (String) -> Unit,
    onImportSubscription: (name: String, url: String) -> Unit,
    onRefreshSubscription: (subscriptionId: String) -> Unit,
    onDeleteSubscription: (subscriptionId: String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onTestSingle: (ProxyProfile) -> Unit,
    onTestSubscription: (subscriptionId: String?) -> Unit,
    onTestAll: () -> Unit,
    onCancelLatencyTests: () -> Unit,
    onSelectExitProvider: (ChainExitType) -> Unit,
    onSelectTorBridgeMode: (TorBridgeMode) -> Unit,
    onAddTorBridges: (String) -> Unit,
    onSetProfileAllowInsecure: (profileId: String, allow: Boolean) -> Unit
) {
    val isPersian = uiState.isPersian
    val clipboard = LocalClipboardManager.current
    var showSubscriptionDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showTorBridgeDialog by remember { mutableStateOf(false) }
    var pendingDeleteSub by remember { mutableStateOf<SubscriptionSource?>(null) }
    var tlsSecurityProfile by remember { mutableStateOf<ProxyProfile?>(null) }
    val collapsedGroups = remember { mutableStateMapOf<String, Boolean>() }

    // Latency-test ownership belongs to VisionViewModel, not this Composable.
    // Navigating to Home/Chain/Settings must not cancel an active batch; only the
    // explicit Cancel action above is allowed to stop it.

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader(
            title = if (isPersian) "پروفایل‌ها و اشتراک‌ها" else "Profiles & Subscriptions",
            subtitle = if (isPersian) {
                "گروه‌بندی مجزا برای هر اشتراک • تست واقعی پروکسی"
            } else {
                "Subscription-grouped • HTTPS round-trip measured through each proxy (not ICMP or live-core delay)"
            },
            trailing = {
                if (uiState.isTestingLatency) {
                    IconButton(onClick = onCancelLatencyTests) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Cancel Latency Test",
                            tint = VisionColors.CoralError
                        )
                    }
                } else {
                    IconButton(
                        onClick = onTestAll,
                        enabled = uiState.profiles.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Speed,
                            contentDescription = "Test All Latency",
                            tint = VisionColors.CyanHighlight
                        )
                    }
                }
            }
        )

        // Import Action Bar (Clipboard, URL Subscription, QR Scanner)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ImportActionTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.ContentPaste,
                label = if (isPersian) "کلیپ‌بورد" else "Clipboard",
                onClick = {
                    val clip = clipboard.getText()?.text.orEmpty()
                    onImportRaw(clip)
                }
            )
            ImportActionTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Link,
                label = if (isPersian) "لینک اشتراک" else "Sub URL",
                onClick = { showSubscriptionDialog = true }
            )
            ImportActionTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.QrCodeScanner,
                label = if (isPersian) "اسکن QR" else "QR Code",
                onClick = { showQrScanner = true }
            )
        }

        if (uiState.isFetchingSubscription) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = VisionColors.ElectricBlue
                    )
                    Text(
                        text = if (isPersian) "در حال دریافت اشتراک…" else "Fetching subscription nodes…",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        uiState.latencyProgress?.let { (done, total) ->
            if (uiState.isTestingLatency && total > 0) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Testing proxies ($done / $total)…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VisionColors.CyanHighlight
                        )
                        TextButton(onClick = onCancelLatencyTests) {
                            Text("Cancel", color = VisionColors.CoralError)
                        }
                    }
                    LinearProgressIndicator(
                        progress = { (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = VisionColors.CyanHighlight,
                        trackColor = VisionColors.CardBorder
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // Built-in Engine Profiles (Tor, Psiphon, Cloudflare WARP) synchronized with ChainConfig
            item(key = "built-in-engines") {
                BuiltInEnginesCard(
                    uiState = uiState,
                    onSelectBuiltInProfile = onSelectBuiltInProfile,
                    onSelectExitProvider = onSelectExitProvider,
                    onSelectTorBridgeMode = onSelectTorBridgeMode,
                    onOpenCustomBridgeDialog = { showTorBridgeDialog = true }
                )
            }

            if (uiState.profiles.isEmpty() && uiState.subscriptions.isEmpty()) {
                item(key = "empty-profiles") {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (isPersian) "هیچ سروری اضافه نشده است" else "No Profiles Imported Yet",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = if (isPersian) {
                                    "از دکمه‌های بالا برای وارد کردن لینک اشتراک، JSON، YAML یا کد QR استفاده کنید."
                                } else {
                                    "Import share links, Sing-box/Xray JSON, Clash YAML, WireGuard .conf, or a subscription URL."
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            } else {
                uiState.profileGroups.forEach { group ->
                    val isCollapsed = collapsedGroups[group.groupKey] == true
                    val sub = group.subscription

                    item(key = "group-header-${group.groupKey}") {
                        SubscriptionGroupHeaderCard(
                            group = group,
                            isPersian = isPersian,
                            isCollapsed = isCollapsed,
                            isRefreshing = sub != null && sub.id in uiState.refreshingSubscriptionIds,
                            isTestingThisGroup = uiState.isTestingLatency &&
                                (uiState.testingSubscriptionId == null || uiState.testingSubscriptionId == sub?.id),
                            onToggleCollapse = {
                                collapsedGroups[group.groupKey] = !isCollapsed
                            },
                            onRefresh = { sub?.let { onRefreshSubscription(it.id) } },
                            onTestGroup = { onTestSubscription(sub?.id) },
                            onRequestDelete = { sub?.let { pendingDeleteSub = it } }
                        )
                    }

                    if (!isCollapsed) {
                        if (group.profiles.isEmpty() && sub != null) {
                            item(key = "group-empty-${group.groupKey}") {
                                GlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = if (isPersian) "سروری در این اشتراک وجود ندارد." else "No nodes in this subscription.",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        } else {
                            items(group.profiles, key = { it.id }) { profile ->
                                ProfileRowCard(
                                    profile = profile,
                                    ownerLabel = sub?.name ?: if (isPersian) "دستی" else "Manual",
                                    isSelected = profile.id == uiState.activeProfile?.id &&
                                        uiState.chainConfig.activeExitProvider == ChainExitType.NONE,
                                    onSelect = {
                                        if (uiState.chainConfig.activeExitProvider != ChainExitType.NONE &&
                                            !uiState.chainConfig.useEntryProxy
                                        ) {
                                            onSelectExitProvider(ChainExitType.NONE)
                                        }
                                        onSelectProfile(profile.id)
                                    },
                                    onTestSingle = { onTestSingle(profile) },
                                    onConfigureTlsSecurity = { tlsSecurityProfile = profile },
                                    onDelete = { onDeleteProfile(profile.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSubscriptionDialog) {
        var subName by remember { mutableStateOf("") }
        var subUrl by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showSubscriptionDialog = false },
            containerColor = VisionColors.CardSurfaceDark,
            title = {
                Text(
                    text = if (isPersian) "افزودن لینک اشتراک" else "Import Subscription URL",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = subName,
                        onValueChange = { subName = it },
                        label = { Text(if (isPersian) "نام اشتراک (اختیاری)" else "Name (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = subUrl,
                        onValueChange = { subUrl = it },
                        label = { Text(if (isPersian) "آدرس https://..." else "Subscription URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                GradientPrimaryButton(
                    text = if (isPersian) "دریافت" else "Import",
                    enabled = subUrl.isNotBlank(),
                    onClick = {
                        onImportSubscription(subName, subUrl)
                        showSubscriptionDialog = false
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showSubscriptionDialog = false }) {
                    Text(
                        text = if (isPersian) "انصراف" else "Cancel",
                        color = VisionColors.TextSecondary
                    )
                }
            }
        )
    }

    pendingDeleteSub?.let { subToDelete ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSub = null },
            containerColor = VisionColors.CardSurfaceDark,
            title = {
                Text(
                    text = if (isPersian) "حذف اشتراک" else "Remove Subscription",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = if (isPersian) {
                        "اشتراک «${subToDelete.name}» و فقط سرورهای متعلق به آن حذف می‌شوند. سایر اشتراک‌ها و پروفایل‌های دستی دست‌نخورده می‌مانند."
                    } else {
                        "Remove '${subToDelete.name}' and its ${subToDelete.profileCount} node(s)? Other subscriptions and manual profiles will not be affected."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSubscription(subToDelete.id)
                        pendingDeleteSub = null
                    }
                ) {
                    Text(
                        text = if (isPersian) "حذف" else "Remove",
                        color = VisionColors.CoralError
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSub = null }) {
                    Text(
                        text = if (isPersian) "انصراف" else "Cancel",
                        color = VisionColors.TextSecondary
                    )
                }
            }
        )
    }

    if (showTorBridgeDialog) {
        var bridgeText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTorBridgeDialog = false },
            containerColor = VisionColors.CardSurfaceDark,
            title = {
                Text(
                    text = if (isPersian) "افزودن پل سفارشی تور" else "Add Custom Tor Bridges",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                OutlinedTextField(
                    value = bridgeText,
                    onValueChange = { bridgeText = it },
                    label = { Text(if (isPersian) "خطوط obfs4 / webtunnel / snowflake" else "obfs4 / webtunnel / snowflake lines") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                GradientPrimaryButton(
                    text = if (isPersian) "ذخیره" else "Save",
                    enabled = bridgeText.isNotBlank(),
                    onClick = {
                        onAddTorBridges(bridgeText)
                        showTorBridgeDialog = false
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showTorBridgeDialog = false }) {
                    Text(
                        text = if (isPersian) "انصراف" else "Cancel",
                        color = VisionColors.TextSecondary
                    )
                }
            }
        )
    }

    tlsSecurityProfile?.let { target ->
        AlertDialog(
            onDismissRequest = { tlsSecurityProfile = null },
            containerColor = VisionColors.CardSurfaceDark,
            title = {
                Text(
                    text = "TLS Verification — ${target.name}",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (target.allowInsecure) {
                            "Certificate validation is currently DISABLED for '${target.name}'. This exposes traffic on this node to active MITM interception."
                        } else {
                            "Imported metadata requested skipping TLS certificate verification for '${target.name}'. VisionVPN blocked this by default to prevent MITM attacks."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = VisionColors.AmberWarning
                    )
                    Text(
                        text = "Only allow insecure TLS if you control this server and understand the risk. Applies strictly to this single profile.",
                        style = MaterialTheme.typography.labelSmall,
                        color = VisionColors.TextSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSetProfileAllowInsecure(target.id, !target.allowInsecure)
                        tlsSecurityProfile = null
                    }
                ) {
                    Text(
                        text = if (target.allowInsecure) "Re-enable TLS Verification" else "Allow Insecure TLS (Risky)",
                        color = if (target.allowInsecure) VisionColors.EmeraldConnected else VisionColors.CoralError
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { tlsSecurityProfile = null }) {
                    Text("Cancel", color = VisionColors.TextSecondary)
                }
            }
        )
    }

    if (showQrScanner) {
        QrScannerDialog(
            isPersian = isPersian,
            onDismiss = { showQrScanner = false },
            onQrScanned = { scanned ->
                showQrScanner = false
                if (scanned.startsWith("http://", true) ||
                    scanned.startsWith("https://", true) ||
                    scanned.startsWith("hiddify://import/http", true)
                ) {
                    onImportSubscription("", scanned)
                } else {
                    onImportRaw(scanned)
                }
            }
        )
    }
}

@Composable
private fun BuiltInEnginesCard(
    uiState: VisionUiState,
    onSelectBuiltInProfile: (LocalEngineProfileType) -> Unit,
    onSelectExitProvider: (ChainExitType) -> Unit,
    onSelectTorBridgeMode: (TorBridgeMode) -> Unit,
    onOpenCustomBridgeDialog: () -> Unit
) {
    val isPersian = false
    val activeExit = uiState.chainConfig.activeExitProvider
    val torConfig = uiState.chainConfig.torConfig
    val psiphonConfig = uiState.chainConfig.psiphonConfig
    val warpConfig = uiState.chainConfig.warpConfig

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Built-in / Local Engines",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = uiState.selectedProfile?.id == LocalEngineProfileType.TOR.reservedProfileId,
                onClick = { onSelectBuiltInProfile(LocalEngineProfileType.TOR) },
                label = { Text("Tor") }
            )
            FilterChip(
                selected = uiState.selectedProfile?.id == LocalEngineProfileType.PSIPHON.reservedProfileId,
                onClick = { onSelectBuiltInProfile(LocalEngineProfileType.PSIPHON) },
                label = { Text("Psiphon") }
            )
            FilterChip(
                selected = uiState.selectedProfile?.id == LocalEngineProfileType.WARP.reservedProfileId,
                onClick = { onSelectBuiltInProfile(LocalEngineProfileType.WARP) },
                label = { Text("WARP") }
            )
        }

        if (activeExit == ChainExitType.TOR) {
            Spacer(modifier = Modifier.height(8.dp))
            val currentMode = torConfig.effectiveBridgeMode()
            Text(
                text = if (isPersian) {
                    "حالت پل تور: ${currentMode.labelFa}"
                } else {
                    "Tor Bridge Mode: ${currentMode.labelEn}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = VisionColors.CyanHighlight
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    TorBridgeMode.DIRECT to "Direct",
                    TorBridgeMode.SNOWFLAKE_TRANSPORT to "Snowflake",
                    TorBridgeMode.OBFS4_TRANSPORT to "obfs4",
                    TorBridgeMode.WEBTUNNEL_TRANSPORT to "WebTunnel"
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = currentMode == mode,
                        onClick = { onSelectTorBridgeMode(mode) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            TextButton(onClick = onOpenCustomBridgeDialog) {
                Text(
                    text = if (isPersian) {
                        "+ افزودن پل سفارشی (${torConfig.savedBridges.size} ذخیره شده)"
                    } else {
                        "+ Add Custom Bridge Lines (${torConfig.savedBridges.size} saved)"
                    },
                    color = VisionColors.CyanHighlight
                )
            }
        } else if (activeExit == ChainExitType.PSIPHON) {
            Spacer(modifier = Modifier.height(6.dp))
            val statusNote = if (psiphonConfig.isProvisioned) {
                if (isPersian) "منطقه: ${psiphonConfig.regionCode} • پیکربندی آماده است"
                else "Region: ${psiphonConfig.regionCode} • Provisioned"
            } else {
                if (isPersian) "نیازمند تکمیل کلیدهای Psiphon در تنظیمات"
                else "Needs official Psiphon provisioning (configure in Settings)"
            }
            Text(text = statusNote, style = MaterialTheme.typography.bodyMedium, color = VisionColors.CyanHighlight)
        } else if (activeExit == ChainExitType.CLOUDFLARE_WARP) {
            Spacer(modifier = Modifier.height(6.dp))
            val warpNote = if (warpConfig.isRegistered) {
                if (isPersian) "کلیدهای WireGuard WARP ثبت شده است"
                else "WireGuard WARP credentials registered (${warpConfig.endpointHost}:${warpConfig.endpointPort})"
            } else {
                if (isPersian) "نیازمند پیکربندی معتبر ارائه‌دهنده"
                else "Requires a complete provider-supplied WireGuard configuration"
            }
            Text(text = warpNote, style = MaterialTheme.typography.bodyMedium, color = VisionColors.CyanHighlight)
        }
    }
}

@Composable
private fun SubscriptionGroupHeaderCard(
    group: ProfileGroup,
    isPersian: Boolean,
    isCollapsed: Boolean,
    isRefreshing: Boolean,
    isTestingThisGroup: Boolean,
    onToggleCollapse: () -> Unit,
    onRefresh: () -> Unit,
    onTestGroup: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val sub = group.subscription
    val title = sub?.name ?: if (isPersian) "پروفایل‌های دستی / محلی" else "Manual / Local Profiles"
    val countLabel = if (isPersian) "${group.profiles.size} سرور" else "${group.profiles.size} node(s)"

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = if (sub?.lastError?.isNotBlank() == true) VisionColors.CoralError else VisionColors.ElectricBlue.copy(alpha = 0.45f),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        onClick = onToggleCollapse
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isCollapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                contentDescription = null,
                tint = VisionColors.CyanHighlight,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(text = countLabel, style = MaterialTheme.typography.labelSmall, color = VisionColors.TextSecondary)
                if (sub != null && sub.lastError.isNotBlank()) {
                    Text(
                        text = sub.lastError,
                        style = MaterialTheme.typography.labelSmall,
                        color = VisionColors.CoralError,
                        maxLines = 2
                    )
                }
            }
            if (group.profiles.isNotEmpty()) {
                IconButton(onClick = onTestGroup, enabled = !isTestingThisGroup) {
                    if (isTestingThisGroup) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = VisionColors.CyanHighlight
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Speed,
                            contentDescription = "Test Subscription Latency",
                            tint = VisionColors.CyanHighlight
                        )
                    }
                }
            }
            if (sub != null) {
                IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = VisionColors.ElectricBlue
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh Subscription",
                            tint = VisionColors.ElectricBlue
                        )
                    }
                }
                IconButton(onClick = onRequestDelete) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Remove Subscription",
                        tint = VisionColors.TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileRowCard(
    profile: ProxyProfile,
    ownerLabel: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onTestSingle: () -> Unit,
    onConfigureTlsSecurity: () -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = if (isSelected) VisionColors.ElectricBlue else VisionColors.CardBorder,
        onClick = onSelect
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = VisionColors.EmeraldConnected,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "${profile.server}:${profile.port} • $ownerLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProtocolBadge(profile.protocol.displayName)
                    LatencyPill(profile.latencyMs, status = profile.effectiveLatencyStatus())
                    if (profile.hasSecurityWarning) {
                        Text(
                            text = if (profile.isUnencryptedTransport) "Unencrypted" else "TLS Warn",
                            style = MaterialTheme.typography.labelSmall,
                            color = VisionColors.AmberWarning
                        )
                    }
                }
            }
            if (profile.importedInsecureRequested || profile.allowInsecure) {
                IconButton(onClick = onConfigureTlsSecurity) {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = "TLS Verification Override",
                        tint = if (profile.allowInsecure) VisionColors.CoralError else VisionColors.AmberWarning
                    )
                }
            }
            IconButton(onClick = onTestSingle) {
                Icon(
                    imageVector = Icons.Rounded.Speed,
                    contentDescription = "Test Proxy Latency",
                    tint = VisionColors.CyanHighlight
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete",
                    tint = VisionColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun ImportActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier,
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = VisionColors.CyanHighlight,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = VisionColors.TextPrimary,
                maxLines = 1
            )
        }
    }
}
