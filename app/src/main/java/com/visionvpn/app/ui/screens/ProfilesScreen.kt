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
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import com.visionvpn.app.data.model.ProxyProfile
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
    onImportRaw: (String) -> Unit,
    onImportSubscription: (name: String, url: String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onTestSingle: (ProxyProfile) -> Unit,
    onTestAll: () -> Unit
) {
    val isPersian = uiState.isPersian
    val clipboard = LocalClipboardManager.current
    var showSubscriptionDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionHeader(
            title = if (isPersian) "پروفایل‌ها و اشتراک‌ها" else "Profiles & Subscriptions",
            subtitle = if (isPersian) {
                "پشتیبانی از VLESS, VMess, Trojan, HY2, TUIC, WireGuard, SOCKS5"
            } else {
                "VLESS, VMess, Trojan, Hysteria2, TUIC, WireGuard, SOCKS5"
            },
            trailing = {
                IconButton(
                    onClick = onTestAll,
                    enabled = !uiState.isTestingLatency && uiState.profiles.isNotEmpty()
                ) {
                    if (uiState.isTestingLatency) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = VisionColors.CyanHighlight
                        )
                    } else {
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

        if (uiState.profiles.isEmpty()) {
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
                            "از دکمه‌های بالا برای وارد کردن لینک، کلیپ‌بورد یا کد QR استفاده کنید."
                        } else {
                            "Import single URIs from clipboard/QR or sync a Base64 subscription URL."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(uiState.profiles, key = { it.id }) { profile ->
                    val isSelected = profile.id == uiState.activeProfile?.id
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = if (isSelected) VisionColors.ElectricBlue else VisionColors.CardBorder,
                        onClick = { onSelectProfile(profile.id) }
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
                                    text = "${profile.server}:${profile.port}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ProtocolBadge(profile.protocol.displayName)
                                    LatencyPill(profile.latencyMs)
                                }
                            }
                            IconButton(onClick = { onTestSingle(profile) }) {
                                Icon(
                                    imageVector = Icons.Rounded.Speed,
                                    contentDescription = "Ping",
                                    tint = VisionColors.CyanHighlight
                                )
                            }
                            IconButton(onClick = { onDeleteProfile(profile.id) }) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete",
                                    tint = VisionColors.TextSecondary
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
                        label = { Text(if (isPersian) "نام اشتراک" else "Name (optional)") },
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

    if (showQrScanner) {
        QrScannerDialog(
            isPersian = isPersian,
            onDismiss = { showQrScanner = false },
            onQrScanned = { scanned ->
                showQrScanner = false
                if (scanned.startsWith("http://", true) || scanned.startsWith("https://", true)) {
                    onImportSubscription("QR Subscription", scanned)
                } else {
                    onImportRaw(scanned)
                }
            }
        )
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
