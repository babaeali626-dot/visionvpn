package com.hiddify.hiddify.tor

import android.content.Context
import java.io.File

/**
 * Manages Android-compatible Tor binary (libtor.so) and Pluggable Transport binaries
 * (Snowflake -> libsnowflake.so, obfs4/lyrebird -> liblyrebird.so) packaged into nativeLibraryDir,
 * plus torrc generation and strict bridge line validation.
 */
internal object TorTransportConfig {
    const val MODE_AUTOMATIC = "automatic"
    const val MODE_SNOWFLAKE = "snowflake"
    const val MODE_OBFS4 = "obfs4"

    private const val TOR_LIB = "libtor.so"
    private const val SNOWFLAKE_LIB = "libsnowflake.so"
    private const val LYREBIRD_LIB = "liblyrebird.so"

    /**
     * Official Tor Project Snowflake default bridge lines (from Tor Browser / Tor Project).
     * Used when Snowflake transport is selected and no custom snowflake bridge is configured.
     */
    val DEFAULT_SNOWFLAKE_BRIDGES: List<String> = listOf(
        "Bridge snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "url=https://1098762253.rsc.cdn77.org/ " +
            "fronts=www.cdn77.com,www.phpmyadmin.net " +
            "ice=stun:stun.l.google.com:19302,stun:stun.antisip.com:3478,stun:stun.bluesip.net:3478," +
            "stun:stun.dus.net:3478,stun:stun.epygi.com:3478,stun:stun.sonetel.com:3478," +
            "stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.voys.nl:3478 " +
            "utls-imitate=hellorandomizedalpn",
        "Bridge snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA " +
            "fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA " +
            "url=https://1098762253.rsc.cdn77.org/ " +
            "fronts=www.cdn77.com,www.phpmyadmin.net " +
            "ice=stun:stun.l.google.com:19302,stun:stun.antisip.com:3478,stun:stun.bluesip.net:3478," +
            "stun:stun.dus.net:3478,stun:stun.epygi.com:3478,stun:stun.sonetel.com:3478," +
            "stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.voys.nl:3478 " +
            "utls-imitate=hellorandomizedalpn",
    )

    /**
     * Built-in official Tor Project default obfs4 bridges (used only when the user selects obfs4
     * without providing custom bridges from BridgeDB/Telegram/Email).
     */
    val DEFAULT_OBFS4_BRIDGES: List<String> = listOf(
        "Bridge obfs4 192.95.36.142:443 CDF2E852BF539B82BD10E27E9115A31734E378C2 " +
            "cert=qUVQ0srL1JI/vO6V6m/24anYXiJD3QP2HgzUKQtQ7GRqqUvs7P+tG43RtAqdhLOALP7DJQ iat-mode=1",
        "Bridge obfs4 37.218.245.14:38224 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D " +
            "cert=bjRaMrr1BRiAW8IE9U5z27fQaYgOhX1UCmOpg2pFpoMvo6ZgQMzLsaTzzQNTlm7hNcb+Sg iat-mode=0",
        "Bridge obfs4 85.31.186.98:443 011F2599C0E9B27EE74B353155E244813763C3E5 " +
            "cert=ayq0XzCwhpdysn5o0EyDUbmSOx3X/oTEbzDMvczHOdBJKlvIdHHLJGkzartT4dcBFArPPg iat-mode=0",
    )

    data class Capabilities(
        val torAvailable: Boolean,
        val snowflakeAvailable: Boolean,
        val obfs4Available: Boolean,
        val torPath: String?,
        val snowflakePath: String?,
        val obfs4Path: String?,
    )

    data class BuiltTorrc(
        val content: String,
        val activeTransport: String,
        val bridgeCount: Int,
    )

    private val VANILLA_ENDPOINT_REGEX = Regex("^(\\[[0-9a-fA-F:]+\\]|[A-Za-z0-9.-]+):[0-9]{1,5}$")
    private val HEX_FINGERPRINT_REGEX = Regex("^[0-9A-Fa-f]{40}$")

    fun normalizeMode(raw: String?): String = when (raw?.trim()?.lowercase()) {
        MODE_SNOWFLAKE -> MODE_SNOWFLAKE
        MODE_OBFS4 -> MODE_OBFS4
        else -> MODE_AUTOMATIC
    }

    fun capabilities(context: Context): Capabilities {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val tor = File(nativeDir, TOR_LIB)
        val snowflake = File(nativeDir, SNOWFLAKE_LIB)
        val lyrebird = File(nativeDir, LYREBIRD_LIB)
        val torOk = tor.exists() && tor.canExecute()
        val snowflakeOk = snowflake.exists() && snowflake.canExecute()
        val obfs4Ok = lyrebird.exists() && lyrebird.canExecute()
        return Capabilities(
            torAvailable = torOk,
            snowflakeAvailable = snowflakeOk,
            obfs4Available = obfs4Ok,
            torPath = if (torOk) tor.absolutePath else null,
            snowflakePath = if (snowflakeOk) snowflake.absolutePath else null,
            obfs4Path = if (obfs4Ok) lyrebird.absolutePath else null,
        )
    }

    fun normalizeAndValidateBridgeLine(rawLine: String): String {
        val trimmed = rawLine.trim()
        require(trimmed.isNotEmpty()) { "Bridge line cannot be empty" }
        require(!trimmed.contains('\n') && !trimmed.contains('\r') && !trimmed.contains('#')) {
            "Bridge line contains disallowed control or comment characters"
        }
        require(trimmed.length <= 1024) { "Bridge line exceeds 1024 characters" }

        val normalized = if (trimmed.startsWith("Bridge ", ignoreCase = true)) {
            "Bridge " + trimmed.substring(7).trim()
        } else {
            "Bridge $trimmed"
        }

        val tokens = normalized.split(Regex("\\s+"))
        require(tokens.size >= 2) { "Bridge line is incomplete" }

        val second = tokens[1].lowercase()
        when (second) {
            "obfs4" -> {
                require(tokens.size >= 5) {
                    "Invalid obfs4 bridge: expected 'Bridge obfs4 <ip:port> <fingerprint> cert=... iat-mode=...'"
                }
                validateEndpoint(tokens[2])
                require(HEX_FINGERPRINT_REGEX.matches(tokens[3])) {
                    "Invalid obfs4 bridge fingerprint"
                }
                val hasCert = tokens.drop(4).any { it.startsWith("cert=") && it.length > 10 }
                require(hasCert) { "obfs4 bridge line must include a valid cert= parameter" }
            }
            "snowflake" -> {
                require(tokens.size >= 4) {
                    "Invalid snowflake bridge: expected 'Bridge snowflake <endpoint> <fingerprint> ...'"
                }
                validateEndpoint(tokens[2])
                require(HEX_FINGERPRINT_REGEX.matches(tokens[3])) {
                    "Invalid snowflake bridge fingerprint"
                }
            }
            else -> {
                validateEndpoint(tokens[1])
            }
        }
        return normalized
    }

    private fun validateEndpoint(endpoint: String) {
        require(VANILLA_ENDPOINT_REGEX.matches(endpoint)) {
            "Invalid bridge address:port '$endpoint'"
        }
        val port = endpoint.substringAfterLast(':').toIntOrNull()
        require(port != null && port in 1..65535) { "Bridge port is out of range: $endpoint" }
    }

    fun buildTorrc(
        context: Context,
        requestedMode: String,
        customBridgeLines: List<String>,
        socksPort: Int,
        dataDir: File,
        cacheDir: File,
        owningPid: Int,
        forceSnowflakeFallback: Boolean = false,
    ): BuiltTorrc {
        require(socksPort in 1..65535) { "Invalid SOCKS port: $socksPort" }
        val caps = capabilities(context)
        val mode = normalizeMode(requestedMode)
        val sanitizedCustom = customBridgeLines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .also { require(it.size <= 30) { "At most 30 custom bridge lines are allowed" } }
            .map(::normalizeAndValidateBridgeLine)

        val customObfs4 = sanitizedCustom.filter { it.startsWith("Bridge obfs4 ", ignoreCase = true) }
        val customSnowflake = sanitizedCustom.filter { it.startsWith("Bridge snowflake ", ignoreCase = true) }
        val customVanilla = sanitizedCustom.filter {
            !it.startsWith("Bridge obfs4 ", ignoreCase = true) &&
                !it.startsWith("Bridge snowflake ", ignoreCase = true)
        }

        val resolvedTransport: String
        val pluginLines = mutableListOf<String>()
        val selectedBridges = mutableListOf<String>()

        when {
            mode == MODE_SNOWFLAKE || (mode == MODE_AUTOMATIC && forceSnowflakeFallback) -> {
                val bin = caps.snowflakePath
                    ?: throw IllegalStateException(
                        "Snowflake transport binary (libsnowflake.so) is not packaged in this APK ABI"
                    )
                resolvedTransport = MODE_SNOWFLAKE
                pluginLines += "ClientTransportPlugin snowflake exec $bin"
                selectedBridges += if (customSnowflake.isNotEmpty()) customSnowflake else DEFAULT_SNOWFLAKE_BRIDGES
            }
            mode == MODE_OBFS4 -> {
                val bin = caps.obfs4Path
                    ?: throw IllegalStateException(
                        "obfs4 transport binary (liblyrebird.so) is not packaged in this APK ABI"
                    )
                resolvedTransport = MODE_OBFS4
                pluginLines += "ClientTransportPlugin obfs4 exec $bin"
                selectedBridges += if (customObfs4.isNotEmpty()) customObfs4 else DEFAULT_OBFS4_BRIDGES
            }
            else -> {
                when {
                    customObfs4.isNotEmpty() && caps.obfs4Path != null -> {
                        resolvedTransport = MODE_OBFS4
                        pluginLines += "ClientTransportPlugin obfs4 exec ${caps.obfs4Path}"
                        selectedBridges += customObfs4
                    }
                    customSnowflake.isNotEmpty() && caps.snowflakePath != null -> {
                        resolvedTransport = MODE_SNOWFLAKE
                        pluginLines += "ClientTransportPlugin snowflake exec ${caps.snowflakePath}"
                        selectedBridges += customSnowflake
                    }
                    customVanilla.isNotEmpty() -> {
                        resolvedTransport = "vanilla-bridge"
                        selectedBridges += customVanilla
                    }
                    else -> {
                        resolvedTransport = "direct"
                    }
                }
            }
        }

        val torrc = buildString {
            appendLine("RunAsDaemon 0")
            appendLine("ClientOnly 1")
            appendLine("SafeSocks 1")
            appendLine("TestSocks 1")
            appendLine("AvoidDiskWrites 1")
            appendLine("DormantCanceledByStartup 1")
            appendLine("Log notice stdout")
            appendLine("DataDirectory ${dataDir.absolutePath}")
            appendLine("CacheDirectory ${cacheDir.absolutePath}")
            appendLine("SocksPort 127.0.0.1:$socksPort IsolateClientAddr IsolateSOCKSAuth IsolateClientProtocol IsolateDestPort IsolateDestAddr")
            if (owningPid > 0) {
                appendLine("__OwningControllerProcess $owningPid")
            }
            if (selectedBridges.isNotEmpty()) {
                appendLine("UseBridges 1")
                pluginLines.forEach(::appendLine)
                selectedBridges.forEach(::appendLine)
            }
        }

        return BuiltTorrc(
            content = torrc,
            activeTransport = resolvedTransport,
            bridgeCount = selectedBridges.size,
        )
    }
}
