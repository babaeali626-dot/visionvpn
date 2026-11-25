package com.visionvpn.app.chain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

class LocalEngineManager(
    private val onEngineUnrecoverableFailure: (suspend (engineId: String, reason: String) -> Unit)? = null
) {

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeEngines = LinkedHashMap<String, LocalEngine>()
    private val collectorJobs = mutableMapOf<String, Job>()
    private var watchdogJob: Job? = null

    private val _engineStates = MutableStateFlow<Map<String, LocalEngineState>>(emptyMap())
    val engineStates: StateFlow<Map<String, LocalEngineState>> = _engineStates.asStateFlow()

    suspend fun startEngine(engine: LocalEngine, readyTimeoutMs: Long = 45_000L): Result<Unit> =
        mutex.withLock {
            collectorJobs.remove(engine.engineId)?.cancel()
            activeEngines.remove(engine.engineId)?.stop()

            collectorJobs[engine.engineId] = scope.launch {
                engine.state.collect { st -> updateState(st) }
            }

            val launchResult = engine.start()
            if (launchResult.isFailure) {
                engine.stop()
                collectorJobs.remove(engine.engineId)?.cancel()
                val err = launchResult.exceptionOrNull()
                    ?: IllegalStateException("Failed to launch ${engine.engineId}")
                updateState(
                    LocalEngineState(
                        engineId = engine.engineId,
                        status = LocalEngineStatus.FAILED,
                        phaseLabel = "Failed",
                        message = err.message.orEmpty()
                    )
                )
                return Result.failure(err)
            }

            val healthy = awaitEngineReadiness(engine, readyTimeoutMs)
            if (!healthy) {
                val failureMsg = engine.state.value.message.ifBlank {
                    "${engine.engineId} timed out waiting for tunnel readiness"
                }
                engine.stop()
                collectorJobs.remove(engine.engineId)?.cancel()
                updateState(
                    LocalEngineState(
                        engineId = engine.engineId,
                        status = LocalEngineStatus.FAILED,
                        phaseLabel = "Connection Failed",
                        message = failureMsg
                    )
                )
                return Result.failure(IllegalStateException(failureMsg))
            }

            activeEngines[engine.engineId] = engine
            ensureWatchdogRunning()
            Result.success(Unit)
        }

    suspend fun stopAll() = mutex.withLock {
        watchdogJob?.cancel()
        watchdogJob = null
        collectorJobs.values.forEach { it.cancel() }
        collectorJobs.clear()

        val snapshot = activeEngines.values.toList().reversed()
        activeEngines.clear()
        snapshot.forEach { engine ->
            updateState(engine.state.value.copy(status = LocalEngineStatus.STOPPING, phaseLabel = "Stopping"))
            try {
                engine.stop()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Best-effort cleanup of remaining engines
            }
            updateState(
                LocalEngineState(
                    engineId = engine.engineId,
                    status = LocalEngineStatus.STOPPED,
                    phaseLabel = "Stopped",
                    message = "Stopped"
                )
            )
        }
    }

    suspend fun shutdown() {
        stopAll()
        scope.cancel()
    }

    fun destroy(timeoutMs: Long = 3_000L) {
        runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(timeoutMs) { stopAll() }
            }
        }
        scope.cancel()
    }

    private suspend fun awaitEngineReadiness(engine: LocalEngine, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (engine.state.value.status == LocalEngineStatus.FAILED) return false
            if (engine.isHealthy()) return true
            delay(400)
        }
        return false
    }

    private fun ensureWatchdogRunning() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            val consecutiveFailures = mutableMapOf<String, Int>()
            val failureAlreadyReported = mutableSetOf<String>()

            while (isActive) {
                delay(4_000)
                val engines = mutex.withLock { activeEngines.values.toList() }
                if (engines.isEmpty()) break

                for (engine in engines) {
                    if (engine.engineId in failureAlreadyReported) continue
                    val ok = try {
                        engine.isHealthy()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        false
                    }

                    if (ok) {
                        consecutiveFailures[engine.engineId] = 0
                        continue
                    }

                    val attempt = (consecutiveFailures[engine.engineId] ?: 0) + 1
                    consecutiveFailures[engine.engineId] = attempt

                    if (attempt <= 2) {
                        val backoffMs = 1_000L * attempt
                        updateState(
                            engine.state.value.copy(
                                status = LocalEngineStatus.RECONNECTING,
                                phaseLabel = "Reconnecting ($attempt/2)",
                                message = "${engine.engineId} unhealthy; retrying after ${backoffMs}ms..."
                            )
                        )
                        val recovered = mutex.withLock {
                            if (activeEngines[engine.engineId] !== engine) return@withLock false
                            engine.stop()
                            delay(backoffMs)
                            val restart = engine.start()
                            restart.isSuccess && awaitEngineReadiness(engine, 15_000L)
                        }
                        if (recovered) {
                            consecutiveFailures[engine.engineId] = 0
                        }
                    } else {
                        failureAlreadyReported.add(engine.engineId)
                        val reason = "${engine.engineId} failed recovery after 2 attempts (Fail-Closed)."
                        updateState(
                            engine.state.value.copy(
                                status = LocalEngineStatus.FAILED,
                                phaseLabel = "Failed",
                                message = reason
                            )
                        )
                        onEngineUnrecoverableFailure?.invoke(engine.engineId, reason)
                    }
                }
            }
        }
    }

    private fun updateState(state: LocalEngineState) {
        _engineStates.value = _engineStates.value.toMutableMap().apply {
            put(state.engineId, state)
        }
    }

    companion object {
        fun isLocalTcpPortOpen(port: Int, timeoutMs: Int = 500): Boolean {
            return runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                }
                true
            }.getOrDefault(false)
        }
    }
}
