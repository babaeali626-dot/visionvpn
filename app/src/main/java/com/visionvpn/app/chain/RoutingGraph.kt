package com.visionvpn.app.chain

import com.visionvpn.app.core.ConfigBuilder
import com.visionvpn.app.data.model.ChainConfig
import com.visionvpn.app.data.model.ChainExitType
import com.visionvpn.app.data.model.ProxyProfile
import com.visionvpn.app.data.model.ProxyProtocol
import com.visionvpn.app.data.model.TorBridgeMode
import com.visionvpn.app.data.model.TorChainMode

data class NodeCapabilities(
    val supportsTcp: Boolean,
    val supportsUdp: Boolean,
    val requiresUdpTransport: Boolean,
    val supportsUpstreamSocks: Boolean,
    val canBeStandalone: Boolean
)

sealed class RoutingNode {
    abstract val index: Int
    abstract val tag: String
    abstract val displayName: String
    abstract val upstreamTag: String?
    abstract val capabilities: NodeCapabilities

    open val upstreamLoopbackPort: Int? = null
    open val upstreamInboundTag: String? = null

    data class ProxyNode(
        override val index: Int,
        override val tag: String,
        val profile: ProxyProfile,
        override val upstreamTag: String?,
        val isCustomExit: Boolean = false
    ) : RoutingNode() {
        override val displayName: String = profile.name
        override val capabilities: NodeCapabilities = deriveProxyCapabilities(profile)

        companion object {
            /**
             * Conservative per-profile capability derivation based on protocol and transport.
             * Native UDP protocols (WireGuard/Hysteria2/TUIC) and direct TCP stream proxies
             * (VLESS/VMess/Trojan/Shadowsocks over raw TCP) support UDP relay in Sing-box v1.10.7.
             * SOCKS5 (unconfirmed UDP ASSOCIATE) and HTTP/CDN transports (ws/grpc/httpupgrade)
             * are conservatively treated as TCP-only so nested WireGuard/WARP hops are rejected.
             */
            fun deriveProxyCapabilities(profile: ProxyProfile): NodeCapabilities {
                val net = profile.network.trim().lowercase().ifBlank { "tcp" }
                val supportsUdp = when (profile.protocol) {
                    ProxyProtocol.WIREGUARD,
                    ProxyProtocol.HYSTERIA2,
                    ProxyProtocol.TUIC -> true
                    ProxyProtocol.SOCKS5 -> false
                    ProxyProtocol.VLESS,
                    ProxyProtocol.VMESS,
                    ProxyProtocol.TROJAN,
                    ProxyProtocol.SHADOWSOCKS -> net == "tcp"
                }
                val requiresUdp = profile.protocol in setOf(
                    ProxyProtocol.WIREGUARD,
                    ProxyProtocol.HYSTERIA2,
                    ProxyProtocol.TUIC
                )
                return NodeCapabilities(
                    supportsTcp = true,
                    supportsUdp = supportsUdp,
                    requiresUdpTransport = requiresUdp,
                    supportsUpstreamSocks = true,
                    canBeStandalone = true
                )
            }
        }
    }

    data class WarpNode(
        override val index: Int,
        override val tag: String,
        override val upstreamTag: String?
    ) : RoutingNode() {
        override val displayName: String = "Cloudflare WARP"
        override val capabilities: NodeCapabilities = NodeCapabilities(
            supportsTcp = true,
            supportsUdp = true,
            requiresUdpTransport = true,
            supportsUpstreamSocks = false,
            canBeStandalone = true
        )
    }

    data class TorNode(
        override val index: Int,
        override val tag: String,
        val socksPort: Int,
        val dnsPort: Int,
        override val upstreamTag: String?,
        override val upstreamLoopbackPort: Int?,
        override val upstreamInboundTag: String?
    ) : RoutingNode() {
        override val displayName: String = if (index == 0) "Tor Entry" else "Tor Exit"
        override val capabilities: NodeCapabilities = NodeCapabilities(
            supportsTcp = true,
            supportsUdp = false,
            requiresUdpTransport = false,
            supportsUpstreamSocks = true,
            canBeStandalone = true
        )
    }

    data class PsiphonNode(
        override val index: Int,
        override val tag: String,
        val localSocksPort: Int,
        val localHttpPort: Int,
        val requestedRegion: String,
        override val upstreamTag: String?,
        override val upstreamLoopbackPort: Int?,
        override val upstreamInboundTag: String?
    ) : RoutingNode() {
        override val displayName: String = "Psiphon ($requestedRegion)"
        override val capabilities: NodeCapabilities = NodeCapabilities(
            supportsTcp = true,
            supportsUdp = false,
            requiresUdpTransport = false,
            supportsUpstreamSocks = true,
            canBeStandalone = true
        )
    }
}

data class RoutingGraph(
    val nodes: List<RoutingNode>,
    val chainConfig: ChainConfig
) {
    val finalOutboundTag: String
        get() = nodes.last().tag

    /**
     * Authoritative effective final exit provider derived strictly from `nodes.last()`.
     * Prevents misreporting Tor as the final exit when `TOR_ENTRY_TO_SERVER_EXIT` is active.
     */
    val effectiveFinalExitProvider: ChainExitType
        get() = when (val last = nodes.last()) {
            is RoutingNode.TorNode -> ChainExitType.TOR
            is RoutingNode.PsiphonNode -> ChainExitType.PSIPHON
            is RoutingNode.WarpNode -> ChainExitType.CLOUDFLARE_WARP
            is RoutingNode.ProxyNode -> if (last.isCustomExit) ChainExitType.CUSTOM_SERVER else ChainExitType.NONE
        }

    val includesTor: Boolean
        get() = nodes.any { it is RoutingNode.TorNode }

    val includesPsiphon: Boolean
        get() = nodes.any { it is RoutingNode.PsiphonNode }

    val isPrivacyExitMode: Boolean
        get() = effectiveFinalExitProvider == ChainExitType.TOR ||
            effectiveFinalExitProvider == ChainExitType.PSIPHON

    fun describeChain(): List<String> = nodes.map { it.displayName }

    companion object {
        const val LOOPBACK_UPSTREAM_PORT_BASE = 20800

        fun build(
            entryProfile: ProxyProfile?,
            chainConfig: ChainConfig,
            allProfiles: List<ProxyProfile>
        ): Result<RoutingGraph> = runCatching {
            val hops = chainConfig.orderedExitHops()

            if (entryProfile == null) {
                val standaloneExit = hops.firstOrNull()
                require(
                    hops.size == 1 &&
                        (standaloneExit == ChainExitType.TOR ||
                            standaloneExit == ChainExitType.PSIPHON ||
                            standaloneExit == ChainExitType.CLOUDFLARE_WARP)
                ) {
                    "Select an Entry Server profile first (or choose standalone Tor, Psiphon, or WARP exit)."
                }
            }

            require(hops.distinct().size == hops.size) {
                "Duplicate hops in chain configuration are not allowed."
            }

            if (ChainExitType.CLOUDFLARE_WARP in hops) {
                require(chainConfig.warpConfig.isRegistered) {
                    "WARP is blocked until a complete provider-supplied WireGuard configuration is available. " +
                        "Automatic enrollment and fallback keys are disabled."
                }
            }

            val torAsEntry = entryProfile != null &&
                hops.contains(ChainExitType.TOR) &&
                chainConfig.torConfig.mode == TorChainMode.TOR_ENTRY_TO_SERVER_EXIT

            val orderedSteps = mutableListOf<Any>()
            if (torAsEntry) {
                orderedSteps += ChainExitType.TOR
                orderedSteps += entryProfile!!
                hops.filter { it != ChainExitType.TOR }.forEach { orderedSteps += it }
            } else {
                if (entryProfile != null) orderedSteps += entryProfile
                hops.forEach { orderedSteps += it }
            }

            require(orderedSteps.isNotEmpty()) { "No proxy or exit provider configured." }

            val nodes = mutableListOf<RoutingNode>()
            val seenProfileIds = mutableSetOf<String>()
            val allocatedPorts = mutableSetOf(ConfigBuilder.CLASH_API_PORT, ConfigBuilder.PROBE_PROXY_PORT)

            fun claimPort(port: Int, owner: String) {
                require(port in 1024..65535) { "Invalid loopback port $port for $owner." }
                require(allocatedPorts.add(port)) {
                    "Loopback port collision on 127.0.0.1:$port ($owner)."
                }
            }

            orderedSteps.forEachIndexed { idx, step ->
                val prevNode = nodes.lastOrNull()
                val prevTag = prevNode?.tag

                val nextNode: RoutingNode = when (step) {
                    is ProxyProfile -> {
                        require(seenProfileIds.add(step.id)) {
                            "Circular route detected: '${step.name}' appears multiple times in the chain."
                        }
                        RoutingNode.ProxyNode(
                            index = idx,
                            tag = "hop-$idx-entry",
                            profile = step,
                            upstreamTag = prevTag,
                            isCustomExit = false
                        )
                    }
                    ChainExitType.CLOUDFLARE_WARP -> {
                        RoutingNode.WarpNode(
                            index = idx,
                            tag = "hop-$idx-warp",
                            upstreamTag = prevTag
                        )
                    }
                    ChainExitType.TOR -> {
                        val torCfg = chainConfig.torConfig
                        if (prevTag != null && torCfg.effectiveBridgeMode() == TorBridgeMode.SNOWFLAKE_TRANSPORT) {
                            error("Unsupported topology: Snowflake WebRTC transport cannot be chained behind an upstream SOCKS proxy.")
                        }
                        claimPort(torCfg.socksPort, "Tor SOCKS")
                        claimPort(torCfg.dnsPort, "Tor DNS")
                        val loopbackPort = if (prevTag != null) {
                            (LOOPBACK_UPSTREAM_PORT_BASE + idx).also { claimPort(it, "Tor Upstream") }
                        } else null
                        RoutingNode.TorNode(
                            index = idx,
                            tag = "hop-$idx-tor",
                            socksPort = torCfg.socksPort,
                            dnsPort = torCfg.dnsPort,
                            upstreamTag = prevTag,
                            upstreamLoopbackPort = loopbackPort,
                            upstreamInboundTag = if (prevTag != null) "mixed-upstream-$idx" else null
                        )
                    }
                    ChainExitType.PSIPHON -> {
                        val psiCfg = chainConfig.psiphonConfig
                        claimPort(psiCfg.localSocksPort, "Psiphon SOCKS")
                        claimPort(psiCfg.localHttpPort, "Psiphon HTTP")
                        val loopbackPort = if (prevTag != null) {
                            (LOOPBACK_UPSTREAM_PORT_BASE + idx).also { claimPort(it, "Psiphon Upstream") }
                        } else null
                        RoutingNode.PsiphonNode(
                            index = idx,
                            tag = "hop-$idx-psiphon",
                            localSocksPort = psiCfg.localSocksPort,
                            localHttpPort = psiCfg.localHttpPort,
                            requestedRegion = psiCfg.regionCode,
                            upstreamTag = prevTag,
                            upstreamLoopbackPort = loopbackPort,
                            upstreamInboundTag = if (prevTag != null) "mixed-upstream-$idx" else null
                        )
                    }
                    ChainExitType.CUSTOM_SERVER -> {
                        val exitProfile = allProfiles.firstOrNull { it.id == chainConfig.customExitProfileId }
                            ?: error("Select a second server in Settings or Chain Manager for Custom Exit.")
                        require(seenProfileIds.add(exitProfile.id)) {
                            "Circular route detected: Entry and Custom Exit servers must be different."
                        }
                        RoutingNode.ProxyNode(
                            index = idx,
                            tag = "hop-$idx-custom",
                            profile = exitProfile,
                            upstreamTag = prevTag,
                            isCustomExit = true
                        )
                    }
                    else -> error("Unsupported chain step: $step")
                }

                // Full capability-based directed edge validation
                if (prevNode == null && !nextNode.capabilities.canBeStandalone) {
                    error("Unsupported topology: ${nextNode.displayName} cannot run standalone without an upstream hop.")
                }
                if (prevNode != null) {
                    if (nextNode.upstreamLoopbackPort != null && !nextNode.capabilities.supportsUpstreamSocks) {
                        error("Unsupported topology: ${nextNode.displayName} does not support upstream SOCKS chaining.")
                    }
                    if (nextNode.capabilities.requiresUdpTransport && !prevNode.capabilities.supportsUdp) {
                        error(
                            "Unsupported topology: ${nextNode.displayName} requires UDP transport, " +
                                "which cannot traverse TCP-only upstream ${prevNode.displayName}."
                        )
                    }
                    if (!nextNode.capabilities.requiresUdpTransport && !prevNode.capabilities.supportsTcp) {
                        error(
                            "Unsupported topology: ${nextNode.displayName} requires TCP transport, " +
                                "which is not supported by upstream ${prevNode.displayName}."
                        )
                    }
                }

                nodes += nextNode
            }

            RoutingGraph(nodes = nodes, chainConfig = chainConfig)
        }
    }
}
