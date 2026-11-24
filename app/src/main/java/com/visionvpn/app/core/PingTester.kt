package com.visionvpn.app.core

import com.visionvpn.app.data.model.ProxyProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object PingTester {

    private const val DEFAULT_TIMEOUT_MS = 3500

    suspend fun measureTcpLatency(host: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Int =
        withContext(Dispatchers.IO) {
            runCatching {
                val startNs = System.nanoTime()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                }
                ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
            }.getOrDefault(-1)
        }

    suspend fun testAll(
        profiles: List<ProxyProfile>,
        maxConcurrency: Int = 8,
        onResult: (profileId: String, latencyMs: Int) -> Unit
    ) = coroutineScope {
        val semaphore = Semaphore(maxConcurrency)
        profiles.map { profile ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val latency = measureTcpLatency(profile.server, profile.port)
                    onResult(profile.id, latency)
                }
            }
        }.awaitAll()
    }
}
