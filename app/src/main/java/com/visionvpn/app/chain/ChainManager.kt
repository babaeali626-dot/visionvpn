package com.visionvpn.app.chain

import android.content.Context
import com.visionvpn.app.data.model.ChainConfig
import com.visionvpn.app.data.model.ProxyProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Validates the configured routing graph and starts/stops local daemon engines (Tor, Psiphon)
 * in strict dependency order while preserving coroutine [CancellationException]. WARP is never
 * silently enrolled here; graph validation requires a complete provider-supplied configuration.
 */
class ChainManager(
    private val context: Context,
    private val localEngineManager: LocalEngineManager
) {
    suspend fun prepareRoutingGraph(
        entryProfile: ProxyProfile?,
        rawChainConfig: ChainConfig,
        allProfiles: List<ProxyProfile>
    ): Result<RoutingGraph> {
        return try {
            Result.success(
                RoutingGraph.build(
                    entryProfile = entryProfile,
                    chainConfig = rawChainConfig,
                    allProfiles = allProfiles
                ).getOrThrow()
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Starts standalone or entry-hop local daemon engines (those without an upstream Sing-box
     * loopback port) BEFORE Sing-box opens TUN so provisioning/bootstrap failures fail fast.
     */
    suspend fun startPreCoreLocalEngines(graph: RoutingGraph): Result<Unit> {
        return startMatchingLocalEngines(graph) { node -> node.upstreamLoopbackPort == null }
    }

    /**
     * Starts chained local daemon engines (those whose `UpstreamProxyURL` / `Socks5Proxy`
     * targets a Sing-box `mixed-upstream-N` loopback inbound) AFTER Hiddify Core is running.
     */
    suspend fun startPostCoreLocalEngines(graph: RoutingGraph): Result<Unit> {
        return startMatchingLocalEngines(graph) { node -> node.upstreamLoopbackPort != null }
    }

    /**
     * Starts all local daemon engines in exact sequential graph order.
     * Cleans up all previously started engines if any stage fails or is cancelled.
     */
    suspend fun startLocalEnginesForGraph(graph: RoutingGraph): Result<Unit> {
        return startMatchingLocalEngines(graph) { true }
    }

    private suspend fun startMatchingLocalEngines(
        graph: RoutingGraph,
        predicate: (RoutingNode) -> Boolean
    ): Result<Unit> {
        return try {
            for (node in graph.nodes) {
                if (!predicate(node)) continue
                when (node) {
                    is RoutingNode.TorNode -> {
                        val torEngine = TorEngine(
                            context = context,
                            config = graph.chainConfig.torConfig,
                            upstreamSocksPort = node.upstreamLoopbackPort
                        )
                        localEngineManager.startEngine(torEngine, readyTimeoutMs = 60_000L).getOrThrow()
                    }
                    is RoutingNode.PsiphonNode -> {
                        val psiphonEngine = PsiphonEngine(
                            context = context,
                            config = graph.chainConfig.psiphonConfig,
                            upstreamSocksPort = node.upstreamLoopbackPort
                        )
                        localEngineManager.startEngine(psiphonEngine, readyTimeoutMs = 45_000L).getOrThrow()
                    }
                    else -> Unit
                }
            }
            Result.success(Unit)
        } catch (ce: CancellationException) {
            withContext(NonCancellable) { localEngineManager.stopAll() }
            throw ce
        } catch (e: Exception) {
            withContext(NonCancellable) { localEngineManager.stopAll() }
            Result.failure(e)
        }
    }

    suspend fun stopLocalEngines() {
        localEngineManager.stopAll()
    }
}
