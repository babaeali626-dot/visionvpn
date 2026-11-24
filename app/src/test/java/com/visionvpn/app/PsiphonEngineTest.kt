package com.visionvpn.app

import com.visionvpn.app.data.model.PsiphonChainConfig
import com.visionvpn.app.data.model.PsiphonRegionValidation
import com.visionvpn.app.data.model.PsiphonTransferUrl
import com.visionvpn.app.data.model.RegionAvailabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PsiphonEngineTest {

    private val validTestSignatureKeyBase64: String =
        Base64.getEncoder().encodeToString(ByteArray(32) { idx -> (idx + 1).toByte() })

    @Test
    fun `official Psiphon region catalog includes AUTO and valid ISO 3166-1 alpha-2 codes`() {
        val regions = PsiphonChainConfig.OFFICIAL_REGIONS
        assertTrue(regions.size >= 30)
        assertEquals("AUTO", regions.first().code)

        val isoRegions = regions.drop(1)
        assertTrue(isoRegions.all { it.code.matches(Regex("^[A-Z]{2}$")) })
        assertTrue(isoRegions.all { it.nameEn.isNotBlank() && it.nameFa.isNotBlank() && it.flag.isNotBlank() })
    }

    @Test
    fun `findRegionOrNull never silently falls back on unknown codes`() {
        assertEquals("Germany", PsiphonChainConfig.findRegionOrNull("de")?.nameEn)
        assertNull(PsiphonChainConfig.findRegionOrNull("INVALID_CODE"))
        assertNull(PsiphonChainConfig.findRegionOrNull("ZZ"))
    }

    @Test
    fun `buildMergedRegionList reflects live availability and includes newly reported ISO codes`() {
        val noLive = PsiphonChainConfig.buildMergedRegionList(emptyList())
        assertEquals(RegionAvailabilityStatus.AVAILABLE, noLive.first { it.code == "AUTO" }.availability)
        assertEquals(RegionAvailabilityStatus.NOT_YET_REPORTED, noLive.first { it.code == "US" }.availability)

        val withLive = PsiphonChainConfig.buildMergedRegionList(listOf("US", "KR"))
        assertEquals(RegionAvailabilityStatus.AVAILABLE, withLive.first { it.code == "US" }.availability)
        assertEquals(RegionAvailabilityStatus.UNAVAILABLE, withLive.first { it.code == "DE" }.availability)
        assertEquals(RegionAvailabilityStatus.AVAILABLE, withLive.first { it.code == "KR" }.availability)
    }

    @Test
    fun `validateRegionCode distinguishes AUTO, valid ISO, invalid format, and live unavailability`() {
        assertTrue(PsiphonChainConfig.validateRegionCode("AUTO") is PsiphonRegionValidation.ValidAuto)
        assertTrue(PsiphonChainConfig.validateRegionCode("US") is PsiphonRegionValidation.ValidRegion)
        assertTrue(PsiphonChainConfig.validateRegionCode("123") is PsiphonRegionValidation.InvalidFormat)
        assertTrue(PsiphonChainConfig.validateRegionCode("ZZ") is PsiphonRegionValidation.UnsupportedRegion)

        val liveCheck = PsiphonChainConfig.validateRegionCode("JP", liveAvailableRegions = setOf("US", "DE"))
        assertTrue(liveCheck is PsiphonRegionValidation.UnsupportedRegion)
        val liveDynamic = PsiphonChainConfig.validateRegionCode("KR", liveAvailableRegions = setOf("US", "KR"))
        assertTrue(liveDynamic is PsiphonRegionValidation.ValidRegion)
    }

    @Test
    fun `PsiphonChainConfig enforces ClientVersion, official credentials, and hardened TransferURLs`() {
        // Missing credentials, ClientVersion, and discovery
        assertFalse(PsiphonChainConfig().isProvisioned)

        // Missing ClientVersion must fail even with credentials & embedded entries
        assertFalse(
            PsiphonChainConfig(
                clientVersion = "",
                propagationChannelId = "CH1",
                sponsorId = "SP1",
                embeddedServerEntries = "hexEncodedServerEntry"
            ).isProvisioned
        )

        // Credentials + ClientVersion present, but NO server discovery -> must NOT be provisioned
        assertFalse(
            PsiphonChainConfig(
                clientVersion = "1.1.0",
                propagationChannelId = "CH1",
                sponsorId = "SP1"
            ).isProvisioned
        )

        // Remote URL without signature public key -> invalid
        assertFalse(
            PsiphonChainConfig(
                clientVersion = "1.1.0",
                propagationChannelId = "CH1",
                sponsorId = "SP1",
                remoteServerListUrls = listOf(PsiphonTransferUrl("https://example.org/list", onlyAfterAttempts = 0))
            ).isProvisioned
        )

        // Remote URL with short/invalid Base64 signature key -> invalid
        assertFalse(
            PsiphonChainConfig(
                clientVersion = "1.1.0",
                propagationChannelId = "CH1",
                sponsorId = "SP1",
                remoteServerListSignaturePublicKey = "SHORT_KEY",
                remoteServerListUrls = listOf(PsiphonTransferUrl("https://example.org/list", onlyAfterAttempts = 0))
            ).isProvisioned
        )

        // Remote URL with OnlyAfterAttempts > 0 only -> invalid per Psiphon schema
        assertFalse(
            PsiphonChainConfig(
                clientVersion = "1.1.0",
                propagationChannelId = "CH1",
                sponsorId = "SP1",
                remoteServerListSignaturePublicKey = validTestSignatureKeyBase64,
                remoteServerListUrls = listOf(PsiphonTransferUrl("https://example.org/list", onlyAfterAttempts = 2))
            ).isProvisioned
        )

        // Negative OnlyAfterAttempts -> invalid
        assertFalse(
            PsiphonChainConfig(
                clientVersion = "1.1.0",
                propagationChannelId = "CH1",
                sponsorId = "SP1",
                remoteServerListSignaturePublicKey = validTestSignatureKeyBase64,
                remoteServerListUrls = listOf(PsiphonTransferUrl("https://example.org/list", onlyAfterAttempts = -1))
            ).isProvisioned
        )

        // SkipVerify = true -> invalid
        assertFalse(
            PsiphonChainConfig(
                clientVersion = "1.1.0",
                propagationChannelId = "CH1",
                sponsorId = "SP1",
                remoteServerListSignaturePublicKey = validTestSignatureKeyBase64,
                remoteServerListUrls = listOf(
                    PsiphonTransferUrl("https://example.org/list", skipVerify = true, onlyAfterAttempts = 0)
                )
            ).isProvisioned
        )

        // Duplicate TransferURLs -> invalid
        assertFalse(
            PsiphonChainConfig(
                clientVersion = "1.1.0",
                propagationChannelId = "CH1",
                sponsorId = "SP1",
                remoteServerListSignaturePublicKey = validTestSignatureKeyBase64,
                remoteServerListUrls = listOf(
                    PsiphonTransferUrl("https://example.org/list", onlyAfterAttempts = 0),
                    PsiphonTransferUrl("https://example.org/list", onlyAfterAttempts = 1)
                )
            ).isProvisioned
        )

        // Complete valid RemoteServerListURLs + 32-byte Base64 SignaturePublicKey + ClientVersion -> valid
        assertTrue(
            PsiphonChainConfig(
                clientVersion = "1.1.0",
                propagationChannelId = "CH1",
                sponsorId = "SP1",
                remoteServerListSignaturePublicKey = validTestSignatureKeyBase64,
                remoteServerListUrls = listOf(PsiphonTransferUrl("https://example.org/list", onlyAfterAttempts = 0))
            ).isProvisioned
        )

        // Complete valid embedded server entry + ClientVersion -> valid
        assertTrue(
            PsiphonChainConfig(
                clientVersion = "1.1.0",
                propagationChannelId = "CH1",
                sponsorId = "SP1",
                embeddedServerEntries = "hexEncodedServerEntry"
            ).isProvisioned
        )
    }
}
