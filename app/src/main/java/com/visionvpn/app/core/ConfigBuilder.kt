package com.visionvpn.app.core

import com.visionvpn.app.chain.RoutingGraph
import com.visionvpn.app.chain.RoutingNode
import com.visionvpn.app.data.model.AppSettings
import com.visionvpn.app.data.model.BalancerStrategy
import com.visionvpn.app.data.model.Ipv6Policy
import com.visionvpn.app.data.model.ProxyProfile
import com.visionvpn.app.data.model.ProxyProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * Generates Hiddify Core v5 JSON configurations directly from a validated [RoutingGraph],
 * as well as isolated headless latency-probe configurations for real end-to-end proxy testing.
 *
 * Security guarantees:
 * - Unconditional privacy isolation whenever Tor or Psiphon is the selected final exit:
 *   no LAN bypass, no regional (.ir) bypass, no direct user DNS fallback.
 * - Blocks raw UDP on TUN unconditionally when Tor OR Psiphon is the final privacy exit
 *   (neither local SOCKS5 daemon tunnels raw UDP), preventing silent UDP leakage.
 * - Bootstrap DNS is narrowly scoped strictly to explicit entry-proxy/WARP hostnames and
 *   omitted completely in standalone Tor/Psiphon mode.
 * - Exposes a loopback-only `mixed-probe-in` inbound routed strictly to `finalTag` so
 *   connectivity verification traverses the active tunnel rather than the app's VPN-excluded sockets.
 */
class ConfigBuilder {

    private val prettyJson = Json { prettyPrint = true }

    companion object {
        const val TAG_DIRECT = "direct"
        const val TAG_BLOCK = "block"
        const val TAG_DNS_OUT = "dns-out"
        const val TAG_PROBE_IN = "mixed-probe-in"
        const val CLASH_API_PORT = 9090
        const val PROBE_PROXY_PORT = 10808
        const val BALANCER_GROUP_TAG = "vision-balancer"
    }

    /**
     * Validates profile security and protocol parameters before emitting Hiddify Core v5 JSON.
     * Prevents incomplete REALITY configs from silently downgrading to partial/misleading TLS.
     */
    /**
     * Supported uTLS fingerprints in Hiddify Core v5 (`common/tls/utls_client.go`).
     */
    private val supportedUtlsFingerprints = setOf(
        "chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized"
    )

    /**
     * Decodes a REALITY X25519 public key (`pbk`) using URL-safe Base64 (matching Hiddify Core v5
     * `base64.RawURLEncoding`, rejecting whitespace and non-URL-safe characters) and verifies
     * that it decodes to exactly 32 bytes.
     */
    internal fun isValidRealityPublicKey(rawKey: String): Boolean {
        val trimmed = rawKey
        if (trimmed.isEmpty() || trimmed != trimmed.trim()) return false
        // Go's base64.RawURLEncoding used by Sing-box rejects padding and whitespace. Match
        // that contract exactly instead of silently normalizing an invalid imported key.
        if (!Regex("^[A-Za-z0-9_-]+$").matches(trimmed)) return false
        val decoded = runCatching { Base64.getUrlDecoder().decode(trimmed) }.getOrNull() ?: return false
        return decoded.size == 32
    }

    /**
     * Validates a REALITY `short_id` (`sid`): empty (when server permits) or even-length
     * hexadecimal string up to 8 bytes (16 hex characters), matching Hiddify Core v5 hex decode.
     */
    internal fun isValidRealityShortId(rawShortId: String): Boolean {
        val trimmed = rawShortId
        if (trimmed.isEmpty()) return true
        return trimmed == trimmed.trim() && trimmed.length <= 16 && trimmed.length % 2 == 0 &&
            Regex("^[0-9a-fA-F]+$").matches(trimmed)
    }

    fun validateProfile(profile: ProxyProfile): Result<Unit> = runCatching {
        require(profile.server.isNotBlank()) { "Profile '${profile.name}' is missing a server hostname or IP." }
        require(profile.port in 1..65535) { "Profile '${profile.name}' has an invalid port: ${profile.port}." }

        val sec = profile.security.trim().lowercase()
        val net = profile.network.trim().lowercase().ifBlank { "tcp" }

        if (sec == "reality") {
            require(isValidRealityPublicKey(profile.realityPublicKey)) {
                "Profile '${profile.name}' declares security=reality but public_key (pbk) is not a valid 32-byte Base64URL X25519 key."
            }
            require(isValidRealityShortId(profile.realityShortId)) {
                "Profile '${profile.name}' has an invalid REALITY short_id (must be empty or even-length hex up to 16 chars / 8 bytes)."
            }
            val effectiveSni = profile.sni.trim()
            require(effectiveSni.isNotBlank()) {
                "Profile '${profile.name}' declares security=reality and requires a non-blank SNI (server_name)."
            }
            val fp = profile.fingerprint.trim().lowercase()
            require(fp.isNotEmpty() && fp in supportedUtlsFingerprints) {
                "Profile '${profile.name}' requires a supported, non-empty uTLS fingerprint for REALITY."
            }
            require(net !in setOf("ws", "httpupgrade")) {
                "Profile '${profile.name}' uses REALITY with incompatible transport '$net'."
            }
        }

        if (profile.protocol == ProxyProtocol.WIREGUARD) {
            require(profile.wgPrivateKey.isNotBlank() && profile.wgPublicKey.isNotBlank()) {
                "WireGuard profile '${profile.name}' is missing private_key or peer_public_key."
            }
        }
    }


    fun buildSingBoxConfig(
        graph: RoutingGraph,
        settings: AppSettings,
        balancerProfiles: List<ProxyProfile> = emptyList()
    ): String {
        val outbounds = mutableListOf<JsonObject>()
        val enforceStrictPrivacy = graph.isPrivacyExitMode
        require(!(settings.tlsFragmentEnabled && settings.tlsPaddingEnabled)) {
            "TLS fragmentation and TLS padding cannot be enabled at the same time. Choose one technique."
        }
        val selectedBalancerProfiles = if (
            settings.balancerEnabled &&
            graph.nodes.size == 1 && graph.nodes.single() is RoutingNode.ProxyNode
        ) {
            balancerProfiles
                .distinctBy { it.id }
                .filter { it.id in settings.balancerProfileIds }
                .onEach { validateProfile(it).getOrThrow() }
                .take(settings.balancerCandidateCount.coerceIn(2, 100))
        } else emptyList()
        // Stable, deterministic tags: never expose profile IDs in the generated native config.
        val balancerTagByProfileId = selectedBalancerProfiles.mapIndexed { index, profile ->
            profile.id to "balancer-proxy-$index"
        }.toMap()
        val finalTag = if (selectedBalancerProfiles.size >= 2) BALANCER_GROUP_TAG else graph.finalOutboundTag

        if (selectedBalancerProfiles.size >= 2) {
            selectedBalancerProfiles.forEach { profile ->
                outbounds += buildProfileOutbound(
                    tag = balancerTagByProfileId.getValue(profile.id),
                    profile = profile,
                    detourTag = null,
                    enableEchAuto = settings.echAutoEnabled,
                    settings = settings
                )
            }
            outbounds += buildJsonObject {
                put("type", "balancer")
                put("tag", BALANCER_GROUP_TAG)
                putJsonArray("outbounds") { selectedBalancerProfiles.forEach { add(JsonPrimitive(balancerTagByProfileId.getValue(it.id))) } }
                put("strategy", settings.balancerStrategy.coreValue)
                // Health-check URLs, interval and idle timeout belong to the global
                // experimental.monitoring block below, not to the balancer object.
                put("interrupt_exist_connections", false)
            }
        } else {
            for (node in graph.nodes) {
            when (node) {
                is RoutingNode.ProxyNode -> {
                    outbounds += buildProfileOutbound(
                        tag = node.tag,
                        profile = node.profile,
                        detourTag = node.upstreamTag,
                        enableEchAuto = settings.echAutoEnabled,
                        settings = settings
                    )
                }
                is RoutingNode.WarpNode -> {
                    outbounds += buildWarpOutbound(
                        tag = node.tag,
                        graph = graph,
                        detourTag = node.upstreamTag
                    )
                }
                is RoutingNode.TorNode -> {
                    outbounds += buildLocalSocksOutbound(
                        tag = node.tag,
                        port = node.socksPort
                    )
                }
                is RoutingNode.PsiphonNode -> {
                    outbounds += buildLocalSocksOutbound(
                        tag = node.tag,
                        port = node.localSocksPort
                    )
                }
            }
            }
        }

        outbounds += buildJsonObject {
            put("type", "direct")
            put("tag", TAG_DIRECT)
        }
        outbounds += buildJsonObject {
            put("type", "block")
            put("tag", TAG_BLOCK)
        }
        outbounds += buildJsonObject {
            put("type", "dns")
            put("tag", TAG_DNS_OUT)
        }

        val root = buildJsonObject {
            putJsonObject("log") {
                put("level", settings.coreLogLevel.trim().lowercase().takeIf { it in setOf("error", "warn", "info", "debug", "trace") } ?: "info")
                put("timestamp", true)
            }
            put("dns", buildDnsConfig(graph, settings, finalTag, enforceStrictPrivacy, selectedBalancerProfiles))
            put("inbounds", buildInbounds(graph, settings))
            put("outbounds", JsonArray(outbounds))
            put("route", buildRouteConfig(graph, settings, finalTag, enforceStrictPrivacy))
            putJsonObject("experimental") {
                putJsonObject("monitoring") {
                    put("interval", "${settings.balancerIntervalSeconds.coerceIn(15, 3600)}s")
                    putJsonArray("urls") {
                        add(JsonPrimitive(settings.latencyTestUrl))
                        add(JsonPrimitive("https://cp.cloudflare.com/generate_204"))
                    }
                    put("url_test_timeout", "${settings.latencyProbeTimeoutSeconds.coerceIn(1, 30)}s")
                    putJsonArray("on_demand_timeouts") {
                        add(JsonPrimitive("1s"))
                        add(JsonPrimitive("3s"))
                        add(JsonPrimitive("10s"))
                    }
                    put("idle_timeout", "10m")
                }
                putJsonObject("clash_api") {
                    put("external_controller", "127.0.0.1:$CLASH_API_PORT")
                }
                putJsonObject("cache_file") {
                    put("enabled", true)
                }
            }
        }

        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * Builds one no-TUN Libbox instance containing one independently routed loopback inbound
     * and outbound per candidate. This is the safe basis for real concurrent HTTPS probes:
     * one Go runtime/service owns all outbounds, while bounded HTTP probes run in parallel.
     */
    fun buildLatencyProbeBatchConfig(
        profiles: List<ProxyProfile>,
        localPorts: List<Int>,
        settings: AppSettings = AppSettings()
    ): String {
        require(profiles.isNotEmpty()) { "At least one profile is required for a batch probe." }
        require(profiles.size == localPorts.size) { "Every probe profile must have one reserved loopback port." }
        require(profiles.size <= 100) { "A probe batch is limited to 100 profiles." }
        profiles.forEach { validateProfile(it).getOrThrow() }
        require(localPorts.distinct().size == localPorts.size) { "Probe loopback ports must be unique." }
        localPorts.forEach { require(it in 1024..65535) { "Invalid probe loopback port." } }

        val candidateTags = profiles.indices.map { "probe-candidate-$it" }
        val remoteDnsTags = profiles.indices.map { "dns-remote-$it" }
        val bootstrapDnsTags = profiles.indices.map { "dns-bootstrap-$it" }
        val root = buildJsonObject {
            putJsonObject("log") { put("level", "error") }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    profiles.forEachIndexed { index, profile ->
                        add(buildJsonObject {
                            put("tag", remoteDnsTags[index])
                            put("address", settings.remoteDns)
                            put("detour", candidateTags[index])
                            put("strategy", "ipv4_only")
                        })
                        if (!isIpLiteral(profile.server)) {
                            add(buildJsonObject {
                                put("tag", bootstrapDnsTags[index])
                                put("address", settings.directDns)
                                put("detour", TAG_DIRECT)
                                put("strategy", "ipv4_only")
                            })
                        }
                    }
                }
                putJsonArray("rules") {
                    // Resolve each proxy server with the narrow bootstrap resolver, otherwise
                    // its own detoured DNS request could loop back into that not-yet-resolved proxy.
                    profiles.forEachIndexed { index, profile ->
                        if (!isIpLiteral(profile.server)) {
                            add(buildJsonObject {
                                putJsonArray("domain") { add(JsonPrimitive(profile.server)) }
                                put("server", bootstrapDnsTags[index])
                            })
                        }
                    }
                    // Resolve the actual health-check destination through the same candidate
                    // that carries its HTTPS request. This avoids testing all candidates with
                    // candidate zero's DNS path.
                    profiles.indices.forEach { index ->
                        add(buildJsonObject {
                            putJsonArray("inbound") { add(JsonPrimitive("probe-in-$index")) }
                            put("server", remoteDnsTags[index])
                        })
                    }
                }
                put("final", remoteDnsTags.first())
                put("strategy", "ipv4_only")
                put("independent_cache", true)
            }
            putJsonArray("inbounds") {
                profiles.indices.forEach { index ->
                    add(buildJsonObject {
                        put("type", "mixed")
                        put("tag", "probe-in-$index")
                        put("listen", "127.0.0.1")
                        put("listen_port", localPorts[index])
                    })
                }
            }
            putJsonArray("outbounds") {
                profiles.forEachIndexed { index, profile ->
                    add(buildProfileOutbound(
                        tag = candidateTags[index],
                        profile = profile,
                        detourTag = null,
                        enableEchAuto = settings.echAutoEnabled,
                        settings = settings
                    ))
                }
                add(buildJsonObject { put("type", "direct"); put("tag", TAG_DIRECT) })
                add(buildJsonObject { put("type", "dns"); put("tag", TAG_DNS_OUT) })
            }
            putJsonObject("route") {
                putJsonArray("rules") {
                    add(buildJsonObject {
                        put("protocol", "dns")
                        put("outbound", TAG_DNS_OUT)
                    })
                    profiles.indices.forEach { index ->
                        add(buildJsonObject {
                            put("inbound", "probe-in-$index")
                            put("outbound", candidateTags[index])
                        })
                    }
                }
                put("final", candidateTags.first())
            }
        }
        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * Builds a minimal, no-TUN Hiddify Core v5 configuration with a single loopback `mixed`
     * inbound on [localPort] that routes all test requests exclusively through [profile].
     * Used for real end-to-end proxy latency testing without touching Android's TUN interface.
     */
    fun buildLatencyProbeConfig(
        profile: ProxyProfile,
        localPort: Int,
        settings: AppSettings = AppSettings()
    ): String {
        val candidateTag = "probe-candidate"
        val outbounds = listOf(
            buildProfileOutbound(
                tag = candidateTag,
                profile = profile,
                detourTag = null,
                enableEchAuto = settings.echAutoEnabled,
                settings = settings
            ),
            buildJsonObject {
                put("type", "direct")
                put("tag", TAG_DIRECT)
            },
            buildJsonObject {
                put("type", "dns")
                put("tag", TAG_DNS_OUT)
            }
        )

        val root = buildJsonObject {
            putJsonObject("log") {
                put("level", "error")
            }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    add(buildJsonObject {
                        put("tag", "dns-remote")
                        put("address", settings.remoteDns)
                        put("detour", candidateTag)
                        put("strategy", "ipv4_only")
                    })
                    if (!isIpLiteral(profile.server)) {
                        add(buildJsonObject {
                            put("tag", "dns-bootstrap")
                            put("address", settings.directDns)
                            put("detour", TAG_DIRECT)
                            put("strategy", "ipv4_only")
                        })
                    }
                }
                putJsonArray("rules") {
                    if (!isIpLiteral(profile.server)) {
                        add(buildJsonObject {
                            putJsonArray("domain") { add(JsonPrimitive(profile.server)) }
                            put("server", "dns-bootstrap")
                        })
                    }
                }
                put("final", "dns-remote")
                put("strategy", "ipv4_only")
            }
            putJsonArray("inbounds") {
                add(buildJsonObject {
                    put("type", "mixed")
                    put("tag", TAG_PROBE_IN)
                    put("listen", "127.0.0.1")
                    put("listen_port", localPort)
                })
            }
            put("outbounds", JsonArray(outbounds))
            putJsonObject("route") {
                putJsonArray("rules") {
                    add(buildJsonObject {
                        put("protocol", "dns")
                        put("outbound", TAG_DNS_OUT)
                    })
                }
                put("final", candidateTag)
            }
        }
        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }

    private fun buildDnsConfig(
        graph: RoutingGraph,
        settings: AppSettings,
        finalTag: String,
        enforceStrictPrivacy: Boolean,
        additionalBootstrapProfiles: List<ProxyProfile> = emptyList()
    ): JsonObject = buildJsonObject {
        val effectiveStrategy = if (settings.ipv6Policy == Ipv6Policy.BLOCK_IPV6) {
            "ipv4_only"
        } else {
            settings.dnsStrategy.singBoxValue
        }

        // Preserve user's configured remote DNS (for TCP-only Tor/Psiphon exits, ensure TCP transport)
        val remoteDnsAddress = if (enforceStrictPrivacy &&
            !settings.remoteDns.startsWith("https://") &&
            !settings.remoteDns.startsWith("tls://") &&
            !settings.remoteDns.startsWith("tcp://")
        ) {
            "tcp://${settings.remoteDns}"
        } else {
            settings.remoteDns
        }

        val bootstrapDomains = buildList {
            graph.nodes.forEach { node ->
                when (node) {
                    is RoutingNode.ProxyNode -> {
                        if (!isIpLiteral(node.profile.server)) add(node.profile.server)
                    }
                    is RoutingNode.WarpNode -> {
                        val host = graph.chainConfig.warpConfig.endpointHost
                        if (!isIpLiteral(host)) add(host)
                    }
                    else -> Unit
                }
            }
        }.plus(additionalBootstrapProfiles.filter { !isIpLiteral(it.server) }.map { it.server }).distinct()

        putJsonArray("servers") {
            add(buildJsonObject {
                put("tag", "dns-remote")
                put("address", remoteDnsAddress)
                put("detour", finalTag)
                put("strategy", effectiveStrategy)
            })
            if (bootstrapDomains.isNotEmpty() || (!enforceStrictPrivacy && settings.bypassIran && !settings.strictDnsLeakProtection)) {
                add(buildJsonObject {
                    put("tag", "dns-bootstrap")
                    put("address", settings.directDns)
                    put("detour", TAG_DIRECT)
                    put("strategy", effectiveStrategy)
                })
            }
            add(buildJsonObject {
                put("tag", "dns-block")
                put("address", "rcode://refused")
            })
            if (settings.enableFakeDns) {
                add(buildJsonObject {
                    put("tag", "dns-fakeip")
                    put("address", "fakeip")
                })
            }
        }

        putJsonArray("rules") {
            if (bootstrapDomains.isNotEmpty()) {
                add(buildJsonObject {
                    putJsonArray("domain") {
                        bootstrapDomains.forEach { add(JsonPrimitive(it)) }
                    }
                    put("server", "dns-bootstrap")
                })
            }
            if (settings.ipv6Policy == Ipv6Policy.BLOCK_IPV6) {
                add(buildJsonObject {
                    putJsonArray("query_type") { add(JsonPrimitive("AAAA")) }
                    put("server", "dns-block")
                })
            }
            if (settings.bypassIran && !enforceStrictPrivacy && !settings.strictDnsLeakProtection) {
                add(buildJsonObject {
                    putJsonArray("domain_suffix") {
                        add(JsonPrimitive(".ir"))
                    }
                    put("server", "dns-bootstrap")
                })
            }
            if (settings.enableFakeDns) {
                add(buildJsonObject {
                    putJsonArray("query_type") {
                        add(JsonPrimitive("A"))
                        if (settings.ipv6Policy == Ipv6Policy.TUNNEL_IPV6) {
                            add(JsonPrimitive("AAAA"))
                        }
                    }
                    put("server", "dns-fakeip")
                })
            }
        }

        put("final", "dns-remote")
        put("strategy", effectiveStrategy)
        put("independent_cache", true)

        if (settings.enableFakeDns) {
            putJsonObject("fakeip") {
                put("enabled", true)
                put("inet4_range", "198.18.0.0/15")
                if (settings.ipv6Policy == Ipv6Policy.TUNNEL_IPV6) {
                    put("inet6_range", "fc00::/18")
                }
            }
        }
    }

    private fun buildInbounds(graph: RoutingGraph, settings: AppSettings): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("type", "tun")
            put("tag", "tun-in")
            put("interface_name", "tun0")
            put("inet4_address", "172.19.0.1/30")
            put("inet6_address", "fdfe:dcba:9876::1/126")
            put("mtu", settings.mtu.coerceIn(1280, 1500))
            put("auto_route", true)
            put("strict_route", true)
            put("stack", "mixed")
            put("sniff", true)
            put("sniff_override_destination", false)
        })

        // Loopback-only probe inbound routed strictly to `finalTag` so end-to-end connectivity
        // checks traverse the active VPN chain even though the app itself is excluded from TUN.
        add(buildJsonObject {
            put("type", "mixed")
            put("tag", TAG_PROBE_IN)
            put("listen", "127.0.0.1")
            put("listen_port", PROBE_PROXY_PORT)
        })

        graph.nodes.forEach { node ->
            val port = node.upstreamLoopbackPort
            val inTag = node.upstreamInboundTag
            if (port != null && inTag != null) {
                add(buildJsonObject {
                    put("type", "mixed")
                    put("tag", inTag)
                    put("listen", "127.0.0.1")
                    put("listen_port", port)
                    put("sniff", true)
                })
            }
        }
    }

    private fun buildRouteConfig(
        graph: RoutingGraph,
        settings: AppSettings,
        finalTag: String,
        enforceStrictPrivacy: Boolean
    ): JsonObject = buildJsonObject {
        putJsonArray("rules") {
            add(buildJsonObject {
                put("protocol", "dns")
                put("outbound", TAG_DNS_OUT)
            })

            // Route internal connectivity probes unconditionally through the final active chain
            add(buildJsonObject {
                put("inbound", TAG_PROBE_IN)
                put("outbound", finalTag)
            })

            graph.nodes.forEach { node ->
                val inTag = node.upstreamInboundTag
                val upTag = node.upstreamTag
                if (inTag != null && upTag != null) {
                    add(buildJsonObject {
                        put("inbound", inTag)
                        put("outbound", upTag)
                    })
                }
            }

            if (settings.ipv6Policy == Ipv6Policy.BLOCK_IPV6) {
                add(buildJsonObject {
                    putJsonArray("ip_cidr") {
                        add(JsonPrimitive("::/0"))
                    }
                    put("outbound", TAG_BLOCK)
                })
            }

            // Both Tor and Psiphon local SOCKS5 listeners are TCP-only; block raw UDP on TUN
            if (enforceStrictPrivacy) {
                add(buildJsonObject {
                    put("inbound", "tun-in")
                    put("network", "udp")
                    put("outbound", TAG_BLOCK)
                })
            }

            if (settings.bypassLan && !enforceStrictPrivacy) {
                add(buildJsonObject {
                    put("ip_is_private", true)
                    put("outbound", TAG_DIRECT)
                })
            }

            if (settings.bypassIran && !enforceStrictPrivacy) {
                add(buildJsonObject {
                    putJsonArray("domain_suffix") {
                        add(JsonPrimitive(".ir"))
                    }
                    put("outbound", TAG_DIRECT)
                })
            }

            if (settings.blockAds) {
                add(buildJsonObject {
                    putJsonArray("domain_keyword") {
                        add(JsonPrimitive("doubleclick"))
                        add(JsonPrimitive("adservice"))
                        add(JsonPrimitive("googlesyndication"))
                    }
                    put("outbound", TAG_BLOCK)
                })
            }
        }

        put("final", finalTag)
        put("auto_detect_interface", true)
    }

    private fun buildWarpOutbound(
        tag: String,
        graph: RoutingGraph,
        detourTag: String?
    ): JsonObject = buildJsonObject {
        val warp = graph.chainConfig.warpConfig
        require(warp.isRegistered) {
            "WARP outbound requires a complete provider-supplied WireGuard configuration."
        }
        put("type", "wireguard")
        put("tag", tag)
        put("server", warp.endpointHost)
        put("server_port", warp.endpointPort)
        putJsonArray("local_address") {
            add(JsonPrimitive(warp.ipv4Address))
            if (warp.ipv6Address.isNotBlank()) {
                add(JsonPrimitive(warp.ipv6Address))
            }
        }
        put("private_key", warp.privateKey)
        put("peer_public_key", warp.peerPublicKey)
        if (warp.reserved.size == 3) {
            putJsonArray("reserved") {
                warp.reserved.forEach { add(JsonPrimitive(it)) }
            }
        }
        put("mtu", warp.mtu)
        if (!detourTag.isNullOrBlank()) {
            put("detour", detourTag)
        }
    }

    private fun buildLocalSocksOutbound(
        tag: String,
        port: Int
    ): JsonObject = buildJsonObject {
        put("type", "socks")
        put("tag", tag)
        put("server", "127.0.0.1")
        put("server_port", port)
        put("version", "5")
    }

    private fun buildProfileOutbound(
        tag: String,
        profile: ProxyProfile,
        detourTag: String?,
        enableEchAuto: Boolean = false,
        settings: AppSettings = AppSettings()
    ): JsonObject {
        validateProfile(profile).getOrThrow()
        return buildJsonObject {
        put("tag", tag)
        if (!detourTag.isNullOrBlank()) {
            put("detour", detourTag)
        }

        when (profile.protocol) {
            ProxyProtocol.VLESS -> {
                put("type", "vless")
                put("server", profile.server)
                put("server_port", profile.port)
                put("uuid", profile.uuid)
                if (profile.flow.isNotBlank()) put("flow", profile.flow)
                putTlsAndTransport(profile, enableEchAuto, settings)
            }
            ProxyProtocol.VMESS -> {
                put("type", "vmess")
                put("server", profile.server)
                put("server_port", profile.port)
                put("uuid", profile.uuid)
                put("security", profile.encryption.ifBlank { "auto" })
                put("alter_id", profile.alterId)
                putTlsAndTransport(profile, enableEchAuto, settings)
            }
            ProxyProtocol.TROJAN -> {
                put("type", "trojan")
                put("server", profile.server)
                put("server_port", profile.port)
                put("password", profile.password)
                putTlsAndTransport(profile, enableEchAuto, settings)
            }
            ProxyProtocol.SHADOWSOCKS -> {
                put("type", "shadowsocks")
                put("server", profile.server)
                put("server_port", profile.port)
                put("method", profile.encryption.ifBlank { "aes-256-gcm" })
                put("password", profile.password)
            }
            ProxyProtocol.HYSTERIA2 -> {
                put("type", "hysteria2")
                put("server", profile.server)
                put("server_port", profile.port)
                put("password", profile.password)
                if (profile.obfsType.isNotBlank()) {
                    putJsonObject("obfs") {
                        put("type", profile.obfsType)
                        put("password", profile.obfsPassword)
                    }
                }
                putJsonObject("tls") {
                    put("enabled", true)
                    put("server_name", profile.sni.ifBlank { profile.server })
                    put("insecure", profile.allowInsecure)
                    if (enableEchAuto) putJsonObject("ech") { put("enabled", true); putJsonArray("config") { } }
                    putTlsTricks(settings, allowPadding = false, allowMixedCaseSni = true)
                }
            }
            ProxyProtocol.TUIC -> {
                put("type", "tuic")
                put("server", profile.server)
                put("server_port", profile.port)
                put("uuid", profile.uuid)
                put("password", profile.password)
                put("congestion_control", profile.congestionControl.ifBlank { "bbr" })
                put("udp_relay_mode", profile.udpRelayMode.ifBlank { "native" })
                putJsonObject("tls") {
                    put("enabled", true)
                    put("server_name", profile.sni.ifBlank { profile.server })
                    put("insecure", profile.allowInsecure)
                    if (enableEchAuto) putJsonObject("ech") { put("enabled", true); putJsonArray("config") { } }
                    if (profile.alpn.isNotEmpty()) {
                        putJsonArray("alpn") { profile.alpn.forEach { add(JsonPrimitive(it)) } }
                    }
                    putTlsTricks(settings, allowPadding = false, allowMixedCaseSni = true)
                }
            }
            ProxyProtocol.WIREGUARD -> {
                put("type", "wireguard")
                put("server", profile.server)
                put("server_port", profile.port)
                put("private_key", profile.wgPrivateKey)
                put("peer_public_key", profile.wgPublicKey)
                if (profile.wgPreSharedKey.isNotBlank()) {
                    put("pre_shared_key", profile.wgPreSharedKey)
                }
                putJsonArray("local_address") {
                    profile.wgLocalAddresses.forEach { add(JsonPrimitive(it)) }
                }
                if (profile.wgReserved.size == 3) {
                    putJsonArray("reserved") {
                        profile.wgReserved.forEach { add(JsonPrimitive(it)) }
                    }
                }
                put("mtu", profile.wgMtu)
            }
            ProxyProtocol.SOCKS5 -> {
                put("type", "socks")
                put("server", profile.server)
                put("server_port", profile.port)
                put("version", "5")
                if (profile.username.isNotBlank()) put("username", profile.username)
                if (profile.password.isNotBlank()) put("password", profile.password)
            }
        }
        if (settings.tlsFragmentEnabled && profile.protocol in setOf(
                ProxyProtocol.VLESS, ProxyProtocol.VMESS, ProxyProtocol.TROJAN,
                ProxyProtocol.HYSTERIA2, ProxyProtocol.TUIC
            ) && (profile.security.equals("tls", true) || profile.security.equals("reality", true) ||
                profile.protocol in setOf(ProxyProtocol.HYSTERIA2, ProxyProtocol.TUIC))) {
            putJsonObject("tls_fragment") {
                put("enabled", true)
                put("method", settings.tlsFragmentMethod)
                if (settings.tlsFragmentMethod == "range") put("range", settings.tlsFragmentRange)
                put("size", settings.tlsFragmentSize)
                put("sleep", settings.tlsFragmentSleep)
            }
        }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putTlsAndTransport(
        profile: ProxyProfile,
        enableEchAuto: Boolean,
        settings: AppSettings
    ) {
        val useTls = profile.security.equals("tls", ignoreCase = true) ||
            profile.security.equals("reality", ignoreCase = true)
        if (useTls) {
            putJsonObject("tls") {
                put("enabled", true)
                put("server_name", profile.sni.ifBlank { profile.wsHost.ifBlank { profile.server } })
                val isRealitySecurity = profile.security.equals("reality", ignoreCase = true)
                put("insecure", if (isRealitySecurity) false else profile.allowInsecure)
                if (profile.alpn.isNotEmpty()) {
                    putJsonArray("alpn") { profile.alpn.forEach { add(JsonPrimitive(it)) } }
                }
                if (enableEchAuto && !profile.security.equals("reality", ignoreCase = true)) {
                    putJsonObject("ech") {
                        put("enabled", true)
                        // Empty config tells Hiddify Core v5 to discover ECHConfig via DNS.
                        putJsonArray("config") { }
                    }
                }
                val isReality = profile.security.equals("reality", ignoreCase = true)
                val rawFp = profile.fingerprint.trim()
                val effectiveFp = when {
                    rawFp.isNotBlank() && !rawFp.equals("none", ignoreCase = true) -> rawFp
                    isReality -> "chrome"
                    else -> ""
                }
                if (effectiveFp.isNotBlank()) {
                    putJsonObject("utls") {
                        put("enabled", true)
                        put("fingerprint", effectiveFp)
                    }
                }
                if (isReality) {
                    putJsonObject("reality") {
                        put("enabled", true)
                        put("public_key", profile.realityPublicKey.trim())
                        put("short_id", profile.realityShortId.trim())
                    }
                }
                val fixedUtlsFingerprint = effectiveFp.lowercase() in fixedUtlsFingerprints
                putTlsTricks(
                    settings,
                    allowPadding = !isReality && fixedUtlsFingerprint,
                    allowMixedCaseSni = !isReality
                )
            }
        }

        when (profile.network.lowercase()) {
            "ws" -> putJsonObject("transport") {
                put("type", "ws")
                put("path", profile.wsPath.ifBlank { "/" })
                if (profile.wsHost.isNotBlank()) {
                    putJsonObject("headers") {
                        put("Host", profile.wsHost)
                    }
                }
                if (profile.wsMaxEarlyData > 0) {
                    put("max_early_data", profile.wsMaxEarlyData)
                    put(
                        "early_data_header_name",
                        profile.wsEarlyDataHeaderName.ifBlank { "Sec-WebSocket-Protocol" }
                    )
                }
            }
            "grpc" -> putJsonObject("transport") {
                put("type", "grpc")
                put("service_name", profile.grpcServiceName)
            }
            "httpupgrade" -> putJsonObject("transport") {
                put("type", "httpupgrade")
                put("path", profile.wsPath.ifBlank { "/" })
                if (profile.wsHost.isNotBlank()) {
                    put("host", profile.wsHost)
                }
            }
        }
    }

    private fun isIpLiteral(host: String): Boolean {
        val cleaned = host.trim().removeSurrounding("[", "]")
        val ipv4 = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")
        return ipv4.matches(cleaned) || cleaned.contains(":")
    }
    /**
     * Emits only TLS tricks supported by the selected TLS implementation.
     * Padding requires a fixed uTLS fingerprint and is intentionally omitted for
     * REALITY, Hysteria2/TUIC (which do not currently emit uTLS here), and random fingerprints.
     */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putTlsTricks(
        settings: AppSettings,
        allowPadding: Boolean,
        allowMixedCaseSni: Boolean
    ) {
        val emitMixedCase = settings.tlsMixedSniCase && allowMixedCaseSni
        val emitPadding = settings.tlsPaddingEnabled && allowPadding
        if (emitMixedCase || emitPadding) {
            putJsonObject("tls_tricks") {
                if (emitMixedCase) put("mixedcase_sni", true)
                if (emitPadding) {
                    put("padding_mode", "random")
                    put("padding_size", settings.tlsPaddingSize)
                }
            }
        }
    }

    private val fixedUtlsFingerprints = setOf(
        "chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android"
    )

}
