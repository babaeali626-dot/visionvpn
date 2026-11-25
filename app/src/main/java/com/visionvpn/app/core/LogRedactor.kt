package com.visionvpn.app.core

/**
 * Redacts sensitive VPN credentials, tokens, keys, bridge lines, and subscription URLs
 * before writing to Android logs, diagnostics, or user-facing error messages.
 */
object LogRedactor {

    private val keyValueRegex = Regex(
        """(?i)(password|pass|uuid|private_key|privatekey|pre_shared_key|presharedkey|public_key|publickey|access_token|accesstoken|token|secret|cert|pbk|sid|sponsorid|propagationchannelid|targetserverentry|remoteserverlistsignaturepublickey)\s*([=:])\s*"?([^\s&,"';}\]]+)"?"""
    )

    private val uriCredentialsRegex = Regex(
        """(?i)\b(vless|vmess|trojan|ss|hysteria2|hy2|tuic|wireguard|wg|socks5|socks)://[^\s"']+"""
    )

    private val httpsSubQueryRegex = Regex(
        """(?i)(https?://[^\s"'?#]+)\?([^\s"'#]+)"""
    )

    private val bridgeLineRegex = Regex(
        """(?i)\b(obfs4|webtunnel|snowflake|meek_lite)\s+[^\n\r]+"""
    )

    private val uuidRegex = Regex(
        """\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"""
    )

    fun redact(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        var out = raw
        out = uriCredentialsRegex.replace(out) { m -> "${m.groupValues[1]}://<redacted>" }
        out = bridgeLineRegex.replace(out) { m -> "${m.groupValues[1]} <redacted-bridge>" }
        out = keyValueRegex.replace(out) { m -> "${m.groupValues[1]}${m.groupValues[2]}<redacted>" }
        out = httpsSubQueryRegex.replace(out) { m -> "${m.groupValues[1]}?<redacted>" }
        out = uuidRegex.replace(out, "<redacted-uuid>")
        return out
    }
}
