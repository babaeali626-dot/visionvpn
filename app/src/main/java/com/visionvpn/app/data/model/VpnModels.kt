package com.visionvpn.app.data.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest
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
enum class LatencyStatus {
    UNTESTED,
    SUCCESS,
    TIMEOUT,
    DNS_ERROR,
    TLS_ERROR,
    PROTOCOL_ERROR,
    FAILED
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
    val latencyStatus: LatencyStatus = LatencyStatus.UNTESTED,
    val latencyError: String? = null,
    val latencyTestedAtMs: Long = 0L,
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
    val wsMaxEarlyData: Int = 0,
    val wsEarlyDataHeaderName: String = "",
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
) {
    /**
     * Stable connection-parameter fingerprint used to deduplicate imports and prevent
     * stale latency measurements from attaching to a different profile after refresh/edit.
     * Deliberately excludes display [name], random [id], [subscriptionId], and latency fields.
     */
    fun connectionFingerprint(): String {
        val canonical = buildString {
            append(protocol.name).append('|')
            append(server.trim().lowercase()).append('|')
            append(port).append('|')
            append(uuid.trim()).append('|')
            append(password).append('|')
            append(username).append('|')
            append(flow.trim()).append('|')
            append(encryption.trim().lowercase()).append('|')
            append(alterId).append('|')
            append(network.trim().lowercase()).append('|')
            append(security.trim().lowercase()).append('|')
            append(sni.trim().lowercase()).append('|')
            append(fingerprint.trim().lowercase()).append('|')
            append(alpn.joinToString(",") { it.trim().lowercase() }).append('|')
            append(allowInsecure).append('|')
            append(realityPublicKey.trim()).append('|')
            append(realityShortId.trim()).append('|')
            append(wsPath).append('|')
            append(wsHost.trim().lowercase()).append('|')
            append(wsMaxEarlyData).append('|')
            append(wsEarlyDataHeaderName.trim()).append('|')
            append(grpcServiceName).append('|')
            append(obfsType.trim().lowercase()).append('|')
            append(obfsPassword).append('|')
            append(congestionControl.trim().lowercase()).append('|')
            append(udpRelayMode.trim().lowercase()).append('|')
            append(wgPrivateKey.trim()).append('|')
            append(wgPublicKey.trim()).append('|')
            append(wgPreSharedKey.trim()).append('|')
            append(wgLocalAddresses.joinToString(",")).append('|')
            append(wgReserved.joinToString(",")).append('|')
            append(wgMtu)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * Effective latency status accounting for legacy persisted profiles where only [latencyMs]
     * was stored prior to [latencyStatus] being introduced.
     */
    fun effectiveLatencyStatus(): LatencyStatus {
        if (latencyStatus != LatencyStatus.UNTESTED) return latencyStatus
        return when {
            latencyMs == null -> LatencyStatus.UNTESTED
            latencyMs > 0 -> LatencyStatus.SUCCESS
            else -> LatencyStatus.TIMEOUT
        }
    }
}

@Serializable
data class SubscriptionSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val lastUpdatedEpochMs: Long = System.currentTimeMillis(),
    val profileCount: Int = 0,
    val lastError: String = "",
    val updateIntervalHours: Int? = null,
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expireEpochSec: Long? = null
)

enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    ENGINE_READY,
    VERIFYING_CONNECTIVITY,
    CONNECTED,
    RECONNECTING,
    DISCONNECTING,
    ERROR;

    val isInProgress: Boolean
        get() = this == CONNECTING ||
            this == ENGINE_READY ||
            this == VERIFYING_CONNECTIVITY ||
            this == RECONNECTING

    val isActiveOrStarting: Boolean
        get() = this == CONNECTED || isInProgress
}

data class TrafficSample(
    val timestampMs: Long = System.currentTimeMillis(),
    val uploadBytesPerSec: Long = 0L,
    val downloadBytesPerSec: Long = 0L,
    val totalUploadBytes: Long = 0L,
    val totalDownloadBytes: Long = 0L
)
