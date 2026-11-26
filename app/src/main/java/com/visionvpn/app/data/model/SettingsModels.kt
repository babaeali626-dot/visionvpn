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
enum class BalancerStrategy(val coreValue: String, val label: String, val description: String) {
    ROUND_ROBIN("round-robin", "Round robin", "Rotate new connections across healthy selected servers."),
    CONSISTENT_HASH("consistent-hashing", "Consistent hash", "Keep the same destination on the same server when possible."),
    STICKY_SESSION("sticky-sessions", "Sticky session", "Keep a client/destination on one server for a sticky session.")
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
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
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
    /** HTTPS profile-probe timeout only; native engine startup has a separate timeout. */
    val latencyProbeTimeoutSeconds: Int = 5,
    val latencyTestUrl: String = "https://www.gstatic.com/generate_204",
    /** Preserve historical behavior by default; users may retain their imported/manual order. */
    val autoSortAfterLatencyTest: Boolean = true,
    /** Hiddify Core log verbosity; secrets are still redacted by VisionVPN's log path. */
    val coreLogLevel: String = "info",
    /** Parallel HTTPS probes share one headless Libbox instance; never one native runtime per profile. */
    val latencyProbeConcurrency: Int = 20,
    /** Manual speed-test payload only; the balancer never downloads this payload. */
    val speedTestDownloadSizeMiB: Int = 1,
    /** Enable Hiddify Core ECH auto-discovery for compatible TLS outbounds. */
    val echAutoEnabled: Boolean = false,
    /** Hiddify Core balancer is applied only to a single imported-proxy route. */
    val balancerEnabled: Boolean = false,
    val balancerProfileIds: Set<String> = emptySet(),
    val balancerIntervalSeconds: Int = 60,
    val balancerCandidateCount: Int = 20,
    val balancerStrategy: BalancerStrategy = BalancerStrategy.ROUND_ROBIN,
    /** Hiddify TLS fragment. Padding and fragmentation are mutually exclusive. */
    val tlsFragmentEnabled: Boolean = false,
    val tlsFragmentMethod: String = "tlsHello",
    val tlsFragmentRange: String = "1-3",
    val tlsFragmentSize: String = "10-30",
    val tlsFragmentSleep: String = "2-8",
    val tlsMixedSniCase: Boolean = false,
    val tlsPaddingEnabled: Boolean = false,
    val tlsPaddingSize: String = "800-1200",
    /** Legacy Smart Connect fields remain for one-time JSON migration only. */
    @Deprecated("Use balancerEnabled") val smartConnectEnabled: Boolean = false,
    @Deprecated("Use balancerProfileIds") val smartConnectProfileIds: Set<String> = emptySet(),
    @Deprecated("Use balancerIntervalSeconds") val smartConnectIntervalSeconds: Int = 60,
    @Deprecated("Removed by Hiddify balancer strategies") val smartConnectCandidateCount: Int = 20,
    @Deprecated("Removed by Hiddify balancer strategies") val smartConnectToleranceMs: Int = 50,
    /** Conservative Android VPN MTU; 9000-byte jumbo frames are not portable on mobile paths. */
    val mtu: Int = 1500,
    val language: AppLanguage = AppLanguage.ENGLISH,
    val themeVariant: VisionThemeVariant = VisionThemeVariant.VISION_NAVY
) {
    val effectiveLanguage: AppLanguage
        get() = AppLanguage.ENGLISH

    val effectiveThemeVariant: VisionThemeVariant
        get() = VisionThemeVariant.VISION_NAVY

    companion object {
        const val CURRENT_SCHEMA_VERSION = 6
    }
}

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean
)
