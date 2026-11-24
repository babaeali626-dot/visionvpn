package com.visionvpn.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class ProxyProtocol(val displayName: String) {
    VLESS("VLESS"),
    VMESS("VMess"),
    TROJAN("Trojan"),
    SHADOWSOCKS("Shadowsocks"),
    HYSTERIA2("Hysteria2"),
    TUIC("TUIC"),
    WIREGUARD("WireGuard"),
    SOCKS5("SOCKS5")
}

@Serializable
data class ProxyProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: ProxyProtocol,
    val server: String,
    val port: Int,
    val rawUri: String,
    val subscriptionId: String? = null,
    val latencyMs: Int? = null,
    // Protocol-specific fields
    val uuid: String = "",
    val password: String = "",
    val username: String = "",
    val flow: String = "",
    val encryption: String = "none",
    val alterId: Int = 0,
    val network: String = "tcp",
    val security: String = "none",
    val sni: String = "",
    val fingerprint: String = "chrome",
    val alpn: List<String> = emptyList(),
    val allowInsecure: Boolean = false,
    // Reality parameters
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    // Transport parameters (ws / grpc / httpupgrade)
    val wsPath: String = "",
    val wsHost: String = "",
    val grpcServiceName: String = "",
    // Hysteria2 / TUIC
    val obfsType: String = "",
    val obfsPassword: String = "",
    val congestionControl: String = "bbr",
    val udpRelayMode: String = "native",
    // WireGuard
    val wgPrivateKey: String = "",
    val wgPublicKey: String = "",
    val wgPreSharedKey: String = "",
    val wgLocalAddresses: List<String> = listOf("172.16.0.2/32"),
    val wgReserved: List<Int> = emptyList(),
    val wgMtu: Int = 1280
)

@Serializable
data class SubscriptionSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val lastUpdatedEpochMs: Long = System.currentTimeMillis(),
    val profileCount: Int = 0
)

enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

data class TrafficSample(
    val timestampMs: Long = System.currentTimeMillis(),
    val uploadBytesPerSec: Long = 0L,
    val downloadBytesPerSec: Long = 0L,
    val totalUploadBytes: Long = 0L,
    val totalDownloadBytes: Long = 0L
)
