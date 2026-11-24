package com.visionvpn.app.chain

import kotlinx.coroutines.flow.StateFlow

enum class LocalEngineStatus {
    STOPPED,
    INITIALIZING,
    STARTING,
    CONNECTING,
    BOOTSTRAPPING,
    CONNECTED,
    DEGRADED,
    RECONNECTING,
    STOPPING,
    FAILED
}

data class LocalEngineState(
    val engineId: String,
    val status: LocalEngineStatus = LocalEngineStatus.STOPPED,
    val localSocksPort: Int = 0,
    val bootstrapProgress: Int = 0,
    val requestedRegion: String? = null,
    val observedRegion: String? = null,
    val availableRegions: List<String> = emptyList(),
    val phaseLabel: String = "",
    val message: String = ""
)

interface LocalEngine {
    val engineId: String
    val state: StateFlow<LocalEngineState>

    suspend fun start(): Result<Unit>
    suspend fun isHealthy(): Boolean
    suspend fun stop()
}
