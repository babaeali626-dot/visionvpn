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
        return SubscriptionFormatParser.parse(rawInput, subscriptionId).getOrNull()?.profiles.orEmpty()
    }

    internal fun parseShareLinkLines(content: String, subscriptionId: String? = null): List<ProxyProfile> {
        return content
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .mapNotNull { line -> parseSingle(line, subscriptionId) }
            .toList()
    }

    fun parseSingle(rawUri: String, subscriptionId: String? = null): ProxyProfile? {
        val unwrapped = unwrapHiddifyImportLink(rawUri.trim())
        val uriStr = unwrapped.trim()
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

    /**
     * Unwraps Hiddify deep links (`hiddify://import/<target>#<name>`,
     * `hiddify://install-proxy?url=...`) when they wrap a supported proxy URI.
     */
    fun unwrapHiddifyImportLink(raw: String): String {
        val trimmed = raw.trim()
        val lower = trimmed.lowercase()
        if (lower.startsWith("hiddify://import/")) {
            return trimmed.substring("hiddify://import/".length).trim()
        }
        if (lower.startsWith("hiddify://install-config?") ||
            lower.startsWith("hiddify://install-sub?") ||
            lower.startsWith("hiddify://install-proxy?") ||
            lower.startsWith("v2ray://install-config?") ||
            lower.startsWith("clash://install-config?") ||
            lower.startsWith("sing-box://import-remote-profile?")
        ) {
            val query = trimmed.substringAfter("?", "").substringBefore("#")
            val params = parseQueryParams(query)
            val url = params["url"]?.trim()
            if (!url.isNullOrBlank()) return url
        }
        return trimmed
    }

    private fun parseShadowsocks(uriStr: String, subId: String?): ProxyProfile? {
        val withoutScheme = uriStr.substring(5)
        val fragment = if (withoutScheme.contains('#')) {
            decodeUrl(withoutScheme.substringAfter('#'))
        } else ""
        val mainPart = withoutScheme.substringBefore('#').substringBefore('?')

        val (method, password, host, port) = if (mainPart.contains('@')) {
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
        val uuid = decodeUrl(uri.rawUserInfo).substringBefore(":").trim()
        if (uuid.isBlank()) return null
        val host = uri.host?.removeSurrounding("[", "]")?.trim() ?: return null
        if (host.isBlank()) return null
        val port = if (uri.port > 0) uri.port else 443
        if (port !in 1..65535) return null

        val params = parseQueryParams(uri.rawQuery)
        val fragment = decodeUrl(uri.rawFragment).ifBlank { "$host:$port" }
        val network = params["type"]?.trim()?.lowercase()?.ifBlank { "tcp" } ?: "tcp"
        val security = params["security"]?.trim()?.lowercase()?.ifBlank { "none" } ?: "none"
        val wsHost = params["host"]?.trim().orEmpty()
        val (cleanWsPath, maxEarlyData, earlyDataHeader) = extractWsEarlyData(params["path"].orEmpty())

        val sni = firstNonBlank(params["sni"], params["peer"], wsHost, host)
        val fp = params["fp"]?.trim()?.lowercase()
            ?: if (security == "tls" || security == "reality") "chrome" else ""
        val alpn = params["alpn"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val allowInsecure = isTruthyFlag(params["allowinsecure"]) || isTruthyFlag(params["insecure"])

        val realityPbk = if (security == "reality") params["pbk"]?.trim().orEmpty() else ""
        val realitySid = if (security == "reality") params["sid"]?.trim().orEmpty() else ""

        return ProxyProfile(
            name = fragment,
            protocol = ProxyProtocol.VLESS,
            server = host,
            port = port,
            rawUri = uriStr,
            subscriptionId = subId,
            uuid = uuid,
            flow = params["flow"]?.trim().orEmpty(),
            encryption = params["encryption"]?.trim()?.ifBlank { "none" } ?: "none",
            network = network,
            security = security,
            sni = sni,
            fingerprint = if (fp == "none") "" else fp,
            alpn = alpn,
            allowInsecure = allowInsecure,
            realityPublicKey = realityPbk,
            realityShortId = realitySid,
            wsPath = cleanWsPath,
            wsHost = wsHost,
            wsMaxEarlyData = maxEarlyData,
            wsEarlyDataHeaderName = earlyDataHeader,
            grpcServiceName = params["servicename"]?.trim().orEmpty()
        )
    }

    private fun parseVmess(uriStr: String, subId: String?): ProxyProfile? {
        val payload = uriStr.substring(8).trim()
        val decoded = decodeBase64Safe(payload) ?: return null
        val obj: JsonObject = json.parseToJsonElement(decoded).jsonObject

        val host = obj["add"]?.jsonPrimitive?.contentOrNull?.trim() ?: return null
        if (host.isBlank()) return null
        val port = obj["port"]?.jsonPrimitive?.intOrNull
            ?: obj["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: return null
        if (port !in 1..65535) return null
        val uuid = obj["id"]?.jsonPrimitive?.contentOrNull?.trim() ?: return null
        if (uuid.isBlank()) return null
        val ps = obj["ps"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { "$host:$port" } ?: "$host:$port"
        val tls = obj["tls"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val net = obj["net"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.ifBlank { "tcp" } ?: "tcp"
        val rawPath = obj["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val (cleanWsPath, maxEarlyData, earlyDataHeader) = extractWsEarlyData(rawPath)
        val wsHost = obj["host"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val sni = firstNonBlank(obj["sni"]?.jsonPrimitive?.contentOrNull, wsHost, host)

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
            encryption = obj["scy"]?.jsonPrimitive?.contentOrNull?.ifBlank { "auto" } ?: "auto",
            network = net,
            security = if (tls.equals("tls", ignoreCase = true)) "tls" else "none",
            sni = sni,
            fingerprint = obj["fp"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { "chrome" } ?: "chrome",
            alpn = obj["alpn"]?.jsonPrimitive?.contentOrNull
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty(),
            wsPath = cleanWsPath,
            wsHost = wsHost,
            wsMaxEarlyData = maxEarlyData,
            wsEarlyDataHeaderName = earlyDataHeader,
            grpcServiceName = if (net == "grpc") rawPath else ""
        )
    }

    private fun parseTrojan(uriStr: String, subId: String?): ProxyProfile? {
        val uri = URI(uriStr)
        val password = decodeUrl(uri.rawUserInfo)
        if (password.isBlank()) return null
        val host = uri.host?.removeSurrounding("[", "]")?.trim() ?: return null
        if (host.isBlank()) return null
        val port = if (uri.port > 0) uri.port else 443
        if (port !in 1..65535) return null
        val params = parseQueryParams(uri.rawQuery)
        val fragment = decodeUrl(uri.rawFragment).ifBlank { "$host:$port" }
        val wsHost = params["host"]?.trim().orEmpty()
        val (cleanWsPath, maxEarlyData, earlyDataHeader) = extractWsEarlyData(params["path"].orEmpty())

        return ProxyProfile(
            name = fragment,
            protocol = ProxyProtocol.TROJAN,
            server = host,
            port = port,
            rawUri = uriStr,
            subscriptionId = subId,
            password = password,
            network = params["type"]?.trim()?.lowercase()?.ifBlank { "tcp" } ?: "tcp",
            security = params["security"]?.trim()?.lowercase()?.ifBlank { "tls" } ?: "tls",
            sni = firstNonBlank(params["sni"], params["peer"], wsHost, host),
            fingerprint = params["fp"]?.trim()?.lowercase()?.ifBlank { "chrome" } ?: "chrome",
            alpn = params["alpn"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty(),
            allowInsecure = isTruthyFlag(params["allowinsecure"]) || isTruthyFlag(params["insecure"]),
            wsPath = cleanWsPath,
            wsHost = wsHost,
            wsMaxEarlyData = maxEarlyData,
            wsEarlyDataHeaderName = earlyDataHeader,
            grpcServiceName = params["servicename"]?.trim().orEmpty()
        )
    }

    private fun parseHysteria2(uriStr: String, subId: String?): ProxyProfile? {
        val normalized = if (uriStr.startsWith("hy2://", ignoreCase = true)) {
            "hysteria2://" + uriStr.substring(6)
        } else uriStr
        val uri = URI(normalized)
        val password = decodeUrl(uri.rawUserInfo)
        val host = uri.host?.removeSurrounding("[", "]")?.trim() ?: return null
        if (host.isBlank()) return null
        val port = if (uri.port > 0) uri.port else 443
        if (port !in 1..65535) return null
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
            sni = firstNonBlank(params["sni"], host),
            allowInsecure = isTruthyFlag(params["insecure"]) || isTruthyFlag(params["allowinsecure"]),
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
        val host = uri.host?.removeSurrounding("[", "]")?.trim() ?: return null
        if (host.isBlank()) return null
        val port = if (uri.port > 0) uri.port else 443
        if (port !in 1..65535) return null
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
            sni = firstNonBlank(params["sni"], host),
            alpn = params["alpn"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: listOf("h3"),
            allowInsecure = isTruthyFlag(params["allow_insecure"]) || isTruthyFlag(params["insecure"]) || isTruthyFlag(params["allowinsecure"]),
            congestionControl = params["congestion_control"]?.ifBlank { "bbr" } ?: "bbr",
            udpRelayMode = params["udp_relay_mode"]?.ifBlank { "native" } ?: "native"
        )
    }

    private fun parseWireGuard(uriStr: String, subId: String?): ProxyProfile? {
        val normalized = if (uriStr.startsWith("wg://", ignoreCase = true)) {
            "wireguard://" + uriStr.substring(5)
        } else uriStr
        val uri = URI(normalized)
        val privateKey = decodeUrl(uri.rawUserInfo)
        val host = uri.host?.removeSurrounding("[", "]")?.trim() ?: return null
        if (host.isBlank()) return null
        val port = if (uri.port > 0) uri.port else 51820
        if (port !in 1..65535) return null
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
        val host = uri.host?.removeSurrounding("[", "]")?.trim() ?: return null
        if (host.isBlank()) return null
        val port = if (uri.port > 0) uri.port else 1080
        if (port !in 1..65535) return null
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

    /**
     * Extracts Xray/V2Ray WebSocket early-data query parameter (`?ed=2048` or `&ed=2048`)
     * into Sing-box v1.10.7 `max_early_data` + `early_data_header_name = "Sec-WebSocket-Protocol"`.
     */
    internal fun extractWsEarlyData(rawPath: String): Triple<String, Int, String> {
        if (rawPath.isBlank()) return Triple("", 0, "")
        val qIdx = rawPath.indexOf('?')
        if (qIdx < 0) return Triple(rawPath, 0, "")

        val basePath = rawPath.substring(0, qIdx).ifBlank { "/" }
        val query = rawPath.substring(qIdx + 1)
        val remainingPairs = mutableListOf<String>()
        var earlyData = 0

        query.split("&").forEach { pair ->
            if (pair.isBlank()) return@forEach
            val key = pair.substringBefore("=").lowercase()
            val value = pair.substringAfter("=", "")
            if (key == "ed") {
                val parsed = value.toIntOrNull()
                if (parsed != null && parsed > 0) {
                    earlyData = parsed
                }
            } else {
                remainingPairs += pair
            }
        }

        val cleanPath = if (remainingPairs.isEmpty()) basePath else "$basePath?${remainingPairs.joinToString("&")}"
        val headerName = if (earlyData > 0) "Sec-WebSocket-Protocol" else ""
        return Triple(cleanPath, earlyData, headerName)
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&")
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null
                else decodeUrl(pair.substring(0, idx)).trim().lowercase() to decodeUrl(pair.substring(idx + 1))
            }
            .toMap()
    }

    private fun isTruthyFlag(value: String?): Boolean {
        val v = value?.trim()?.lowercase() ?: return false
        return v == "1" || v == "true" || v == "yes"
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    }

    internal fun decodeUrl(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return runCatching {
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
    }

    internal fun decodeBase64Safe(input: String): String? {
        val cleaned = input.trim().replace('-', '+').replace('_', '/').replace("\n", "").replace("\r", "")
        if (cleaned.isEmpty() || cleaned.length % 4 == 1) return null
        val padded = when (cleaned.length % 4) {
            2 -> "$cleaned=="
            3 -> "$cleaned="
            else -> cleaned
        }
        return runCatching {
            val bytes = Base64.getDecoder().decode(padded)
            String(bytes, StandardCharsets.UTF_8)
        }.getOrNull()
    }
}
