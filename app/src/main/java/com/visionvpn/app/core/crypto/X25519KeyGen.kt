package com.visionvpn.app.core.crypto

import android.util.Base64
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Pure-Kotlin RFC 7748 X25519 keypair generator for WireGuard / Cloudflare WARP.
 */
object X25519KeyGen {

    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val BASE_POINT: BigInteger = BigInteger.valueOf(9)

    data class KeyPairBase64(
        val privateKeyBase64: String,
        val publicKeyBase64: String
    )

    fun generateKeyPair(): KeyPairBase64 {
        val priv = ByteArray(32)
        SecureRandom().nextBytes(priv)
        // RFC 7748 clamping
        priv[0] = (priv[0].toInt() and 248).toByte()
        priv[31] = (priv[31].toInt() and 127).toByte()
        priv[31] = (priv[31].toInt() or 64).toByte()

        val pub = scalarMultBase(priv)
        return KeyPairBase64(
            privateKeyBase64 = Base64.encodeToString(priv, Base64.NO_WRAP),
            publicKeyBase64 = Base64.encodeToString(pub, Base64.NO_WRAP)
        )
    }

    private fun scalarMultBase(scalar: ByteArray): ByteArray {
        val k = decodeLittleEndian(scalar)
        val u = BASE_POINT

        var x1 = u
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = u
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = if (k.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap != 0) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)

            val sum = da.add(cb).mod(P)
            val diff = da.subtract(cb).mod(P)
            x3 = sum.multiply(sum).mod(P)
            z3 = x1.multiply(diff.multiply(diff).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P)
        }

        if (swap != 0) {
            x2 = x3
            z2 = z3
        }

        val result = x2.multiply(z2.modInverse(P)).mod(P)
        return encodeLittleEndian(result)
    }

    private fun decodeLittleEndian(bytes: ByteArray): BigInteger {
        val reversed = bytes.reversedArray()
        return BigInteger(1, reversed)
    }

    private fun encodeLittleEndian(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(32)
        val len = minOf(raw.size, 32)
        for (i in 0 until len) {
            out[i] = raw[raw.size - 1 - i]
        }
        return out
    }
}
