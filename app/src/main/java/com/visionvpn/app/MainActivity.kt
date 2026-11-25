package com.visionvpn.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visionvpn.app.ui.navigation.VisionAppScaffold
import com.visionvpn.app.ui.theme.VisionVpnTheme
import com.visionvpn.app.ui.viewmodel.VisionViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: VisionViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.startVpn()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notification permission is optional; VPN still runs */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        setContent {
            // Observe only language & theme at root so 1s traffic/status ticks never recompose the entire tree
            val (language, themeVariant) = viewModel.themeLanguageState.collectAsStateWithLifecycle().value
            VisionVpnTheme(
                language = language,
                themeVariant = themeVariant
            ) {
                VisionAppScaffold(
                    viewModel = viewModel,
                    onRequestVpnConnect = { handleVpnToggle() }
                )
            }
        }
    }

    private fun handleVpnToggle() {
        val currentState = viewModel.uiState.value.connectionState
        if (currentState.isActiveOrStarting) {
            viewModel.stopVpn()
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            viewModel.startVpn()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
