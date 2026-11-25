package com.visionvpn.app.data.model

import java.util.UUID

/**
 * Represents a distinct visual and logical profile group in the UI:
 * either an owned [SubscriptionSource] or the dedicated Manual / Local group ([subscription] == null).
 */
data class ProfileGroup(
    val subscription: SubscriptionSource?,
    val profiles: List<ProxyProfile>
) {
    val groupKey: String
        get() = subscription?.id ?: SubscriptionProfileManager.MANUAL_GROUP_KEY

    val isManualGroup: Boolean
        get() = subscription == null
}

/**
 * Identity-bound latency measurement record preventing stale test results from
 * attaching to a different profile or overwriting newer measurements.
 */
data class LatencyMeasurement(
    val subscriptionId: String?,
    val fingerprint: String,
    val status: LatencyStatus,
    val latencyMs: Int?,
    val errorReason: String? = null,
    val testedAtMs: Long = System.currentTimeMillis()
)

data class SubscriptionMetadata(
    val title: String? = null,
    val updateIntervalHours: Int? = null,
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expireEpochSec: Long? = null
)

data class SubscriptionMutationResult(
    val subscriptions: List<SubscriptionSource>,
    val profiles: List<ProxyProfile>,
    val activeProfileId: String?,
    val targetSubscriptionId: String? = null,
    val addedCount: Int = 0,
    val updatedCount: Int = 0
)

/**
 * Pure domain manager enforcing subscription-first profile ownership, isolated refreshes,
 * safe deletions, fingerprint-based deduplication, and independent per-subscription latency sorting.
 */
object SubscriptionProfileManager {

    const val MANUAL_GROUP_KEY = "__manual__"

    /**
     * Groups profiles strictly by owning subscription ID without flattening across subscriptions.
     * Profiles with `subscriptionId == null` are placed in the dedicated Manual / Local group.
     */
    fun groupProfiles(
        profiles: List<ProxyProfile>,
        subscriptions: List<SubscriptionSource>
    ): List<ProfileGroup> {
        val bySubId = profiles.groupBy { it.subscriptionId }
        val knownSubIds = subscriptions.map { it.id }.toSet()

        val result = mutableListOf<ProfileGroup>()
        subscriptions.forEach { sub ->
            result += ProfileGroup(
                subscription = sub,
                profiles = bySubId[sub.id].orEmpty()
            )
        }

        // Manual profiles (subscriptionId == null) plus any orphaned legacy profiles
        val manualProfiles = buildList {
            addAll(bySubId[null].orEmpty())
            bySubId.forEach { (subId, list) ->
                if (subId != null && subId !in knownSubIds) {
                    addAll(list)
                }
            }
        }
        if (manualProfiles.isNotEmpty() || result.isEmpty()) {
            result += ProfileGroup(subscription = null, profiles = manualProfiles)
        }
        return result
    }

    /**
     * Backward-compatible, idempotent migration: reassigns any profile whose `subscriptionId`
     * references a missing/deleted subscription to `null` (Manual / Local) so no user data is lost.
     */
    fun migrateOrphanProfiles(
        profiles: List<ProxyProfile>,
        subscriptions: List<SubscriptionSource>
    ): List<ProxyProfile> {
        val validIds = subscriptions.map { it.id }.toSet()
        return profiles.map { profile ->
            val subId = profile.subscriptionId
            if (subId != null && subId !in validIds) {
                profile.copy(subscriptionId = null)
            } else {
                profile
            }
        }
    }

    /**
     * Imports manual profiles (`subscriptionId = null`) without creating uncontrolled duplicates.
     * Deduplicates by connection fingerprint within the Manual group only, leaving all
     * subscription-owned profiles completely untouched.
     */
    fun mergeManualImport(
        existingProfiles: List<ProxyProfile>,
        incoming: List<ProxyProfile>,
        activeProfileId: String?
    ): SubscriptionMutationResult {
        val existingManualFingerprints = existingProfiles
            .filter { it.subscriptionId == null }
            .map { it.connectionFingerprint() }
            .toMutableSet()

        val toAdd = mutableListOf<ProxyProfile>()
        for (raw in incoming) {
            val manual = raw.copy(subscriptionId = null)
            val fp = manual.connectionFingerprint()
            if (existingManualFingerprints.add(fp)) {
                toAdd += manual
            }
        }

        val updatedProfiles = existingProfiles + toAdd
        val nextActive = activeProfileId ?: toAdd.firstOrNull()?.id
        return SubscriptionMutationResult(
            subscriptions = emptyList(),
            profiles = updatedProfiles,
            activeProfileId = nextActive,
            addedCount = toAdd.size
        )
    }

    /**
     * Adds a new subscription or updates an existing subscription matching the same normalized URL.
     * Isolates changes strictly to that subscription's profiles.
     */
    fun upsertSubscription(
        existingSubscriptions: List<SubscriptionSource>,
        existingProfiles: List<ProxyProfile>,
        activeProfileId: String?,
        requestedName: String,
        url: String,
        fetchedProfiles: List<ProxyProfile>,
        metadata: SubscriptionMetadata = SubscriptionMetadata(),
        nowMs: Long = System.currentTimeMillis()
    ): SubscriptionMutationResult {
        val normalizedUrl = url.trim()
        val existingSub = existingSubscriptions.firstOrNull {
            it.url.trim().equals(normalizedUrl, ignoreCase = true)
        }
        val subId = existingSub?.id ?: UUID.randomUUID().toString()
        val resolvedName = requestedName.trim()
            .ifBlank { metadata.title?.trim().orEmpty() }
            .ifBlank { existingSub?.name.orEmpty() }
            .ifBlank { "Subscription" }

        val refreshedSub = SubscriptionSource(
            id = subId,
            name = resolvedName,
            url = normalizedUrl,
            lastUpdatedEpochMs = nowMs,
            profileCount = 0, // filled after deduplication
            lastError = "",
            updateIntervalHours = metadata.updateIntervalHours ?: existingSub?.updateIntervalHours,
            uploadBytes = metadata.uploadBytes ?: existingSub?.uploadBytes,
            downloadBytes = metadata.downloadBytes ?: existingSub?.downloadBytes,
            totalBytes = metadata.totalBytes ?: existingSub?.totalBytes,
            expireEpochSec = metadata.expireEpochSec ?: existingSub?.expireEpochSec
        )

        val subsWithTarget = if (existingSub != null) {
            existingSubscriptions.map { if (it.id == subId) refreshedSub else it }
        } else {
            existingSubscriptions + refreshedSub
        }

        return applySubscriptionProfiles(
            subscriptions = subsWithTarget,
            existingProfiles = existingProfiles,
            activeProfileId = activeProfileId,
            subscriptionId = subId,
            fetchedProfiles = fetchedProfiles,
            metadata = metadata,
            nowMs = nowMs
        )
    }

    /**
     * Refreshes an existing subscription's profiles in isolation.
     * - Other subscriptions and manual profiles are preserved untouched (same order, IDs, latency).
     * - Duplicate nodes within the fetched payload are collapsed by connection fingerprint.
     * - Matching fingerprints from the previous version of this subscription retain their stable
     *   profile ID (preserving active selection) and their last valid latency measurement.
     */
    fun refreshSubscriptionSuccess(
        existingSubscriptions: List<SubscriptionSource>,
        existingProfiles: List<ProxyProfile>,
        activeProfileId: String?,
        subscriptionId: String,
        fetchedProfiles: List<ProxyProfile>,
        metadata: SubscriptionMetadata = SubscriptionMetadata(),
        nowMs: Long = System.currentTimeMillis()
    ): SubscriptionMutationResult {
        return applySubscriptionProfiles(
            subscriptions = existingSubscriptions,
            existingProfiles = existingProfiles,
            activeProfileId = activeProfileId,
            subscriptionId = subscriptionId,
            fetchedProfiles = fetchedProfiles,
            metadata = metadata,
            nowMs = nowMs
        )
    }

    /**
     * Records a refresh failure on the target subscription without erasing its last-known-good profiles.
     */
    fun refreshSubscriptionFailure(
        existingSubscriptions: List<SubscriptionSource>,
        subscriptionId: String,
        errorMessage: String
    ): List<SubscriptionSource> {
        return existingSubscriptions.map { sub ->
            if (sub.id == subscriptionId) {
                sub.copy(lastError = errorMessage.ifBlank { "Subscription refresh failed" })
            } else {
                sub
            }
        }
    }

    /**
     * Deletes a subscription and ONLY the profiles owned by that subscription.
     * Never deletes unrelated subscriptions or manually added profiles (`subscriptionId == null`).
     */
    fun deleteSubscription(
        existingSubscriptions: List<SubscriptionSource>,
        existingProfiles: List<ProxyProfile>,
        activeProfileId: String?,
        subscriptionId: String
    ): SubscriptionMutationResult {
        val remainingSubs = existingSubscriptions.filterNot { it.id == subscriptionId }
        val removedIds = existingProfiles
            .filter { it.subscriptionId == subscriptionId }
            .map { it.id }
            .toSet()
        val remainingProfiles = existingProfiles.filterNot { it.subscriptionId == subscriptionId }
        val nextActive = if (activeProfileId != null && activeProfileId in removedIds) {
            null
        } else {
            activeProfileId
        }
        return SubscriptionMutationResult(
            subscriptions = remainingSubs,
            profiles = remainingProfiles,
            activeProfileId = nextActive,
            targetSubscriptionId = subscriptionId
        )
    }

    /**
     * Applies latency test results with strict `(subscriptionId, fingerprint)` and timestamp
     * staleness checks, then sorts profiles **independently inside each subscription** (and
     * independently inside the Manual group):
     * 1. Verified `SUCCESS` profiles with `latencyMs > 0` sorted ascending by `latencyMs`.
     * 2. `UNTESTED` profiles preserving their relative order.
     * 3. Failed/timeout/error profiles placed after untested profiles.
     *
     * Profiles from separate subscriptions are NEVER merged into one global latency-sorted list.
     */
    fun applyLatencyResultsAndSortPerSubscription(
        profiles: List<ProxyProfile>,
        subscriptions: List<SubscriptionSource>,
        measurements: List<LatencyMeasurement>
    ): List<ProxyProfile> {
        // Keep only the latest measurement per (subscriptionId, fingerprint)
        val latestByKey = measurements
            .groupBy { it.subscriptionId to it.fingerprint }
            .mapValues { (_, list) -> list.maxBy { it.testedAtMs } }

        val updated = profiles.map { profile ->
            val key = profile.subscriptionId to profile.connectionFingerprint()
            val measurement = latestByKey[key] ?: return@map profile
            // Reject stale results older than what is already stored for this profile
            if (measurement.testedAtMs < profile.latencyTestedAtMs) {
                return@map profile
            }
            val validLatency = if (measurement.status == LatencyStatus.SUCCESS && (measurement.latencyMs ?: 0) > 0) {
                measurement.latencyMs
            } else {
                null
            }
            val effectiveStatus = if (measurement.status == LatencyStatus.SUCCESS && validLatency == null) {
                LatencyStatus.FAILED
            } else {
                measurement.status
            }
            profile.copy(
                latencyMs = validLatency,
                latencyStatus = effectiveStatus,
                latencyError = if (effectiveStatus == LatencyStatus.SUCCESS) null else measurement.errorReason,
                latencyTestedAtMs = measurement.testedAtMs
            )
        }

        // Reassemble strictly per-group, sorting each subscription independently
        val groups = groupProfiles(updated, subscriptions)
        return groups.flatMap { group ->
            sortGroupByLatency(group.profiles)
        }
    }

    /**
     * Sorts a single group's profiles:
     * - Tier 0: `SUCCESS` with `latencyMs > 0`, ascending by `latencyMs`
     * - Tier 1: `UNTESTED` (relative order preserved via stable sort)
     * - Tier 2: Failed/Timeout/DNS/TLS/Protocol errors (relative order preserved)
     */
    fun sortGroupByLatency(groupProfiles: List<ProxyProfile>): List<ProxyProfile> {
        return groupProfiles.sortedWith(
            compareBy<ProxyProfile> { profile ->
                when (profile.effectiveLatencyStatus()) {
                    LatencyStatus.SUCCESS -> if ((profile.latencyMs ?: 0) > 0) 0 else 2
                    LatencyStatus.UNTESTED -> 1
                    else -> 2
                }
            }.thenBy { profile ->
                if (profile.effectiveLatencyStatus() == LatencyStatus.SUCCESS && (profile.latencyMs ?: 0) > 0) {
                    profile.latencyMs!!
                } else {
                    Int.MAX_VALUE
                }
            }
        )
    }

    private fun applySubscriptionProfiles(
        subscriptions: List<SubscriptionSource>,
        existingProfiles: List<ProxyProfile>,
        activeProfileId: String?,
        subscriptionId: String,
        fetchedProfiles: List<ProxyProfile>,
        metadata: SubscriptionMetadata,
        nowMs: Long
    ): SubscriptionMutationResult {
        val oldForSub = existingProfiles.filter { it.subscriptionId == subscriptionId }
        val oldByFingerprint = oldForSub.associateBy { it.connectionFingerprint() }

        val seenFingerprints = mutableSetOf<String>()
        val dedupedNew = mutableListOf<ProxyProfile>()
        for (raw in fetchedProfiles) {
            val owned = raw.copy(subscriptionId = subscriptionId)
            val fp = owned.connectionFingerprint()
            if (!seenFingerprints.add(fp)) continue
            val previous = oldByFingerprint[fp]
            dedupedNew += if (previous != null) {
                owned.copy(
                    id = previous.id,
                    latencyMs = previous.latencyMs,
                    latencyStatus = previous.latencyStatus,
                    latencyError = previous.latencyError,
                    latencyTestedAtMs = previous.latencyTestedAtMs
                )
            } else {
                owned
            }
        }

        val untouchedProfiles = existingProfiles.filterNot { it.subscriptionId == subscriptionId }
        val updatedProfiles = untouchedProfiles + dedupedNew

        val updatedSubs = subscriptions.map { sub ->
            if (sub.id == subscriptionId) {
                sub.copy(
                    name = sub.name.ifBlank { metadata.title?.trim().orEmpty().ifBlank { "Subscription" } },
                    lastUpdatedEpochMs = nowMs,
                    profileCount = dedupedNew.size,
                    lastError = "",
                    updateIntervalHours = metadata.updateIntervalHours ?: sub.updateIntervalHours,
                    uploadBytes = metadata.uploadBytes ?: sub.uploadBytes,
                    downloadBytes = metadata.downloadBytes ?: sub.downloadBytes,
                    totalBytes = metadata.totalBytes ?: sub.totalBytes,
                    expireEpochSec = metadata.expireEpochSec ?: sub.expireEpochSec
                )
            } else {
                sub
            }
        }

        val allIds = updatedProfiles.map { it.id }.toSet()
        val nextActive = when {
            activeProfileId != null && activeProfileId in allIds -> activeProfileId
            activeProfileId == null -> dedupedNew.firstOrNull()?.id
            else -> dedupedNew.firstOrNull()?.id
        }

        return SubscriptionMutationResult(
            subscriptions = updatedSubs,
            profiles = updatedProfiles,
            activeProfileId = nextActive,
            targetSubscriptionId = subscriptionId,
            addedCount = dedupedNew.count { it.connectionFingerprint() !in oldByFingerprint },
            updatedCount = dedupedNew.size
        )
    }
}
