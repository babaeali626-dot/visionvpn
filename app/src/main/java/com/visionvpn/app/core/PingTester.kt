package com.visionvpn.app.core

import android.content.Context
import android.os.SystemClock
import com.visionvpn.app.data.model.AppSettings
import com.visionvpn.app.data.model.LatencyMeasurement
import com.visionvpn.app.data.model.LatencyStatus
import com.visionvpn.app.data.model.ProxyProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy as JavaNetProxy
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

sealed class LatencyTestOutcome {
    data class Success(val latencyMs: Int) : LatencyTestOutcome()
    data class Failure(val status: LatencyStatus, val reason: String) : LatencyTestOutcome()
}

fun interface ProxyLatencyProbe {
    suspend fun measure(profile: ProxyProfile, testUrl: String, timeoutMs: Int): LatencyTestOutcome
}

/**
 * Genuine end-to-end proxy latency tester.
 *
 * Unlike raw TCP socket connect checks, every measurement routes a real HTTPS request
 * (`generate_204`) through an isolated headless Sing-box (`Libbox`) instance configured
 * exclusively with the candidate [ProxyProfile] outbound on an ephemeral loopback port.
 * Direct device sockets are never used as proof of proxy reachability.
 */
object PingTester {

    const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"
    const val DEFAULT_TIMEOUT_MS = 8_000
    const val DEFAULT_MAX_CONCURRENCY = 3

    private const val LIBBOX_CLASS = "io.nekohasekai.libbox.Libbox"
    private const val SETUP_OPTIONS_CLASS = "io.nekohasekai.libbox.SetupOptions"
    private const val PLATFORM_INTERFACE_CLASS = "io.nekohasekai.libbox.PlatformInterface"
    private val probeLibboxInitialized = AtomicBoolean(false)

    /**
     * Executes an HTTPS probe explicitly through a local HTTP/mixed proxy endpoint
     * (`127.0.0.1:[proxyPort]`), verifying HTTP 2xx status and measuring real end-to-end RTT.
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
            } catch (t: Throwable) {
                LatencyTestOutcome.Failure(LatencyStatus.FAILED, t.message ?: "Probe failed")
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
    }

    /**
     * Creates a real Sing-box (`Libbox`) end-to-end proxy probe that spins up a temporary
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

    suspend fun testProfiles(
        profiles: List<ProxyProfile>,
        probe: ProxyLatencyProbe,
        testUrl: String = DEFAULT_TEST_URL,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
        clockMs: () -> Long = { System.currentTimeMillis() },
        onResult: (LatencyMeasurement) -> Unit = {}
    ): List<LatencyMeasurement> = coroutineScope {
        val semaphore = Semaphore(maxConcurrency.coerceIn(1, 6))
        profiles.map { profile ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val outcome = probe.measure(profile, testUrl, timeoutMs)
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
                    onResult(measurement)
                    measurement
                }
            }
        }.awaitAll()
    }

    private suspend fun measureViaHeadlessLibbox(
        context: Context,
        configBuilder: ConfigBuilder,
        profile: ProxyProfile,
        settings: AppSettings,
        testUrl: String,
        timeoutMs: Int
    ): LatencyTestOutcome = withContext(Dispatchers.IO) {
        val libboxClass = runCatching { Class.forName(LIBBOX_CLASS) }.getOrNull()
            ?: return@withContext LatencyTestOutcome.Failure(
                LatencyStatus.FAILED,
                "Sing-box native core (libbox.aar) is not available for proxy testing"
            )

        val localPort = allocateEphemeralPort()
            ?: return@withContext LatencyTestOutcome.Failure(
                LatencyStatus.FAILED,
                "Unable to allocate local test port"
            )

        var boxService: Any? = null
        try {
            ensureProbeSetup(context, libboxClass)
            val platformClass = Class.forName(PLATFORM_INTERFACE_CLASS)
            val headlessPlatform = Proxy.newProxyInstance(
                platformClass.classLoader,
                arrayOf(platformClass)
            ) { _, method, _ ->
                when (method.name) {
                    "usePlatformAutoDetectInterfaceControl",
                    "usePlatformDefaultInterfaceMonitor",
                    "usePlatformInterfaceGetter",
                    "useProcFS",
                    "underNetworkExtension",
                    "includeAllNetworks" -> false
                    "packageNameByUid" -> ""
                    "uidByPackageName", "findConnectionOwner" -> -1
                    "toString" -> "VisionHeadlessProbePlatform"
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> false
                    else -> null
                }
            }

            val configJson = configBuilder.buildLatencyProbeConfig(profile, localPort, settings)
            val newServiceMethod = libboxClass.getMethod(
                "newService",
                String::class.java,
                platformClass
            )
            val service = newServiceMethod.invoke(null, configJson, headlessPlatform)
                ?: return@withContext LatencyTestOutcome.Failure(
                    LatencyStatus.PROTOCOL_ERROR,
                    "Core rejected profile configuration"
                )
            boxService = service
            service.javaClass.getMethod("start").invoke(service)

            // Wait briefly for loopback mixed listener to accept connections
            waitForLoopbackPortReady(localPort, maxWaitMs = 1_500L)

            probeThroughLocalHttpProxy(
                proxyPort = localPort,
                testUrl = testUrl,
                timeoutMs = timeoutMs
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val rootMsg = (t.cause?.message ?: t.message).orEmpty()
            val status = when {
                rootMsg.contains("timeout", ignoreCase = true) -> LatencyStatus.TIMEOUT
                rootMsg.contains("tls", ignoreCase = true) || rootMsg.contains("cert", ignoreCase = true) ->
                    LatencyStatus.TLS_ERROR
                else -> LatencyStatus.PROTOCOL_ERROR
            }
            LatencyTestOutcome.Failure(status, "Proxy test startup failed: ${rootMsg.take(80)}")
        } finally {
            withContext(NonCancellable) {
                boxService?.let { svc ->
                    runCatching { svc.javaClass.getMethod("close").invoke(svc) }
                }
            }
        }
    }

    private fun ensureProbeSetup(context: Context, libboxClass: Class<*>) {
        if (probeLibboxInitialized.get()) return
        synchronized(probeLibboxInitialized) {
            if (probeLibboxInitialized.get()) return
            val baseDir = File(context.filesDir, "singbox").apply { mkdirs() }
            val workDir = File(baseDir, "work").apply { mkdirs() }
            val tempDir = File(context.cacheDir, "singbox_tmp").apply { mkdirs() }

            val optionsClass = Class.forName(SETUP_OPTIONS_CLASS)
            val options = optionsClass.getDeclaredConstructor().newInstance()
            optionsClass.getMethod("setBasePath", String::class.java).invoke(options, baseDir.absolutePath)
            optionsClass.getMethod("setWorkingPath", String::class.java).invoke(options, workDir.absolutePath)
            optionsClass.getMethod("setTempPath", String::class.java).invoke(options, tempDir.absolutePath)
            runCatching { libboxClass.getMethod("setup", optionsClass).invoke(null, options) }
            probeLibboxInitialized.set(true)
        }
    }

    private fun allocateEphemeralPort(): Int? = runCatching {
        ServerSocket(0).use { it.localPort }
    }.getOrNull()

    private suspend fun waitForLoopbackPortReady(port: Int, maxWaitMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + maxWaitMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val open = runCatching {
                java.net.Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 150)
                }
                true
            }.getOrDefault(false)
            if (open) return
            delay(50L)
        }
    }
}
