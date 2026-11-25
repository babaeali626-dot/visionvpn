package com.visionvpn.app.chain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

class LocalEngineManager(
    private val onEngineUnrecoverableFailure: (suspend (engineId: String, reason: String) -> Unit)? = null
) {

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startingEngines = LinkedHashMap<String, LocalEngine>()
    private val activeEngines = LinkedHashMap<String, LocalEngine>()
    private val collectorJobs = mutableMapOf<String, Job>()
    private var watchdogJob: Job? = null

    private val _engineStates = MutableStateFlow<Map<String, LocalEngineState>>(emptyMap())
    val engineStates: StateFlow<Map<String, LocalEngineState>> = _engineStates.asStateFlow()

    suspend fun startEngine(engine: LocalEngine, readyTimeoutMs: Long = 45_000L): Result<Unit> =
        mutex.withLock {
            collectorJobs[engine.engineId]?.cancel()
            // Do not drop ownership before an old process is confirmed stopped. If stop() fails,
            // the old engine remains tracked so a later Disconnect can retry cleanup.
            startingEngines[engine.engineId]?.let { old ->
                withContext(NonCancellable) { old.stop() }
                startingEngines.remove(engine.engineId)
            }
            activeEngines[engine.engineId]?.let { old ->
                withContext(NonCancellable) { old.stop() }
                activeEngines.remove(engine.engineId)
            }
            collectorJobs.remove(engine.engineId)?.cancel()

            // Track engine BEFORE launching so cancellation/failure during startup is always cleaned up
            startingEngines[engine.engineId] = engine
            collectorJobs[engine.engineId] = scope.launch {
                engine.state.collect { st -> updateState(st) }
            }

            try {
                val launchResult = engine.start()
                if (launchResult.isFailure) {
                    val cleanupConfirmed = cleanupStartingEngineLocked(engine)
                    val err = launchResult.exceptionOrNull()
                        ?: IllegalStateException("Failed to launch ${engine.engineId}")
                    if (!cleanupConfirmed) {
                        val cleanupError = IllegalStateException(
                            "${engine.engineId} launch failed and the child process could not be confirmed stopped; ownership retained for retry.",
                            err
                        )
                        updateState(
                            engine.state.value.copy(
                                status = LocalEngineStatus.FAILED,
                                phaseLabel = "Cleanup Required",
                                message = cleanupError.message.orEmpty()
                            )
                        )
                        return Result.failure(cleanupError)
                    }
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
                    val cleanupConfirmed = cleanupStartingEngineLocked(engine)
                    val failure = if (cleanupConfirmed) {
                        IllegalStateException(failureMsg)
                    } else {
                        IllegalStateException(
                            "$failureMsg Engine cleanup could not be confirmed; ownership retained for retry."
                        )
                    }
                    updateState(
                        engine.state.value.copy(
                            status = LocalEngineStatus.FAILED,
                            phaseLabel = if (cleanupConfirmed) "Connection Failed" else "Cleanup Required",
                            message = failure.message.orEmpty()
                        )
                    )
                    return Result.failure(failure)
                }

                startingEngines.remove(engine.engineId)
                activeEngines[engine.engineId] = engine
                ensureWatchdogRunning()
                Result.success(Unit)
            } catch (ce: CancellationException) {
                withContext(NonCancellable) {
                    val cleanupConfirmed = cleanupStartingEngineLocked(engine)
                    if (cleanupConfirmed) {
                        updateState(
                            LocalEngineState(
                                engineId = engine.engineId,
                                status = LocalEngineStatus.STOPPED,
                                phaseLabel = "Cancelled",
                                message = "Startup cancelled"
                            )
                        )
                    }
                    // If cleanup failed, cleanupStartingEngineLocked already published FAILED /
                    // Cleanup Required. Never overwrite that state with a false STOPPED result.
                }
                throw ce
            }
        }

    private suspend fun cleanupStartingEngineLocked(engine: LocalEngine): Boolean {
        collectorJobs.remove(engine.engineId)?.cancel()
        return try {
            withContext(NonCancellable) { engine.stop() }
            startingEngines.remove(engine.engineId)
            activeEngines.remove(engine.engineId)
            true
        } catch (t: Throwable) {
            // Keep the engine in startingEngines so explicit Disconnect can retry. Never report
            // STOPPED when a native child process may still be alive.
            startingEngines[engine.engineId] = engine
            updateState(
                engine.state.value.copy(
                    status = LocalEngineStatus.FAILED,
                    phaseLabel = "Cleanup Required",
                    message = "Startup failed and engine cleanup could not be confirmed: ${t.message.orEmpty()}"
                )
            )
            false
        }
    }

    suspend fun stopAll() = mutex.withLock {
        watchdogJob?.cancel()
        watchdogJob = null
        collectorJobs.values.forEach { it.cancel() }
        collectorJobs.clear()

        // Stop downstream engines before upstream engines, including any still-starting engines.
        // Keep failed engines in the ownership maps so a subsequent Disconnect can retry them.
        val combined = LinkedHashMap<String, LocalEngine>()
        combined.putAll(activeEngines)
        combined.putAll(startingEngines)
        val snapshot = combined.values.toList().reversed()
        val failures = mutableListOf<Pair<String, Throwable>>()

        snapshot.forEach { engine ->
            updateState(engine.state.value.copy(status = LocalEngineStatus.STOPPING, phaseLabel = "Stopping"))
            try {
                withContext(NonCancellable) { engine.stop() }
                activeEngines.remove(engine.engineId)
                startingEngines.remove(engine.engineId)
                updateState(
                    LocalEngineState(
                        engineId = engine.engineId,
                        status = LocalEngineStatus.STOPPED,
                        phaseLabel = "Stopped",
                        message = "Stopped"
                    )
                )
            } catch (t: Throwable) {
                if (!activeEngines.containsKey(engine.engineId) && !startingEngines.containsKey(engine.engineId)) {
                    // Preserve ownership of an engine that was still in the startup map snapshot.
                    startingEngines[engine.engineId] = engine
                }
                failures += engine.engineId to t
                updateState(
                    engine.state.value.copy(
                        status = LocalEngineStatus.FAILED,
                        phaseLabel = "Cleanup Required",
                        message = "Could not confirm engine shutdown: ${t.message.orEmpty()}"
                    )
                )
            }
        }

        if (failures.isNotEmpty()) {
            val root = IllegalStateException(
                "Could not confirm shutdown of local engine(s): ${failures.joinToString { it.first }}"
            )
            failures.forEach { (_, cause) -> root.addSuppressed(cause) }
            throw root
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
