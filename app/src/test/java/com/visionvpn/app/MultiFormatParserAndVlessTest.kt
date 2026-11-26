package com.visionvpn.app

import com.visionvpn.app.chain.RoutingGraph
import com.visionvpn.app.core.ConfigBuilder
import com.visionvpn.app.data.model.AppSettings
import com.visionvpn.app.data.model.ChainConfig
import com.visionvpn.app.data.model.ProxyProtocol
import com.visionvpn.app.data.parser.DetectedSubscriptionFormat
import com.visionvpn.app.data.parser.ProfileUriParser
import com.visionvpn.app.data.parser.SubscriptionFormatParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MultiFormatParserAndVlessTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `VLESS TLS WebSocket parses encoded path, early data, Host header, SNI, ALPN, uTLS and omits reality`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@cdn.example.test:443" +
            "?encryption=none&security=tls&sni=edge.example.test&fp=chrome&alpn=http%2F1.1%2Ch2" +
            "&type=ws&host=ws.example.test&path=%2Fvless%2Fsub%2B1%3Fed%3D2048#Synthetic%20WS%2BTLS"

        val profile = ProfileUriParser.parseSingle(uri, subscriptionId = "sub-1")
        assertNotNull(profile)
        profile!!

        assertEquals(ProxyProtocol.VLESS, profile.protocol)
        assertEquals("Synthetic WS+TLS", profile.name)
        assertEquals("cdn.example.test", profile.server)
        assertEquals(443, profile.port)
        assertEquals("tls", profile.security)
        assertEquals("edge.example.test", profile.sni)
        assertEquals("chrome", profile.fingerprint)
        assertEquals(listOf("http/1.1", "h2"), profile.alpn)
        assertFalse(profile.allowInsecure)
        assertEquals("ws", profile.network)
        assertEquals("/vless/sub+1", profile.wsPath)
        assertEquals("ws.example.test", profile.wsHost)
        assertEquals(2048, profile.wsMaxEarlyData)
        assertEquals("Sec-WebSocket-Protocol", profile.wsEarlyDataHeaderName)
        assertEquals("", profile.realityPublicKey)

        // Verify exact Hiddify Core (Sing-box fork v1.13.0) generated outbound
        val graph = RoutingGraph.build(profile, ChainConfig(), listOf(profile)).getOrThrow()
        val cfg = json.parseToJsonElement(ConfigBuilder().buildSingBoxConfig(graph, AppSettings())).jsonObject
        val outbound = cfg["outbounds"]!!.jsonArray.first {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "hop-0-entry"
        }.jsonObject

        assertEquals("vless", outbound["type"]!!.jsonPrimitive.content)
        val tls = outbound["tls"]!!.jsonObject
        assertTrue(tls["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("edge.example.test", tls["server_name"]!!.jsonPrimitive.content)
        assertFalse(tls["insecure"]!!.jsonPrimitive.boolean)
        assertNull(tls["reality"])
        assertEquals("chrome", tls["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)

        val transport = outbound["transport"]!!.jsonObject
        assertEquals("ws", transport["type"]!!.jsonPrimitive.content)
        assertEquals("/vless/sub+1", transport["path"]!!.jsonPrimitive.content)
        assertEquals("ws.example.test", transport["headers"]!!.jsonObject["Host"]!!.jsonPrimitive.content)
        assertEquals(2048, transport["max_early_data"]!!.jsonPrimitive.int)
        assertEquals("Sec-WebSocket-Protocol", transport["early_data_header_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `VLESS blank sni falls back to peer or wsHost and rejects malformed URIs`() {
        val uriWithEmptySni = "vless://11111111-2222-3333-4444-555555555555@198.51.100.10:443" +
            "?security=tls&sni=&type=ws&host=front.example.test&path=%2Fws#Fallback"
        val profile = ProfileUriParser.parseSingle(uriWithEmptySni)
        assertNotNull(profile)
        assertEquals("front.example.test", profile!!.sni)

        assertNull(ProfileUriParser.parseSingle("vless://@broken.example.test:443#NoUuid"))
        assertNull(ProfileUriParser.parseSingle("not-a-valid-uri"))
    }

    @Test
    fun `SubscriptionFormatParser parses Base64 share links with Hiddify comment headers`() {
        val plain = """
            #profile-title: base64:VmlzaW9uIFRFU1Q=
            #profile-update-interval: 6
            #subscription-userinfo: upload=100; download=200; total=1000; expire=1893456000
            vless://11111111-2222-3333-4444-555555555555@n1.example.test:443?security=tls&type=ws&path=%2Fws#Node1
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))

        val parsed = SubscriptionFormatParser.parse(encoded, subscriptionId = "sub-b64").getOrThrow()
        assertEquals(DetectedSubscriptionFormat.SHARE_LINKS, parsed.format)
        assertEquals(1, parsed.profiles.size)
        assertEquals("sub-b64", parsed.profiles.single().subscriptionId)
        assertEquals("Vision TEST", parsed.metadata.title)
        assertEquals(6, parsed.metadata.updateIntervalHours)
        assertEquals(100L, parsed.metadata.uploadBytes)
        assertEquals(1000L, parsed.metadata.totalBytes)
    }

    @Test
    fun `SubscriptionFormatParser parses Sing-box JSON, Xray JSON, Clash YAML, and WireGuard conf`() {
        val singBoxJson = """
            {
              "outbounds": [
                {
                  "type": "vless",
                  "tag": "SB-Node",
                  "server": "sb.example.test",
                  "server_port": 443,
                  "uuid": "11111111-2222-3333-4444-555555555555",
                  "tls": { "enabled": true, "server_name": "sb.example.test" },
                  "transport": { "type": "ws", "path": "/sb", "headers": { "Host": "sb.example.test" } }
                },
                { "type": "direct", "tag": "direct" }
              ]
            }
        """.trimIndent()
        val sbParsed = SubscriptionFormatParser.parse(singBoxJson, "sub-sb").getOrThrow()
        assertEquals(DetectedSubscriptionFormat.SING_BOX_JSON, sbParsed.format)
        assertEquals(1, sbParsed.profiles.size)
        assertEquals("SB-Node", sbParsed.profiles.single().name)

        val xrayJson = """
            [
              {
                "remarks": "Xray-Node",
                "outbounds": [
                  {
                    "tag": "proxy",
                    "protocol": "vless",
                    "settings": {
                      "vnext": [{ "address": "xr.example.test", "port": 443, "users": [{ "id": "22222222-2222-2222-2222-222222222222" }] }]
                    },
                    "streamSettings": {
                      "network": "ws",
                      "security": "tls",
                      "tlsSettings": { "serverName": "xr.example.test" },
                      "wsSettings": { "path": "/xr", "headers": { "Host": "xr.example.test" } }
                    }
                  }
                ]
              }
            ]
        """.trimIndent()
        val xrParsed = SubscriptionFormatParser.parse(xrayJson, "sub-xr").getOrThrow()
        assertEquals(DetectedSubscriptionFormat.XRAY_JSON, xrParsed.format)
        assertEquals("Xray-Node", xrParsed.profiles.single().name)

        val clashYaml = """
            proxies:
              - name: Clash-Node
                type: vless
                server: cl.example.test
                port: 443
                uuid: 33333333-3333-3333-3333-333333333333
                tls: true
                servername: cl.example.test
                network: ws
                ws-opts:
                  path: /cl
                  headers:
                    Host: cl.example.test
            rules:
              - MATCH,PROXY
        """.trimIndent()
        val clParsed = SubscriptionFormatParser.parse(clashYaml, "sub-cl").getOrThrow()
        assertEquals(DetectedSubscriptionFormat.CLASH_YAML, clParsed.format)
        assertEquals("Clash-Node", clParsed.profiles.single().name)
        assertEquals("/cl", clParsed.profiles.single().wsPath)

        val wgConf = """
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Address = 10.0.0.2/32
            MTU = 1280

            [Peer]
            PublicKey = BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=
            Endpoint = wg.example.test:51820
        """.trimIndent()
        val wgParsed = SubscriptionFormatParser.parse(wgConf, "sub-wg").getOrThrow()
        assertEquals(DetectedSubscriptionFormat.WIREGUARD_CONF, wgParsed.format)
        assertEquals(ProxyProtocol.WIREGUARD, wgParsed.profiles.single().protocol)
        assertEquals(51820, wgParsed.profiles.single().port)

        // Unsupported format returns explicit failure rather than silently guessing
        val unsupported = SubscriptionFormatParser.parse("<html><body>Not a subscription</body></html>")
        assertTrue(unsupported.isFailure)
    }
}
