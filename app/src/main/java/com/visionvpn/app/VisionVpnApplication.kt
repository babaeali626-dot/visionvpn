package com.visionvpn.app

import android.app.Application
import com.visionvpn.app.core.VpnCoreController
import com.visionvpn.app.data.local.VisionPreferencesRepository
import com.visionvpn.app.data.parser.SubscriptionFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VisionVpnApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var preferencesRepository: VisionPreferencesRepository
        private set

    lateinit var subscriptionFetcher: SubscriptionFetcher
        private set

    lateinit var vpnCoreController: VpnCoreController
        private set

    override fun onCreate() {
        super.onCreate()
        preferencesRepository = VisionPreferencesRepository(this)
        subscriptionFetcher = SubscriptionFetcher()
        vpnCoreController = VpnCoreController(this, preferencesRepository)

        // Run durable transactional migration for any legacy plaintext/v1 credentials at startup
        appScope.launch {
            preferencesRepository.runDurableMigrationIfNeeded()
        }
    }

    override fun onTerminate() {
        vpnCoreController.localEngineManager.destroy()
        super.onTerminate()
    }
}
