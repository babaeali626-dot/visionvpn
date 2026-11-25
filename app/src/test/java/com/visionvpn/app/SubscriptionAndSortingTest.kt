package com.visionvpn.app

import com.visionvpn.app.data.model.LatencyMeasurement
import com.visionvpn.app.data.model.LatencyStatus
import com.visionvpn.app.data.model.ProxyProfile
import com.visionvpn.app.data.model.ProxyProtocol
import com.visionvpn.app.data.model.SubscriptionMetadata
import com.visionvpn.app.data.model.SubscriptionProfileManager
import com.visionvpn.app.data.model.SubscriptionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionAndSortingTest {

    private fun sampleProfile(
        id: String,
        name: String,
        server: String,
        port: Int = 443,
        subId: String? = null,
        uuid: String = "00000000-0000-0000-0000-000000000001"
    ) = ProxyProfile(
        id = id,
        name = name,
        protocol = ProxyProtocol.VLESS,
        server = server,
        port = port,
        rawUri = "vless://$uuid@$server:$port?security=tls#$name",
        subscriptionId = subId,
        uuid = uuid,
        security = "tls",
        sni = server
    )

    @Test
    fun `two subscriptions and manual profiles remain independently grouped`() {
        val manual = sampleProfile("m1", "Manual Node", "manual.example.test", subId = null)
        val first = SubscriptionProfileManager.upsertSubscription(
            existingSubscriptions = emptyList(),
            existingProfiles = listOf(manual),
            activeProfileId = manual.id,
            requestedName = "Sub Alpha",
            url = "https://sub-a.example.test/feed",
            fetchedProfiles = listOf(
                sampleProfile("a1", "Alpha 1", "a1.example.test"),
                sampleProfile("a2", "Alpha 2", "a2.example.test")
            ),
            nowMs = 1000L
        )
        val subAId = first.targetSubscriptionId!!

        val second = SubscriptionProfileManager.upsertSubscription(
            existingSubscriptions = first.subscriptions,
            existingProfiles = first.profiles,
            activeProfileId = first.activeProfileId,
            requestedName = "Sub Beta",
            url = "https://sub-b.example.test/feed",
            fetchedProfiles = listOf(
                sampleProfile("b1", "Beta 1", "b1.example.test")
            ),
            nowMs = 2000L
        )
        val subBId = second.targetSubscriptionId!!

        val groups = SubscriptionProfileManager.groupProfiles(second.profiles, second.subscriptions)
        assertEquals(3, groups.size)
        assertEquals(subAId, groups[0].subscription?.id)
        assertEquals(2, groups[0].profiles.size)
        assertEquals(subBId, groups[1].subscription?.id)
        assertEquals(1, groups[1].profiles.size)
        assertTrue(groups[2].isManualGroup)
        assertEquals(listOf("m1"), groups[2].profiles.map { it.id })
    }

    @Test
    fun `refreshing subscription A isolates changes and preserves B and manual profiles`() {
        val subA = SubscriptionSource(id = "sub-a", name = "Alpha", url = "https://a.example.test/sub", profileCount = 1)
        val subB = SubscriptionSource(id = "sub-b", name = "Beta", url = "https://b.example.test/sub", profileCount = 1)
        val pA = sampleProfile("a1", "A-Old", "a1.example.test", subId = "sub-a").copy(
            latencyMs = 120,
            latencyStatus = LatencyStatus.SUCCESS,
            latencyTestedAtMs = 5000L
        )
        val pB = sampleProfile("b1", "B-Node", "b1.example.test", subId = "sub-b")
        val pM = sampleProfile("m1", "Manual", "m1.example.test", subId = null)

        val refreshed = SubscriptionProfileManager.refreshSubscriptionSuccess(
            existingSubscriptions = listOf(subA, subB),
            existingProfiles = listOf(pA, pB, pM),
            activeProfileId = "a1",
            subscriptionId = "sub-a",
            fetchedProfiles = listOf(
                // Same connection fingerprint as pA -> should keep id & latency!
                sampleProfile("new-random-id", "A-Renamed", "a1.example.test"),
                sampleProfile("a2", "A-New", "a2.example.test")
            ),
            metadata = SubscriptionMetadata(title = "Alpha", updateIntervalHours = 12),
            nowMs = 9000L
        )

        val aProfiles = refreshed.profiles.filter { it.subscriptionId == "sub-a" }
        val bProfiles = refreshed.profiles.filter { it.subscriptionId == "sub-b" }
        val mProfiles = refreshed.profiles.filter { it.subscriptionId == null }

        assertEquals(2, aProfiles.size)
        assertEquals("a1", aProfiles[0].id)
        assertEquals(120, aProfiles[0].latencyMs)
        assertEquals("a1", refreshed.activeProfileId)
        assertEquals(listOf(pB), bProfiles)
        assertEquals(listOf(pM), mProfiles)
    }

    @Test
    fun `failed refresh retains last known good profiles and records lastError`() {
        val subA = SubscriptionSource(id = "sub-a", name = "Alpha", url = "https://a.example.test/sub", profileCount = 1)
        val updatedSubs = SubscriptionProfileManager.refreshSubscriptionFailure(
            existingSubscriptions = listOf(subA),
            subscriptionId = "sub-a",
            errorMessage = "HTTP 503: Service Unavailable"
        )
        assertEquals("HTTP 503: Service Unavailable", updatedSubs.single().lastError)
        assertEquals(1, updatedSubs.single().profileCount)
    }

    @Test
    fun `deleting subscription removes only its own profiles and never deletes manual or other subs`() {
        val subA = SubscriptionSource(id = "sub-a", name = "Alpha", url = "https://a.example.test/sub", profileCount = 1)
        val subB = SubscriptionSource(id = "sub-b", name = "Beta", url = "https://b.example.test/sub", profileCount = 1)
        val pA = sampleProfile("a1", "A", "a1.example.test", subId = "sub-a")
        val pB = sampleProfile("b1", "B", "b1.example.test", subId = "sub-b")
        val pM = sampleProfile("m1", "M", "m1.example.test", subId = null)

        val deleted = SubscriptionProfileManager.deleteSubscription(
            existingSubscriptions = listOf(subA, subB),
            existingProfiles = listOf(pA, pB, pM),
            activeProfileId = "a1",
            subscriptionId = "sub-a"
        )

        assertEquals(listOf("sub-b"), deleted.subscriptions.map { it.id })
        assertEquals(listOf("b1", "m1"), deleted.profiles.map { it.id })
        assertNull(deleted.activeProfileId)
    }

    @Test
    fun `duplicate imports collapse identical fingerprints while keeping distinct nodes with same name`() {
        val existing = listOf(sampleProfile("m1", "Fast Node", "one.example.test", port = 443, subId = null))
        val incoming = listOf(
            // Exact duplicate connection params (different name) -> skipped
            sampleProfile("m2", "Fast Node Copy", "one.example.test", port = 443, subId = null),
            // Same display name, different server -> kept
            sampleProfile("m3", "Fast Node", "two.example.test", port = 443, subId = null)
        )

        val merged = SubscriptionProfileManager.mergeManualImport(existing, incoming, activeProfileId = "m1")
        assertEquals(1, merged.addedCount)
        assertEquals(2, merged.profiles.size)
    }

    @Test
    fun `latency sorting runs independently inside each subscription and rejects stale measurements`() {
        val subA = SubscriptionSource(id = "sub-a", name = "Alpha", url = "https://a.example.test/sub", profileCount = 3)
        val subB = SubscriptionSource(id = "sub-b", name = "Beta", url = "https://b.example.test/sub", profileCount = 2)

        val aSlow = sampleProfile("a-slow", "A Slow", "a-slow.example.test", subId = "sub-a")
        val aFail = sampleProfile("a-fail", "A Fail", "a-fail.example.test", subId = "sub-a")
        val aFast = sampleProfile("a-fast", "A Fast", "a-fast.example.test", subId = "sub-a")
        val bSlow = sampleProfile("b-slow", "B Slow", "b-slow.example.test", subId = "sub-b")
        val bFast = sampleProfile("b-fast", "B Fast", "b-fast.example.test", subId = "sub-b").copy(
            latencyMs = 95,
            latencyStatus = LatencyStatus.SUCCESS,
            latencyTestedAtMs = 5000L
        )

        val measurements = listOf(
            LatencyMeasurement("sub-a", aSlow.connectionFingerprint(), LatencyStatus.SUCCESS, 320, testedAtMs = 6000L),
            LatencyMeasurement("sub-a", aFail.connectionFingerprint(), LatencyStatus.TIMEOUT, null, "Timed out", testedAtMs = 6000L),
            LatencyMeasurement("sub-a", aFast.connectionFingerprint(), LatencyStatus.SUCCESS, 85, testedAtMs = 6000L),
            LatencyMeasurement("sub-b", bSlow.connectionFingerprint(), LatencyStatus.SUCCESS, 210, testedAtMs = 6000L),
            // Stale older measurement (testedAtMs=4000 < 5000) must NOT overwrite bFast's 95ms
            LatencyMeasurement("sub-b", bFast.connectionFingerprint(), LatencyStatus.SUCCESS, 999, testedAtMs = 4000L)
        )

        val sorted = SubscriptionProfileManager.applyLatencyResultsAndSortPerSubscription(
            profiles = listOf(aSlow, aFail, aFast, bSlow, bFast),
            subscriptions = listOf(subA, subB),
            measurements = measurements
        )

        val groups = SubscriptionProfileManager.groupProfiles(sorted, listOf(subA, subB))
        assertEquals(listOf("a-fast", "a-slow", "a-fail"), groups[0].profiles.map { it.id })
        assertEquals(listOf("b-fast", "b-slow"), groups[1].profiles.map { it.id })
        assertEquals(95, groups[1].profiles.first().latencyMs)
        assertNull(groups[0].profiles.last().latencyMs)
        assertEquals(LatencyStatus.TIMEOUT, groups[0].profiles.last().latencyStatus)
    }

    @Test
    fun `orphan profile migration is idempotent and preserves legacy profiles in manual group`() {
        val subA = SubscriptionSource(id = "sub-a", name = "Alpha", url = "https://a.example.test/sub")
        val orphan = sampleProfile("old-1", "Legacy Node", "legacy.example.test", subId = "deleted-sub")
        val owned = sampleProfile("ok-1", "Owned Node", "owned.example.test", subId = "sub-a")

        val pass1 = SubscriptionProfileManager.migrateOrphanProfiles(listOf(orphan, owned), listOf(subA))
        val pass2 = SubscriptionProfileManager.migrateOrphanProfiles(pass1, listOf(subA))

        assertEquals(pass1, pass2)
        assertNull(pass1.first { it.id == "old-1" }.subscriptionId)
        assertNotNull(pass1.first { it.id == "ok-1" }.subscriptionId)
    }
}
