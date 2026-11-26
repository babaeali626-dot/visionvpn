package com.visionvpn.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

enum class ExitIpStatus {
    DISCONNECTED,
    LOADING,
    VERIFIED,
    STALE,
    UNAVAILABLE
}

data class ExitIpInfo(
    val status: ExitIpStatus = ExitIpStatus.DISCONNECTED,
    val ip: String? = null,
    val countryCode: String? = null,
    val countryName: String? = null,
    val updatedAtMs: Long = 0L,
    val errorNote: String? = null
) {
    val countryFlag: String
        get() {
            val code = countryCode?.uppercase().orEmpty()
            if (code.length != 2 || code.any { it !in 'A'..'Z' }) return ""
            return String(Character.toChars(0x1F1E6 + (code[0] - 'A'))) +
                String(Character.toChars(0x1F1E6 + (code[1] - 'A')))
        }

    val formattedCountry: String
        get() = when {
            !countryName.isNullOrBlank() && !countryCode.isNullOrBlank() -> "$countryName ($countryCode)"
            !countryCode.isNullOrBlank() -> countryCode
            else -> "Unknown"
        }
}

/**
 * Tunnel-only public egress IP and country lookup.
 *
 * Security & privacy guarantees:
 * - Routes EXCLUSIVELY through the active Hiddify Core loopback probe inbound (`127.0.0.1:[proxyPort]`).
 * - NEVER falls back to the device's physical/direct network stack.
 * - Uses standard system HTTPS certificate-chain and hostname verification (no trust-all client).
 * - Sends minimal request headers (no account IDs, profile credentials, or subscription URLs).
 * - Validates that the returned IP is a syntactically valid PUBLIC IP (rejecting loopback,
 *   RFC1918 private, link-local, CGNAT, ULA, unspecified, and multicast addresses).
 * - Never writes the resolved IP into logs or persistent storage.
 */
object ExitIpLookup {

    const val PRIMARY_ENDPOINT = "https://api.country.is/"
    const val FALLBACK_ENDPOINT = "https://www.cloudflare.com/cdn-cgi/trace"
    const val IP_SB_ENDPOINT = "https://api.ip.sb/geoip"
    private const val MAX_BODY_BYTES = 4_096

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val isoCountryRegex = Regex("^[A-Z]{2}$")

    suspend fun fetchThroughTunnelProxy(
        proxyPort: Int = ConfigBuilder.PROBE_PROXY_PORT,
        timeoutMs: Int = 8_000,
        clockMs: () -> Long = { System.currentTimeMillis() }
    ): ExitIpInfo = withContext(Dispatchers.IO) {
        val primary = queryEndpointThroughProxy(PRIMARY_ENDPOINT, proxyPort, timeoutMs, isJson = true, clockMs = clockMs)
        if (primary.status == ExitIpStatus.VERIFIED) return@withContext primary

        val fallback = queryEndpointThroughProxy(FALLBACK_ENDPOINT, proxyPort, timeoutMs, isJson = false, clockMs = clockMs)
        if (fallback.status == ExitIpStatus.VERIFIED) return@withContext fallback

        val ipSb = queryEndpointThroughProxy(IP_SB_ENDPOINT, proxyPort, timeoutMs, isJson = true, clockMs = clockMs)
        if (ipSb.status == ExitIpStatus.VERIFIED) return@withContext ipSb

        primary.copy(
            status = ExitIpStatus.UNAVAILABLE,
            errorNote = ipSb.errorNote ?: fallback.errorNote ?: primary.errorNote ?: "Exit IP lookup unavailable through tunnel"
        )
    }

    private suspend fun queryEndpointThroughProxy(
        endpointUrl: String,
        proxyPort: Int,
        timeoutMs: Int,
        isJson: Boolean,
        clockMs: () -> Long
    ): ExitIpInfo = runInterruptible(Dispatchers.IO) {
        var conn: HttpsURLConnection? = null
        try {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
            val url = URL(endpointUrl)
            require(url.protocol.equals("https", ignoreCase = true)) { "Exit IP endpoint must use HTTPS" }
            conn = (url.openConnection(proxy) as HttpsURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", if (isJson) "application/json" else "text/plain")
                setRequestProperty("Connection", "close")
                setRequestProperty("Cache-Control", "no-cache")
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                return@runInterruptible ExitIpInfo(
                    status = ExitIpStatus.UNAVAILABLE,
                    errorNote = "Exit lookup returned HTTP $code"
                )
            }

            val body = conn.inputStream.use { stream ->
                val buf = ByteArray(MAX_BODY_BYTES)
                var total = 0
                while (total < MAX_BODY_BYTES) {
                    val n = stream.read(buf, total, MAX_BODY_BYTES - total)
                    if (n <= 0) break
                    total += n
                }
                String(buf, 0, total, Charsets.UTF_8)
            }

            if (isJson) parseCountryIsJson(body, clockMs()) else parseCloudflareTrace(body, clockMs())
        } catch (ce: CancellationException) {
            throw ce
        } catch (ie: InterruptedException) {
            throw CancellationException("Exit IP lookup cancelled")
        } catch (e: Exception) {
            ExitIpInfo(
                status = ExitIpStatus.UNAVAILABLE,
                errorNote = "Exit IP lookup failed (${e.javaClass.simpleName})"
            )
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    internal fun parseCountryIsJson(rawBody: String, nowMs: Long = System.currentTimeMillis()): ExitIpInfo {
        val obj = runCatching { json.parseToJsonElement(rawBody).jsonObject }.getOrNull()
            ?: return ExitIpInfo(status = ExitIpStatus.UNAVAILABLE, errorNote = "Malformed JSON response")
        val ip = obj["ip"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val country = (obj["country_code"]?.jsonPrimitive?.contentOrNull
            ?: obj["countryCode"]?.jsonPrimitive?.contentOrNull
            ?: obj["country"]?.jsonPrimitive?.contentOrNull)?.trim()?.uppercase()
        return buildValidatedInfo(ip, country, nowMs)
    }

    internal fun parseCloudflareTrace(rawBody: String, nowMs: Long = System.currentTimeMillis()): ExitIpInfo {
        val map = rawBody.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()
        val ip = map["ip"].orEmpty()
        val loc = map["loc"]?.uppercase()
        return buildValidatedInfo(ip, loc, nowMs)
    }

    internal fun buildValidatedInfo(rawIp: String, rawCountry: String?, nowMs: Long): ExitIpInfo {
        val cleanIp = rawIp.trim()
        if (!isValidPublicIp(cleanIp)) {
            return ExitIpInfo(
                status = ExitIpStatus.UNAVAILABLE,
                errorNote = "Rejected non-public or malformed IP address"
            )
        }
        val code = rawCountry?.trim()?.uppercase()?.takeIf { isoCountryRegex.matches(it) }
        val countryName = code?.let { mapCountryCodeToEnglishName(it) }
        return ExitIpInfo(
            status = ExitIpStatus.VERIFIED,
            ip = cleanIp,
            countryCode = code,
            countryName = countryName,
            updatedAtMs = nowMs,
            errorNote = null
        )
    }

    /**
     * Validates that [rawIp] is a syntactically valid literal IPv4 or IPv6 address (never triggers DNS)
     * AND is globally reachable according to the IANA IPv4/IPv6 Special-Purpose Address Registries
     * (including documentation, benchmarking, CGNAT, loopback, link-local, RFC1918, ULA, multicast,
     * and IPv4-mapped IPv6, while preserving IANA globally-reachable exceptions).
     */
    fun isValidPublicIp(rawIp: String): Boolean {
        return IpAddressClassifier.isGloballyReachableLiteral(rawIp)
    }

    fun mapCountryCodeToEnglishName(code: String): String {
        val upper = code.trim().uppercase()
        if (!isoCountryRegex.matches(upper)) return upper
        val name = Locale("", upper).getDisplayCountry(Locale.ENGLISH)
        return name.ifBlank { upper }
    }
}
