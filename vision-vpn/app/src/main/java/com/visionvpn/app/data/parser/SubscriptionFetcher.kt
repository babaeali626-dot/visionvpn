package com.visionvpn.app.data.parser

import com.visionvpn.app.data.model.ProxyProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class SubscriptionFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    suspend fun fetchSubscription(url: String, subscriptionId: String): Result<List<ProxyProfile>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(url.trim())
                    .header("User-Agent", "VisionVPN/1.0 (Sing-box; Android)")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("HTTP ${response.code}: Failed to fetch subscription")
                    }
                    val body = response.body?.string().orEmpty()
                    val profiles = ProfileUriParser.parseBatch(body, subscriptionId)
                    if (profiles.isEmpty()) {
                        error("No supported proxy profiles found in subscription")
                    }
                    profiles
                }
            }
        }
}
