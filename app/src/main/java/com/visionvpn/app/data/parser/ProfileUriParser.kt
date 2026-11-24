package com.visionvpn.app.data.parser

import com.visionvpn.app.data.model.ProxyProfile
import com.visionvpn.app.data.model.ProxyProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

object ProfileUriParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseBatch(rawInput: String, subscriptionId: String? = null): List<ProxyProfile> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return emptyList()

        val decodedContent = if (!trimmed.contains("://")) {
            decodeBase64Safe(trimmed) ?: trimmed
        } else {
            trimmed
        }

        return decodedContent
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line -> parseSingle(line, subscriptionId) }
            .toList()
    }

    fun parseSingle(rawUri: String, subscriptionId: String? = null): ProxyProfile? {
        val uriStr = rawUri.trim()
        val lower = uriStr.lowercase()
        return runCatching {
            when {
                lower.startsWith("vless://") -> parseVless(uriStr, subscriptionId)
                lower.startsWith("vmess://") -> parseVmess(uriStr, subscriptionId)
                lower.startsWith("trojan://") -> parseTrojan(uriStr, subscriptionId)
                lower.startsWith("ss://") -> parseShadowsocks(uriStr, subscriptionId)
                lower.startsWith("hysteria2://") || lower.startsWith("hy2://") ->
                    parseHysteria2(uriStr, subscriptionId)
                lower.startsWith("tuic://") -> parseTuic(uriStr, subscriptionId)
                lower.startsWith("wireguard://") || lower.startsWith("wg://") ->
                    parseWireGuard(uriStr, subscriptionId)
                lower.startsWith("socks5://") || lower.startsWith("socks://") ->
                    parseSocks5(uriStr, subscriptionId)
                else -> null
            }
        }.getOrNull()
    }

    private fun parseShadowsocks(uriStr: String, subId: String?): ProxyProfile? {
        val withoutScheme = uriStr.substring(5)
        val fragment = if (withoutScheme.contains('#')) {
            decodeUrl(withoutScheme.substringAfter('#'))
        } else ""
        val mainPart = withoutScheme.substringBefore('#').substringBefore('?')

        val (method, password, host, port) = if (mainPart.contains('@')) {
            // SIP002: base64(method:password)@host:port or plain method:password@host:port
            val userInfoRaw = mainPart.substringBefore('@')
            val hostPort = mainPart.substringAfter('@')
            val userInfo = if (userInfoRaw.contains(':')) {
                decodeUrl(userInfoRaw)
            } else {
                decodeBase64Safe(userInfoRaw) ?: return null
            }
            val m = userInfo.substringBefore(':')
            val p = userInfo.substringAfter(':', "")
            val h = hostPort.substringBeforeLast(':').removeSurrounding("[", "]")
            val prt = hostPort.substringAfterLast(':').toIntOrNull() ?: return null
            Quad(m, p, h, prt)
        } else {
            // Legacy: base64(method:password@host:port)
            val decoded = decodeBase64Safe(mainPart) ?: return null
            val creds = decoded.substringBeforeLast('@')
            val hostPort = decoded.substringAfterLast('@')
            val m = creds.substringBefore(':')
            val p = creds.substringAfter(':', "")
            val h = hostPort.substringBeforeLast(':').removeSurrounding("[", "]")
            val prt = hostPort.substringAfterLast(':').toIntOrNull() ?: return null
            Quad(m, p, h, prt)
        }

        if (method.isBlank() || host.isBlank() || port !in 1..65535) return null
        return ProxyProfile(
            name = fragment.ifBlank { "SS-$host:$port" },
            protocol = ProxyProtocol.SHADOWSOCKS,
            server = host,
            port = port,
            rawUri = uriStr,
            subscriptionId = subId,
            encryption = method,
            password = password
        )
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: Int)

    private fun parseVless(uriStr: String, subId: String?): ProxyProfile? {
        val uri = URI(uriStr)
        val uuid = uri.userInfo?.substringBefore(":") ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val params = parseQueryParams(uri.rawQuery)
        val fragment = decodeUrl(uri.rawFragment).ifBlank { "$host:$port" }

        return ProxyProfile(
            name = fragment,
            protocol = ProxyProtocol.VLESS,
            server = host,
            port = port,
            rawUri = uriStr,
            subscriptionId = subId,
            uuid = uuid,
            flow = params["flow"].orEmpty(),
            encryption = params["encryption"].orEmpty().ifBlank { "none" },
            network = params["type"].orEmpty().ifBlank { "tcp" },
            security = params["security"].orEmpty().ifBlank { "none" },
            sni = params["sni"] ?: params["peer"] ?: host,
            fingerprint = params["fp"].orEmpty().ifBlank { "chrome" },
            alpn = params["alpn"]?.split(",")?.filter { it.isNotBlank() }.orEmpty(),
            allowInsecure = params["allowInsecure"] == "1" || params["insecure"] == "1",
            realityPublicKey = params["pbk"].orEmpty(),
            realityShortId = params["sid"].orEmpty(),
            wsPath = params["path"].orEmpty(),
            wsHost = params["host"].orEmpty(),
            grpcServiceName = params["serviceName"].orEmpty()
        )
    }

    private fun parseVmess(uriStr: String, subId: String?): ProxyProfile? {
        val payload = uriStr.substring(8).trim()
        val decoded = decodeBase64Safe(payload) ?: return null
        val obj: JsonObject = json.parseToJsonElement(decoded).jsonObject

        val host = obj["add"]?.jsonPrimitive?.contentOrNull ?: return null
        val port = obj["port"]?.jsonPrimitive?.intOrNull
            ?: obj["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: return null
        val uuid = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val ps = obj["ps"]?.jsonPrimitive?.contentOrNull?.ifBlank { "$host:$port" } ?: "$host:$port"
        val tls = obj["tls"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val net = obj["net"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "tcp" }

        return ProxyProfile(
            name = ps,
            protocol = ProxyProtocol.VMESS,
            server = host,
            port = port,
            rawUri = uriStr,
            subscriptionId = subId,
            uuid = uuid,
            alterId = obj["aid"]?.jsonPrimitive?.intOrNull
                ?: obj["aid"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            encryption = obj["scy"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "auto" },
            network = net,
            security = if (tls.equals("tls", ignoreCase = true)) "tls" else "none",
            sni = obj["sni"]?.jsonPrimitive?.contentOrNull
                ?.ifBlank { obj["host"]?.jsonPrimitive?.contentOrNull.orEmpty() }
                ?.ifBlank { host } ?: host,
            fingerprint = obj["fp"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "chrome" },
            alpn = obj["alpn"]?.jsonPrimitive?.contentOrNull
                ?.split(",")?.filter { it.isNotBlank() }.orEmpty(),
            wsPath = obj["path"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            wsHost = obj["host"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            grpcServiceName = if (net == "grpc") obj["path"]?.jsonPrimitive?.contentOrNull.orEmpty() else ""
        )
    }

    private fun parseTrojan(uriStr: String, subId: String?): ProxyProfile? {
        val uri = URI(uriStr)
        val password = decodeUrl(uri.rawUserInfo)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val params = parseQueryParams(uri.rawQuery)
        val fragment = decodeUrl(uri.rawFragment).ifBlank { "$host:$port" }

        return ProxyProfile(
            name = fragment,
            protocol = ProxyProtocol.TROJAN,
            server = host,
            port = port,
            rawUri = uriStr,
            subscriptionId = subId,
            password = password,
            network = params["type"].orEmpty().ifBlank { "tcp" },
            security = params["security"].orEmpty().ifBlank { "tls" },
            sni = params["sni"] ?: params["peer"] ?: host,
            fingerprint = params["fp"].orEmpty().ifBlank { "chrome" },
            alpn = params["alpn"]?.split(",")?.filter { it.isNotBlank() }.orEmpty(),
            allowInsecure = params["allowInsecure"] == "1" || params["insecure"] == "1",
            wsPath = params["path"].orEmpty(),
            wsHost = params["host"].orEmpty(),
            grpcServiceName = params["serviceName"].orEmpty()
        )
    }

    private fun parseHysteria2(uriStr: String, subId: String?): ProxyProfile? {
        val normalized = if (uriStr.startsWith("hy2://", ignoreCase = true)) {
            "hysteria2://" + uriStr.substring(6)
        } else uriStr
        val uri = URI(normalized)
        val password = decodeUrl(uri.rawUserInfo)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val params = parseQueryParams(uri.rawQuery)
        val fragment = decodeUrl(uri.rawFragment).ifBlank { "HY2-$host" }

        return ProxyProfile(
            name = fragment,
            protocol = ProxyProtocol.HYSTERIA2,
            server = host,
            port = port,
            rawUri = uriStr,
            subscriptionId = subId,
            password = password,
            security = "tls",
            sni = params["sni"] ?: host,
            allowInsecure = params["insecure"] == "1" || params["allowInsecure"] == "1",
            obfsType = params["obfs"].orEmpty(),
            obfsPassword = params["obfs-password"].orEmpty()
        )
    }

    private fun parseTuic(uriStr: String, subId: String?): ProxyProfile? {
        val uri = URI(uriStr)
        val userInfo = decodeUrl(uri.rawUserInfo)
        val parts = userInfo.split(":", limit = 2)
        val uuid = parts.getOrElse(0) { "" }
        val password = parts.getOrElse(1) { "" }
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val params = parseQueryParams(uri.rawQuery)
        val fragment = decodeUrl(uri.rawFragment).ifBlank { "TUIC-$host" }

        return ProxyProfile(
            name = fragment,
            protocol = ProxyProtocol.TUIC,
            server = host,
            port = port,
            rawUri = uriStr,
            subscriptionId = subId,
            uuid = uuid,
            password = password,
            security = "tls",
            sni = params["sni"] ?: host,
            alpn = params["alpn"]?.split(",")?.filter { it.isNotBlank() } ?: listOf("h3"),
            allowInsecure = params["allow_insecure"] == "1" || params["insecure"] == "1",
            congestionControl = params["congestion_control"].orEmpty().ifBlank { "bbr" },
            udpRelayMode = params["udp_relay_mode"].orEmpty().ifBlank { "native" }
        )
    }

    private fun parseWireGuard(uriStr: String, subId: String?): ProxyProfile? {
        val normalized = if (uriStr.startsWith("wg://", ignoreCase = true)) {
            "wireguard://" + uriStr.substring(5)
        } else uriStr
        val uri = URI(normalized)
        val privateKey = decodeUrl(uri.rawUserInfo)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 51820
        val params = parseQueryParams(uri.rawQuery)
        val fragment = decodeUrl(uri.rawFragment).ifBlank { "WG-$host" }

        val addresses = params["address"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.ifEmpty { listOf("172.16.0.2/32") }
            ?: listOf("172.16.0.2/32")

        val reserved = params["reserved"]
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            .orEmpty()

        return ProxyProfile(
            name = fragment,
            protocol = ProxyProtocol.WIREGUARD,
            server = host,
            port = port,
            rawUri = uriStr,
            subscriptionId = subId,
            wgPrivateKey = privateKey,
            wgPublicKey = params["publickey"].orEmpty(),
            wgPreSharedKey = params["presharedkey"].orEmpty(),
            wgLocalAddresses = addresses,
            wgReserved = reserved,
            wgMtu = params["mtu"]?.toIntOrNull() ?: 1280
        )
    }

    private fun parseSocks5(uriStr: String, subId: String?): ProxyProfile? {
        val normalized = if (uriStr.startsWith("socks://", ignoreCase = true)) {
            "socks5://" + uriStr.substring(8)
        } else uriStr
        val uri = URI(normalized)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 1080
        val rawAuth = decodeUrl(uri.rawUserInfo)
        val decodedAuth = if (rawAuth.isNotBlank() && !rawAuth.contains(":")) {
            decodeBase64Safe(rawAuth) ?: rawAuth
        } else rawAuth
        val authParts = decodedAuth.split(":", limit = 2)
        val fragment = decodeUrl(uri.rawFragment).ifBlank { "SOCKS5-$host" }

        return ProxyProfile(
            name = fragment,
            protocol = ProxyProtocol.SOCKS5,
            server = host,
            port = port,
            rawUri = uriStr,
            subscriptionId = subId,
            username = authParts.getOrElse(0) { "" },
            password = authParts.getOrElse(1) { "" }
        )
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&")
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null
                else decodeUrl(pair.substring(0, idx)) to decodeUrl(pair.substring(idx + 1))
            }
            .toMap()
    }

    private fun decodeUrl(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return runCatching {
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
    }

    private fun decodeBase64Safe(input: String): String? {
        val cleaned = input.trim().replace('-', '+').replace('_', '/').replace("\n", "").replace("\r", "")
        val padded = when (cleaned.length % 4) {
            2 -> "$cleaned=="
            3 -> "$cleaned="
            else -> cleaned
        }
        return runCatching {
            String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
        }.getOrNull()
    }
}
