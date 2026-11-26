package com.visionvpn.app.data.parser

import com.visionvpn.app.core.IpAddressClassifier
import com.visionvpn.app.core.LogRedactor
import com.visionvpn.app.data.model.ProxyProfile
import com.visionvpn.app.data.model.SubscriptionMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class FetchedSubscription(
    val profiles: List<ProxyProfile>,
    val format: DetectedSubscriptionFormat,
    val metadata: SubscriptionMetadata
)

/**
 * Custom OkHttp [Dns] that validates EVERY resolved A/AAAA record against
 * [IpAddressClassifier.isGloballyReachableAddress] and passes the exact validated
 * [InetAddress] list directly to OkHttp's socket connector, preventing DNS-rebinding
 * bypasses and mixed public/private record SSRF.
 */
internal class SafePublicDns(
    private val delegate: Dns = Dns.SYSTEM
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val normalized = hostname.trim().lowercase()
        if (normalized == "localhost" || normalized.endsWith(".localhost") || normalized.endsWith(".local")) {
            throw UnknownHostException("Blocked local hostname: $hostname")
        }
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty()) {
            throw UnknownHostException("DNS returned no addresses for $hostname")
        }
        // Reject conservatively if ANY resolved A/AAAA record is non-globally-reachable
        val hasUnsafe = addresses.any { !IpAddressClassifier.isGloballyReachableAddress(it) }
        if (hasUnsafe) {
            throw UnknownHostException("Blocked non-globally-reachable destination IP for $hostname")
        }
        return addresses
    }
}

class SubscriptionFetcher(
    baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
    dns: Dns = Dns.SYSTEM
) {
    // Enforce manual bounded redirect handling + anti-DNS-rebinding SafePublicDns on every request
    private val client: OkHttpClient = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(SafePublicDns(dns))
        .build()

    companion object {
        const val MAX_REDIRECTS = 4

        /**
         * Validates a subscription URL (initial or redirect hop):
         * - Must use `https://` scheme (no cleartext `http://` or downgrade).
         * - Must not contain URL userinfo credentials (`user:pass@host`).
         * - Must not target `localhost`, `*.localhost`, `*.local`, or any non-globally-reachable
         *   literal IPv4/IPv6 address per IANA Special-Purpose Address Registries.
         */
        fun validateSubscriptionUrl(rawUrl: String): String {
            val cleanUrl = ProfileUriParser.unwrapHiddifyImportLink(rawUrl).trim()
            val parsed = cleanUrl.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Malformed subscription URL.")
            validateHttpUrl(parsed)
            return cleanUrl
        }

        internal fun validateHttpUrl(url: HttpUrl) {
            require(url.scheme.equals("https", ignoreCase = true)) {
                "Subscription URL must use HTTPS (cleartext http:// subscriptions and HTTPS->HTTP downgrades are blocked)."
            }
            require(url.username.isEmpty() && url.password.isEmpty()) {
                "Subscription URL must not include embedded userinfo credentials."
            }
            val host = url.host.trim().lowercase()
            require(host.isNotBlank()) {
                "Subscription URL is missing a valid hostname."
            }
            require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) {
                "Subscription URL cannot point to a local/loopback host."
            }
            // If host is an IP literal, validate it without performing DNS
            val literalBytes = IpAddressClassifier.parseIpLiteralBytes(host)
            if (literalBytes != null) {
                require(IpAddressClassifier.isGloballyReachableBytes(literalBytes)) {
                    "Subscription URL cannot point to a non-globally-reachable IP address."
                }
            }
        }
    }

    suspend fun fetchSubscriptionDetailed(
        url: String,
        subscriptionId: String
    ): Result<FetchedSubscription> = withContext(Dispatchers.IO) {
        try {
            var currentUrl = validateSubscriptionUrl(url).toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Malformed subscription URL.")
            val visited = mutableSetOf<String>()

            for (hop in 0..MAX_REDIRECTS) {
                validateHttpUrl(currentUrl)
                if (!visited.add(currentUrl.toString())) {
                    error("Subscription redirect loop detected.")
                }

                val request = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", "VisionVPN/1.2.0 (Hiddify-Core/5.9.15; Android)")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code in setOf(301, 302, 303, 307, 308)) {
                        if (hop >= MAX_REDIRECTS) {
                            error("Subscription exceeded maximum redirect limit ($MAX_REDIRECTS).")
                        }
                        val location = response.header("Location")?.trim()
                            ?: error("Redirect response ($code) missing Location header.")
                        val nextUrl = currentUrl.resolve(location)
                            ?: error("Malformed redirect Location URL.")
                        validateHttpUrl(nextUrl)
                        currentUrl = nextUrl
                        return@use
                    }

                    if (!response.isSuccessful) {
                        error("HTTP $code: Failed to fetch subscription")
                    }

                    val bodyStream = response.body?.byteStream()
                        ?: error("Empty subscription response body")
                    val body = readBoundedUtf8(bodyStream, SubscriptionFormatParser.MAX_SUBSCRIPTION_BYTES)

                    val httpTitle = SubscriptionFormatParser.decodeProfileTitle(response.header("profile-title"))
                        ?: extractFilenameFromContentDisposition(response.header("content-disposition"))
                    val userInfo = SubscriptionFormatParser.parseSubscriptionUserInfo(
                        response.header("subscription-userinfo")
                    )
                    val updateInterval = response.header("profile-update-interval")
                        ?.trim()
                        ?.toIntOrNull()
                        ?.takeIf { it > 0 }

                    val httpMeta = SubscriptionMetadata(
                        title = httpTitle,
                        updateIntervalHours = updateInterval,
                        uploadBytes = userInfo.uploadBytes,
                        downloadBytes = userInfo.downloadBytes,
                        totalBytes = userInfo.totalBytes,
                        expireEpochSec = userInfo.expireEpochSec
                    )

                    val parsed = SubscriptionFormatParser.parse(
                        rawInput = body,
                        subscriptionId = subscriptionId,
                        httpMetadata = httpMeta
                    ).getOrThrow()

                    return@withContext Result.success(
                        FetchedSubscription(
                            profiles = parsed.profiles,
                            format = parsed.format,
                            metadata = parsed.metadata
                        )
                    )
                }
            }
            error("Subscription exceeded maximum redirect limit ($MAX_REDIRECTS).")
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(IllegalStateException(LogRedactor.redact(e.message ?: "Subscription fetch failed"), e))
        }
    }

    suspend fun fetchSubscription(url: String, subscriptionId: String): Result<List<ProxyProfile>> {
        return fetchSubscriptionDetailed(url, subscriptionId).map { it.profiles }
    }

    private fun readBoundedUtf8(input: InputStream, maxBytes: Int): String {
        val output = ByteArrayOutputStream(8 * 1024)
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            total += count
            if (total > maxBytes) {
                throw IOException("Subscription payload exceeds the 2 MB maximum size limit.")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun extractFilenameFromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val utf8Match = Regex("filename\\*=(?:UTF-8|utf-8)''([^;]+)").find(header)
        if (utf8Match != null) {
            return ProfileUriParser.decodeUrl(utf8Match.groupValues[1].trim()).takeIf { it.isNotBlank() }
        }
        val plainMatch = Regex("filename=\"?([^\";]+)\"?").find(header)
        return plainMatch?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }
}
