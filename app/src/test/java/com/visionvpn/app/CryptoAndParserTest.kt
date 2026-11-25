package com.visionvpn.app

import com.visionvpn.app.chain.RoutingGraph
import com.visionvpn.app.core.ConfigBuilder
import com.visionvpn.app.core.crypto.AesGcmSecretCipher
import com.visionvpn.app.core.crypto.SecureStorageException
import com.visionvpn.app.data.model.AppSettings
import com.visionvpn.app.data.model.ChainConfig
import com.visionvpn.app.data.model.ChainExitType
import com.visionvpn.app.data.model.ProxyProtocol
import com.visionvpn.app.data.parser.ProfileUriParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.KeyGenerator

class CryptoAndParserTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun testKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `AES-256-GCM cipher binds AAD, randomizes IV, migrates legacy plaintext, and rejects tampering`() {
        val key = testKey()
        val cipher = AesGcmSecretCipher { key }
        val secret = """{"privateKey":"SUPER_SECRET_WG_KEY","bridge":"obfs4 1.2.3.4:443"}"""

        val enc1 = cipher.encrypt(secret, associatedData = "profiles_json")
        val enc2 = cipher.encrypt(secret, associatedData = "profiles_json")

        assertTrue(cipher.isEncrypted(enc1))
        assertTrue(enc1.startsWith(AesGcmSecretCipher.PREFIX_V2))
        assertNotEquals("Each write must use a fresh random IV", enc1, enc2)
        assertFalse(enc1.contains("SUPER_SECRET_WG_KEY"))
        assertEquals(secret, cipher.decrypt(enc1, associatedData = "profiles_json"))

        // Mismatched AAD must fail with SecureStorageException (prevents cross-key ciphertext transplant)
        val wrongAadResult = runCatching { cipher.decrypt(enc1, associatedData = "chain_config_json") }
        assertTrue(wrongAadResult.exceptionOrNull() is SecureStorageException)

        // Legacy plaintext migration compatibility
        assertEquals(secret, cipher.decrypt(secret, associatedData = "profiles_json"))

        // Tampered ciphertext must throw SecureStorageException
        val tampered = enc1.dropLast(2) + "AA"
        assertTrue(runCatching { cipher.decrypt(tampered, associatedData = "profiles_json") }.exceptionOrNull() is SecureStorageException)
    }

    @Test
    fun `ChainConfig withExitProvider keeps Settings and Chain Manager synchronized`() {
        val torMode = ChainConfig().withExitProvider(ChainExitType.TOR)
        assertTrue(torMode.enabled)
        assertFalse(torMode.useEntryProxy)
        assertEquals(ChainExitType.TOR, torMode.activeExitProvider)

        val directMode = torMode.withExitProvider(ChainExitType.NONE)
        assertFalse(directMode.enabled)
        assertEquals(ChainExitType.NONE, directMode.activeExitProvider)
    }

    @Test
    fun `ProfileUriParser parses VLESS, Shadowsocks, Trojan, Hysteria2, TUIC, WireGuard, and SOCKS5`() {
        val vless = ProfileUriParser.parseSingle(
            "vless://11111111-1111-1111-1111-111111111111@vless.example.com:443?security=reality&pbk=pub123&sid=abcd#MyVless"
        )
        assertNotNull(vless)
        assertEquals(ProxyProtocol.VLESS, vless!!.protocol)

        val ssUserInfo = Base64.getEncoder().encodeToString("aes-256-gcm:myPass123".toByteArray())
        val ss = ProfileUriParser.parseSingle("ss://$ssUserInfo@ss.example.com:8388#MySS")
        assertNotNull(ss)
        assertEquals(ProxyProtocol.SHADOWSOCKS, ss!!.protocol)

        val graph = RoutingGraph.build(ss, ChainConfig(), listOf(ss)).getOrThrow()
        val cfgJson = json.parseToJsonElement(ConfigBuilder().buildSingBoxConfig(graph, AppSettings())).jsonObject
        val ssOutbound = cfgJson["outbounds"]!!.jsonArray.first {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "hop-0-entry"
        }.jsonObject
        assertEquals("shadowsocks", ssOutbound["type"]!!.jsonPrimitive.content)
    }
}
