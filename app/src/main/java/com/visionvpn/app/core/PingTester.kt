package com.visionvpn.app.core

import android.content.Context
import android.os.SystemClock
import com.visionvpn.app.data.model.AppSettings
import com.visionvpn.app.data.model.LatencyMeasurement
import com.visionvpn.app.data.model.LatencyStatus
import com.visionvpn.app.data.model.ProxyProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy as JavaNetProxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

sealed class LatencyTestOutcome {
    data class Success(val latencyMs: Int) : LatencyTestOutcome()
    data class Failure(val status: LatencyStatus, val reason: String) : LatencyTestOutcome()
}

fun interface ProxyLatencyProbe {
    suspend fun measure(profile: ProxyProfile, testUrl: String, timeoutMs: Int): LatencyTestOutcome
}

/**
 * Genuine end-to-end proxy latency tester with crash-hardened lifecycle management.
 *
 * - Uses a genuinely bounded fixed worker pool (never allocates N coroutines for N profiles).
 * - Defaults to a single serialized native Libbox probe lane ([DEFAULT_MAX_CONCURRENCY] = 1)
 *   coordinated via [LibboxRuntime.headlessProbeMutex] so concurrent native Go runtime/working-dir
 *   races cannot crash the Android process.
 * - Uses [LibboxRuntime.ensureInitialized] as the single process-wide initialization owner and
 *   surfaces initialization failures visibly.
 * - Uses race-resistant port reservation with retries and fails explicitly when the loopback
 *   listener does not become ready in time.
 * - Preserves [CancellationException] and fatal [Error]s (e.g., [OutOfMemoryError]).
 */
object PingTester {

    const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"
    const val DEFAULT_TIMEOUT_MS = 5_000
    const val DEFAULT_MAX_CONCURRENCY = 1
    private const val MAX_PORT_BIND_ATTEMPTS = 3

    private const val LIBBOX_CLASS = "com.hiddify.core.libbox.Libbox"
    private const val PLATFORM_INTERFACE_CLASS = "com.hiddify.core.libbox.PlatformInterface"

    private val reservedPorts: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())

    /**
     * Executes an HTTPS probe explicitly through a local HTTP/mixed proxy endpoint
     * (`127.0.0.1:[proxyPort]`), verifying HTTP 2xx status with standard TLS certificate
     * and hostname validation, and measuring real end-to-end RTT.
     */
    suspend fun probeThroughLocalHttpProxy(
        proxyPort: Int,
        testUrl: String = DEFAULT_TEST_URL,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): LatencyTestOutcome = withContext(Dispatchers.IO) {
        runInterruptible(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val proxy = JavaNetProxy(JavaNetProxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
                connection = (URL(testUrl).openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("Connection", "close")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val startNs = System.nanoTime()
                val code = connection.responseCode
                val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
                if (code in 200..299) {
                    LatencyTestOutcome.Success(elapsedMs)
                } else if (code == 407 || code == 502 || code == 503) {
                    LatencyTestOutcome.Failure(
                        LatencyStatus.PROTOCOL_ERROR,
                        "Proxy rejected request (HTTP $code)"
                    )
                } else {
                    LatencyTestOutcome.Failure(
                        LatencyStatus.FAILED,
                        "Unexpected probe HTTP status $code"
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: InterruptedException) {
                throw CancellationException("Latency probe cancelled")
            } catch (e: SocketTimeoutException) {
                LatencyTestOutcome.Failure(LatencyStatus.TIMEOUT, "Probe timed out (${timeoutMs}ms)")
            } catch (e: UnknownHostException) {
                LatencyTestOutcome.Failure(LatencyStatus.DNS_ERROR, "DNS resolution failed")
            } catch (e: SSLException) {
                LatencyTestOutcome.Failure(LatencyStatus.TLS_ERROR, "TLS handshake failed")
            } catch (e: IOException) {
                val msg = e.message.orEmpty().lowercase()
                val status = when {
                    "timed out" in msg || "timeout" in msg -> LatencyStatus.TIMEOUT
                    "dns" in msg || "resolve" in msg || "nodename" in msg -> LatencyStatus.DNS_ERROR
                    "ssl" in msg || "tls" in msg || "certificate" in msg -> LatencyStatus.TLS_ERROR
                    "auth" in msg || "eof" in msg || "reset" in msg || "refused" in msg ->
                        LatencyStatus.PROTOCOL_ERROR
                    else -> LatencyStatus.FAILED
                }
                LatencyTestOutcome.Failure(status, "Proxy connection failed (${status.name})")
            } catch (e: Exception) {
                LatencyTestOutcome.Failure(
                    LatencyStatus.FAILED,
                    LogRedactor.redact(e.message ?: "Probe failed")
                )
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
    }

    data class SpeedTestResult(
        val bytesDownloaded: Long,
        val durationMs: Long,
        val megabitsPerSecond: Double
    )

    /**
     * Downloads a bounded Cloudflare speed-test payload through the active tunnel's loopback
     * proxy. The body is streamed in a fixed 32 KiB buffer; memory use does not scale with size.
     */
    suspend fun downloadSpeedTestThroughLocalHttpProxy(
        proxyPort: Int = ConfigBuilder.PROBE_PROXY_PORT,
        downloadSizeMiB: Int = 1,
        timeoutMs: Int = 60_000,
        onProgress: (downloadedBytes: Long, expectedBytes: Long) -> Unit = { _, _ -> }
    ): SpeedTestResult = withContext(Dispatchers.IO) {
        val expectedBytes = downloadSizeMiB.coerceIn(1, 100).toLong() * 1024L * 1024L
        runInterruptible(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val proxy = JavaNetProxy(JavaNetProxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
                val url = URL("https://speed.cloudflare.com/__down?bytes=$expectedBytes")
                require(url.protocol.equals("https", ignoreCase = true)) { "Speed-test endpoint must use HTTPS." }
                connection = (url.openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = timeoutMs.coerceIn(5_000, 120_000)
                    readTimeout = timeoutMs.coerceIn(5_000, 120_000)
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/octet-stream")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Connection", "close")
                }
                val response = connection.responseCode
                require(response in 200..299) { "Speed-test server returned HTTP $response." }
                val contentLength = connection.contentLengthLong
                require(contentLength < 0L || contentLength == expectedBytes) {
                    "Speed-test server returned an unexpected payload size."
                }
                val startNs = System.nanoTime()
                var total = 0L
                val buffer = ByteArray(32 * 1024)
                connection.inputStream.use { input ->
                    while (total < expectedBytes) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("Speed test cancelled")
                        val requestBytes = minOf(buffer.size.toLong(), expectedBytes - total).toInt()
                        val count = input.read(buffer, 0, requestBytes)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        onProgress(total, expectedBytes)
                    }
                }
                require(total == expectedBytes) {
                    "Speed-test download ended early ($total of $expectedBytes bytes)."
                }
                val durationMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                val mbps = (total.toDouble() * 8.0) / (durationMs.toDouble() * 1_000.0)
                SpeedTestResult(total, durationMs, mbps)
            } catch (ce: CancellationException) {
                throw ce
            } catch (ie: InterruptedException) {
                throw CancellationException("Speed test cancelled")
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
    }

    /**
     * Creates a real Hiddify Core (`Libbox`) end-to-end HTTPS proxy probe that spins up a temporary
     * no-TUN loopback inbound for each candidate profile and tears it down reliably in `finally`.
     */
    fun createLibboxProbe(
        context: Context,
        configBuilder: ConfigBuilder = ConfigBuilder(),
        settingsProvider: () -> AppSettings = { AppSettings() }
    ): ProxyLatencyProbe = ProxyLatencyProbe { profile, testUrl, timeoutMs ->
        measureViaHeadlessLibbox(
            context = context,
            configBuilder = configBuilder,
            profile = profile,
            settings = settingsProvider(),
            testUrl = testUrl,
            timeoutMs = timeoutMs
        )
    }

    /**
     * Tests [profiles] using a fixed-size worker pool of at most [maxConcurrency] coroutines
     * (never allocating one coroutine per profile). Preserves input ordering, isolates per-profile
     * failures, and propagates [CancellationException] and fatal JVM [Error]s cleanly.
     */
    suspend fun testProfiles(
        profiles: List<ProxyProfile>,
        probe: ProxyLatencyProbe,
        testUrl: String = DEFAULT_TEST_URL,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
        clockMs: () -> Long = { System.currentTimeMillis() },
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        onResult: (LatencyMeasurement) -> Unit = {}
    ): List<LatencyMeasurement> {
        if (profiles.isEmpty()) return emptyList()
        val total = profiles.size
        val workerCount = maxConcurrency.coerceIn(1, 6).coerceAtMost(total)
        val nextIndex = AtomicInteger(0)
        val completedCount = AtomicInteger(0)
        val results = arrayOfNulls<LatencyMeasurement>(total)

        coroutineScope {
            repeat(workerCount) {
                launch(Dispatchers.IO) {
                    while (true) {
                        // A failed native close quarantines the process-wide runtime. Stop the
                        // batch immediately instead of turning the remaining hundreds of profiles
                        // into misleading fast failures against a disabled Libbox instance.
                        if (LibboxRuntime.currentMode() == LibboxLeaseMode.FAULTED) break
                        val idx = nextIndex.getAndIncrement()
                        if (idx >= total) break
                        val profile = profiles[idx]
                        val outcome = try {
                            probe.measure(profile, testUrl, timeoutMs)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            LatencyTestOutcome.Failure(
                                LatencyStatus.FAILED,
                                LogRedactor.redact(e.message ?: "Probe exception")
                            )
                        }
                        val measurement = when (outcome) {
                            is LatencyTestOutcome.Success -> LatencyMeasurement(
                                subscriptionId = profile.subscriptionId,
                                fingerprint = profile.connectionFingerprint(),
                                status = LatencyStatus.SUCCESS,
                                latencyMs = outcome.latencyMs.coerceAtLeast(1),
                                errorReason = null,
                                testedAtMs = clockMs()
                            )
                            is LatencyTestOutcome.Failure -> LatencyMeasurement(
                                subscriptionId = profile.subscriptionId,
                                fingerprint = profile.connectionFingerprint(),
                                status = outcome.status,
                                latencyMs = null,
                                errorReason = outcome.reason,
                                testedAtMs = clockMs()
                            )
                        }
                        results[idx] = measurement
                        onResult(measurement)
                        val done = completedCount.incrementAndGet()
                        onProgress(done, total)
                    }
                }
            }
        }
        return results.filterNotNull()
    }

    /**
     * Runs a complete profile batch through one headless Libbox service and unique mixed inbounds.
     * Unlike the former per-profile service startup loop, this allows up to `maxConcurrency`
     * simultaneous HTTPS health requests without racing multiple Go runtimes or data directories.
     */
    suspend fun testProfilesWithSharedLibbox(
        context: Context,
        configBuilder: ConfigBuilder,
        profiles: List<ProxyProfile>,
        settings: AppSettings,
        testUrl: String = DEFAULT_TEST_URL,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        maxConcurrency: Int = 20,
        clockMs: () -> Long = { System.currentTimeMillis() },
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        onResult: (LatencyMeasurement) -> Unit = {}
    ): List<LatencyMeasurement> {
        if (profiles.isEmpty()) return emptyList()
        val allResults = ArrayList<LatencyMeasurement>(profiles.size)
        var completedBeforeBatch = 0
        // Bound native configuration size while still supporting large subscriptions. Batches are
        // strictly sequential at the Libbox lifecycle level; only HTTPS probes inside each batch
        // run concurrently, up to the user's configured limit.
        for (batch in profiles.chunked(100)) {
            val batchResults = testProfileChunkWithSharedLibbox(
                context = context,
                configBuilder = configBuilder,
                profiles = batch,
                settings = settings,
                testUrl = testUrl,
                timeoutMs = timeoutMs,
                maxConcurrency = maxConcurrency,
                clockMs = clockMs,
                onProgress = { completed, _ -> onProgress(completedBeforeBatch + completed, profiles.size) },
                onResult = onResult
            )
            allResults += batchResults
            completedBeforeBatch += batch.size
            onProgress(completedBeforeBatch, profiles.size)
        }
        return allResults
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun testProfileChunkWithSharedLibbox(
        context: Context,
        configBuilder: ConfigBuilder,
        profiles: List<ProxyProfile>,
        settings: AppSettings,
        testUrl: String = DEFAULT_TEST_URL,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        maxConcurrency: Int = 20,
        clockMs: () -> Long = { System.currentTimeMillis() },
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        onResult: (LatencyMeasurement) -> Unit = {}
    ): List<LatencyMeasurement> {
        if (profiles.isEmpty()) return emptyList()
        require(profiles.size <= 100) { "A native profile-test batch is limited to 100 profiles." }
        val safeTimeoutMs = timeoutMs.coerceIn(1_000, 30_000)
        val safeConcurrency = maxConcurrency.coerceIn(1, 100)
        val validUrl = runCatching { URL(testUrl) }.getOrNull()
        if (validUrl == null || !validUrl.protocol.equals("https", ignoreCase = true) || validUrl.host.isNullOrBlank()) {
            return profiles.map { profile ->
                LatencyMeasurement(
                    profile.subscriptionId,
                    profile.connectionFingerprint(),
                    LatencyStatus.PROTOCOL_ERROR,
                    null,
                    "Latency test URL must be a valid HTTPS endpoint.",
                    clockMs()
                ).also(onResult)
            }.also { onProgress(it.size, profiles.size) }
        }

        val results = arrayOfNulls<LatencyMeasurement>(profiles.size)
        val validOriginalIndexes = mutableListOf<Int>()
        profiles.forEachIndexed { index, profile ->
            val validation = configBuilder.validateProfile(profile)
            if (validation.isSuccess) {
                validOriginalIndexes += index
            } else {
                val result = LatencyMeasurement(
                    profile.subscriptionId,
                    profile.connectionFingerprint(),
                    LatencyStatus.PROTOCOL_ERROR,
                    null,
                    LogRedactor.redact(validation.exceptionOrNull()?.message ?: "Invalid profile configuration"),
                    clockMs()
                )
                results[index] = result
                onResult(result)
            }
        }
        val completed = AtomicInteger(results.count { it != null })
        if (completed.get() > 0) onProgress(completed.get(), profiles.size)
        if (validOriginalIndexes.isEmpty()) {
            onProgress(profiles.size, profiles.size)
            return results.filterNotNull()
        }

        val batchResult = LibboxRuntime.withHeadlessProbeLease {
            val libboxClass = try {
                Class.forName(LIBBOX_CLASS)
            } catch (_: ClassNotFoundException) {
                val reason = "Hiddify Core native library (hiddify-core.aar) is not available for proxy testing"
                validOriginalIndexes.forEach { originalIndex ->
                    val profile = profiles[originalIndex]
                    results[originalIndex] = LatencyMeasurement(
                        profile.subscriptionId, profile.connectionFingerprint(), LatencyStatus.FAILED,
                        null, reason, clockMs()
                    )
                    onResult(results[originalIndex]!!)
                }
                onProgress(profiles.size, profiles.size)
                return@withHeadlessProbeLease results.filterNotNull()
            }

            val platformClass = try {
                Class.forName(PLATFORM_INTERFACE_CLASS)
            } catch (e: Exception) {
                return@withHeadlessProbeLease results.filterNotNull()
            }
            val headlessPlatform = newHeadlessPlatform(platformClass, "VisionHeadlessBatchProbePlatform")
            try {
                LibboxRuntime.ensureInitialized(context, platformClass, headlessPlatform)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                val reason = "Libbox initialization failed: ${LogRedactor.redact(unwrapReflectiveError(e).message).take(80)}"
                validOriginalIndexes.forEach { originalIndex ->
                    val profile = profiles[originalIndex]
                    results[originalIndex] = LatencyMeasurement(
                        profile.subscriptionId, profile.connectionFingerprint(), LatencyStatus.FAILED,
                        null, reason, clockMs()
                    )
                    onResult(results[originalIndex]!!)
                }
                onProgress(profiles.size, profiles.size)
                return@withHeadlessProbeLease results.filterNotNull()
            }

            val validProfiles = validOriginalIndexes.map { profiles[it] }
            val ports = mutableListOf<Int>()
            var service: Any? = null
            try {
                repeat(validProfiles.size) {
                    val port = reserveEphemeralPort() ?: error("Unable to reserve a unique local probe port.")
                    ports += port
                }
                val configJson = configBuilder.buildLatencyProbeBatchConfig(validProfiles, ports, settings)
                service = libboxClass.getMethod("newService", String::class.java, platformClass)
                    .invoke(null, configJson, headlessPlatform)
                    ?: error("Core rejected the multi-profile diagnostic configuration.")
                service.javaClass.getMethod("start").invoke(service)

                val readiness = coroutineScope {
                    ports.map { port -> async(Dispatchers.IO) { waitForLoopbackPortReady(port, 3_000L) } }.awaitAll()
                }
                val readyIndexes = validProfiles.indices.filter { readiness[it] }
                validProfiles.indices.filterNot { readiness[it] }.forEach { validIndex ->
                    val originalIndex = validOriginalIndexes[validIndex]
                    val profile = profiles[originalIndex]
                    val result = LatencyMeasurement(
                        profile.subscriptionId, profile.connectionFingerprint(), LatencyStatus.TIMEOUT, null,
                        "Local probe inbound did not become ready", clockMs()
                    )
                    results[originalIndex] = result
                    onResult(result)
                    completed.incrementAndGet()
                    onProgress(completed.get(), profiles.size)
                }

                val nextIndex = AtomicInteger(0)
                val workerCount = safeConcurrency.coerceAtMost(readyIndexes.size.coerceAtLeast(1))
                val probeDispatcher = Dispatchers.IO.limitedParallelism(workerCount)
                coroutineScope {
                    repeat(workerCount) {
                        launch(probeDispatcher) {
                            while (true) {
                                if (LibboxRuntime.currentMode() == LibboxLeaseMode.FAULTED) break
                                val offset = nextIndex.getAndIncrement()
                                if (offset >= readyIndexes.size) break
                                val validIndex = readyIndexes[offset]
                                val originalIndex = validOriginalIndexes[validIndex]
                                val profile = profiles[originalIndex]
                                val outcome = probeThroughLocalHttpProxy(
                                    proxyPort = ports[validIndex],
                                    testUrl = testUrl,
                                    timeoutMs = safeTimeoutMs
                                )
                                val measurement = when (outcome) {
                                    is LatencyTestOutcome.Success -> LatencyMeasurement(
                                        profile.subscriptionId, profile.connectionFingerprint(), LatencyStatus.SUCCESS,
                                        outcome.latencyMs.coerceAtLeast(1), null, clockMs()
                                    )
                                    is LatencyTestOutcome.Failure -> LatencyMeasurement(
                                        profile.subscriptionId, profile.connectionFingerprint(), outcome.status,
                                        null, outcome.reason, clockMs()
                                    )
                                }
                                results[originalIndex] = measurement
                                onResult(measurement)
                                completed.incrementAndGet()
                                onProgress(completed.get(), profiles.size)
                            }
                        }
                    }
                }

                // A quarantined native runtime can stop remaining work. Preserve already completed
                // results and report every not-yet-tested candidate honestly.
                validOriginalIndexes.forEach { originalIndex ->
                    if (results[originalIndex] == null) {
                        val profile = profiles[originalIndex]
                        val result = LatencyMeasurement(
                            profile.subscriptionId, profile.connectionFingerprint(), LatencyStatus.FAILED,
                            null,
                            "Probe was not completed because the native runtime was quarantined or unavailable.",
                            clockMs()
                        )
                        results[originalIndex] = result
                        onResult(result)
                        completed.incrementAndGet()
                        onProgress(completed.get(), profiles.size)
                    }
                }
                results.filterNotNull()
            } finally {
                var closeFailure: Throwable? = null
                withContext(NonCancellable) {
                    service?.let { svc ->
                        try {
                            svc.javaClass.getMethod("close").invoke(svc)
                        } catch (t: Throwable) {
                            closeFailure = t
                        }
                    }
                    ports.forEach { reservedPorts.remove(it) }
                }
                closeFailure?.let {
                    throw LibboxCleanupException(
                        "Headless batch Libbox service could not be confirmed closed; native runtime quarantined.",
                        unwrapReflectiveError(it)
                    )
                }
            }
        }

        val measurements = batchResult.getOrElse { error ->
            if (error is CancellationException) throw error
            val reason = LogRedactor.redact(error.message ?: "Unable to start the shared Libbox test engine")
            validOriginalIndexes.forEach { originalIndex ->
                if (results[originalIndex] == null) {
                    val profile = profiles[originalIndex]
                    val result = LatencyMeasurement(
                        profile.subscriptionId, profile.connectionFingerprint(), LatencyStatus.FAILED,
                        null, reason, clockMs()
                    )
                    results[originalIndex] = result
                    onResult(result)
                }
            }
            results.filterNotNull()
        }
        onProgress(measurements.size, profiles.size)
        return measurements
    }

    private suspend fun measureViaHeadlessLibbox(
        context: Context,
        configBuilder: ConfigBuilder,
        profile: ProxyProfile,
        settings: AppSettings,
        testUrl: String,
        timeoutMs: Int
    ): LatencyTestOutcome = withContext(Dispatchers.IO) {
        val libboxClass = try {
            Class.forName(LIBBOX_CLASS)
        } catch (_: ClassNotFoundException) {
            return@withContext LatencyTestOutcome.Failure(
                LatencyStatus.FAILED,
                "Hiddify Core native library (hiddify-core.aar) is not available for proxy testing"
            )
        }

        val validation = configBuilder.validateProfile(profile)
        if (validation.isFailure) {
            return@withContext LatencyTestOutcome.Failure(
                LatencyStatus.PROTOCOL_ERROR,
                LogRedactor.redact(validation.exceptionOrNull()?.message ?: "Invalid profile configuration")
            )
        }

        // Atomically acquire a process-wide headless probe lease (mutually exclusive with active VPN core)
        val leaseResult = LibboxRuntime.withHeadlessProbeLease {
            val platformClass = try {
                Class.forName(PLATFORM_INTERFACE_CLASS)
            } catch (e: Exception) {
                return@withHeadlessProbeLease LatencyTestOutcome.Failure(
                    LatencyStatus.FAILED,
                    "Hiddify Core platform adapter is unavailable"
                )
            }
            val headlessPlatform = newHeadlessPlatform(platformClass, "VisionHeadlessProbePlatform")
            try {
                LibboxRuntime.ensureInitialized(context, platformClass, headlessPlatform)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                val root = unwrapReflectiveError(e)
                return@withHeadlessProbeLease LatencyTestOutcome.Failure(
                    LatencyStatus.FAILED,
                    "Libbox initialization failed: ${LogRedactor.redact(root.message).take(80)}"
                )
            }

            var lastStartupFailure: LatencyTestOutcome.Failure? = null
            for (attempt in 1..MAX_PORT_BIND_ATTEMPTS) {
                val localPort = reserveEphemeralPort()
                if (localPort == null) {
                    lastStartupFailure = LatencyTestOutcome.Failure(
                        LatencyStatus.FAILED,
                        "Unable to allocate local loopback test port"
                    )
                    continue
                }

                var boxService: Any? = null
                try {
                    val configJson = configBuilder.buildLatencyProbeConfig(profile, localPort, settings)
                    val newServiceMethod = libboxClass.getMethod(
                        "newService",
                        String::class.java,
                        platformClass
                    )
                    val service = newServiceMethod.invoke(null, configJson, headlessPlatform)
                        ?: return@withHeadlessProbeLease LatencyTestOutcome.Failure(
                            LatencyStatus.PROTOCOL_ERROR,
                            "Core rejected profile configuration"
                        )
                    boxService = service
                    service.javaClass.getMethod("start").invoke(service)

                    val listenerReady = waitForLoopbackPortReady(localPort, maxWaitMs = 1_500L)
                    if (!listenerReady) {
                        lastStartupFailure = LatencyTestOutcome.Failure(
                            LatencyStatus.TIMEOUT,
                            "Local probe listener on 127.0.0.1:$localPort did not become ready"
                        )
                        continue
                    }

                    return@withHeadlessProbeLease probeThroughLocalHttpProxy(
                        proxyPort = localPort,
                        testUrl = testUrl,
                        timeoutMs = timeoutMs
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    val root = unwrapReflectiveError(e)
                    val rootMsg = LogRedactor.redact(root.message.orEmpty())
                    val isBindConflict = rootMsg.contains("address already in use", ignoreCase = true) ||
                        rootMsg.contains("bind", ignoreCase = true)
                    val status = when {
                        rootMsg.contains("timeout", ignoreCase = true) -> LatencyStatus.TIMEOUT
                        rootMsg.contains("tls", ignoreCase = true) || rootMsg.contains("cert", ignoreCase = true) ->
                            LatencyStatus.TLS_ERROR
                        else -> LatencyStatus.PROTOCOL_ERROR
                    }
                    lastStartupFailure = LatencyTestOutcome.Failure(
                        status,
                        "Proxy test startup failed: ${rootMsg.take(80)}"
                    )
                    if (!isBindConflict) {
                        return@withHeadlessProbeLease lastStartupFailure
                    }
                } finally {
                    var closeFailure: Throwable? = null
                    withContext(NonCancellable) {
                        boxService?.let { svc ->
                            try {
                                svc.javaClass.getMethod("close").invoke(svc)
                            } catch (t: Throwable) {
                                closeFailure = t
                            }
                        }
                        reservedPorts.remove(localPort)
                    }
                    closeFailure?.let { t ->
                        throw LibboxCleanupException(
                            "Headless Libbox service could not be confirmed closed; native runtime quarantined.",
                            unwrapReflectiveError(t)
                        )
                    }
                }
            }

            lastStartupFailure ?: LatencyTestOutcome.Failure(
                LatencyStatus.FAILED,
                "Proxy probe failed after $MAX_PORT_BIND_ATTEMPTS port allocation attempts"
            )
        }
        leaseResult.getOrElse { err ->
            LatencyTestOutcome.Failure(
                LatencyStatus.FAILED,
                LogRedactor.redact(err.message ?: "VPN core is active; disconnect before testing")
            )
        }
    }

    private fun newHeadlessPlatform(platformClass: Class<*>, label: String): Any =
        Proxy.newProxyInstance(
            platformClass.classLoader,
            arrayOf(platformClass)
        ) { proxy, method, _ ->
            when (method.name) {
                "usePlatformAutoDetectInterfaceControl", "usePlatformDefaultInterfaceMonitor",
                "usePlatformInterfaceGetter", "useProcFS", "underNetworkExtension", "includeAllNetworks" -> false
                "packageNameByUid" -> ""
                "uidByPackageName", "findConnectionOwner" -> -1
                "toString" -> label
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> false
                else -> null
            }
        }

    internal fun reserveEphemeralPort(): Int? {
        repeat(8) {
            val candidate = runCatching {
                ServerSocket(0).use { sock ->
                    sock.reuseAddress = true
                    sock.localPort
                }
            }.getOrNull() ?: return null
            if (candidate in 1024..65535 && reservedPorts.add(candidate)) {
                return candidate
            }
        }
        return null
    }

    internal fun releaseReservedPort(port: Int) {
        reservedPorts.remove(port)
    }

    /**
     * Waits until `127.0.0.1:[port]` accepts TCP connections or [maxWaitMs] elapses.
     * Returns `true` when ready, or `false` on timeout so callers fail visibly.
     */
    internal suspend fun waitForLoopbackPortReady(
        port: Int,
        maxWaitMs: Long,
        clockMs: () -> Long = { SystemClock.elapsedRealtime() }
    ): Boolean {
        val deadline = clockMs() + maxWaitMs
        while (clockMs() < deadline) {
            val open = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 150)
                }
                true
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                false
            }
            if (open) return true
            delay(50L)
        }
        return false
    }

    private fun unwrapReflectiveError(e: Throwable): Throwable {
        return if (e is InvocationTargetException && e.targetException != null) {
            e.targetException
        } else {
            e.cause ?: e
        }
    }
}
