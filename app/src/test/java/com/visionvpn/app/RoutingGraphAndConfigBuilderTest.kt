package com.visionvpn.app

import com.visionvpn.app.chain.RoutingGraph
import com.visionvpn.app.chain.RoutingNode
import com.visionvpn.app.core.ConfigBuilder
import com.visionvpn.app.core.VpnCoreController
import com.visionvpn.app.data.model.AppSettings
import com.visionvpn.app.data.model.ChainConfig
import com.visionvpn.app.data.model.ChainExitType
import com.visionvpn.app.data.model.Ipv6Policy
import com.visionvpn.app.data.model.ProxyProfile
import com.visionvpn.app.data.model.ProxyProtocol
import com.visionvpn.app.data.model.PsiphonChainConfig
import com.visionvpn.app.data.model.TorBridgeMode
import com.visionvpn.app.data.model.TorChainConfig
import com.visionvpn.app.data.model.TorChainMode
import com.visionvpn.app.data.model.WarpAccountConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingGraphAndConfigBuilderTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val builder = ConfigBuilder()

    private val entryVless = ProxyProfile(
        id = "p-entry",
        name = "Entry-VLESS",
        protocol = ProxyProtocol.VLESS,
        server = "entry.example.com",
        port = 443,
        rawUri = "vless://11111111-1111-1111-1111-111111111111@entry.example.com:443?security=tls",
        uuid = "11111111-1111-1111-1111-111111111111",
        security = "tls"
    )

    private val entrySocks5 = ProxyProfile(
        id = "p-socks",
        name = "Entry-SOCKS5",
        protocol = ProxyProtocol.SOCKS5,
        server = "socks.example.com",
        port = 1080,
        rawUri = "socks5://socks.example.com:1080"
    )

    private val exitHysteria2 = ProxyProfile(
        id = "p-hy2",
        name = "Exit-HY2",
        protocol = ProxyProtocol.HYSTERIA2,
        server = "hy2.example.com",
        port = 443,
        rawUri = "hy2://pass@hy2.example.com:443",
        password = "pass"
    )

    private val readyWarp = WarpAccountConfig(
        privateKey = "privKeyBase64==",
        peerPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
        reserved = listOf(12, 34, 56)
    )

    @Test
    fun `Standalone Tor never injects saved profiles when useEntryProxy is false`() {
        val cfg = ChainConfig(useEntryProxy = false).withExitProvider(ChainExitType.TOR)
        val resolved = VpnCoreController.resolveEntryProfile(
            profiles = listOf(entryVless),
            activeProfileId = entryVless.id,
            chainConfig = cfg
        ).getOrThrow()
        assertNull("Standalone mode must not inject saved profile", resolved)
    }

    @Test
    fun `Tor and Psiphon privacy isolation blocks raw UDP and direct bypasses unconditionally`() {
        listOf(ChainExitType.TOR, ChainExitType.PSIPHON).forEach { exitType ->
            val cfg = ChainConfig(useEntryProxy = false).withExitProvider(exitType)
            val graph = RoutingGraph.build(null, cfg, emptyList()).getOrThrow()
            assertEquals(exitType, graph.effectiveFinalExitProvider)
            val weakSettings = AppSettings(
                ipv6Policy = Ipv6Policy.BLOCK_IPV6,
                strictDnsLeakProtection = false,
                failClosedMode = false,
                bypassLan = true,
                bypassIran = true
            )
            val singBox = json.parseToJsonElement(builder.buildSingBoxConfig(graph, weakSettings)).jsonObject
            val routeRules = singBox["route"]!!.jsonObject["rules"]!!.jsonArray

            assertTrue(routeRules.none { it.jsonObject["outbound"]?.jsonPrimitive?.content == "direct" })
            assertTrue(routeRules.any {
                it.jsonObject["network"]?.jsonPrimitive?.content == "udp" &&
                    it.jsonObject["outbound"]?.jsonPrimitive?.content == "block"
            })
        }
    }

    @Test
    fun `Capability validation rejects Psiphon to WARP, Tor to Hysteria2, and SOCKS5 to WARP UDP-over-TCP chains`() {
        val psiToWarp = ChainConfig(
            enabled = true,
            useEntryProxy = true,
            primaryExitType = ChainExitType.PSIPHON,
            secondaryExitTypes = listOf(ChainExitType.CLOUDFLARE_WARP),
            warpConfig = readyWarp
        )
        val r1 = RoutingGraph.build(entryVless, psiToWarp, listOf(entryVless))
        assertTrue(r1.isFailure)
        assertTrue(r1.exceptionOrNull()!!.message!!.contains("UDP"))

        val torToHy2 = ChainConfig(
            enabled = true,
            useEntryProxy = true,
            primaryExitType = ChainExitType.TOR,
            secondaryExitTypes = listOf(ChainExitType.CUSTOM_SERVER),
            customExitProfileId = exitHysteria2.id
        )
        val r2 = RoutingGraph.build(entryVless, torToHy2, listOf(entryVless, exitHysteria2))
        assertTrue(r2.isFailure)
        assertTrue(r2.exceptionOrNull()!!.message!!.contains("UDP"))

        val socksToWarp = ChainConfig(
            enabled = true,
            useEntryProxy = true,
            primaryExitType = ChainExitType.CLOUDFLARE_WARP,
            warpConfig = readyWarp
        )
        val r3 = RoutingGraph.build(entrySocks5, socksToWarp, listOf(entrySocks5))
        assertTrue("SOCKS5 -> WARP must be rejected due to missing UDP transport", r3.isFailure)
        assertTrue(r3.exceptionOrNull()!!.message!!.contains("UDP"))
    }

    @Test
    fun `Loopback port collision between Tor and Psiphon is rejected clearly`() {
        val collisionCfg = ChainConfig(
            enabled = true,
            useEntryProxy = true,
            primaryExitType = ChainExitType.TOR,
            secondaryExitTypes = listOf(ChainExitType.PSIPHON),
            torConfig = TorChainConfig(socksPort = 10801),
            psiphonConfig = PsiphonChainConfig(localSocksPort = 10801)
        )
        val result = RoutingGraph.build(entryVless, collisionCfg, listOf(entryVless))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("collision"))
    }

    @Test
    fun `VLESS to WARP to Psiphon wires Psiphon upstream through WARP and reports PSIPHON as effective final exit`() {
        val cfg = ChainConfig(
            enabled = true,
            useEntryProxy = true,
            primaryExitType = ChainExitType.CLOUDFLARE_WARP,
            secondaryExitTypes = listOf(ChainExitType.PSIPHON),
            warpConfig = readyWarp
        )
        val graph = RoutingGraph.build(entryVless, cfg, listOf(entryVless)).getOrThrow()
        val psiphonNode = graph.nodes[2] as RoutingNode.PsiphonNode
        assertEquals("hop-1-warp", psiphonNode.upstreamTag)
        assertNotNull(psiphonNode.upstreamLoopbackPort)
        assertEquals("mixed-upstream-2", psiphonNode.upstreamInboundTag)
        assertEquals(ChainExitType.PSIPHON, graph.effectiveFinalExitProvider)
    }

    @Test
    fun `Snowflake transport chained behind Entry proxy is rejected clearly`() {
        val cfg = ChainConfig(
            enabled = true,
            useEntryProxy = true,
            primaryExitType = ChainExitType.TOR,
            torConfig = TorChainConfig(
                bridgesEnabled = true,
                bridgeMode = TorBridgeMode.SNOWFLAKE_TRANSPORT
            )
        )
        val result = RoutingGraph.build(entryVless, cfg, listOf(entryVless))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Snowflake"))
    }

    @Test
    fun `Tor Entry to Server Exit orders Tor first and reports Selected Server NONE as effective final exit`() {
        val cfg = ChainConfig(
            enabled = true,
            useEntryProxy = true,
            primaryExitType = ChainExitType.TOR,
            torConfig = TorChainConfig(mode = TorChainMode.TOR_ENTRY_TO_SERVER_EXIT)
        )
        val graph = RoutingGraph.build(entryVless, cfg, listOf(entryVless)).getOrThrow()
        assertEquals(2, graph.nodes.size)
        assertTrue(graph.nodes[0] is RoutingNode.TorNode)
        assertEquals("hop-0-tor", graph.nodes[1].upstreamTag)
        assertFalse(graph.isPrivacyExitMode)
        assertEquals(
            "Final exit node is the selected proxy server, NOT Tor",
            ChainExitType.NONE,
            graph.effectiveFinalExitProvider
        )
    }
}
