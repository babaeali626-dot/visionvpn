package com.visionvpn.app.core

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Auditable IPv4/IPv6 global-reachability classifier based on the official IANA Special-Purpose
 * Address Registries (`Globally Reachable` column and its documented exceptions):
 * - IANA IPv4 Special-Purpose Address Registry (RFC 6890, RFC 1918, RFC 6598, RFC 5737):
 *   https://www.iana.org/assignments/iana-ipv4-special-registry/iana-ipv4-special-registry.xhtml
 * - IANA IPv6 Special-Purpose Address Registry (RFC 6890, RFC 4193, RFC 3849, RFC 9637):
 *   https://www.iana.org/assignments/iana-ipv6-special-registry/iana-ipv6-special-registry.xhtml
 *
 * Registry values are evaluated against the current IANA `Globally Reachable` field;
 * unknown/non-global address space is rejected conservatively.
 *
 * Literal parsing NEVER invokes DNS resolution.
 */
object IpAddressClassifier {

    private data class CidrBlock(val networkBytes: ByteArray, val prefixLen: Int) {
        fun matches(addrBytes: ByteArray): Boolean {
            if (addrBytes.size != networkBytes.size) return false
            val fullBytes = prefixLen / 8
            val remBits = prefixLen % 8
            for (i in 0 until fullBytes) {
                if (addrBytes[i] != networkBytes[i]) return false
            }
            if (remBits > 0) {
                val mask = (0xFF shl (8 - remBits)) and 0xFF
                if ((addrBytes[fullBytes].toInt() and mask) != (networkBytes[fullBytes].toInt() and mask)) {
                    return false
                }
            }
            return true
        }
    }

    private fun v4(a: Int, b: Int, c: Int, d: Int, prefix: Int) =
        CidrBlock(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()), prefix)

    private fun v6(hexGroups: IntArray, prefix: Int): CidrBlock {
        val bytes = ByteArray(16)
        for (i in 0 until 8) {
            val g = if (i < hexGroups.size) hexGroups[i] else 0
            bytes[i * 2] = ((g shr 8) and 0xFF).toByte()
            bytes[i * 2 + 1] = (g and 0xFF).toByte()
        }
        return CidrBlock(bytes, prefix)
    }

    // IANA IPv4 Globally Reachable = True exceptions inside 192.0.0.0/24
    private val ipv4GlobalExceptions = listOf(
        v4(192, 0, 0, 9, 32),   // Port Control Protocol Anycast (RFC 7723)
        v4(192, 0, 0, 10, 32)   // Traversal Using Relays around NAT Anycast (RFC 8155)
    )

    // IANA IPv4 Special-Purpose blocks with Globally Reachable = False (plus multicast)
    private val ipv4NonGlobalBlocks = listOf(
        v4(0, 0, 0, 0, 8),          // "This network" (RFC 1122)
        v4(10, 0, 0, 0, 8),         // Private-Use (RFC 1918)
        v4(100, 64, 0, 0, 10),      // Shared Address Space / CGNAT (RFC 6598)
        v4(127, 0, 0, 0, 8),        // Loopback (RFC 1122)
        v4(169, 254, 0, 0, 16),     // Link Local & Cloud Metadata (RFC 3927)
        v4(172, 16, 0, 0, 12),      // Private-Use (RFC 1918)
        v4(192, 0, 0, 0, 24),       // IETF Protocol Assignments (RFC 6890; exceptions checked first)
        v4(192, 0, 2, 0, 24),       // Documentation TEST-NET-1 (RFC 5737)
        v4(192, 88, 99, 0, 24),     // 6to4 Relay Anycast (Deprecated, RFC 7526)
        v4(192, 168, 0, 0, 16),     // Private-Use (RFC 1918)
        v4(198, 18, 0, 0, 15),      // Benchmarking / FakeIP (RFC 2544)
        v4(198, 51, 100, 0, 24),    // Documentation TEST-NET-2 (RFC 5737)
        v4(203, 0, 113, 0, 24),     // Documentation TEST-NET-3 (RFC 5737)
        v4(224, 0, 0, 0, 4),        // Multicast (RFC 5771)
        v4(240, 0, 0, 0, 4)         // Reserved & Limited Broadcast 255.255.255.255/32 (RFC 1112)
    )

    // IPv6 global unicast allocation: 2000::/3. Addresses outside this range are rejected
    // unless IANA explicitly lists a more-specific globally-reachable exception below.
    private val ipv6GlobalUnicast = v6(intArrayOf(0x2000, 0, 0, 0, 0, 0, 0, 0), 3)

    // Well-Known NAT64 prefix is globally reachable, but the embedded IPv4 destination must
    // itself be globally reachable too; otherwise it could tunnel SSRF to RFC1918/loopback IPs.
    private val ipv6WellKnownNat64 = v6(intArrayOf(0x0064, 0xFF9B, 0, 0, 0, 0, 0, 0), 96)

    // IANA IPv6 Globally Reachable = True exceptions inside special-purpose allocations.
    // Registry source: https://www.iana.org/assignments/iana-ipv6-special-registry/
    private val ipv6GlobalExceptions = listOf(
        v6(intArrayOf(0x2001, 0x0001, 0, 0, 0, 0, 0, 1), 128),  // PCP Anycast (RFC 7723)
        v6(intArrayOf(0x2001, 0x0001, 0, 0, 0, 0, 0, 2), 128),  // TURN Anycast (RFC 8155)
        v6(intArrayOf(0x2001, 0x0001, 0, 0, 0, 0, 0, 3), 128),  // DNS-SD Anycast (RFC 9665)
        v6(intArrayOf(0x2001, 0x0003, 0, 0, 0, 0, 0, 0), 32),   // AMT (RFC 7450)
        v6(intArrayOf(0x2001, 0x0004, 0x0112, 0, 0, 0, 0, 0), 48), // AS112-v6 (RFC 7535)
        v6(intArrayOf(0x2001, 0x0020, 0, 0, 0, 0, 0, 0), 28),   // ORCHIDv2 (RFC 7343)
        v6(intArrayOf(0x2001, 0x0030, 0, 0, 0, 0, 0, 0), 28),   // Drone Remote ID (RFC 9374)
        v6(intArrayOf(0x2620, 0x004F, 0x8000, 0, 0, 0, 0, 0), 48) // Direct Delegation AS112 (RFC 7534)
    )

    // IANA IPv6 Special-Purpose blocks with Globally Reachable = False (plus multicast)
    private val ipv6NonGlobalBlocks = listOf(
        v6(intArrayOf(0, 0, 0, 0, 0, 0, 0, 0), 128),             // Unspecified ::/128
        v6(intArrayOf(0, 0, 0, 0, 0, 0, 0, 1), 128),             // Loopback ::1/128
        v6(intArrayOf(0, 0, 0, 0, 0, 0xFFFF, 0, 0), 96),         // IPv4-mapped ::ffff:0:0/96
        v6(intArrayOf(0x0064, 0xFF9B, 0x0001, 0, 0, 0, 0, 0), 48), // IPv4-IPv6 Local-Use Translation (RFC 8215)
        v6(intArrayOf(0x0100, 0, 0, 0, 0, 0, 0, 0), 64),         // Discard-Only 100::/64 (RFC 6666)
        v6(intArrayOf(0x0100, 0, 0, 1, 0, 0, 0, 0), 64),         // Dummy IPv6 Prefix (RFC 9780)
        v6(intArrayOf(0x2001, 0, 0, 0, 0, 0, 0, 0), 23),         // IETF Protocol Assignments (exceptions checked first)
        v6(intArrayOf(0x2001, 0x0002, 0, 0, 0, 0, 0, 0), 48),   // Benchmarking (RFC 5180)
        v6(intArrayOf(0x2001, 0x0DB8, 0, 0, 0, 0, 0, 0), 32),    // Documentation 2001:db8::/32 (RFC 3849)
        v6(intArrayOf(0x2002, 0, 0, 0, 0, 0, 0, 0), 16),         // 6to4 2002::/16 (RFC 3056 / RFC 6890)
        v6(intArrayOf(0x3FFF, 0, 0, 0, 0, 0, 0, 0), 20),         // Documentation 3fff::/20 (RFC 9637)
        v6(intArrayOf(0x5F00, 0, 0, 0, 0, 0, 0, 0), 16),         // SRv6 SIDs 5f00::/16 (RFC 9602)
        v6(intArrayOf(0xFC00, 0, 0, 0, 0, 0, 0, 0), 7),          // Unique-Local fc00::/7 (RFC 4193)
        v6(intArrayOf(0xFE80, 0, 0, 0, 0, 0, 0, 0), 10),         // Link-Local fe80::/10 (RFC 4291)
        v6(intArrayOf(0xFEC0, 0, 0, 0, 0, 0, 0, 0), 10),         // Deprecated Site-Local fec0::/10 (RFC 3879)
        v6(intArrayOf(0xFF00, 0, 0, 0, 0, 0, 0, 0), 8)           // Multicast ff00::/8 (RFC 4291)
    )

    /**
     * Parses a literal IPv4 or IPv6 address WITHOUT ever triggering DNS resolution.
     * Returns raw 4-byte (IPv4) or 16-byte (IPv6) address bytes, or `null` if [raw] is not a
     * syntactically valid IP literal (including hostnames).
     */
    fun parseIpLiteralBytes(raw: String): ByteArray? {
        val cleaned = raw.trim().removeSurrounding("[", "]")
        if (cleaned.isEmpty() || cleaned.any { it.isWhitespace() } || '%' in cleaned) return null
        return if (':' in cleaned) parseIpv6Bytes(cleaned) else parseIpv4Bytes(cleaned)
    }

    /**
     * Returns `true` iff [raw] is a syntactically valid literal IPv4 or IPv6 address that is
     * globally reachable according to IANA Special-Purpose Address Registries. Never performs DNS.
     */
    fun isGloballyReachableLiteral(raw: String): Boolean {
        val bytes = parseIpLiteralBytes(raw) ?: return false
        return isGloballyReachableBytes(bytes)
    }

    /**
     * Returns `true` iff [address] is globally reachable according to IANA Special-Purpose registries.
     */
    fun isGloballyReachableAddress(address: InetAddress): Boolean {
        if (address !is Inet4Address && address !is Inet6Address) return false
        return isGloballyReachableBytes(address.address)
    }

    fun isGloballyReachableBytes(bytes: ByteArray): Boolean = when (bytes.size) {
        4 -> {
            if (ipv4GlobalExceptions.any { it.matches(bytes) }) true
            else ipv4NonGlobalBlocks.none { it.matches(bytes) }
        }
        16 -> {
            when {
                // RFC 6052 well-known NAT64 prefix: validate the embedded IPv4 destination too.
                ipv6WellKnownNat64.matches(bytes) ->
                    isGloballyReachableBytes(bytes.copyOfRange(12, 16))
                ipv6GlobalExceptions.any { it.matches(bytes) } -> true
                else -> ipv6GlobalUnicast.matches(bytes) && ipv6NonGlobalBlocks.none { it.matches(bytes) }
            }
        }
        else -> false
    }

    private fun parseIpv4Bytes(s: String): ByteArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0 until 4) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            if (p.length > 1 && p[0] == '0') return null // Reject ambiguous octal/leading-zero IPv4
            val v = p.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out[i] = v.toByte()
        }
        return out
    }

    private fun parseIpv6Bytes(raw: String): ByteArray? {
        // Handle embedded IPv4 suffix (e.g., ::ffff:192.0.2.1)
        var s = raw
        var embeddedV4: ByteArray? = null
        if ('.' in s) {
            val lastColon = s.lastIndexOf(':')
            if (lastColon < 0) return null
            embeddedV4 = parseIpv4Bytes(s.substring(lastColon + 1)) ?: return null
            s = s.substring(0, lastColon)
            if (s.isEmpty()) return null
        }

        val doubleColonIdx = s.indexOf("::")
        if (doubleColonIdx >= 0 && s.indexOf("::", doubleColonIdx + 2) >= 0) return null // Multiple ::

        val targetGroups = if (embeddedV4 != null) 6 else 8
        val headGroups: List<Int>
        val tailGroups: List<Int>

        if (doubleColonIdx >= 0) {
            val leftStr = s.substring(0, doubleColonIdx)
            val rightStr = s.substring(doubleColonIdx + 2)
            headGroups = if (leftStr.isEmpty()) emptyList() else parseHexGroups(leftStr) ?: return null
            tailGroups = if (rightStr.isEmpty()) emptyList() else parseHexGroups(rightStr) ?: return null
            if (headGroups.size + tailGroups.size >= targetGroups) return null
        } else {
            headGroups = parseHexGroups(s) ?: return null
            tailGroups = emptyList()
            if (headGroups.size != targetGroups) return null
        }

        val zeros = targetGroups - headGroups.size - tailGroups.size
        val out = ByteArray(16)
        var pos = 0
        fun writeGroup(g: Int) {
            out[pos++] = ((g shr 8) and 0xFF).toByte()
            out[pos++] = (g and 0xFF).toByte()
        }
        headGroups.forEach { writeGroup(it) }
        repeat(zeros) { writeGroup(0) }
        tailGroups.forEach { writeGroup(it) }
        if (embeddedV4 != null) {
            System.arraycopy(embeddedV4, 0, out, 12, 4)
        }
        return out
    }

    private fun parseHexGroups(part: String): List<Int>? {
        val tokens = part.split(':')
        val result = ArrayList<Int>(tokens.size)
        for (tok in tokens) {
            if (tok.isEmpty() || tok.length > 4) return null
            val v = tok.toIntOrNull(16) ?: return null
            if (v !in 0..0xFFFF) return null
            result += v
        }
        return result
    }
}
