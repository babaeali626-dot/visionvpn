package com.visionvpn.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.visionvpn.app.ui.components.VisionBackground
import com.visionvpn.app.ui.screens.ChainManagerScreen
import com.visionvpn.app.ui.screens.HomeScreen
import com.visionvpn.app.ui.screens.ProfilesScreen
import com.visionvpn.app.ui.screens.SettingsScreen
import com.visionvpn.app.ui.theme.VisionColors
import com.visionvpn.app.ui.viewmodel.VisionViewModel

enum class VisionTab(
    val route: String,
    val labelEn: String,
    val labelFa: String,
    val icon: ImageVector
) {
    HOME("home", "Home", "داشبورد", Icons.Rounded.Dashboard),
    PROFILES("profiles", "Profiles", "پروفایل‌ها", Icons.Rounded.Dns),
    CHAIN("chain", "Chain", "زنجیره", Icons.Rounded.Hub),
    SETTINGS("settings", "Settings", "تنظیمات", Icons.Rounded.Tune)
}

@Composable
fun VisionAppScaffold(
    viewModel: VisionViewModel,
    onRequestVpnConnect: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeSnackbar()
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: VisionTab.HOME.route

    VisionBackground(themeVariant = uiState.settings.themeVariant) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                VisionGlassBottomBar(
                    currentRoute = currentRoute,
                    isPersian = uiState.isPersian,
                    onSelectTab = { tab ->
                        navController.navigate(tab.route) {
                            popUpTo(VisionTab.HOME.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = VisionTab.HOME.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                composable(VisionTab.HOME.route) {
                    HomeScreen(
                        uiState = uiState,
                        onToggleConnection = onRequestVpnConnect,
                        onSelectProfile = viewModel::selectProfile,
                        onOpenChainManager = { navController.navigate(VisionTab.CHAIN.route) },
                        onOpenProfiles = { navController.navigate(VisionTab.PROFILES.route) }
                    )
                }
                composable(VisionTab.PROFILES.route) {
                    ProfilesScreen(
                        uiState = uiState,
                        onSelectProfile = viewModel::selectProfile,
                        onImportRaw = viewModel::importFromRawText,
                        onImportSubscription = viewModel::importSubscription,
                        onDeleteProfile = viewModel::deleteProfile,
                        onTestSingle = viewModel::testSingleLatency,
                        onTestAll = viewModel::testAllLatencies
                    )
                }
                composable(VisionTab.CHAIN.route) {
                    ChainManagerScreen(
                        uiState = uiState,
                        onUpdateChain = viewModel::updateChainConfig,
                        onRegisterWarp = viewModel::registerWarpAccount
                    )
                }
                composable(VisionTab.SETTINGS.route) {
                    SettingsScreen(
                        uiState = uiState,
                        installedApps = installedApps,
                        onLoadInstalledApps = viewModel::loadInstalledApps,
                        onUpdateSettings = viewModel::updateSettings,
                        onSelectExitProvider = viewModel::setExitProvider,
                        onSetUseEntryProxy = viewModel::setUseEntryProxy,
                        onSelectPsiphonRegion = viewModel::setPsiphonRegion,
                        onUpdatePsiphonProvisioning = viewModel::updatePsiphonProvisioning,
                        onSetTorBridgesEnabled = viewModel::setTorBridgesEnabled,
                        onSelectTorBridgeMode = viewModel::setTorBridgeMode,
                        onAddTorBridges = viewModel::addTorBridgesFromText,
                        onEditSavedTorBridge = viewModel::editSavedTorBridge,
                        onToggleSavedTorBridge = viewModel::toggleSavedTorBridge,
                        onDeleteSavedTorBridge = viewModel::deleteSavedTorBridge,
                        onClearAllTorBridges = viewModel::clearAllTorBridges,
                        onReconnectVpn = viewModel::reconnectVpn
                    )
                }
            }
        }
    }
}

@Composable
private fun VisionGlassBottomBar(
    currentRoute: String,
    isPersian: Boolean,
    onSelectTab: (VisionTab) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(VisionColors.CardSurfaceDark.copy(alpha = 0.92f))
            .border(1.dp, VisionColors.CardBorder, RoundedCornerShape(22.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            VisionTab.entries.forEach { tab ->
                val selected = currentRoute == tab.route
                val itemShape = RoundedCornerShape(16.dp)
                Column(
                    modifier = Modifier
                        .clip(itemShape)
                        .then(
                            if (selected) Modifier.background(VisionColors.PrimaryAccentGradient)
                            else Modifier
                        )
                        .clickable { onSelectTab(tab) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.labelEn,
                        tint = if (selected) VisionColors.TextPrimary else VisionColors.TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = if (isPersian) tab.labelFa else tab.labelEn,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) VisionColors.TextPrimary else VisionColors.TextSecondary
                    )
                }
            }
        }
    }
}
