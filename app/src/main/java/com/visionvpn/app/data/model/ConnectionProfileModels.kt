package com.visionvpn.app.data.model

import com.visionvpn.app.chain.PluggableTransportAvailability

/**
 * First-class local engine profile types selectable directly alongside remote proxy profiles
 * without faking remote server/port entries.
 */
enum class LocalEngineProfileType(
    val reservedProfileId: String,
    val displayName: String,
    val exitType: ChainExitType
) {
    TOR("__builtin_tor__", "Tor Network", ChainExitType.TOR),
    PSIPHON("__builtin_psiphon__", "Psiphon Network", ChainExitType.PSIPHON),
    WARP("__builtin_warp__", "Cloudflare WARP", ChainExitType.CLOUDFLARE_WARP);

    companion object {
        fun fromReservedId(id: String?): LocalEngineProfileType? {
            if (id.isNullOrBlank()) return null
            return entries.firstOrNull { it.reservedProfileId == id }
        }

        fun isReservedId(id: String?): Boolean = fromReservedId(id) != null
    }
}

/**
 * Typed connection-profile abstraction uniting remote [ProxyProfile] entries and built-in
 * local engine profiles (Tor, Psiphon, Cloudflare WARP) across Profiles and Home selectors.
 */
sealed class SelectableProfile {
    abstract val id: String
    abstract val displayName: String

    data class RemoteProxy(
        val profile: ProxyProfile,
        val ownerGroupName: String
    ) : SelectableProfile() {
        override val id: String = profile.id
        override val displayName: String = profile.name
    }

    data class BuiltInEngine(
        val engineType: LocalEngineProfileType,
        override val displayName: String,
        val subtitle: String,
        val isReady: Boolean,
        val statusNote: String
    ) : SelectableProfile() {
        override val id: String = engineType.reservedProfileId
    }
}

/**
 * Builds the `Built-in / Local Engines` catalog from the persisted [ChainConfig] and
 * detected pluggable-transport binaries, reused identically by ProfilesScreen and HomeScreen.
 */
object BuiltInProfilesCatalog {

    const val BUILT_IN_GROUP_KEY = "__built_in_engines__"

    fun buildBuiltInProfiles(
        chainConfig: ChainConfig,
        ptAvailability: PluggableTransportAvailability
    ): List<SelectableProfile.BuiltInEngine> {
        val tor = chainConfig.torConfig
        val bridgeMode = tor.effectiveBridgeMode()
        val torBinaryOk = when (bridgeMode) {
            TorBridgeMode.DIRECT -> true
            TorBridgeMode.OBFS4_TRANSPORT, TorBridgeMode.WEBTUNNEL_TRANSPORT ->
                ptAvailability.supportsObfs4 && tor.activeCustomBridgeLines().isNotEmpty()
            TorBridgeMode.SNOWFLAKE_TRANSPORT -> ptAvailability.supportsSnowflake
            TorBridgeMode.CUSTOM -> tor.activeCustomBridgeLines().isNotEmpty()
        }
        val torStatus = when {
            bridgeMode == TorBridgeMode.DIRECT -> "Direct Tor (no bridge)"
            !torBinaryOk && (bridgeMode == TorBridgeMode.OBFS4_TRANSPORT || bridgeMode == TorBridgeMode.WEBTUNNEL_TRANSPORT) ->
                "${bridgeMode.labelEn} • Missing bridge or transport binary"
            !torBinaryOk && bridgeMode == TorBridgeMode.SNOWFLAKE_TRANSPORT ->
                "Snowflake • Missing libsnowflake.so"
            !torBinaryOk -> "Bridge Mode enabled • No bridges configured"
            else -> "Bridge: ${bridgeMode.labelEn}"
        }

        val psi = chainConfig.psiphonConfig
        val regionLabel = PsiphonChainConfig.findRegionOrNull(psi.regionCode)?.nameEn ?: psi.regionCode
        val psiStatus = if (psi.isProvisioned) {
            "Region: $regionLabel • Provisioned"
        } else {
            "Needs official Psiphon provisioning"
        }

        val warp = chainConfig.warpConfig
        val warpStatus = if (warp.isRegistered) {
            "WireGuard registered (${warp.endpointHost}:${warp.endpointPort})"
        } else {
            "Auto-registers WireGuard keys on connect"
        }

        return listOf(
            SelectableProfile.BuiltInEngine(
                engineType = LocalEngineProfileType.TOR,
                displayName = "Tor Network",
                subtitle = "Local Tor Engine (SOCKS5 127.0.0.1:${tor.socksPort})",
                isReady = torBinaryOk,
                statusNote = torStatus
            ),
            SelectableProfile.BuiltInEngine(
                engineType = LocalEngineProfileType.PSIPHON,
                displayName = "Psiphon Network",
                subtitle = "Local Psiphon Engine (Region: $regionLabel)",
                isReady = psi.isProvisioned,
                statusNote = psiStatus
            ),
            SelectableProfile.BuiltInEngine(
                engineType = LocalEngineProfileType.WARP,
                displayName = "Cloudflare WARP",
                subtitle = "Built-in WireGuard Engine",
                isReady = true,
                statusNote = warpStatus
            )
        )
    }

    /**
     * Resolves the currently active [SelectableProfile] from persisted state without ever
     * misinterpreting a stale remote ID or conflating standalone local engines with remote nodes.
     */
    fun resolveSelectedProfile(
        profiles: List<ProxyProfile>,
        subscriptions: List<SubscriptionSource>,
        activeProfileId: String?,
        chainConfig: ChainConfig,
        ptAvailability: PluggableTransportAvailability
    ): SelectableProfile? {
        val builtIns = buildBuiltInProfiles(chainConfig, ptAvailability)

        // Standalone local engine mode takes precedence when useEntryProxy is false
        if (chainConfig.enabled && !chainConfig.useEntryProxy) {
            val match = builtIns.firstOrNull { it.engineType.exitType == chainConfig.activeExitProvider }
            if (match != null) return match
        }

        // Reserved built-in ID in activeProfileId
        val builtInType = LocalEngineProfileType.fromReservedId(activeProfileId)
        if (builtInType != null) {
            return builtIns.firstOrNull { it.engineType == builtInType }
        }

        // Remote proxy profile
        val remote = activeProfileId?.let { id -> profiles.firstOrNull { it.id == id } } ?: return null
        val ownerName = remote.subscriptionId?.let { sid ->
            subscriptions.firstOrNull { it.id == sid }?.name
        } ?: "Manual / Local"
        return SelectableProfile.RemoteProxy(profile = remote, ownerGroupName = ownerName)
    }
}
