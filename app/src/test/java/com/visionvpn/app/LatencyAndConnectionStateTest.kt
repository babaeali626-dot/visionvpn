package com.visionvpn.app

import com.visionvpn.app.chain.RoutingGraph
import com.visionvpn.app.core.ConfigBuilder
import com.visionvpn.app.core.LatencyTestOutcome
import com.visionvpn.app.core.PingTester
import com.visionvpn.app.core.ProxyLatencyProbe
import com.visionvpn.app.core.VpnCoreController
import com.visionvpn.app.data.model.AppSettings
import com.visionvpn.app.data.model.ChainConfig
import com.visionvpn.app.data.model.LatencyStatus
import com.visionvpn.app.data.model.ProxyProfile
import com.visionvpn.app.data.model.ProxyProtocol
import com.visionvpn.app.data.model.VpnConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class LatencyAndConnectionStateTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun testProfile(id: String, host: String) = ProxyProfile(
        id = id,
        name = id,
        protocol = ProxyProtocol.VLESS,
        server = host,
        port = 443,
        rawUri = "vless://11111111-2222-3333-4444-555555555555@$host:443?security=tls#$id",
        subscriptionId = "sub-1",
        uuid = "11111111-2222-3333-4444-555555555555",
        security = "tls",
        sni = host
    )

    @Test
    fun `PingTester records classified proxy outcomes and never assigns 0ms as valid latency`() = runTest {
        val pOk = testProfile("ok", "ok.example.test")
        val pTimeout = testProfile("timeout", "timeout.example.test")
        val pTls = testProfile("tls", "tls.example.test")

        val fakeProbe = ProxyLatencyProbe { profile, _, _ ->
            when (profile.id) {
                "ok" -> LatencyTestOutcome.Success(145)
                "timeout" -> LatencyTestOutcome.Failure(LatencyStatus.TIMEOUT, "Timed out")
                else -> LatencyTestOutcome.Failure(LatencyStatus.TLS_ERROR, "TLS handshake failed")
            }
        }

        val measurements = PingTester.testProfiles(
            profiles = listOf(pOk, pTimeout, pTls),
            probe = fakeProbe,
            clockMs = { 12345L }
        )

        val byFp = measurements.associateBy { it.fingerprint }
        assertEquals(LatencyStatus.SUCCESS, byFp[pOk.connectionFingerprint()]?.status)
        assertEquals(145, byFp[pOk.connectionFingerprint()]?.latencyMs)
        assertEquals(LatencyStatus.TIMEOUT, byFp[pTimeout.connectionFingerprint()]?.status)
        assertNull(byFp[pTimeout.connectionFingerprint()]?.latencyMs)
        assertEquals(LatencyStatus.TLS_ERROR, byFp[pTls.connectionFingerprint()]?.status)
    }

    @Test
    fun `PingTester cancellation aborts in-flight probes cleanly`() = runTest {
        val cleanedUp = AtomicBoolean(false)
        val probeStarted = CompletableDeferred<Unit>()
        val slowProbe = ProxyLatencyProbe { _, _, _ ->
            probeStarted.complete(Unit)
            try {
                awaitCancellation()
            } catch (ce: CancellationException) {
                cleanedUp.set(true)
                throw ce
            }
        }

        val job = launch {
            PingTester.testProfiles(listOf(testProfile("p1", "slow.example.test")), slowProbe)
        }

        // Wait until the Dispatchers.IO probe has actually entered its suspended operation.
        // This prevents virtual-time advancement from cancelling the job before the IO task starts.
        probeStarted.await()
        job.cancelAndJoin()
        assertTrue(cleanedUp.get())
    }

    @Test
    fun `ConfigBuilder emits no-TUN latency probe config and loopback mixed-probe-in for VPN tunnel`() {
        val profile = testProfile("p1", "node.example.test")
        val builder = ConfigBuilder()

        // 1. Headless latency probe config must have NO tun inbound and route strictly to candidate
        val probeCfg = json.parseToJsonElement(
            builder.buildLatencyProbeConfig(profile, localPort = 20808, settings = AppSettings())
        ).jsonObject
        val probeInbounds = probeCfg["inbounds"]!!.jsonArray
        assertEquals(1, probeInbounds.size)
        assertEquals("mixed", probeInbounds[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("probe-candidate", probeCfg["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)

        // 2. Full VPN config must expose mixed-probe-in routed to finalTag
        val graph = RoutingGraph.build(profile, ChainConfig(), listOf(profile)).getOrThrow()
        val vpnCfg = json.parseToJsonElement(builder.buildSingBoxConfig(graph, AppSettings())).jsonObject
        val inbounds = vpnCfg["inbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertTrue(ConfigBuilder.TAG_PROBE_IN in inbounds)
        val probeRule = vpnCfg["route"]!!.jsonObject["rules"]!!.jsonArray.firstOrNull {
            it.jsonObject["inbound"]?.jsonPrimitive?.content == ConfigBuilder.TAG_PROBE_IN
        }?.jsonObject
        assertEquals(graph.finalOutboundTag, probeRule?.get("outbound")?.jsonPrimitive?.content)
    }

    @Test
    fun `verifyEndToEndConnectivity retries and fails closed when tunnel cannot reach internet`() = runTest {
        var attempts = 0
        val failed = VpnCoreController.verifyEndToEndConnectivity(
            probePort = ConfigBuilder.PROBE_PROXY_PORT,
            maxAttempts = 2,
            retryDelayMs = 10L
        ) {
            attempts++
            LatencyTestOutcome.Failure(LatencyStatus.TIMEOUT, "Probe timed out")
        }
        assertEquals(2, attempts)
        assertTrue(failed is LatencyTestOutcome.Failure)

        val passed = VpnCoreController.verifyEndToEndConnectivity(
            probePort = ConfigBuilder.PROBE_PROXY_PORT,
            maxAttempts = 2,
            retryDelayMs = 10L
        ) {
            LatencyTestOutcome.Success(188)
        }
        assertTrue(passed is LatencyTestOutcome.Success)
        assertEquals(188, (passed as LatencyTestOutcome.Success).latencyMs)

        // Verify staged VpnConnectionState helper semantics
        assertTrue(VpnConnectionState.ENGINE_READY.isInProgress)
        assertTrue(VpnConnectionState.VERIFYING_CONNECTIVITY.isInProgress)
        assertTrue(VpnConnectionState.CONNECTED.isActiveOrStarting)
        assertFalse(VpnConnectionState.ERROR.isActiveOrStarting)
    }
}
