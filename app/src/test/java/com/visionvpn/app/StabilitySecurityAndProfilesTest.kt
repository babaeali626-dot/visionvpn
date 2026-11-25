package com.visionvpn.app

import com.visionvpn.app.chain.LocalEngine
import com.visionvpn.app.chain.LocalEngineManager
import com.visionvpn.app.chain.LocalEngineState
import com.visionvpn.app.chain.LocalEngineStatus
import com.visionvpn.app.chain.PluggableTransportAvailability
import com.visionvpn.app.chain.RoutingGraph
import com.visionvpn.app.core.ConfigBuilder
import com.visionvpn.app.core.ExitIpLookup
import com.visionvpn.app.core.ExitIpStatus
import com.visionvpn.app.core.IpAddressClassifier
import com.visionvpn.app.core.LatencyTestOutcome
import com.visionvpn.app.core.LibboxBridge
import com.visionvpn.app.core.LibboxLeaseMode
import com.visionvpn.app.core.LibboxRuntime
import com.visionvpn.app.core.LogRedactor
import com.visionvpn.app.core.PingTester
import com.visionvpn.app.core.ProxyLatencyProbe
import com.visionvpn.app.core.VpnCoreController
import com.visionvpn.app.data.model.AppLanguage
import com.visionvpn.app.data.model.AppSettings
import com.visionvpn.app.data.model.BuiltInProfilesCatalog
import com.visionvpn.app.data.model.ChainConfig
import com.visionvpn.app.data.model.ChainExitType
import com.visionvpn.app.data.model.Ipv6Policy
import com.visionvpn.app.data.model.LatencyStatus
import com.visionvpn.app.data.model.LocalEngineProfileType
import com.visionvpn.app.data.model.ProxyProfile
import com.visionvpn.app.data.model.ProxyProtocol
import com.visionvpn.app.data.model.SelectableProfile
import com.visionvpn.app.data.model.SplitTunnelMode
import com.visionvpn.app.data.model.SubscriptionSource
import com.visionvpn.app.data.model.TorChainConfig
import com.visionvpn.app.data.model.VisionThemeVariant
import com.visionvpn.app.data.model.WarpAccountConfig
import com.visionvpn.app.data.parser.ProfileUriParser
import com.visionvpn.app.data.parser.SafePublicDns
import com.visionvpn.app.data.parser.SubscriptionFetcher
import com.visionvpn.app.data.parser.SubscriptionFormatParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class StabilitySecurityAndProfilesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Deterministic 32-byte test-only X25519 fixture (NOT a production key)
    private val validRealityPubKey32Bytes: String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })

    private fun dnsReturning(vararg addresses: InetAddress): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = addresses.toList()
    }

    private fun syntheticProfile(idx: Int, network: String = "tcp"): ProxyProfile = ProxyProfile(
        id = "p-$idx",
        name = "Node-$idx",
        protocol = ProxyProtocol.VLESS,
        server = "n$idx.example.test",
        port = 443,
        rawUri = "vless://11111111-2222-3333-4444-555555555555@n$idx.example.test:443?security=tls&type=$network#Node-$idx",
        subscriptionId = "sub-1",
        uuid = "11111111-2222-3333-4444-555555555555",
        network = network,
        security = "tls",
        sni = "n$idx.example.test"
    )

    // -------------------------------------------------------------------------
    // P0. Libbox Lifecycle Coordinator & Bulk Latency Testing Safety
    // -------------------------------------------------------------------------

    @Test
    fun `LibboxRuntime enforces atomic mutual exclusion between VPN core and headless probes`() = runTest {
        LibboxRuntime.resetForTesting()
        assertEquals(LibboxLeaseMode.IDLE, LibboxRuntime.currentMode())

        val vpnToken = LibboxRuntime.tryAcquireVpnLease()
        assertNotNull(vpnToken)
        assertEquals(LibboxLeaseMode.VPN_STARTING, LibboxRuntime.currentMode())
        assertTrue(LibboxRuntime.isVpnCoreActiveOrStarting())

        // Headless probe must be rejected while VPN is starting or active
        assertNull(LibboxRuntime.tryAcquireHeadlessProbeLease())
        val blockedProbe = LibboxRuntime.withHeadlessProbeLease { "should-not-run" }
        assertTrue(blockedProbe.isFailure)

        // Wrong token cannot promote or release another owner's lease
        assertFalse(LibboxRuntime.markVpnLeaseActive(vpnToken!! + 999L))
        assertFalse(LibboxRuntime.releaseVpnLease(vpnToken + 999L))
        assertEquals(LibboxLeaseMode.VPN_STARTING, LibboxRuntime.currentMode())

        assertTrue(LibboxRuntime.markVpnLeaseActive(vpnToken))
        assertEquals(LibboxLeaseMode.VPN_ACTIVE, LibboxRuntime.currentMode())
        assertTrue(LibboxRuntime.releaseVpnLease(vpnToken))
        assertEquals(LibboxLeaseMode.IDLE, LibboxRuntime.currentMode())

        // While headless probe holds lease, VPN lease acquisition must be rejected
        val probeRes = LibboxRuntime.withHeadlessProbeLease {
            assertEquals(LibboxLeaseMode.HEADLESS_PROBE, LibboxRuntime.currentMode())
            assertNull(LibboxRuntime.tryAcquireVpnLease())
            "probe-ok"
        }
        assertEquals("probe-ok", probeRes.getOrThrow())
        assertEquals(LibboxLeaseMode.IDLE, LibboxRuntime.currentMode())

        // Blackhole handover keeps the same VPN owner lease; probes/new cores remain blocked
        val protectedToken = LibboxRuntime.tryAcquireVpnLease()!!
        assertTrue(LibboxRuntime.markVpnLeaseActive(protectedToken))
        assertTrue(LibboxRuntime.beginBlackholeTransition(protectedToken))
        assertEquals(LibboxLeaseMode.BLACKHOLE_STARTING, LibboxRuntime.currentMode())
        assertNull(LibboxRuntime.tryAcquireHeadlessProbeLease())
        assertTrue(LibboxRuntime.markBlackholeActive(protectedToken))
        assertTrue(LibboxRuntime.isBlockingTunInstalled())
        assertNull(LibboxRuntime.tryAcquireVpnLease())
        assertFalse("A live blocking TUN must never lose its owner lease", LibboxRuntime.releaseVpnLease(protectedToken))
        assertTrue(LibboxRuntime.isBlockingTunInstalled())
        assertTrue(LibboxRuntime.clearBlockingTun(protectedToken))
        assertTrue(LibboxRuntime.releaseVpnLease(protectedToken))
        assertFalse(LibboxRuntime.isBlockingTunInstalled())

        // A failed native close quarantines the runtime instead of admitting another service.
        // Exercise the real lease wrapper so its catch/finally must not accidentally release it.
        val closeFailure = LibboxRuntime.withHeadlessProbeLease {
            throw com.visionvpn.app.core.LibboxCleanupException("simulated native close failure")
        }
        assertTrue(closeFailure.isFailure)
        assertEquals(LibboxLeaseMode.FAULTED, LibboxRuntime.currentMode())
        assertNull(LibboxRuntime.tryAcquireHeadlessProbeLease())
        assertNull(LibboxRuntime.tryAcquireVpnLease())
        LibboxRuntime.resetForTesting()
    }

    @Test
    fun `failed blackhole handover retains the prior VPN lease until explicit cleanup`() {
        LibboxRuntime.resetForTesting()
        val token = LibboxRuntime.tryAcquireVpnLease()!!
        assertTrue(LibboxRuntime.markVpnLeaseActive(token))
        assertTrue(LibboxRuntime.beginBlackholeTransition(token))

        // Simulate Builder.establish() failure: the old VPN TUN remains installed, but no
        // replacement blackhole descriptor exists yet. Quarantine blocks probes and new starts.
        assertTrue(LibboxRuntime.markFaulted(token, blockingTunIsInstalled = false))
        assertEquals(LibboxLeaseMode.FAULTED, LibboxRuntime.currentMode())
        assertNull(LibboxRuntime.tryAcquireVpnLease())
        assertNull(LibboxRuntime.tryAcquireHeadlessProbeLease())

        // Explicit Disconnect confirms old native service/TUN cleanup before the owner releases.
        assertTrue(LibboxRuntime.releaseVpnLease(token))
        assertEquals(LibboxLeaseMode.IDLE, LibboxRuntime.currentMode())
        LibboxRuntime.resetForTesting()
    }

    @Test
    fun `PingTester bounded worker pool enforces maxConcurrency across 250 profiles and isolates failures`() = runTest {
        val profiles = (1..250).map { syntheticProfile(it) }
        val inFlight = AtomicInteger(0)
        val peakInFlight = AtomicInteger(0)
        val lastProgress = AtomicInteger(0)

        val probe = ProxyLatencyProbe { profile, _, _ ->
            val current = inFlight.incrementAndGet()
            peakInFlight.accumulateAndGet(current, Math::max)
            try {
                delay(1)
                when {
                    profile.id == "p-13" -> throw IllegalStateException("Simulated probe crash")
                    profile.id == "p-25" -> LatencyTestOutcome.Failure(LatencyStatus.TIMEOUT, "Timed out")
                    else -> LatencyTestOutcome.Success(42)
                }
            } finally {
                inFlight.decrementAndGet()
            }
        }

        val results = PingTester.testProfiles(
            profiles = profiles,
            probe = probe,
            maxConcurrency = 2,
            clockMs = { 9999L },
            onProgress = { done, total ->
                assertEquals(250, total)
                lastProgress.set(done)
            }
        )

        assertEquals(250, results.size)
        assertEquals(250, lastProgress.get())
        assertTrue("Peak concurrency (${peakInFlight.get()}) must not exceed worker bound 2", peakInFlight.get() <= 2)
        assertEquals(LatencyStatus.FAILED, results[12].status)
        assertNull(results[12].latencyMs)
        assertEquals(LatencyStatus.TIMEOUT, results[24].status)
        assertEquals(LatencyStatus.SUCCESS, results[0].status)
        assertEquals(42, results[0].latencyMs)
    }

    @Test
    fun `PingTester stops a batch immediately when Libbox runtime becomes quarantined`() = runTest {
        LibboxRuntime.resetForTesting()
        val owner = LibboxRuntime.tryAcquireHeadlessProbeLease()!!
        val measured = AtomicInteger(0)
        val profiles = (1..50).map { syntheticProfile(it) }

        val results = PingTester.testProfiles(
            profiles = profiles,
            maxConcurrency = 1,
            probe = ProxyLatencyProbe { _, _, _ ->
                measured.incrementAndGet()
                assertTrue(LibboxRuntime.quarantineHeadlessProbeLease(owner))
                LatencyTestOutcome.Success(31)
            }
        )

        assertEquals("No more profiles should be scheduled after native cleanup quarantine", 1, measured.get())
        assertEquals(1, results.size)
        assertEquals(LibboxLeaseMode.FAULTED, LibboxRuntime.currentMode())
        LibboxRuntime.resetForTesting()
    }

    @Test
    fun `LibboxRuntime does not mark initialized when setup fails and succeeds on subsequent retry`() {
        LibboxRuntime.resetForTesting()
        assertFalse(LibboxRuntime.isInitialized())

        val failed = runCatching {
            LibboxRuntime.ensureInitializedWithRunner {
                throw IllegalStateException("Native setup failed")
            }
        }
        assertTrue(failed.isFailure)
        assertFalse("Initialized flag must stay false after failed setup", LibboxRuntime.isInitialized())

        var calls = 0
        LibboxRuntime.ensureInitializedWithRunner { calls++ }
        LibboxRuntime.ensureInitializedWithRunner { calls++ }
        assertTrue(LibboxRuntime.isInitialized())
        assertEquals("Setup must run exactly once after success", 1, calls)
        LibboxRuntime.resetForTesting()
    }

    @Test
    fun `PingTester waitForLoopbackPortReady returns false on timeout instead of silent success`() = runTest {
        val port = PingTester.reserveEphemeralPort()
        assertNotNull(port)
        try {
            var fakeNow = 0L
            val ready = PingTester.waitForLoopbackPortReady(
                port = port!!,
                maxWaitMs = 120L,
                clockMs = {
                    val v = fakeNow
                    fakeNow += 50L
                    v
                }
            )
            assertFalse("Unbound loopback port must return false on timeout", ready)
        } finally {
            PingTester.releaseReservedPort(port!!)
        }
    }

    // -------------------------------------------------------------------------
    // P1. Real Fail-Closed Semantics
    // -------------------------------------------------------------------------

    @Test
    fun `VpnCoreController failClosedMode selects blackhole TUN vs direct teardown distinctly`() {
        assertEquals(
            VpnCoreController.EngineFailureAction.HOLD_BLACKHOLE_TUN,
            VpnCoreController.decideEngineFailureAction(failClosedMode = true, hasActiveVpnService = true)
        )
        assertEquals(
            VpnCoreController.EngineFailureAction.TEARDOWN_DIRECT_NETWORK,
            VpnCoreController.decideEngineFailureAction(failClosedMode = false, hasActiveVpnService = true)
        )
        assertEquals(
            VpnCoreController.EngineFailureAction.TEARDOWN_DIRECT_NETWORK,
            VpnCoreController.decideEngineFailureAction(failClosedMode = true, hasActiveVpnService = false)
        )
    }

    // -------------------------------------------------------------------------
    // P2. ONLY_SELECTED Split-Tunnel Allowlist Validation
    // -------------------------------------------------------------------------

    @Test
    fun `LibboxBridge ONLY_SELECTED rejects empty, self-only, or uninstalled lists and never widens to full tunnel`() {
        val selfPkg = "com.visionvpn.app"
        val installed = setOf(selfPkg, "com.example.browser", "com.example.chat")

        // Empty -> must fail closed
        assertTrue(
            LibboxBridge.resolveSplitTunnelPlan(SplitTunnelMode.ONLY_SELECTED, emptySet(), selfPkg, installed).isFailure
        )
        // Self-only -> must fail closed
        assertTrue(
            LibboxBridge.resolveSplitTunnelPlan(SplitTunnelMode.ONLY_SELECTED, setOf(selfPkg), selfPkg, installed).isFailure
        )
        // Uninstalled only -> must fail closed
        assertTrue(
            LibboxBridge.resolveSplitTunnelPlan(SplitTunnelMode.ONLY_SELECTED, setOf("com.missing.app"), selfPkg, installed).isFailure
        )
        // Mixed valid, missing, self, duplicate -> only valid installed external apps admitted
        val mixed = LibboxBridge.resolveSplitTunnelPlan(
            mode = SplitTunnelMode.ONLY_SELECTED,
            configuredPackages = setOf(selfPkg, "com.example.browser", "com.missing.app", " com.example.browser "),
            selfPackageName = selfPkg,
            installedPackages = installed
        ).getOrThrow()
        assertEquals(listOf("com.example.browser"), mixed.allowedPackages)
        assertTrue(mixed.disallowedPackages.isEmpty())

        // BYPASS_SELECTED with empty list disallows self only
        val bypassEmpty = LibboxBridge.resolveSplitTunnelPlan(
            mode = SplitTunnelMode.BYPASS_SELECTED,
            configuredPackages = emptySet(),
            selfPackageName = selfPkg,
            installedPackages = installed
        ).getOrThrow()
        assertEquals(listOf(selfPkg), bypassEmpty.disallowedPackages)
        assertTrue(bypassEmpty.allowedPackages.isEmpty())
    }

    // -------------------------------------------------------------------------
    // P3. REALITY Validation & Generated Sing-box v1.10.7 JSON
    // -------------------------------------------------------------------------

    @Test
    fun `ConfigBuilder strictly validates 32-byte Base64URL REALITY key, even-length hex short_id, and uTLS`() {
        val builder = ConfigBuilder()

        // Fake short string must be rejected
        assertFalse(builder.isValidRealityPublicKey("validPublicKeyBase64Value"))
        assertFalse(builder.isValidRealityPublicKey(""))
        assertFalse(builder.isValidRealityPublicKey("has spaces in key"))
        assertFalse("RawURLEncoding rejects Base64 padding", builder.isValidRealityPublicKey(Base64.getUrlEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })))
        assertFalse("Standard Base64 alphabet is not Base64URL", builder.isValidRealityPublicKey(Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })))
        assertFalse("Whitespace around a key must not be normalized", builder.isValidRealityPublicKey(" $validRealityPubKey32Bytes"))
        assertTrue(builder.isValidRealityPublicKey(validRealityPubKey32Bytes))

        // Short ID: empty or even-length hex <= 16 chars
        assertTrue(builder.isValidRealityShortId(""))
        assertTrue(builder.isValidRealityShortId("ab"))
        assertTrue(builder.isValidRealityShortId("0123456789abcdef"))
        assertFalse("Odd-length hex must be rejected", builder.isValidRealityShortId("abc"))
        assertFalse("Non-hex must be rejected", builder.isValidRealityShortId("zzzz"))
        assertFalse("Over 16 hex chars (>8 bytes) must be rejected", builder.isValidRealityShortId("0123456789abcdef00"))
        assertFalse("Whitespace must not be normalized", builder.isValidRealityShortId(" abcd"))

        // Unsupported uTLS fingerprint rejected
        val badFp = syntheticProfile(1).copy(
            security = "reality",
            realityPublicKey = validRealityPubKey32Bytes,
            realityShortId = "abcd",
            fingerprint = "bogus-fp"
        )
        assertTrue(builder.validateProfile(badFp).isFailure)

        // Valid REALITY profile emits exact Sing-box v1.10.7 JSON with insecure=false and utls enabled
        val validReality = syntheticProfile(2).copy(
            security = "reality",
            realityPublicKey = validRealityPubKey32Bytes,
            realityShortId = "abcd1234",
            sni = "www.example.com",
            fingerprint = "chrome",
            allowInsecure = true // Even if toggled, REALITY never emits insecure=true
        )
        assertTrue(builder.validateProfile(validReality).isSuccess)
        assertTrue("uTLS is mandatory for REALITY", builder.validateProfile(validReality.copy(fingerprint = "")).isFailure)
        val graph = RoutingGraph.build(validReality, ChainConfig(), listOf(validReality)).getOrThrow()
        val cfg = json.parseToJsonElement(builder.buildSingBoxConfig(graph, AppSettings())).jsonObject
        val ob = cfg["outbounds"]!!.jsonArray.first {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "hop-0-entry"
        }.jsonObject
        val tls = ob["tls"]!!.jsonObject
        assertFalse(tls["insecure"]!!.jsonPrimitive.boolean)
        assertEquals("www.example.com", tls["server_name"]!!.jsonPrimitive.content)
        assertTrue(tls["utls"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("chrome", tls["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
        val reality = tls["reality"]!!.jsonObject
        assertTrue(reality["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(validRealityPubKey32Bytes, reality["public_key"]!!.jsonPrimitive.content)
        assertEquals("abcd1234", reality["short_id"]!!.jsonPrimitive.content)
    }

    // -------------------------------------------------------------------------
    // P4 & P5. IANA IPv4/IPv6 Classification & Subscription SSRF/DNS-Rebinding
    // -------------------------------------------------------------------------

    @Test
    fun `IpAddressClassifier and ExitIpLookup implement IANA Globally Reachable policy and exceptions with zero DNS`() {
        // Known public IPv4 & IPv6 accepted
        assertTrue(ExitIpLookup.isValidPublicIp("1.1.1.1"))
        assertTrue(ExitIpLookup.isValidPublicIp("8.8.8.8"))
        assertTrue(ExitIpLookup.isValidPublicIp("2606:4700:4700::1111"))

        // IANA Globally Reachable = True exceptions inside special-purpose blocks MUST NOT be overblocked
        assertTrue("192.0.0.9 (PCP Anycast)", IpAddressClassifier.isGloballyReachableLiteral("192.0.0.9"))
        assertTrue("192.0.0.10 (TURN Anycast)", IpAddressClassifier.isGloballyReachableLiteral("192.0.0.10"))
        assertTrue("2001:1::1 (PCP Anycast v6)", IpAddressClassifier.isGloballyReachableLiteral("2001:1::1"))
        assertTrue("2001:1::3 (DNS-SD Anycast)", IpAddressClassifier.isGloballyReachableLiteral("2001:1::3"))
        assertTrue("2620:4f:8000::/48 (AS112 delegation)", IpAddressClassifier.isGloballyReachableLiteral("2620:4f:8000::1"))
        assertTrue("Well-Known NAT64 with public embedded IPv4", IpAddressClassifier.isGloballyReachableLiteral("64:ff9b::808:808"))
        assertFalse("NAT64 must not map a private IPv4 destination into the public allowlist", IpAddressClassifier.isGloballyReachableLiteral("64:ff9b::a00:1"))
        assertFalse("Outside IPv6 global unicast", IpAddressClassifier.isGloballyReachableLiteral("4000::1"))
        assertFalse("Outside IPv6 global unicast", IpAddressClassifier.isGloballyReachableLiteral("8000::1"))
        assertTrue("2001:3::1 (AMT)", IpAddressClassifier.isGloballyReachableLiteral("2001:3::1"))
        assertTrue("2001:20::1 (ORCHIDv2)", IpAddressClassifier.isGloballyReachableLiteral("2001:20::1"))

        // Non-globally-reachable IANA blocks MUST be rejected
        val rejected = listOf(
            "0.0.0.0", "10.0.0.5", "100.64.0.1", "127.0.0.1", "169.254.169.254",
            "172.16.0.2", "192.0.0.8", "192.0.2.1", "192.88.99.1", "192.168.1.1",
            "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1",
            "::", "::1", "100::1", "100:0:0:1::1", "2001:2::1", "2001:db8::1", "2002::1", "3fff::1", "5f00::1",
            "4000::1", "8000::1", "ffff::1",
            "64:ff9b:1::1", "fc00::1", "fd00::1", "fe80::1", "ff02::1",
            "::ffff:127.0.0.1", "::ffff:192.168.1.1", "::ffff:1.1.1.1",
            "01.2.3.4", "example.com", "not-an-ip", ""
        )
        rejected.forEach { ip ->
            assertFalse("Expected '$ip' to be rejected as non-globally-reachable", ExitIpLookup.isValidPublicIp(ip))
        }

        val parsed = ExitIpLookup.parseCountryIsJson("""{"ip":"1.1.1.1","country":"DE"}""", nowMs = 5000L)
        assertEquals(ExitIpStatus.VERIFIED, parsed.status)
        assertEquals("Germany", parsed.countryName)

        val docRejected = ExitIpLookup.parseCountryIsJson("""{"ip":"198.51.100.1","country":"US"}""")
        assertEquals(ExitIpStatus.UNAVAILABLE, docRejected.status)
    }

    @Test
    fun `SubscriptionFetcher and SafePublicDns block HTTP, userinfo, non-global IPs, and mixed DNS records`() {
        assertTrue(runCatching { SubscriptionFetcher.validateSubscriptionUrl("http://example.com/sub") }.isFailure)
        assertTrue(runCatching { SubscriptionFetcher.validateSubscriptionUrl("https://user:pass@example.com/sub") }.isFailure)
        assertTrue(runCatching { SubscriptionFetcher.validateSubscriptionUrl("https://localhost/sub") }.isFailure)
        assertTrue(runCatching { SubscriptionFetcher.validateSubscriptionUrl("https://sub.localhost/sub") }.isFailure)
        assertTrue(runCatching { SubscriptionFetcher.validateSubscriptionUrl("https://127.0.0.1/sub") }.isFailure)
        assertTrue(runCatching { SubscriptionFetcher.validateSubscriptionUrl("https://169.254.169.254/latest") }.isFailure)
        assertTrue(runCatching { SubscriptionFetcher.validateSubscriptionUrl("https://192.0.2.1/sub") }.isFailure)
        assertTrue(runCatching { SubscriptionFetcher.validateSubscriptionUrl("https://[2001:db8::1]/sub") }.isFailure)
        assertEquals(
            "https://sub.example.com/list",
            SubscriptionFetcher.validateSubscriptionUrl("https://sub.example.com/list")
        )

        val publicAddr = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
        val privateAddr = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))

        // Mixed public + private DNS answer must be rejected conservatively
        val mixedDns = SafePublicDns(dnsReturning(publicAddr, privateAddr))
        assertTrue(runCatching { mixedDns.lookup("rebind.example.test") }.exceptionOrNull() is UnknownHostException)

        // All-public DNS answer passes and returns exact validated list
        val cleanDns = SafePublicDns(dnsReturning(publicAddr))
        assertEquals(listOf(publicAddr), cleanDns.lookup("clean.example.test"))
    }

    // -------------------------------------------------------------------------
    // P7, P8, P9, P10. Untrusted Imports, Local Profiles, Chaining & Locked UI
    // -------------------------------------------------------------------------

    @Test
    fun `Untrusted imported allowInsecure and skip-cert-verify flags never silently disable TLS verification`() {
        val vless = ProfileUriParser.parseSingle(
            "vless://11111111-2222-3333-4444-555555555555@node.example.test:443?security=tls&allowInsecure=1#InsecureNode"
        )!!
        assertFalse(vless.allowInsecure)
        assertTrue(vless.importedInsecureRequested)

        val clashYaml = """
            proxies:
              - name: InsecureClash
                type: trojan
                server: node.example.test
                port: 443
                password: secret
                skip-cert-verify: true
        """.trimIndent()
        val clashProfile = SubscriptionFormatParser.parse(clashYaml).getOrThrow().profiles.single()
        assertFalse(clashProfile.allowInsecure)
        assertTrue(clashProfile.importedInsecureRequested)
    }

    @Test
    fun `LocalEngineManager retains an engine after stop failure and retries cleanup`() = runTest {
        val stopAttempts = AtomicInteger(0)
        val stopped = AtomicBoolean(false)
        val engine = object : LocalEngine {
            override val engineId: String = "tor-retry"
            override val state: StateFlow<LocalEngineState> = MutableStateFlow(
                LocalEngineState(engineId = engineId, status = LocalEngineStatus.CONNECTED)
            )
            override suspend fun start(): Result<Unit> = Result.success(Unit)
            override suspend fun isHealthy(): Boolean = true
            override suspend fun stop() {
                if (stopAttempts.incrementAndGet() == 1) error("simulated process still alive")
                stopped.set(true)
            }
        }
        val manager = LocalEngineManager()
        assertTrue(manager.startEngine(engine).isSuccess)

        val firstStop = runCatching { manager.stopAll() }
        assertTrue(firstStop.isFailure)
        assertFalse("Failed engine must not be reported as stopped", stopped.get())
        assertEquals(LocalEngineStatus.FAILED, manager.engineStates.value[engine.engineId]?.status)

        manager.stopAll()
        assertTrue(stopped.get())
        assertEquals(LocalEngineStatus.STOPPED, manager.engineStates.value[engine.engineId]?.status)
        manager.shutdown()
    }

    @Test
    fun `LocalEngineManager does not report stopped when cancelled startup cleanup fails`() = runTest {
        val startupEntered = CompletableDeferred<Unit>()
        val stopAttempts = AtomicInteger(0)
        val engine = object : LocalEngine {
            override val engineId: String = "cancel-cleanup-failure"
            override val state: StateFlow<LocalEngineState> = MutableStateFlow(
                LocalEngineState(engineId = engineId, status = LocalEngineStatus.STARTING)
            )
            override suspend fun start(): Result<Unit> {
                startupEntered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
            override suspend fun isHealthy(): Boolean = false
            override suspend fun stop() {
                if (stopAttempts.incrementAndGet() == 1) error("simulated child process still alive")
            }
        }
        val manager = LocalEngineManager()
        val startupJob = launch { manager.startEngine(engine, readyTimeoutMs = 30_000L) }
        startupEntered.await()
        startupJob.cancelAndJoin()

        assertEquals(LocalEngineStatus.FAILED, manager.engineStates.value[engine.engineId]?.status)
        assertEquals("Cleanup Required", manager.engineStates.value[engine.engineId]?.phaseLabel)

        manager.stopAll()
        assertEquals(LocalEngineStatus.STOPPED, manager.engineStates.value[engine.engineId]?.status)
        manager.shutdown()
    }

    @Test
    fun `BuiltInProfilesCatalog, RoutingGraph UDP matrix, LocalEngineManager cancellation, LogRedactor, and locked UI`() = runTest {
        val builtIns = BuiltInProfilesCatalog.buildBuiltInProfiles(
            chainConfig = ChainConfig(),
            ptAvailability = PluggableTransportAvailability(null, null)
        )
        assertEquals(3, builtIns.size)
        val psiphon = builtIns.first { it.engineType == LocalEngineProfileType.PSIPHON }
        assertFalse(psiphon.isReady)
        assertTrue(psiphon.statusNote.contains("Needs official Psiphon provisioning"))

        val sub = SubscriptionSource(id = "sub-1", name = "My Sub", url = "https://sub.example.test/list")
        val remote = syntheticProfile(1)
        val selectedRemote = BuiltInProfilesCatalog.resolveSelectedProfile(
            profiles = listOf(remote),
            subscriptions = listOf(sub),
            activeProfileId = remote.id,
            chainConfig = ChainConfig(),
            ptAvailability = PluggableTransportAvailability(null, null)
        )
        assertTrue(selectedRemote is SelectableProfile.RemoteProxy)
        assertEquals("My Sub", (selectedRemote as SelectableProfile.RemoteProxy).ownerGroupName)

        // WS VLESS -> WARP rejected; PROBE_PROXY_PORT collision rejected
        val wsVless = syntheticProfile(1, network = "ws")
        val warpCfg = ChainConfig(
            enabled = true,
            useEntryProxy = true,
            primaryExitType = ChainExitType.CLOUDFLARE_WARP,
            warpConfig = WarpAccountConfig(privateKey = "privKey==", peerPublicKey = "peerKey==")
        )
        assertTrue(RoutingGraph.build(wsVless, warpCfg, listOf(wsVless)).isFailure)
        val probeCollision = ChainConfig(
            enabled = true,
            useEntryProxy = false,
            primaryExitType = ChainExitType.TOR,
            torConfig = TorChainConfig(socksPort = ConfigBuilder.PROBE_PROXY_PORT)
        )
        assertTrue(RoutingGraph.build(null, probeCollision, emptyList()).isFailure)

        // LocalEngineManager stops in-flight starting engine on cancellation
        val started = CompletableDeferred<Unit>()
        val stopped = AtomicBoolean(false)
        val slowEngine = object : LocalEngine {
            override val engineId: String = "tor"
            override val state: StateFlow<LocalEngineState> = MutableStateFlow(
                LocalEngineState(engineId = "tor", status = LocalEngineStatus.STARTING)
            )
            override suspend fun start(): Result<Unit> {
                started.complete(Unit)
                return Result.success(Unit)
            }
            override suspend fun isHealthy(): Boolean = false
            override suspend fun stop() { stopped.set(true) }
        }
        val manager = LocalEngineManager()
        val job = launch { manager.startEngine(slowEngine, readyTimeoutMs = 30_000L) }
        started.await()
        job.cancelAndJoin()
        assertTrue(stopped.get())
        manager.shutdown()

        // Privacy defaults & locked UI compatibility
        val fresh = AppSettings()
        assertFalse(fresh.bypassLan)
        assertFalse(fresh.bypassIran)
        assertTrue(fresh.failClosedMode)
        assertEquals(Ipv6Policy.BLOCK_IPV6, fresh.ipv6Policy)

        val legacyJson = json.encodeToString(
            AppSettings(language = AppLanguage.PERSIAN, themeVariant = VisionThemeVariant.DEEP_SPACE, bypassLan = true)
        )
        val decoded = json.decodeFromString<AppSettings>(legacyJson)
        assertTrue(decoded.bypassLan)
        assertEquals(AppLanguage.ENGLISH, decoded.effectiveLanguage)
        assertEquals(VisionThemeVariant.VISION_NAVY, decoded.effectiveThemeVariant)

        // LogRedactor coverage
        val raw = "Failed vless://11111111-2222-3333-4444-555555555555@host:443 password=topsecret https://sub.example.com/get?token=abc123"
        val redacted = LogRedactor.redact(raw)
        assertFalse(redacted.contains("topsecret"))
        assertFalse(redacted.contains("abc123"))
        assertFalse(redacted.contains("11111111-2222-3333-4444-555555555555"))
    }
}
