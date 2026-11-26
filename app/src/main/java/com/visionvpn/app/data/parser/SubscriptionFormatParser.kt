package com.visionvpn.app.data.parser

import com.visionvpn.app.data.model.ProxyProfile
import com.visionvpn.app.data.model.ProxyProtocol
import com.visionvpn.app.data.model.SubscriptionMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

enum class DetectedSubscriptionFormat(val label: String) {
    SHARE_LINKS("Share Links / Base64"),
    SING_BOX_JSON("Sing-box JSON"),
    XRAY_JSON("Xray JSON"),
    CLASH_YAML("Clash / Mihomo YAML"),
    WIREGUARD_CONF("WireGuard .conf")
}

data class ParsedSubscriptionPayload(
    val format: DetectedSubscriptionFormat,
    val profiles: List<ProxyProfile>,
    val metadata: SubscriptionMetadata = SubscriptionMetadata()
)

/**
 * Multi-format subscription & config parser compatible with Hiddify and standard proxy formats:
 * - Plain or Base64 share-link lists (with `#profile-title:`, `#subscription-userinfo:` headers)
 * - Sing-box JSON (`outbounds` array)
 * - Xray JSON (single config object or array of configs)
 * - Clash / Mihomo YAML (`proxies:` list)
 * - WireGuard `.conf` (`[Interface]` + `[Peer]`)
 *
 * Returns actionable errors for malformed or unsupported formats rather than silently guessing.
 */
object SubscriptionFormatParser {

    const val MAX_SUBSCRIPTION_BYTES = 2 * 1024 * 1024 // 2 MiB safety cap

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(
        rawInput: String,
        subscriptionId: String? = null,
        httpMetadata: SubscriptionMetadata = SubscriptionMetadata()
    ): Result<ParsedSubscriptionPayload> = runCatching {
        val trimmed = rawInput.removePrefix("\uFEFF").trim()
        require(trimmed.isNotEmpty()) { "Subscription content is empty." }
        require(trimmed.toByteArray(Charsets.UTF_8).size <= MAX_SUBSCRIPTION_BYTES) {
            "Subscription content exceeds the 2 MB maximum size limit."
        }

        val commentMeta = parseCommentHeaders(trimmed)
        val mergedMeta = mergeMetadata(httpMetadata, commentMeta)

        // 1. WireGuard .conf INI format
        if (looksLikeWireGuardConf(trimmed)) {
            val wgProfile = parseWireGuardConf(trimmed, subscriptionId, mergedMeta.title)
                ?: error("Invalid WireGuard configuration: missing [Interface] PrivateKey or [Peer] PublicKey/Endpoint.")
            return@runCatching ParsedSubscriptionPayload(
                format = DetectedSubscriptionFormat.WIREGUARD_CONF,
                profiles = listOf(wgProfile),
                metadata = mergedMeta
            )
        }

        // 2. JSON formats (Sing-box JSON or Xray JSON object/array)
        val firstChar = trimmed.firstOrNull()
        if (firstChar == '{' || firstChar == '[') {
            val parsedJson = parseJsonSubscription(trimmed, subscriptionId, mergedMeta)
            if (parsedJson != null) return@runCatching parsedJson
            error("JSON subscription format is unsupported or contains no valid proxy outbounds.")
        }

        // 3. Clash / Mihomo YAML (`proxies:`)
        if (looksLikeClashYaml(trimmed)) {
            val clashProfiles = parseClashYaml(trimmed, subscriptionId)
            require(clashProfiles.isNotEmpty()) {
                "Clash/Mihomo YAML detected, but no supported proxies were found."
            }
            return@runCatching ParsedSubscriptionPayload(
                format = DetectedSubscriptionFormat.CLASH_YAML,
                profiles = clashProfiles,
                metadata = mergedMeta
            )
        }

        // 4. Plain share links or Base64-encoded share links / configs
        val directLinks = ProfileUriParser.parseShareLinkLines(trimmed, subscriptionId)
        if (directLinks.isNotEmpty()) {
            return@runCatching ParsedSubscriptionPayload(
                format = DetectedSubscriptionFormat.SHARE_LINKS,
                profiles = directLinks,
                metadata = mergedMeta
            )
        }

        // Try Base64 decoding (excluding comment header lines if present)
        val nonCommentBody = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .joinToString("")
        val decoded = ProfileUriParser.decodeBase64Safe(nonCommentBody)?.trim()
        if (!decoded.isNullOrBlank() && decoded != trimmed) {
            // Decoded Base64 might be share links, JSON, YAML, or WireGuard conf
            val nested = parse(decoded, subscriptionId, mergedMeta)
            if (nested.isSuccess) return@runCatching nested.getOrThrow()
        }

        error("Unsupported or malformed subscription format. Supported: share links (plain/Base64), Sing-box JSON, Xray JSON, Clash/Mihomo YAML, and WireGuard .conf.")
    }

    /**
     * Parses Hiddify comment headers from the first 10 lines (`#profile-title:`,
     * `#profile-update-interval:`, `#subscription-userinfo:`).
     */
    fun parseCommentHeaders(content: String): SubscriptionMetadata {
        var title: String? = null
        var intervalHours: Int? = null
        var upload: Long? = null
        var download: Long? = null
        var total: Long? = null
        var expire: Long? = null

        content.lineSequence().take(10).forEach { rawLine ->
            val line = rawLine.trim()
            val stripped = when {
                line.startsWith("#") -> line.removePrefix("#").trim()
                line.startsWith("//") -> line.removePrefix("//").trim()
                else -> return@forEach
            }
            val colonIdx = stripped.indexOf(':')
            if (colonIdx <= 0) return@forEach
            val key = stripped.substring(0, colonIdx).trim().lowercase()
            val value = stripped.substring(colonIdx + 1).trim()
            when (key) {
                "profile-title" -> title = decodeProfileTitle(value)
                "profile-update-interval" -> intervalHours = value.toIntOrNull()?.takeIf { it > 0 }
                "subscription-userinfo" -> {
                    val info = parseSubscriptionUserInfo(value)
                    upload = info.uploadBytes
                    download = info.downloadBytes
                    total = info.totalBytes
                    expire = info.expireEpochSec
                }
            }
        }
        return SubscriptionMetadata(
            title = title,
            updateIntervalHours = intervalHours,
            uploadBytes = upload,
            downloadBytes = download,
            totalBytes = total,
            expireEpochSec = expire
        )
    }

    fun decodeProfileTitle(rawValue: String?): String? {
        val v = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (v.startsWith("base64:", ignoreCase = true)) {
            ProfileUriParser.decodeBase64Safe(v.substring(7).trim())?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            v
        }
    }

    fun parseSubscriptionUserInfo(rawHeader: String?): SubscriptionMetadata {
        if (rawHeader.isNullOrBlank()) return SubscriptionMetadata()
        var upload: Long? = null
        var download: Long? = null
        var total: Long? = null
        var expire: Long? = null
        rawHeader.split(";").forEach { part ->
            val k = part.substringBefore("=").trim().lowercase()
            val v = part.substringAfter("=", "").trim().toLongOrNull() ?: return@forEach
            when (k) {
                "upload" -> upload = v.coerceAtLeast(0L)
                "download" -> download = v.coerceAtLeast(0L)
                "total" -> total = v.coerceAtLeast(0L)
                "expire" -> expire = v.coerceAtLeast(0L)
            }
        }
        return SubscriptionMetadata(
            uploadBytes = upload,
            downloadBytes = download,
            totalBytes = total,
            expireEpochSec = expire
        )
    }

    private fun mergeMetadata(primary: SubscriptionMetadata, fallback: SubscriptionMetadata): SubscriptionMetadata {
        return SubscriptionMetadata(
            title = primary.title?.takeIf { it.isNotBlank() } ?: fallback.title,
            updateIntervalHours = primary.updateIntervalHours ?: fallback.updateIntervalHours,
            uploadBytes = primary.uploadBytes ?: fallback.uploadBytes,
            downloadBytes = primary.downloadBytes ?: fallback.downloadBytes,
            totalBytes = primary.totalBytes ?: fallback.totalBytes,
            expireEpochSec = primary.expireEpochSec ?: fallback.expireEpochSec
        )
    }

    // -------------------------------------------------------------------------
    // JSON Parsers (Sing-box JSON & Xray JSON)
    // -------------------------------------------------------------------------

    private fun parseJsonSubscription(
        content: String,
        subscriptionId: String?,
        metadata: SubscriptionMetadata
    ): ParsedSubscriptionPayload? {
        val element: JsonElement = runCatching { json.parseToJsonElement(content) }.getOrNull() ?: return null
        return when (element) {
            is JsonArray -> {
                val profiles = element.mapIndexedNotNull { idx, item ->
                    (item as? JsonObject)?.let { parseXrayConfigObject(it, idx, subscriptionId) }
                }.flatten()
                if (profiles.isNotEmpty()) {
                    ParsedSubscriptionPayload(DetectedSubscriptionFormat.XRAY_JSON, profiles, metadata)
                } else null
            }
            is JsonObject -> {
                val outbounds = element["outbounds"] as? JsonArray ?: return null
                // Distinguish Sing-box (`type` field) vs Xray (`protocol` field)
                val looksSingBox = outbounds.any { (it as? JsonObject)?.containsKey("type") == true }
                val looksXray = outbounds.any { (it as? JsonObject)?.containsKey("protocol") == true }
                when {
                    looksSingBox -> {
                        val profiles = outbounds.mapIndexedNotNull { idx, item ->
                            (item as? JsonObject)?.let { parseSingBoxOutbound(it, idx, subscriptionId) }
                        }
                        if (profiles.isNotEmpty()) {
                            ParsedSubscriptionPayload(DetectedSubscriptionFormat.SING_BOX_JSON, profiles, metadata)
                        } else null
                    }
                    looksXray -> {
                        val profiles = parseXrayConfigObject(element, 0, subscriptionId)
                        if (profiles.isNotEmpty()) {
                            ParsedSubscriptionPayload(DetectedSubscriptionFormat.XRAY_JSON, profiles, metadata)
                        } else null
                    }
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun parseSingBoxOutbound(obj: JsonObject, index: Int, subId: String?): ProxyProfile? {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return null
        val tag = obj["tag"]?.jsonPrimitive?.contentOrNull?.ifBlank { "Node ${index + 1}" } ?: "Node ${index + 1}"
        val server = obj["server"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val port = obj["server_port"]?.jsonPrimitive?.intOrNull ?: 0

        if (type in setOf("direct", "block", "dns", "selector", "balancer")) return null
        if (server.isBlank() || port !in 1..65535) return null

        val tls = obj["tls"] as? JsonObject
        val tlsEnabled = tls?.get("enabled")?.jsonPrimitive?.booleanOrNull == true
        val reality = tls?.get("reality") as? JsonObject
        val realityEnabled = reality?.get("enabled")?.jsonPrimitive?.booleanOrNull == true
        val utls = tls?.get("utls") as? JsonObject
        val security = when {
            realityEnabled -> "reality"
            tlsEnabled -> "tls"
            else -> "none"
        }
        val sni = tls?.get("server_name")?.jsonPrimitive?.contentOrNull?.ifBlank { server } ?: server
        val importedInsecure = tls?.get("insecure")?.jsonPrimitive?.booleanOrNull ?: false
        val fp = utls?.get("fingerprint")?.jsonPrimitive?.contentOrNull.orEmpty()
        val alpn = (tls?.get("alpn") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

        val transport = obj["transport"] as? JsonObject
        val netType = transport?.get("type")?.jsonPrimitive?.contentOrNull?.lowercase() ?: "tcp"
        val wsPath = transport?.get("path")?.jsonPrimitive?.contentOrNull.orEmpty()
        val wsHost = (transport?.get("headers") as? JsonObject)
            ?.entries?.firstOrNull { it.key.equals("host", ignoreCase = true) }
            ?.value?.jsonPrimitive?.contentOrNull
            ?: transport?.get("host")?.jsonPrimitive?.contentOrNull.orEmpty()
        val wsMaxEarlyData = transport?.get("max_early_data")?.jsonPrimitive?.intOrNull ?: 0
        val wsEarlyDataHeader = transport?.get("early_data_header_name")?.jsonPrimitive?.contentOrNull.orEmpty()
        val grpcService = transport?.get("service_name")?.jsonPrimitive?.contentOrNull.orEmpty()

        return when (type) {
            "vless" -> {
                val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull?.trim() ?: return null
                if (uuid.isBlank()) return null
                ProxyProfile(
                    name = tag,
                    protocol = ProxyProtocol.VLESS,
                    server = server,
                    port = port,
                    rawUri = obj.toString(),
                    subscriptionId = subId,
                    uuid = uuid,
                    flow = obj["flow"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    network = netType,
                    security = security,
                    sni = sni,
                    fingerprint = fp,
                    alpn = alpn,
                    allowInsecure = false,
                    importedInsecureRequested = importedInsecure,
                    realityPublicKey = reality?.get("public_key")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    realityShortId = reality?.get("short_id")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    wsPath = wsPath,
                    wsHost = wsHost,
                    wsMaxEarlyData = wsMaxEarlyData,
                    wsEarlyDataHeaderName = wsEarlyDataHeader,
                    grpcServiceName = grpcService
                )
            }
            "vmess" -> {
                val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull?.trim() ?: return null
                if (uuid.isBlank()) return null
                ProxyProfile(
                    name = tag,
                    protocol = ProxyProtocol.VMESS,
                    server = server,
                    port = port,
                    rawUri = obj.toString(),
                    subscriptionId = subId,
                    uuid = uuid,
                    encryption = obj["security"]?.jsonPrimitive?.contentOrNull ?: "auto",
                    alterId = obj["alter_id"]?.jsonPrimitive?.intOrNull ?: 0,
                    network = netType,
                    security = security,
                    sni = sni,
                    fingerprint = fp,
                    alpn = alpn,
                    allowInsecure = false,
                    importedInsecureRequested = importedInsecure,
                    wsPath = wsPath,
                    wsHost = wsHost,
                    wsMaxEarlyData = wsMaxEarlyData,
                    wsEarlyDataHeaderName = wsEarlyDataHeader,
                    grpcServiceName = grpcService
                )
            }
            "trojan" -> {
                val password = obj["password"]?.jsonPrimitive?.contentOrNull ?: return null
                if (password.isBlank()) return null
                ProxyProfile(
                    name = tag,
                    protocol = ProxyProtocol.TROJAN,
                    server = server,
                    port = port,
                    rawUri = obj.toString(),
                    subscriptionId = subId,
                    password = password,
                    network = netType,
                    security = if (security == "none") "tls" else security,
                    sni = sni,
                    fingerprint = fp,
                    alpn = alpn,
                    allowInsecure = false,
                    importedInsecureRequested = importedInsecure,
                    wsPath = wsPath,
                    wsHost = wsHost,
                    wsMaxEarlyData = wsMaxEarlyData,
                    wsEarlyDataHeaderName = wsEarlyDataHeader,
                    grpcServiceName = grpcService
                )
            }
            "shadowsocks" -> {
                val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return null
                val password = obj["password"]?.jsonPrimitive?.contentOrNull.orEmpty()
                ProxyProfile(
                    name = tag,
                    protocol = ProxyProtocol.SHADOWSOCKS,
                    server = server,
                    port = port,
                    rawUri = obj.toString(),
                    subscriptionId = subId,
                    encryption = method,
                    password = password
                )
            }
            "hysteria2" -> {
                val obfs = obj["obfs"] as? JsonObject
                ProxyProfile(
                    name = tag,
                    protocol = ProxyProtocol.HYSTERIA2,
                    server = server,
                    port = port,
                    rawUri = obj.toString(),
                    subscriptionId = subId,
                    password = obj["password"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    security = "tls",
                    sni = sni,
                    allowInsecure = false,
                    importedInsecureRequested = importedInsecure,
                    obfsType = obfs?.get("type")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    obfsPassword = obfs?.get("password")?.jsonPrimitive?.contentOrNull.orEmpty()
                )
            }
            "tuic" -> ProxyProfile(
                name = tag,
                protocol = ProxyProtocol.TUIC,
                server = server,
                port = port,
                rawUri = obj.toString(),
                subscriptionId = subId,
                uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                password = obj["password"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                security = "tls",
                sni = sni,
                alpn = alpn.ifEmpty { listOf("h3") },
                allowInsecure = false,
                    importedInsecureRequested = importedInsecure,
                congestionControl = obj["congestion_control"]?.jsonPrimitive?.contentOrNull ?: "bbr",
                udpRelayMode = obj["udp_relay_mode"]?.jsonPrimitive?.contentOrNull ?: "native"
            )
            "wireguard" -> {
                val localAddrs = (obj["local_address"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.ifEmpty { listOf("172.16.0.2/32") }
                    ?: listOf("172.16.0.2/32")
                val reserved = (obj["reserved"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.intOrNull }
                    .orEmpty()
                ProxyProfile(
                    name = tag,
                    protocol = ProxyProtocol.WIREGUARD,
                    server = server,
                    port = port,
                    rawUri = obj.toString(),
                    subscriptionId = subId,
                    wgPrivateKey = obj["private_key"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    wgPublicKey = obj["peer_public_key"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    wgPreSharedKey = obj["pre_shared_key"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    wgLocalAddresses = localAddrs,
                    wgReserved = reserved,
                    wgMtu = obj["mtu"]?.jsonPrimitive?.intOrNull ?: 1280
                )
            }
            "socks" -> ProxyProfile(
                name = tag,
                protocol = ProxyProtocol.SOCKS5,
                server = server,
                port = port,
                rawUri = obj.toString(),
                subscriptionId = subId,
                username = obj["username"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                password = obj["password"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
            else -> null
        }
    }

    private fun parseXrayConfigObject(config: JsonObject, index: Int, subId: String?): List<ProxyProfile> {
        val outbounds = config["outbounds"] as? JsonArray ?: return emptyList()
        val remarks = config["remarks"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "Xray ${index + 1}"
        return outbounds.mapNotNull { item ->
            val ob = item as? JsonObject ?: return@mapNotNull null
            val proto = ob["protocol"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return@mapNotNull null
            if (proto !in setOf("vless", "vmess", "trojan", "wireguard", "socks")) return@mapNotNull null
            val settings = ob["settings"] as? JsonObject ?: return@mapNotNull null
            val stream = ob["streamSettings"] as? JsonObject
            val tag = ob["tag"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "proxy" }
            val displayName = if (tag != null && outbounds.size > 1) "$remarks ($tag)" else remarks

            val security = stream?.get("security")?.jsonPrimitive?.contentOrNull?.lowercase() ?: "none"
            val tlsObj = when (security) {
                "tls" -> stream?.get("tlsSettings") as? JsonObject
                "reality" -> stream?.get("realitySettings") as? JsonObject
                else -> null
            }
            val net = stream?.get("network")?.jsonPrimitive?.contentOrNull?.lowercase() ?: "tcp"
            val wsObj = stream?.get("wsSettings") as? JsonObject
            val rawWsPath = wsObj?.get("path")?.jsonPrimitive?.contentOrNull.orEmpty()
            val (cleanWsPath, maxEd, edHeader) = ProfileUriParser.extractWsEarlyData(rawWsPath)
            val wsHost = wsObj?.get("host")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: (wsObj?.get("headers") as? JsonObject)
                    ?.entries?.firstOrNull { it.key.equals("host", ignoreCase = true) }
                    ?.value?.jsonPrimitive?.contentOrNull.orEmpty()
            val grpcObj = stream?.get("grpcSettings") as? JsonObject
            val grpcService = grpcObj?.get("serviceName")?.jsonPrimitive?.contentOrNull.orEmpty()
            val alpn = (tlsObj?.get("alpn") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val fp = tlsObj?.get("fingerprint")?.jsonPrimitive?.contentOrNull.orEmpty()
            val sniFromTls = tlsObj?.get("serverName")?.jsonPrimitive?.contentOrNull.orEmpty()
            val xrayImportedInsecure = tlsObj?.get("allowInsecure")?.jsonPrimitive?.booleanOrNull == true

            when (proto) {
                "vless", "vmess" -> {
                    val vnext = (settings["vnext"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@mapNotNull null
                    val user = (vnext["users"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@mapNotNull null
                    val server = vnext["address"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                    val port = vnext["port"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    val uuid = user["id"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                    if (server.isBlank() || port !in 1..65535 || uuid.isBlank()) return@mapNotNull null
                    ProxyProfile(
                        name = displayName,
                        protocol = if (proto == "vless") ProxyProtocol.VLESS else ProxyProtocol.VMESS,
                        server = server,
                        port = port,
                        rawUri = ob.toString(),
                        subscriptionId = subId,
                        uuid = uuid,
                        flow = user["flow"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        encryption = user["security"]?.jsonPrimitive?.contentOrNull?.ifBlank { "auto" }
                            ?: user["encryption"]?.jsonPrimitive?.contentOrNull?.ifBlank { "none" } ?: "none",
                        alterId = user["alterId"]?.jsonPrimitive?.intOrNull ?: 0,
                        network = net,
                        security = security,
                        sni = sniFromTls.ifBlank { wsHost.ifBlank { server } },
                        fingerprint = fp.ifBlank { if (security != "none") "chrome" else "" },
                        alpn = alpn,
                        allowInsecure = false,
                        importedInsecureRequested = xrayImportedInsecure,
                        realityPublicKey = if (security == "reality") tlsObj?.get("publicKey")?.jsonPrimitive?.contentOrNull.orEmpty() else "",
                        realityShortId = if (security == "reality") tlsObj?.get("shortId")?.jsonPrimitive?.contentOrNull.orEmpty() else "",
                        wsPath = cleanWsPath,
                        wsHost = wsHost,
                        wsMaxEarlyData = maxEd,
                        wsEarlyDataHeaderName = edHeader,
                        grpcServiceName = grpcService
                    )
                }
                "trojan" -> {
                    val srv = (settings["servers"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@mapNotNull null
                    val server = srv["address"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                    val port = srv["port"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    val password = srv["password"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (server.isBlank() || port !in 1..65535 || password.isBlank()) return@mapNotNull null
                    ProxyProfile(
                        name = displayName,
                        protocol = ProxyProtocol.TROJAN,
                        server = server,
                        port = port,
                        rawUri = ob.toString(),
                        subscriptionId = subId,
                        password = password,
                        network = net,
                        security = if (security == "none") "tls" else security,
                        sni = sniFromTls.ifBlank { wsHost.ifBlank { server } },
                        fingerprint = fp.ifBlank { "chrome" },
                        alpn = alpn,
                        allowInsecure = false,
                        importedInsecureRequested = xrayImportedInsecure,
                        wsPath = cleanWsPath,
                        wsHost = wsHost,
                        wsMaxEarlyData = maxEd,
                        wsEarlyDataHeaderName = edHeader,
                        grpcServiceName = grpcService
                    )
                }
                "wireguard" -> {
                    val peer = (settings["peers"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@mapNotNull null
                    val endpoint = peer["endpoint"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val (server, port) = parseHostPort(endpoint) ?: return@mapNotNull null
                    val secretKey = settings["secretKey"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                    val pubKey = peer["publicKey"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                    val addrs = (settings["address"] as? JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?.ifEmpty { listOf("172.16.0.2/32") }
                        ?: listOf("172.16.0.2/32")
                    val reserved = (settings["reserved"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }.orEmpty()
                    ProxyProfile(
                        name = displayName,
                        protocol = ProxyProtocol.WIREGUARD,
                        server = server,
                        port = port,
                        rawUri = ob.toString(),
                        subscriptionId = subId,
                        wgPrivateKey = secretKey,
                        wgPublicKey = pubKey,
                        wgPreSharedKey = peer["preSharedKey"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        wgLocalAddresses = addrs,
                        wgReserved = reserved,
                        wgMtu = settings["mtu"]?.jsonPrimitive?.intOrNull ?: 1280
                    )
                }
                "socks" -> {
                    val srv = (settings["servers"] as? JsonArray)?.firstOrNull() as? JsonObject
                    val server = srv?.get("address")?.jsonPrimitive?.contentOrNull
                        ?: settings["address"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val port = srv?.get("port")?.jsonPrimitive?.intOrNull
                        ?: settings["port"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    if (server.isBlank() || port !in 1..65535) return@mapNotNull null
                    ProxyProfile(
                        name = displayName,
                        protocol = ProxyProtocol.SOCKS5,
                        server = server,
                        port = port,
                        rawUri = ob.toString(),
                        subscriptionId = subId,
                        username = settings["user"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        password = settings["pass"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    )
                }
                else -> null
            }
        }
    }

    // -------------------------------------------------------------------------
    // Clash / Mihomo YAML Parser (inline-JSON & block-style `proxies:`)
    // -------------------------------------------------------------------------

    private fun looksLikeClashYaml(content: String): Boolean {
        return content.lineSequence().any { it.trimStart().startsWith("proxies:") }
    }

    private fun parseClashYaml(content: String, subId: String?): List<ProxyProfile> {
        val lines = content.lines()
        val startIdx = lines.indexOfFirst { it.trim() == "proxies:" || it.trimStart().startsWith("proxies:") }
        if (startIdx < 0) return emptyList()

        val rawMaps = mutableListOf<Map<String, String>>()
        var currentMap: MutableMap<String, String>? = null
        var currentSection: String? = null

        for (i in (startIdx + 1) until lines.size) {
            val raw = lines[i]
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val indent = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            // Next top-level YAML key (e.g., `proxy-groups:` or `rules:`) ends `proxies:`
            if (indent == 0 && !trimmed.startsWith("-")) break

            if (trimmed.startsWith("- ")) {
                currentMap?.let { if (it.isNotEmpty()) rawMaps += it }
                currentSection = null
                val rest = trimmed.removePrefix("- ").trim()
                if (rest.startsWith("{") && rest.endsWith("}")) {
                    parseInlineYamlMap(rest)?.let { rawMaps += it }
                    currentMap = null
                } else {
                    currentMap = mutableMapOf()
                    parseYamlKeyValue(rest)?.let { (k, v) -> currentMap!![k] = v }
                }
            } else if (currentMap != null) {
                if (trimmed.endsWith(":") && !trimmed.contains(": ")) {
                    currentSection = trimmed.removeSuffix(":").trim().lowercase()
                } else {
                    parseYamlKeyValue(trimmed)?.let { (k, v) ->
                        val fullKey = if (currentSection != null && indent >= 6) "$currentSection.$k" else k
                        currentMap!![fullKey] = v
                    }
                }
            }
        }
        currentMap?.let { if (it.isNotEmpty()) rawMaps += it }

        return rawMaps.mapIndexedNotNull { idx, map -> clashMapToProfile(map, idx, subId) }
    }

    private fun parseInlineYamlMap(inline: String): Map<String, String>? {
        val inner = inline.removePrefix("{").removeSuffix("}").trim()
        if (inner.isEmpty()) return null
        val map = mutableMapOf<String, String>()
        // Split top-level commas respecting braces/brackets/quotes
        var depth = 0
        var inQuotes = false
        var quoteChar = '"'
        val token = StringBuilder()
        fun flush() {
            val part = token.toString().trim()
            token.clear()
            if (part.isNotEmpty()) {
                parseYamlKeyValue(part)?.let { (k, v) ->
                    if (v.startsWith("{") && v.endsWith("}")) {
                        parseInlineYamlMap(v)?.forEach { (nk, nv) -> map["$k.$nk"] = nv }
                    } else {
                        map[k] = v
                    }
                }
            }
        }
        for (ch in inner) {
            when {
                inQuotes -> {
                    token.append(ch)
                    if (ch == quoteChar) inQuotes = false
                }
                ch == '"' || ch == '\'' -> {
                    inQuotes = true
                    quoteChar = ch
                    token.append(ch)
                }
                ch == '{' || ch == '[' -> {
                    depth++
                    token.append(ch)
                }
                ch == '}' || ch == ']' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    token.append(ch)
                }
                ch == ',' && depth == 0 -> flush()
                else -> token.append(ch)
            }
        }
        flush()
        return map.takeIf { it.isNotEmpty() }
    }

    private fun parseYamlKeyValue(line: String): Pair<String, String>? {
        val idx = line.indexOf(':')
        if (idx <= 0) return null
        val key = unquoteYaml(line.substring(0, idx).trim()).lowercase()
        val value = unquoteYaml(line.substring(idx + 1).trim())
        return key to value
    }

    private fun unquoteYaml(value: String): String {
        val trimmed = value.trim()
        return when {
            trimmed.length >= 2 && trimmed.startsWith("'") && trimmed.endsWith("'") ->
                trimmed.substring(1, trimmed.length - 1).replace("''", "'")
            trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"") ->
                trimmed.substring(1, trimmed.length - 1)
            else -> trimmed
        }
    }

    private fun clashMapToProfile(m: Map<String, String>, index: Int, subId: String?): ProxyProfile? {
        val type = m["type"]?.lowercase() ?: return null
        val name = m["name"]?.ifBlank { "Clash ${index + 1}" } ?: "Clash ${index + 1}"
        val server = m["server"]?.trim().orEmpty()
        val port = m["port"]?.toIntOrNull() ?: return null
        if (server.isBlank() || port !in 1..65535) return null

        val tls = m["tls"].equals("true", ignoreCase = true)
        val realityPbk = m["reality-opts.public-key"].orEmpty()
        val realitySid = m["reality-opts.short-id"].orEmpty()
        val security = when {
            realityPbk.isNotBlank() -> "reality"
            tls -> "tls"
            else -> "none"
        }
        val net = m["network"]?.lowercase()?.ifBlank { "tcp" } ?: "tcp"
        val rawWsPath = m["ws-opts.path"].orEmpty()
        val (cleanWsPath, maxEd, edHeader) = ProfileUriParser.extractWsEarlyData(rawWsPath)
        val wsHost = m["ws-opts.headers.host"] ?: m["ws-opts.host"] ?: ""
        val sni = (m["servername"] ?: m["sni"] ?: wsHost).ifBlank { server }
        val fp = m["client-fingerprint"].orEmpty().ifBlank { if (security != "none") "chrome" else "" }
        val alpn = m["alpn"]?.removeSurrounding("[", "]")
            ?.split(",")
            ?.map { unquoteYaml(it) }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val grpcService = m["grpc-opts.grpc-service-name"].orEmpty()
        val clashImportedInsecure = m["skip-cert-verify"].equals("true", ignoreCase = true) ||
            m["insecure"].equals("true", ignoreCase = true)

        return when (type) {
            "vless" -> {
                val uuid = m["uuid"]?.trim() ?: return null
                if (uuid.isBlank()) return null
                ProxyProfile(
                    name = name,
                    protocol = ProxyProtocol.VLESS,
                    server = server,
                    port = port,
                    rawUri = m.toString(),
                    subscriptionId = subId,
                    uuid = uuid,
                    flow = m["flow"].orEmpty(),
                    network = net,
                    security = security,
                    sni = sni,
                    fingerprint = fp,
                    alpn = alpn,
                    allowInsecure = false,
                    importedInsecureRequested = clashImportedInsecure,
                    realityPublicKey = realityPbk,
                    realityShortId = realitySid,
                    wsPath = cleanWsPath,
                    wsHost = wsHost,
                    wsMaxEarlyData = maxEd,
                    wsEarlyDataHeaderName = edHeader,
                    grpcServiceName = grpcService
                )
            }
            "vmess" -> {
                val uuid = m["uuid"]?.trim() ?: return null
                if (uuid.isBlank()) return null
                ProxyProfile(
                    name = name,
                    protocol = ProxyProtocol.VMESS,
                    server = server,
                    port = port,
                    rawUri = m.toString(),
                    subscriptionId = subId,
                    uuid = uuid,
                    alterId = m["alterid"]?.toIntOrNull() ?: 0,
                    encryption = m["cipher"]?.ifBlank { "auto" } ?: "auto",
                    network = net,
                    security = security,
                    sni = sni,
                    fingerprint = fp,
                    alpn = alpn,
                    allowInsecure = false,
                    importedInsecureRequested = clashImportedInsecure,
                    wsPath = cleanWsPath,
                    wsHost = wsHost,
                    wsMaxEarlyData = maxEd,
                    wsEarlyDataHeaderName = edHeader,
                    grpcServiceName = grpcService
                )
            }
            "trojan" -> {
                val password = m["password"] ?: return null
                if (password.isBlank()) return null
                ProxyProfile(
                    name = name,
                    protocol = ProxyProtocol.TROJAN,
                    server = server,
                    port = port,
                    rawUri = m.toString(),
                    subscriptionId = subId,
                    password = password,
                    network = net,
                    security = "tls",
                    sni = sni,
                    fingerprint = fp.ifBlank { "chrome" },
                    alpn = alpn,
                    allowInsecure = false,
                    importedInsecureRequested = clashImportedInsecure,
                    wsPath = cleanWsPath,
                    wsHost = wsHost,
                    wsMaxEarlyData = maxEd,
                    wsEarlyDataHeaderName = edHeader,
                    grpcServiceName = grpcService
                )
            }
            "ss", "shadowsocks" -> {
                val cipher = m["cipher"] ?: return null
                ProxyProfile(
                    name = name,
                    protocol = ProxyProtocol.SHADOWSOCKS,
                    server = server,
                    port = port,
                    rawUri = m.toString(),
                    subscriptionId = subId,
                    encryption = cipher,
                    password = m["password"].orEmpty()
                )
            }
            "hysteria2" -> ProxyProfile(
                name = name,
                protocol = ProxyProtocol.HYSTERIA2,
                server = server,
                port = port,
                rawUri = m.toString(),
                subscriptionId = subId,
                password = m["password"].orEmpty(),
                security = "tls",
                sni = sni,
                allowInsecure = false,
                    importedInsecureRequested = clashImportedInsecure,
                obfsType = m["obfs"].orEmpty(),
                obfsPassword = m["obfs-password"].orEmpty()
            )
            "tuic" -> ProxyProfile(
                name = name,
                protocol = ProxyProtocol.TUIC,
                server = server,
                port = port,
                rawUri = m.toString(),
                subscriptionId = subId,
                uuid = m["uuid"].orEmpty(),
                password = m["password"].orEmpty(),
                security = "tls",
                sni = sni,
                alpn = alpn.ifEmpty { listOf("h3") },
                allowInsecure = false,
                    importedInsecureRequested = clashImportedInsecure,
                congestionControl = m["congestion-controller"]?.ifBlank { "bbr" } ?: "bbr",
                udpRelayMode = m["udp-relay-mode"]?.ifBlank { "native" } ?: "native"
            )
            "wireguard" -> {
                val addrs = listOfNotNull(
                    m["ip"]?.takeIf { it.isNotBlank() }?.let { if ('/' in it) it else "$it/32" },
                    m["ipv6"]?.takeIf { it.isNotBlank() }?.let { if ('/' in it) it else "$it/128" }
                ).ifEmpty { listOf("172.16.0.2/32") }
                val reserved = m["reserved"]?.removeSurrounding("[", "]")
                    ?.split(",")?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
                ProxyProfile(
                    name = name,
                    protocol = ProxyProtocol.WIREGUARD,
                    server = server,
                    port = port,
                    rawUri = m.toString(),
                    subscriptionId = subId,
                    wgPrivateKey = m["private-key"].orEmpty(),
                    wgPublicKey = m["public-key"].orEmpty(),
                    wgPreSharedKey = m["pre-shared-key"].orEmpty(),
                    wgLocalAddresses = addrs,
                    wgReserved = reserved,
                    wgMtu = m["mtu"]?.toIntOrNull() ?: 1280
                )
            }
            "socks5", "socks" -> ProxyProfile(
                name = name,
                protocol = ProxyProtocol.SOCKS5,
                server = server,
                port = port,
                rawUri = m.toString(),
                subscriptionId = subId,
                username = m["username"].orEmpty(),
                password = m["password"].orEmpty()
            )
            else -> null
        }
    }

    // -------------------------------------------------------------------------
    // WireGuard .conf Parser
    // -------------------------------------------------------------------------

    private fun looksLikeWireGuardConf(content: String): Boolean {
        val lower = content.lowercase()
        return lower.contains("[interface]") && lower.contains("[peer]")
    }

    private fun parseWireGuardConf(content: String, subId: String?, title: String?): ProxyProfile? {
        var section = ""
        var privateKey = ""
        var addresses = emptyList<String>()
        var mtu = 1280
        var publicKey = ""
        var presharedKey = ""
        var endpoint = ""

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore("#").trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.lowercase()
                return@forEach
            }
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()
            when (section) {
                "[interface]" -> when (key) {
                    "privatekey" -> privateKey = value
                    "address" -> addresses = value.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    "mtu" -> value.toIntOrNull()?.let { if (it in 1280..1500) mtu = it }
                }
                "[peer]" -> when (key) {
                    "publickey" -> publicKey = value
                    "presharedkey" -> presharedKey = value
                    "endpoint" -> endpoint = value
                }
            }
        }

        if (privateKey.isBlank() || publicKey.isBlank() || endpoint.isBlank()) return null
        val (host, port) = parseHostPort(endpoint) ?: return null
        return ProxyProfile(
            name = title?.ifBlank { null } ?: "WireGuard-$host",
            protocol = ProxyProtocol.WIREGUARD,
            server = host,
            port = port,
            rawUri = content,
            subscriptionId = subId,
            wgPrivateKey = privateKey,
            wgPublicKey = publicKey,
            wgPreSharedKey = presharedKey,
            wgLocalAddresses = addresses.ifEmpty { listOf("172.16.0.2/32") },
            wgMtu = mtu
        )
    }

    private fun parseHostPort(endpoint: String): Pair<String, Int>? {
        val uri = runCatching { URI("wg://${endpoint.trim()}") }.getOrNull() ?: return null
        val host = uri.host?.removeSurrounding("[", "]")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: return null
        return host to port
    }
}
