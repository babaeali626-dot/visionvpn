package com.visionvpn.app.chain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Robust RFC 1928 SOCKS5 handshake, TCP-level CONNECT probe, and optional TLS-verified probe helper.
 * Note: [verifyConnect] verifies that a SOCKS5 TCP tunnel opens; use [verifyTlsOverSocks5] or
 * [com.visionvpn.app.core.PingTester.probeThroughLocalHttpProxy] for full TLS/HTTPS verification.
 *
 * - Uses [DataInputStream.readFully] to handle fragmented TCP stream reads safely.
 * - Uses ATYP=0x03 (domain name) so DNS resolution occurs remotely through the tunnel.
 * - Parses complete variable-length `BND.ADDR` (IPv4 / IPv6 / Domain) + 2-byte `BND.PORT`.
 * - Never swallows [CancellationException].
 */
object Socks5Probe {

    const val DEFAULT_PROBE_HOST = "connectivitycheck.gstatic.com"
    const val DEFAULT_PROBE_PORT = 443

    sealed class ProbeResult {
        data object Success : ProbeResult()
        data class Failed(val reason: String) : ProbeResult()
    }

    suspend fun verifyHandshake(port: Int, timeoutMs: Int = 1_500): ProbeResult =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.soTimeout = timeoutMs
                    socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                    val out = socket.getOutputStream()
                    val inp = DataInputStream(socket.getInputStream())
                    out.write(byteArrayOf(0x05, 0x01, 0x00))
                    out.flush()
                    val resp = ByteArray(2)
                    inp.readFully(resp)
                    if (resp[0] != 0x05.toByte()) {
                        return@withContext ProbeResult.Failed("Invalid SOCKS version: ${resp[0]}")
                    }
                    if (resp[1] != 0x00.toByte()) {
                        return@withContext ProbeResult.Failed("SOCKS auth rejected: ${resp[1]}")
                    }
                    ProbeResult.Success
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                ProbeResult.Failed("SOCKS5 handshake failed: ${t.message}")
            }
        }

    suspend fun verifyConnect(
        port: Int,
        targetHost: String = DEFAULT_PROBE_HOST,
        targetPort: Int = DEFAULT_PROBE_PORT,
        timeoutMs: Int = 6_000
    ): ProbeResult = withContext(Dispatchers.IO) {
        val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
        if (hostBytes.isEmpty() || hostBytes.size > 255) {
            return@withContext ProbeResult.Failed("Invalid probe domain length: ${hostBytes.size}")
        }
        if (targetPort !in 1..65535) {
            return@withContext ProbeResult.Failed("Invalid probe port: $targetPort")
        }

        try {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                val out = socket.getOutputStream()
                val inp = DataInputStream(socket.getInputStream())

                // 1. Greeting: VER=5, NMETHODS=1, NO_AUTH=0
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val greet = ByteArray(2)
                inp.readFully(greet)
                if (greet[0] != 0x05.toByte() || greet[1] != 0x00.toByte()) {
                    return@withContext ProbeResult.Failed("SOCKS5 greeting rejected (${greet[0]}, ${greet[1]})")
                }

                // 2. CONNECT: VER=5, CMD=1, RSV=0, ATYP=3 (Domain), len, domain, port
                val req = ByteArray(7 + hostBytes.size)
                req[0] = 0x05
                req[1] = 0x01
                req[2] = 0x00
                req[3] = 0x03
                req[4] = hostBytes.size.toByte()
                System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
                req[5 + hostBytes.size] = ((targetPort shr 8) and 0xFF).toByte()
                req[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
                out.write(req)
                out.flush()

                // 3. Parse fixed 4-byte reply header
                val header = ByteArray(4)
                inp.readFully(header)
                val ver = header[0].toInt() and 0xFF
                val rep = header[1].toInt() and 0xFF
                val rsv = header[2].toInt() and 0xFF
                val atyp = header[3].toInt() and 0xFF

                if (ver != 0x05) {
                    return@withContext ProbeResult.Failed("Unexpected SOCKS5 reply version: $ver")
                }
                if (rsv != 0x00) {
                    return@withContext ProbeResult.Failed("Non-zero SOCKS5 RSV byte: $rsv")
                }

                // 4. Consume variable-length BND.ADDR + 2-byte BND.PORT completely
                val addrLen = when (atyp) {
                    0x01 -> 4 // IPv4
                    0x04 -> 16 // IPv6
                    0x03 -> inp.readUnsignedByte() // Domain
                    else -> return@withContext ProbeResult.Failed("Unknown SOCKS5 ATYP: $atyp")
                }
                val bndAddr = ByteArray(addrLen)
                inp.readFully(bndAddr)
                val bndPort = ByteArray(2)
                inp.readFully(bndPort)

                if (rep != 0x00) {
                    return@withContext ProbeResult.Failed("SOCKS5 CONNECT failed with REP=0x${rep.toString(16)}")
                }
                ProbeResult.Success
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            ProbeResult.Failed("SOCKS5 CONNECT probe error: ${t.message}")
        }
    }

    /**
     * Performs a SOCKS5 CONNECT followed by a real TLS handshake with standard system CA
     * validation and HTTPS hostname verification (`endpointIdentificationAlgorithm = "HTTPS"`).
     */
    suspend fun verifyTlsOverSocks5(
        port: Int,
        targetHost: String = DEFAULT_PROBE_HOST,
        targetPort: Int = DEFAULT_PROBE_PORT,
        timeoutMs: Int = 8_000
    ): ProbeResult = withContext(Dispatchers.IO) {
        val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
        if (hostBytes.isEmpty() || hostBytes.size > 255) {
            return@withContext ProbeResult.Failed("Invalid probe domain length: ${hostBytes.size}")
        }
        if (targetPort !in 1..65535) {
            return@withContext ProbeResult.Failed("Invalid probe port: $targetPort")
        }
        try {
            Socket().use { rawSocket ->
                rawSocket.soTimeout = timeoutMs
                rawSocket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                val connectRes = performSocks5Connect(rawSocket, hostBytes, targetPort)
                if (connectRes !is ProbeResult.Success) return@withContext connectRes

                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sslSocket = sslFactory.createSocket(rawSocket, targetHost, targetPort, true) as SSLSocket
                sslSocket.use { tls ->
                    tls.soTimeout = timeoutMs
                    val params: SSLParameters = tls.sslParameters
                    params.endpointIdentificationAlgorithm = "HTTPS"
                    tls.sslParameters = params
                    tls.startHandshake()
                }
                ProbeResult.Success
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            ProbeResult.Failed("SOCKS5 TLS probe error: ${t.message}")
        }
    }

    private fun performSocks5Connect(socket: Socket, hostBytes: ByteArray, targetPort: Int): ProbeResult {
        val out = socket.getOutputStream()
        val inp = DataInputStream(socket.getInputStream())

        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val greet = ByteArray(2)
        inp.readFully(greet)
        if (greet[0] != 0x05.toByte() || greet[1] != 0x00.toByte()) {
            return ProbeResult.Failed("SOCKS5 greeting rejected (${greet[0]}, ${greet[1]})")
        }

        val req = ByteArray(7 + hostBytes.size)
        req[0] = 0x05
        req[1] = 0x01
        req[2] = 0x00
        req[3] = 0x03
        req[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
        req[5 + hostBytes.size] = ((targetPort shr 8) and 0xFF).toByte()
        req[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
        out.write(req)
        out.flush()

        val header = ByteArray(4)
        inp.readFully(header)
        val ver = header[0].toInt() and 0xFF
        val rep = header[1].toInt() and 0xFF
        val rsv = header[2].toInt() and 0xFF
        val atyp = header[3].toInt() and 0xFF

        if (ver != 0x05) return ProbeResult.Failed("Unexpected SOCKS5 reply version: $ver")
        if (rsv != 0x00) return ProbeResult.Failed("Non-zero SOCKS5 RSV byte: $rsv")

        val addrLen = when (atyp) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> inp.readUnsignedByte()
            else -> return ProbeResult.Failed("Unknown SOCKS5 ATYP: $atyp")
        }
        val bndAddr = ByteArray(addrLen)
        inp.readFully(bndAddr)
        val bndPort = ByteArray(2)
        inp.readFully(bndPort)

        if (rep != 0x00) return ProbeResult.Failed("SOCKS5 CONNECT failed with REP=0x${rep.toString(16)}")
        return ProbeResult.Success
    }
}
