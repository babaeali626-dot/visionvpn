package com.visionvpn.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import com.visionvpn.app.MainActivity
import com.visionvpn.app.R
import com.visionvpn.app.VisionVpnApplication
import com.visionvpn.app.data.model.VpnConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class VisionVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.visionvpn.app.ACTION_START_VPN"
        const val ACTION_STOP = "com.visionvpn.app.ACTION_STOP_VPN"
        private const val CHANNEL_ID = "vision_vpn_tunnel"
        private const val NOTIFICATION_ID = 1042
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = (application as VisionVpnApplication).vpnCoreController
        when (intent?.action) {
            ACTION_STOP -> {
                startJob?.cancel()
                startJob = null
                serviceScope.launch {
                    controller.stopTunnelInternal()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_START -> {
                val st = controller.connectionState.value
                if (st.isActiveOrStarting && st != VpnConnectionState.RECONNECTING) {
                    return START_NOT_STICKY
                }
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        title = getString(R.string.app_name),
                        contentText = getString(R.string.vpn_notification_connecting)
                    )
                )
                startJob?.cancel()
                startJob = serviceScope.launch {
                    val result = controller.startTunnelInternal(this@VisionVpnService)
                    result.onSuccess { graph ->
                        val exitProvider = graph.effectiveFinalExitProvider.labelEn
                        val chainPath = graph.describeChain().joinToString(" → ")
                        val manager = getSystemService(NotificationManager::class.java)
                        manager.notify(
                            NOTIFICATION_ID,
                            buildNotification(
                                title = "VisionVPN • Exit: $exitProvider",
                                contentText = chainPath
                            )
                        )
                    }.onFailure {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        startJob?.cancel()
        startJob = null
        val controller = (application as VisionVpnApplication).vpnCoreController
        serviceScope.launch {
            controller.stopTunnelInternal()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        startJob?.cancel()
        startJob = null
        val controller = (application as VisionVpnApplication).vpnCoreController
        if (controller.connectionState.value != VpnConnectionState.DISCONNECTED) {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(1_000L) {
                        controller.stopTunnelInternal()
                    }
                }
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, contentText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VisionVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, getString(R.string.status_disconnected), disconnectIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
