package com.visionvpn.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class SplitTunnelMode {
    DISABLED,
    BYPASS_SELECTED,
    ONLY_SELECTED
}

@Serializable
enum class DnsStrategy(val singBoxValue: String, val label: String) {
    PREFER_IPV4("prefer_ipv4", "Prefer IPv4"),
    IPV4_ONLY("ipv4_only", "IPv4 Only"),
    PREFER_IPV6("prefer_ipv6", "Prefer IPv6"),
    IPV6_ONLY("ipv6_only", "IPv6 Only")
}

@Serializable
enum class Ipv6Policy(val labelEn: String, val labelFa: String) {
    BLOCK_IPV6("Block IPv6 (Leak-Proof)", "مسدودسازی IPv6 (ضد نشت)"),
    TUNNEL_IPV6("Tunnel IPv6 via VPN", "عبور IPv6 از تونل")
}

@Serializable
enum class AppLanguage(val code: String, val displayName: String, val isRtl: Boolean) {
    ENGLISH("en", "English", false),
    PERSIAN("fa", "فارسی (Vazirmatn)", true)
}

@Serializable
enum class VisionThemeVariant(val label: String) {
    VISION_NAVY("Vision UI Dark"),
    DEEP_SPACE("Deep Space OLED")
}

/**
 * Application settings with privacy-first first-install defaults (`bypassLan = false`,
 * `bypassIran = false`, `failClosedMode = true`, `strictDnsLeakProtection = true`,
 * `ipv6Policy = BLOCK_IPV6`).
 *
 * Legacy persisted [language] and [themeVariant] fields are kept for backward-compatible
 * JSON decoding, while [effectiveLanguage] and [effectiveThemeVariant] lock this release
 * to English LTR + Vision UI Dark (`VISION_NAVY`).
 */
@Serializable
data class AppSettings(
    val schemaVersion: Int = 3,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    val splitTunnelPackages: Set<String> = emptySet(),
    val bypassLan: Boolean = false,
    val bypassIran: Boolean = false,
    val blockAds: Boolean = true,
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val directDns: String = "223.5.5.5",
    val enableFakeDns: Boolean = false,
    val dnsStrategy: DnsStrategy = DnsStrategy.PREFER_IPV4,
    val ipv6Policy: Ipv6Policy = Ipv6Policy.BLOCK_IPV6,
    val strictDnsLeakProtection: Boolean = true,
    val failClosedMode: Boolean = true,
    val mtu: Int = 9000,
    val language: AppLanguage = AppLanguage.ENGLISH,
    val themeVariant: VisionThemeVariant = VisionThemeVariant.VISION_NAVY
) {
    val effectiveLanguage: AppLanguage
        get() = AppLanguage.ENGLISH

    val effectiveThemeVariant: VisionThemeVariant
        get() = VisionThemeVariant.VISION_NAVY
}

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean
)
