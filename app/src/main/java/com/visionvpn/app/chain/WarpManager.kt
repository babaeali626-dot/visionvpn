package com.visionvpn.app.chain

import android.util.Base64
import com.visionvpn.app.core.crypto.X25519KeyGen
import com.visionvpn.app.data.model.WarpAccountConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Registers Cloudflare WARP WireGuard credentials via Cloudflare's client registration API
 * and derives the 3-byte `reserved` routing identifier from `client_id`.
 */
class WarpManager(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private const val WARP_REG_ENDPOINT = "https://api.cloudflareclient.com/v0a2158/reg"
        private const val DEFAULT_PEER_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
        private const val DEFAULT_ENDPOINT_HOST = "engage.cloudflareclient.com"
        private const val DEFAULT_ENDPOINT_PORT = 2408
    }

    suspend fun registerNewAccount(
        existingEndpointHost: String = DEFAULT_ENDPOINT_HOST,
        existingEndpointPort: Int = DEFAULT_ENDPOINT_PORT
    ): Result<WarpAccountConfig> = withContext(Dispatchers.IO) {
        try {
            val keyPair = X25519KeyGen.generateKeyPair()
            val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

            val payload = buildJsonObject {
                put("key", keyPair.publicKeyBase64)
                put("install_id", "")
                put("fcm_token", "")
                put("tos", timestamp)
                put("model", "Android")
                put("serial_number", "")
                put("locale", "en_US")
            }.toString()

            val request = Request.Builder()
                .url(WARP_REG_ENDPOINT)
                .header("User-Agent", "okhttp/3.12.1")
                .header("CF-Client-Version", "a-6.11-2223")
                .header("Content-Type", "application/json; charset=UTF-8")
                .post(payload.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Cloudflare WARP registration failed (HTTP ${response.code})")
                }
                val raw = response.body?.string().orEmpty()
                parseRegistrationResponse(
                    raw = raw,
                    privateKey = keyPair.privateKeyBase64,
                    publicKey = keyPair.publicKeyBase64,
                    fallbackHost = existingEndpointHost,
                    fallbackPort = existingEndpointPort
                )
            }.let { Result.success(it) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseRegistrationResponse(
        raw: String,
        privateKey: String,
        publicKey: String,
        fallbackHost: String,
        fallbackPort: Int
    ): WarpAccountConfig {
        val root: JsonObject = json.parseToJsonElement(raw).jsonObject
        val accountId = root["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val token = root["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val configObj = root["config"]?.jsonObject

        val clientIdB64 = configObj?.get("client_id")?.jsonPrimitive?.contentOrNull.orEmpty()
        val reserved = decodeReservedBytes(clientIdB64)

        val addressesObj = configObj?.get("interface")?.jsonObject?.get("addresses")?.jsonObject
        val v4 = addressesObj?.get("v4")?.jsonPrimitive?.contentOrNull?.let { "$it/32" }
            ?: "172.16.0.2/32"
        val v6 = addressesObj?.get("v6")?.jsonPrimitive?.contentOrNull?.let { "$it/128" }
            ?: "2606:4700:110:8a36:dfab:af1:636:4e94/128"

        val firstPeer = configObj?.get("peers")?.jsonArray?.firstOrNull()?.jsonObject
        val peerPublicKey = firstPeer?.get("public_key")?.jsonPrimitive?.contentOrNull
            ?: DEFAULT_PEER_KEY
        val endpointHostRaw = firstPeer?.get("endpoint")?.jsonObject
            ?.get("host")?.jsonPrimitive?.contentOrNull
        val endpointHost = endpointHostRaw?.substringBefore(":")?.ifBlank { fallbackHost }
            ?: fallbackHost
        val endpointPort = endpointHostRaw?.substringAfter(":", "")?.toIntOrNull()
            ?: fallbackPort

        return WarpAccountConfig(
            privateKey = privateKey,
            publicKey = publicKey,
            peerPublicKey = peerPublicKey,
            endpointHost = endpointHost,
            endpointPort = endpointPort,
            ipv4Address = v4,
            ipv6Address = v6,
            reserved = reserved,
            mtu = 1280,
            accountId = accountId,
            accessToken = token,
            registeredAtEpochMs = System.currentTimeMillis()
        )
    }

    private fun decodeReservedBytes(clientIdBase64: String): List<Int> {
        if (clientIdBase64.isBlank()) return listOf(0, 0, 0)
        return runCatching {
            val decoded = Base64.decode(clientIdBase64, Base64.DEFAULT)
            if (decoded.size >= 3) {
                listOf(
                    decoded[0].toInt() and 0xFF,
                    decoded[1].toInt() and 0xFF,
                    decoded[2].toInt() and 0xFF
                )
            } else listOf(0, 0, 0)
        }.getOrDefault(listOf(0, 0, 0))
    }
}
