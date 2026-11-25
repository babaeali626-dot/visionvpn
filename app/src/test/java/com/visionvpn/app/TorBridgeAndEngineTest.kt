package com.visionvpn.app

import com.visionvpn.app.chain.PluggableTransportAvailability
import com.visionvpn.app.chain.TorBridgeValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TorBridgeAndEngineTest {

    private val validObfs4 =
        "obfs4 198.51.100.10:443 0123456789ABCDEF0123456789ABCDEF01234567 cert=AbCdEfGhIjKlMnOpQrStUvWxYz0123456789 iat-mode=0"

    private val validObfs4Ipv6 =
        "obfs4 [2001:db8::1]:443 0123456789ABCDEF0123456789ABCDEF01234567 cert=AbCdEfGhIjKlMnOpQrStUvWxYz0123456789 iat-mode=0"

    private val validWebTunnel =
        "webtunnel 203.0.113.5:443 ABCDEF0123456789ABCDEF0123456789ABCDEF01 url=https://bridge.example.org/secret"

    private val validSnowflake =
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72"

    @Test
    fun `validates obfs4 IPv4 and IPv6, webtunnel, and snowflake bridge syntax accurately`() {
        assertTrue(TorBridgeValidator.validateLine(validObfs4).isValid)
        assertTrue(TorBridgeValidator.validateLine(validObfs4Ipv6).isValid)
        assertTrue(TorBridgeValidator.validateLine(validWebTunnel).isValid)
        assertTrue(TorBridgeValidator.validateLine(validSnowflake).isValid)
    }

    @Test
    fun `strict IPv6 literal parser rejects malformed IPv6 addresses`() {
        assertTrue(TorBridgeValidator.isValidIpv6Literal("2001:db8::1"))
        assertTrue(TorBridgeValidator.isValidIpv6Literal("2001:0db8:85a3:0000:0000:8a2e:0370:7334"))
        assertFalse(TorBridgeValidator.isValidIpv6Literal(":::::"))
        assertFalse(TorBridgeValidator.isValidIpv6Literal("2001:db8::1::2"))
        assertFalse(TorBridgeValidator.isValidIpv6Literal("99999::1"))
        assertFalse(TorBridgeValidator.isValidIpv6Literal("2001:db8:1:2:3:4:5:6:7"))

        val badIpv6Bridge =
            "obfs4 [:::::]:443 0123456789ABCDEF0123456789ABCDEF01234567 cert=AbCdEfGhIjKlMnOpQrStUvWxYz0123456789 iat-mode=0"
        assertFalse(TorBridgeValidator.validateLine(badIpv6Bridge).isValid)
    }

    @Test
    fun `rejects malformed obfs4 bridge missing cert or invalid fingerprint`() {
        val missingCert = TorBridgeValidator.validateLine(
            "obfs4 198.51.100.10:443 0123456789ABCDEF0123456789ABCDEF01234567 iat-mode=0"
        )
        assertFalse(missingCert.isValid)
        assertTrue(missingCert.errorMessage!!.contains("cert="))

        val badFingerprint = TorBridgeValidator.validateLine(
            "obfs4 198.51.100.10:443 NOT_A_HEX_FINGERPRINT cert=abc iat-mode=0"
        )
        assertFalse(badFingerprint.isValid)
        assertTrue(badFingerprint.errorMessage!!.contains("40 hexadecimal"))

        val badPort = TorBridgeValidator.validateLine(
            "obfs4 198.51.100.10:99999 0123456789ABCDEF0123456789ABCDEF01234567 cert=abc"
        )
        assertFalse(badPort.isValid)
    }

    @Test
    fun `validates multiple custom bridges per line and reports invalid lines`() {
        val multi = """
            $validObfs4
            invalid_transport 1.2.3.4:443 0123456789ABCDEF0123456789ABCDEF01234567
            $validWebTunnel
        """.trimIndent()

        val (valid, errors) = TorBridgeValidator.validateMultiple(multi)
        assertEquals(2, valid.size)
        assertEquals(1, errors.size)
    }

    @Test
    fun `redacts sensitive bridge IP and certificate for logs`() {
        val redacted = TorBridgeValidator.redactForLog(validObfs4)
        assertFalse(redacted.contains("198.51.100.10"))
        assertFalse(redacted.contains("AbCdEfGh"))
        assertTrue(redacted.startsWith("obfs4 "))
    }

    @Test
    fun `detectTransportBinaries accurately reports missing pluggable transports`() {
        val emptyDir = File(System.getProperty("java.io.tmpdir"), "vision_empty_pt_${System.nanoTime()}").apply {
            mkdirs()
        }
        try {
            val availability: PluggableTransportAvailability = TorBridgeValidator.detectTransportBinaries(emptyDir)
            assertFalse(availability.supportsObfs4)
            assertFalse(availability.supportsSnowflake)
            assertFalse(availability.supportsWebTunnel)
        } finally {
            emptyDir.deleteRecursively()
        }
    }
}
