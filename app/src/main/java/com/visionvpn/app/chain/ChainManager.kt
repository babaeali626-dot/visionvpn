package com.visionvpn.app.chain

import android.content.Context
import com.visionvpn.app.data.model.ChainConfig
import com.visionvpn.app.data.model.ChainExitType
import com.visionvpn.app.data.model.ProxyProfile

/**
 * Validates multi-hop routing graphs, ensures Cloudflare WARP registration when needed,
 * and starts/stops local daemon engines (Tor, Psiphon) in strict dependency order.
 */
class ChainManager(
    private val context: Context,
    private val localEngineManager: LocalEngineManager,
    private val warpManager: WarpManager
) {
    suspend fun prepareRoutingGraph(
        entryProfile: ProxyProfile?,
        rawChainConfig: ChainConfig,
        allProfiles: List<ProxyProfile>,
        onWarpConfigUpdated: suspend (ChainConfig) -> Unit
    ): Result<RoutingGraph> = runCatching {
        var effectiveConfig = rawChainConfig
        val hops = effectiveConfig.orderedExitHops()

        // Auto-register Cloudflare WARP keys if WARP is in the chain and unregistered
        if (hops.contains(ChainExitType.CLOUDFLARE_WARP) && !effectiveConfig.warpConfig.isRegistered) {
            val warpReg = warpManager.registerNewAccount(
                existingEndpointHost = effectiveConfig.warpConfig.endpointHost,
                existingEndpointPort = effectiveConfig.warpConfig.endpointPort
            ).getOrThrow()
            effectiveConfig = effectiveConfig.copy(warpConfig = warpReg)
            onWarpConfigUpdated(effectiveConfig)
        }

        RoutingGraph.build(
            entryProfile = entryProfile,
            chainConfig = effectiveConfig,
            allProfiles = allProfiles
        ).getOrThrow()
    }

    /**
     * Starts local daemon engines (Tor, Psiphon) in exact sequential graph order,
     * wiring each engine to its dedicated per-hop loopback upstream port.
     * Cleans up all previously started engines if any stage fails.
     */
    suspend fun startLocalEnginesForGraph(graph: RoutingGraph): Result<Unit> = runCatching {
        try {
            for (node in graph.nodes) {
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
        } catch (t: Throwable) {
            localEngineManager.stopAll()
            throw t
        }
    }

    suspend fun stopLocalEngines() {
        localEngineManager.stopAll()
    }
}
