package com.visionvpn.app

import com.visionvpn.app.chain.Socks5Probe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class Socks5ProbeTest {

    private fun withFakeSocksServer(
        handler: (DataInputStream, java.io.OutputStream) -> Unit,
        block: (port: Int) -> Unit
    ) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val t = thread(start = true) {
                runCatching {
                    server.accept().use { client ->
                        client.soTimeout = 3_000
                        val inp = DataInputStream(client.getInputStream())
                        val out = client.getOutputStream()
                        handler(inp, out)
                    }
                }
            }
            block(server.localPort)
            t.join(3_000)
        }
    }

    private fun writeFragmented(out: java.io.OutputStream, bytes: ByteArray) {
        for (b in bytes) {
            out.write(byteArrayOf(b))
            out.flush()
            Thread.sleep(2)
        }
    }

    private fun readConnectRequest(inp: DataInputStream): Pair<String, Int> {
        val header = ByteArray(4)
        inp.readFully(header)
        assertEquals(0x05, header[0].toInt() and 0xFF)
        assertEquals(0x01, header[1].toInt() and 0xFF)
        assertEquals(0x00, header[2].toInt() and 0xFF)
        assertEquals(0x03, header[3].toInt() and 0xFF) // Must use ATYP=DOMAIN
        val len = inp.readUnsignedByte()
        val domainBytes = ByteArray(len)
        inp.readFully(domainBytes)
        val port = inp.readUnsignedShort()
        return String(domainBytes, Charsets.UTF_8) to port
    }

    @Test
    fun `verifyHandshake handles 1-byte fragmented SOCKS5 reply`() = runBlocking {
        withFakeSocksServer(handler = { inp, out ->
            val greet = ByteArray(3)
            inp.readFully(greet)
            writeFragmented(out, byteArrayOf(0x05, 0x00))
        }) { port ->
            val result = runBlocking { Socks5Probe.verifyHandshake(port, timeoutMs = 2_000) }
            assertTrue("Expected Success, got $result", result is Socks5Probe.ProbeResult.Success)
        }
    }

    @Test
    fun `verifyHandshake rejects unsupported authentication method`() = runBlocking {
        withFakeSocksServer(handler = { inp, out ->
            val greet = ByteArray(3)
            inp.readFully(greet)
            out.write(byteArrayOf(0x05, 0xFF.toByte()))
            out.flush()
        }) { port ->
            val result = runBlocking { Socks5Probe.verifyHandshake(port, timeoutMs = 2_000) }
            assertTrue(result is Socks5Probe.ProbeResult.Failed)
        }
    }

    @Test
    fun `verifyConnect parses 1-byte fragmented IPv4 BND_ADDR reply and sends ATYP_DOMAIN`() = runBlocking {
        var observedHost = ""
        var observedPort = -1
        withFakeSocksServer(handler = { inp, out ->
            val greet = ByteArray(3)
            inp.readFully(greet)
            writeFragmented(out, byteArrayOf(0x05, 0x00))
            val (h, p) = readConnectRequest(inp)
            observedHost = h
            observedPort = p
            // VER=5, REP=0, RSV=0, ATYP=1 (IPv4), 127.0.0.1, port 443
            writeFragmented(
                out,
                byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x01, 0xBB.toByte())
            )
        }) { port ->
            val result = runBlocking {
                Socks5Probe.verifyConnect(port, "connectivitycheck.gstatic.com", 443, timeoutMs = 2_500)
            }
            assertTrue("Expected Success, got $result", result is Socks5Probe.ProbeResult.Success)
            assertEquals("connectivitycheck.gstatic.com", observedHost)
            assertEquals(443, observedPort)
        }
    }

    @Test
    fun `verifyConnect parses full 16-byte IPv6 BND_ADDR reply`() = runBlocking {
        withFakeSocksServer(handler = { inp, out ->
            val greet = ByteArray(3)
            inp.readFully(greet)
            out.write(byteArrayOf(0x05, 0x00))
            out.flush()
            readConnectRequest(inp)
            val reply = ByteArray(4 + 16 + 2)
            reply[0] = 0x05
            reply[1] = 0x00
            reply[2] = 0x00
            reply[3] = 0x04 // ATYP=IPv6
            reply[19] = 0x01 // ::1
            reply[20] = 0x01
            reply[21] = 0xBB.toByte()
            writeFragmented(out, reply)
        }) { port ->
            val result = runBlocking { Socks5Probe.verifyConnect(port, timeoutMs = 2_500) }
            assertTrue("Expected Success for IPv6 BND.ADDR, got $result", result is Socks5Probe.ProbeResult.Success)
        }
    }

    @Test
    fun `verifyConnect parses variable-length Domain BND_ADDR reply`() = runBlocking {
        withFakeSocksServer(handler = { inp, out ->
            val greet = ByteArray(3)
            inp.readFully(greet)
            out.write(byteArrayOf(0x05, 0x00))
            out.flush()
            readConnectRequest(inp)
            val bndDomain = "exit.node.local".toByteArray(Charsets.UTF_8)
            val reply = ByteArray(4 + 1 + bndDomain.size + 2)
            reply[0] = 0x05
            reply[1] = 0x00
            reply[2] = 0x00
            reply[3] = 0x03 // ATYP=Domain
            reply[4] = bndDomain.size.toByte()
            System.arraycopy(bndDomain, 0, reply, 5, bndDomain.size)
            reply[5 + bndDomain.size] = 0x01
            reply[6 + bndDomain.size] = 0xBB.toByte()
            writeFragmented(out, reply)
        }) { port ->
            val result = runBlocking { Socks5Probe.verifyConnect(port, timeoutMs = 2_500) }
            assertTrue("Expected Success for Domain BND.ADDR, got $result", result is Socks5Probe.ProbeResult.Success)
        }
    }

    @Test
    fun `verifyConnect rejects nonzero REP and premature EOF`() = runBlocking {
        // Nonzero REP = 0x05 (Connection Refused)
        withFakeSocksServer(handler = { inp, out ->
            val greet = ByteArray(3)
            inp.readFully(greet)
            out.write(byteArrayOf(0x05, 0x00))
            out.flush()
            readConnectRequest(inp)
            out.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            out.flush()
        }) { port ->
            val result = runBlocking { Socks5Probe.verifyConnect(port, timeoutMs = 2_000) }
            assertTrue(result is Socks5Probe.ProbeResult.Failed)
            assertTrue((result as Socks5Probe.ProbeResult.Failed).reason.contains("REP=0x5"))
        }

        // Premature EOF inside IPv6 BND.ADDR (only 6 bytes sent instead of 16+2)
        withFakeSocksServer(handler = { inp, out ->
            val greet = ByteArray(3)
            inp.readFully(greet)
            out.write(byteArrayOf(0x05, 0x00))
            out.flush()
            readConnectRequest(inp)
            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x04, 0, 0, 0, 0, 0, 0))
            out.flush()
        }) { port ->
            val result = runBlocking { Socks5Probe.verifyConnect(port, timeoutMs = 2_000) }
            assertTrue("Premature EOF must fail", result is Socks5Probe.ProbeResult.Failed)
        }
    }
}
