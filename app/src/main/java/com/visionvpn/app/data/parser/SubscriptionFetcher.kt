package com.visionvpn.app.data.parser

import com.visionvpn.app.data.model.ProxyProfile
import com.visionvpn.app.data.model.SubscriptionMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class FetchedSubscription(
    val profiles: List<ProxyProfile>,
    val format: DetectedSubscriptionFormat,
    val metadata: SubscriptionMetadata
)

class SubscriptionFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    suspend fun fetchSubscriptionDetailed(
        url: String,
        subscriptionId: String
    ): Result<FetchedSubscription> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanUrl = ProfileUriParser.unwrapHiddifyImportLink(url).trim()
            require(cleanUrl.startsWith("https://", ignoreCase = true) || cleanUrl.startsWith("http://", ignoreCase = true)) {
                "Subscription URL must start with https:// or http://"
            }

            val request = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "VisionVPN/1.1 (Sing-box/1.10.7; Android)")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}: Failed to fetch subscription")
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

                FetchedSubscription(
                    profiles = parsed.profiles,
                    format = parsed.format,
                    metadata = parsed.metadata
                )
            }
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
