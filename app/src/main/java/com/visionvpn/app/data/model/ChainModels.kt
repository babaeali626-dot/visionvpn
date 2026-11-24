package com.visionvpn.app.data.model

import kotlinx.serialization.Serializable
import java.net.URI
import java.util.Base64
import java.util.UUID

@Serializable
enum class ChainExitType(val labelEn: String, val labelFa: String) {
    NONE("Selected Proxy Server", "سرور پروکسی انتخاب‌شده"),
    CLOUDFLARE_WARP("Cloudflare WARP", "کلودفلر وارپ (WARP)"),
    TOR("Tor Network", "شبکه تور (Tor)"),
    PSIPHON("Psiphon Network", "شبکه سایفون (Psiphon)"),
    CUSTOM_SERVER("Custom Exit Server", "سرور خروجی دلخواه")
}

@Serializable
enum class TorChainMode {
    ENTRY_TO_TOR_EXIT,
    TOR_ENTRY_TO_SERVER_EXIT
}

@Serializable
enum class TorBridgeMode(val labelEn: String, val labelFa: String, val transportName: String?) {
    DIRECT("No Bridge (Direct Tor)", "بدون پل (اتصال مستقیم تور)", null),
    OBFS4_TRANSPORT("Obfs4 Transport (Add Bridge)", "انتقال Obfs4 (نیازمند پل)", "obfs4"),
    SNOWFLAKE_TRANSPORT("Snowflake Transport (Official Broker)", "انتقال Snowflake (کارگزار رسمی)", "snowflake"),
    WEBTUNNEL_TRANSPORT("WebTunnel Transport (Add Bridge)", "انتقال WebTunnel (نیازمند پل)", "webtunnel"),
    CUSTOM("Custom Bridges", "پل‌های سفارشی", null)
}

@Serializable
data class SavedTorBridge(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val bridgeLine: String,
    val transport: String = "obfs4",
    val enabled: Boolean = true
)

@Serializable
data class WarpAccountConfig(
    val privateKey: String = "",
    val publicKey: String = "",
    val peerPublicKey: String = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
    val endpointHost: String = "engage.cloudflareclient.com",
    val endpointPort: Int = 2408,
    val ipv4Address: String = "172.16.0.2/32",
    val ipv6Address: String = "2606:4700:110:8a36:dfab:af1:636:4e94/128",
    val reserved: List<Int> = listOf(0, 0, 0),
    val mtu: Int = 1280,
    val accountId: String = "",
    val accessToken: String = "",
    val registeredAtEpochMs: Long = 0L
) {
    val isRegistered: Boolean
        get() = privateKey.isNotBlank() && peerPublicKey.isNotBlank()
}

@Serializable
data class TorChainConfig(
    val socksPort: Int = 9050,
    val dnsPort: Int = 9053,
    val mode: TorChainMode = TorChainMode.ENTRY_TO_TOR_EXIT,
    val bridgeMode: TorBridgeMode = TorBridgeMode.DIRECT,
    val bridgesEnabled: Boolean = false,
    val savedBridges: List<SavedTorBridge> = emptyList(),
    val useBridges: Boolean = false,
    val customBridgeLine: String = ""
) {
    fun effectiveBridgeMode(): TorBridgeMode {
        if (!bridgesEnabled && !useBridges) return TorBridgeMode.DIRECT
        if (bridgeMode != TorBridgeMode.DIRECT) return bridgeMode
        return if (savedBridges.any { it.enabled } || customBridgeLine.isNotBlank()) {
            TorBridgeMode.CUSTOM
        } else {
            TorBridgeMode.DIRECT
        }
    }

    fun activeCustomBridgeLines(): List<String> {
        val fromList = savedBridges.filter { it.enabled }.map { it.bridgeLine.trim() }.filter { it.isNotBlank() }
        if (fromList.isNotEmpty()) return fromList
        return customBridgeLine.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
    }
}

enum class RegionAvailabilityStatus {
    AVAILABLE,
    UNAVAILABLE,
    NOT_YET_REPORTED
}

data class PsiphonRegionInfo(
    val code: String,
    val nameEn: String,
    val nameFa: String,
    val flag: String,
    val availability: RegionAvailabilityStatus = RegionAvailabilityStatus.NOT_YET_REPORTED
)

sealed class PsiphonRegionValidation {
    data object ValidAuto : PsiphonRegionValidation()
    data class ValidRegion(val region: PsiphonRegionInfo) : PsiphonRegionValidation()
    data class InvalidFormat(val rawCode: String) : PsiphonRegionValidation()
    data class UnsupportedRegion(val code: String) : PsiphonRegionValidation()
}

@Serializable
data class PsiphonTransferUrl(
    val url: String,
    val onlyAfterAttempts: Int = 0,
    val skipVerify: Boolean = false
)

@Serializable
data class PsiphonChainConfig(
    val regionCode: String = "AUTO",
    val localSocksPort: Int = 10801,
    val localHttpPort: Int = 10802,
    // Official Psiphon provisioning fields (no arbitrary fallback; must be explicitly supplied)
    val clientVersion: String = "",
    val propagationChannelId: String = "",
    val sponsorId: String = "",
    val remoteServerListSignaturePublicKey: String = "",
    val remoteServerListUrls: List<PsiphonTransferUrl> = emptyList(),
    val embeddedServerEntries: String = ""
) {
    val hasValidSignatureKey: Boolean
        get() = remoteServerListSignaturePublicKey.isNotBlank() &&
            runCatching {
                val decoded = Base64.getDecoder().decode(remoteServerListSignaturePublicKey.trim())
                decoded.size >= 32
            }.getOrDefault(false)

    val hasValidRemoteServerList: Boolean
        get() {
            if (!hasValidSignatureKey || remoteServerListUrls.isEmpty()) return false
            val seen = mutableSetOf<String>()
            val allValid = remoteServerListUrls.all { entry ->
                if (entry.onlyAfterAttempts < 0 || entry.skipVerify) return@all false
                val normalized = entry.url.trim()
                if (!seen.add(normalized)) return@all false
                val uri = runCatching { URI(normalized) }.getOrNull() ?: return@all false
                uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
            }
            return allValid && remoteServerListUrls.any { it.onlyAfterAttempts == 0 }
        }

    val hasServerDiscovery: Boolean
        get() = embeddedServerEntries.isNotBlank() || hasValidRemoteServerList

    val isProvisioned: Boolean
        get() = clientVersion.isNotBlank() &&
            propagationChannelId.isNotBlank() &&
            sponsorId.isNotBlank() &&
            hasServerDiscovery

    companion object {
        private val ISO_ALPHA2_REGEX = Regex("^[A-Z]{2}$")

        val OFFICIAL_REGIONS: List<PsiphonRegionInfo> = listOf(
            PsiphonRegionInfo("AUTO", "Automatic (Best Performance)", "خودکار (بهترین عملکرد)", "🌐", RegionAvailabilityStatus.AVAILABLE),
            PsiphonRegionInfo("AT", "Austria", "اتریش", "🇦🇹"),
            PsiphonRegionInfo("AU", "Australia", "استرالیا", "🇦🇺"),
            PsiphonRegionInfo("BE", "Belgium", "بلژیک", "🇧🇪"),
            PsiphonRegionInfo("BG", "Bulgaria", "بلغارستان", "🇧🇬"),
            PsiphonRegionInfo("BR", "Brazil", "برزیل", "🇧🇷"),
            PsiphonRegionInfo("CA", "Canada", "کانادا", "🇨🇦"),
            PsiphonRegionInfo("CH", "Switzerland", "سوئیس", "🇨🇭"),
            PsiphonRegionInfo("CZ", "Czech Republic", "جمهوری چک", "🇨🇿"),
            PsiphonRegionInfo("DE", "Germany", "آلمان", "🇩🇪"),
            PsiphonRegionInfo("DK", "Denmark", "دانمارک", "🇩🇰"),
            PsiphonRegionInfo("EE", "Estonia", "استونی", "🇪🇪"),
            PsiphonRegionInfo("ES", "Spain", "اسپانیا", "🇪🇸"),
            PsiphonRegionInfo("FI", "Finland", "فنلاند", "🇫🇮"),
            PsiphonRegionInfo("FR", "France", "فرانسه", "🇫🇷"),
            PsiphonRegionInfo("GB", "United Kingdom", "بریتانیا", "🇬🇧"),
            PsiphonRegionInfo("HR", "Croatia", "کرواسی", "🇭🇷"),
            PsiphonRegionInfo("HU", "Hungary", "مجارستان", "🇭🇺"),
            PsiphonRegionInfo("IE", "Ireland", "ایرلند", "🇮🇪"),
            PsiphonRegionInfo("IN", "India", "هند", "🇮🇳"),
            PsiphonRegionInfo("IT", "Italy", "ایتالیا", "🇮🇹"),
            PsiphonRegionInfo("JP", "Japan", "ژاپن", "🇯🇵"),
            PsiphonRegionInfo("LV", "Latvia", "لتونی", "🇱🇻"),
            PsiphonRegionInfo("MX", "Mexico", "مکزیک", "🇲🇽"),
            PsiphonRegionInfo("NL", "Netherlands", "هلند", "🇳🇱"),
            PsiphonRegionInfo("NO", "Norway", "نروژ", "🇳🇴"),
            PsiphonRegionInfo("PL", "Poland", "لهستان", "🇵🇱"),
            PsiphonRegionInfo("PT", "Portugal", "پرتغال", "🇵🇹"),
            PsiphonRegionInfo("RO", "Romania", "رومانی", "🇷🇴"),
            PsiphonRegionInfo("RS", "Serbia", "صربستان", "🇷🇸"),
            PsiphonRegionInfo("SE", "Sweden", "سوئد", "🇸🇪"),
            PsiphonRegionInfo("SG", "Singapore", "سنگاپور", "🇸🇬"),
            PsiphonRegionInfo("SK", "Slovakia", "اسلواکی", "🇸🇰"),
            PsiphonRegionInfo("UA", "Ukraine", "اوکراین", "🇺🇦"),
            PsiphonRegionInfo("US", "United States", "ایالات متحده", "🇺🇸"),
            PsiphonRegionInfo("ZA", "South Africa", "آفریقای جنوبی", "🇿🇦")
        )

        fun findRegionOrNull(code: String): PsiphonRegionInfo? =
            OFFICIAL_REGIONS.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }

        fun buildMergedRegionList(liveRegions: List<String>): List<PsiphonRegionInfo> {
            val liveSet = liveRegions.map { it.trim().uppercase() }.filter { ISO_ALPHA2_REGEX.matches(it) }.toSet()
            val hasLive = liveSet.isNotEmpty()
            val base = OFFICIAL_REGIONS.map { info ->
                val status = when {
                    info.code == "AUTO" -> RegionAvailabilityStatus.AVAILABLE
                    !hasLive -> RegionAvailabilityStatus.NOT_YET_REPORTED
                    info.code in liveSet -> RegionAvailabilityStatus.AVAILABLE
                    else -> RegionAvailabilityStatus.UNAVAILABLE
                }
                info.copy(availability = status)
            }
            val knownCodes = OFFICIAL_REGIONS.map { it.code }.toSet()
            val dynamicExtras = liveSet
                .filter { it !in knownCodes }
                .sorted()
                .map { code ->
                    PsiphonRegionInfo(
                        code = code,
                        nameEn = code,
                        nameFa = code,
                        flag = "🏳️",
                        availability = RegionAvailabilityStatus.AVAILABLE
                    )
                }
            return base + dynamicExtras
        }

        fun validateRegionCode(
            rawCode: String,
            liveAvailableRegions: Set<String> = emptySet()
        ): PsiphonRegionValidation {
            val normalized = rawCode.trim().uppercase()
            if (normalized.isEmpty() || normalized == "AUTO") {
                return PsiphonRegionValidation.ValidAuto
            }
            if (!ISO_ALPHA2_REGEX.matches(normalized)) {
                return PsiphonRegionValidation.InvalidFormat(rawCode)
            }
            if (liveAvailableRegions.isNotEmpty()) {
                return if (normalized in liveAvailableRegions) {
                    val info = findRegionOrNull(normalized)?.copy(availability = RegionAvailabilityStatus.AVAILABLE)
                        ?: PsiphonRegionInfo(normalized, normalized, normalized, "🏳️", RegionAvailabilityStatus.AVAILABLE)
                    PsiphonRegionValidation.ValidRegion(info)
                } else {
                    PsiphonRegionValidation.UnsupportedRegion(normalized)
                }
            }
            val matched = findRegionOrNull(normalized)
                ?: return PsiphonRegionValidation.UnsupportedRegion(normalized)
            return PsiphonRegionValidation.ValidRegion(matched)
        }
    }
}

@Serializable
data class ChainConfig(
    val enabled: Boolean = false,
    val useEntryProxy: Boolean = false,
    val primaryExitType: ChainExitType = ChainExitType.NONE,
    val secondaryExitTypes: List<ChainExitType> = emptyList(),
    val customExitProfileId: String? = null,
    val warpConfig: WarpAccountConfig = WarpAccountConfig(),
    val torConfig: TorChainConfig = TorChainConfig(),
    val psiphonConfig: PsiphonChainConfig = PsiphonChainConfig()
) {
    /**
     * Configured requested exit mode. For the actual runtime final outbound (which differs in
     * modes like TOR_ENTRY_TO_SERVER_EXIT), use `RoutingGraph.effectiveFinalExitProvider`.
     */
    val activeExitProvider: ChainExitType
        get() = if (!enabled) ChainExitType.NONE else primaryExitType

    fun orderedExitHops(): List<ChainExitType> {
        if (!enabled || primaryExitType == ChainExitType.NONE) return emptyList()
        return buildList {
            add(primaryExitType)
            secondaryExitTypes
                .filter { it != ChainExitType.NONE && it != primaryExitType }
                .forEach { add(it) }
        }
    }

    fun withExitProvider(provider: ChainExitType): ChainConfig {
        return if (provider == ChainExitType.NONE) {
            copy(
                enabled = false,
                useEntryProxy = true,
                primaryExitType = ChainExitType.NONE,
                secondaryExitTypes = emptyList()
            )
        } else {
            val requiresEntry = provider == ChainExitType.CUSTOM_SERVER
            copy(
                enabled = true,
                useEntryProxy = if (requiresEntry) true else useEntryProxy,
                primaryExitType = provider,
                secondaryExitTypes = secondaryExitTypes.filter { it != provider && it != ChainExitType.NONE }
            )
        }
    }
}
