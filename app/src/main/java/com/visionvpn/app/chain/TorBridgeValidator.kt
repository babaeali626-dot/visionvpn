package com.visionvpn.app.chain

import java.io.File

data class BridgeValidationResult(
    val isValid: Boolean,
    val transport: String = "",
    val normalizedLine: String = "",
    val errorMessage: String? = null
)

data class PluggableTransportAvailability(
    val lyrebirdBinary: File?,
    val snowflakeBinary: File?
) {
    val supportsObfs4: Boolean get() = lyrebirdBinary != null
    val supportsMeek: Boolean get() = lyrebirdBinary != null
    val supportsWebTunnel: Boolean get() = lyrebirdBinary != null
    val supportsSnowflake: Boolean get() = snowflakeBinary != null
}

/**
 * Validates Tor bridge lines (`obfs4`, `snowflake`, `webtunnel`, `meek_lite`, or vanilla)
 * using strict semantic IPv4 and IPv6 literal parsing (zero DNS lookups) and verifies
 * packaged native pluggable transport binaries (`liblyrebird.so`, `libsnowflake.so`).
 */
object TorBridgeValidator {

    private val HEX_40_REGEX = Regex("^[A-Fa-f0-9]{40}$")
    private val HEX_1_TO_4_REGEX = Regex("^[A-Fa-f0-9]{1,4}$")
    private val IPV4_PORT_REGEX = Regex("^(\\d{1,3}\\.){3}\\d{1,3}:(\\d{1,5})$")
    private val SUPPORTED_TRANSPORTS = setOf("obfs4", "snowflake", "webtunnel", "meek_lite")

    fun detectTransportBinaries(nativeLibraryDir: File?): PluggableTransportAvailability {
        if (nativeLibraryDir == null || !nativeLibraryDir.exists()) {
            return PluggableTransportAvailability(null, null)
        }
        val lyrebird = listOf("liblyrebird.so", "libobfs4proxy.so")
            .map { File(nativeLibraryDir, it) }
            .firstOrNull { it.exists() && it.canExecute() }
        val snowflake = listOf("libsnowflake.so", "libsnowflake_client.so")
            .map { File(nativeLibraryDir, it) }
            .firstOrNull { it.exists() && it.canExecute() }
        return PluggableTransportAvailability(lyrebirdBinary = lyrebird, snowflakeBinary = snowflake)
    }

    fun validateLine(rawLine: String): BridgeValidationResult {
        val cleaned = rawLine.trim().removePrefix("Bridge ").removePrefix("bridge ").trim()
        if (cleaned.isBlank()) {
            return BridgeValidationResult(false, errorMessage = "Bridge line is empty.")
        }

        val tokens = cleaned.split(Regex("\\s+"))
        val firstLower = tokens[0].lowercase()

        if (isValidHostPort(tokens[0])) {
            if (tokens.size >= 2 && !HEX_40_REGEX.matches(tokens[1])) {
                return BridgeValidationResult(
                    false,
                    errorMessage = "Invalid 40-character hexadecimal bridge fingerprint."
                )
            }
            return BridgeValidationResult(
                isValid = true,
                transport = "vanilla",
                normalizedLine = cleaned
            )
        }

        if (firstLower !in SUPPORTED_TRANSPORTS) {
            return BridgeValidationResult(
                false,
                errorMessage = "Unsupported bridge transport '${tokens[0]}'. Expected obfs4, snowflake, webtunnel, or meek_lite."
            )
        }

        if (tokens.size < 3) {
            return BridgeValidationResult(
                false,
                errorMessage = "Incomplete $firstLower bridge line: expected address and fingerprint."
            )
        }

        if (!isValidHostPort(tokens[1])) {
            return BridgeValidationResult(
                false,
                errorMessage = "Invalid bridge address '${tokens[1]}'. Expected valid IPv4:PORT or [IPv6]:PORT."
            )
        }

        if (!HEX_40_REGEX.matches(tokens[2])) {
            return BridgeValidationResult(
                false,
                errorMessage = "Invalid bridge fingerprint. Must be 40 hexadecimal characters."
            )
        }

        val kvParams = tokens.drop(3).mapNotNull { token ->
            val eq = token.indexOf('=')
            if (eq > 0) token.substring(0, eq).lowercase() to token.substring(eq + 1) else null
        }.toMap()

        when (firstLower) {
            "obfs4" -> {
                if (kvParams["cert"].isNullOrBlank()) {
                    return BridgeValidationResult(false, errorMessage = "obfs4 bridge requires a 'cert=...' parameter.")
                }
                val iatMode = kvParams["iat-mode"]
                if (iatMode != null && iatMode !in setOf("0", "1", "2")) {
                    return BridgeValidationResult(false, errorMessage = "obfs4 'iat-mode' must be 0, 1, or 2.")
                }
            }
            "webtunnel" -> {
                val url = kvParams["url"]
                if (url.isNullOrBlank() || !url.startsWith("https://")) {
                    return BridgeValidationResult(false, errorMessage = "webtunnel bridge requires an 'url=https://...' parameter.")
                }
            }
            "meek_lite" -> {
                val url = kvParams["url"]
                if (url.isNullOrBlank() || !url.startsWith("https://")) {
                    return BridgeValidationResult(false, errorMessage = "meek_lite bridge requires an 'url=https://...' parameter.")
                }
            }
            "snowflake" -> Unit
        }

        return BridgeValidationResult(
            isValid = true,
            transport = firstLower,
            normalizedLine = cleaned
        )
    }

    fun validateMultiple(rawInput: String): Pair<List<BridgeValidationResult>, List<String>> {
        val valid = mutableListOf<BridgeValidationResult>()
        val errors = mutableListOf<String>()
        rawInput.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEachIndexed { idx, line ->
                val res = validateLine(line)
                if (res.isValid) valid += res
                else errors += "Line ${idx + 1}: ${res.errorMessage}"
            }
        return valid to errors
    }

    fun redactForLog(bridgeLine: String): String {
        val transport = bridgeLine.trim().substringBefore(" ")
        return "$transport <redacted-bridge>"
    }

    internal fun isValidHostPort(value: String): Boolean {
        val v4 = IPV4_PORT_REGEX.matchEntire(value)
        if (v4 != null) {
            val port = v4.groupValues[2].toIntOrNull() ?: return false
            val octets = value.substringBefore(':').split('.').mapNotNull { it.toIntOrNull() }
            return port in 1..65535 && octets.size == 4 && octets.all { it in 0..255 }
        }
        if (value.startsWith("[")) {
            val closeIdx = value.indexOf("]:")
            if (closeIdx <= 1) return false
            val ipv6Literal = value.substring(1, closeIdx)
            val port = value.substring(closeIdx + 2).toIntOrNull() ?: return false
            return port in 1..65535 && isValidIpv6Literal(ipv6Literal)
        }
        return false
    }

    /**
     * Strict semantic IPv6 literal validator (RFC 4291) without triggering any DNS lookups.
     */
    internal fun isValidIpv6Literal(literal: String): Boolean {
        if (literal.isEmpty() || literal.contains(":::")) return false
        val doubleColonParts = literal.split("::")
        if (doubleColonParts.size > 2) return false

        val hasCompression = doubleColonParts.size == 2
        val leftGroups = if (doubleColonParts[0].isEmpty()) emptyList() else doubleColonParts[0].split(":")
        val rightGroups = if (hasCompression && doubleColonParts[1].isNotEmpty()) {
            doubleColonParts[1].split(":")
        } else emptyList()

        val allGroups = leftGroups + rightGroups
        if (allGroups.any { !HEX_1_TO_4_REGEX.matches(it) }) return false

        return if (hasCompression) {
            allGroups.size < 8
        } else {
            allGroups.size == 8
        }
    }
}
