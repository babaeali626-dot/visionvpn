package com.visionvpn.app.chain

import com.visionvpn.app.data.model.WarpAccountConfig

/**
 * WARP account enrollment is intentionally not performed by this build. The previously used
 * consumer registration endpoint/client headers are not verified here as a supported public
 * third-party enrollment contract. A successful HTTP response must never be synthesized into
 * a ready WireGuard account by filling missing keys, addresses, reserved bytes, or endpoints.
 *
 * A future implementation must use an authorized provider flow and validate every returned
 * field before persisting it through the encrypted preferences repository.
 */
class WarpManager {
    companion object {
        const val PROVISIONING_BLOCKED_MESSAGE =
            "WARP setup is blocked: this build has no verified authorized enrollment flow. " +
                "Do not use sample keys or guessed endpoints; provider-issued account and WireGuard " +
                "configuration are required."
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun registerNewAccount(
        existingEndpointHost: String = "",
        existingEndpointPort: Int = 0
    ): Result<WarpAccountConfig> = Result.failure(IllegalStateException(PROVISIONING_BLOCKED_MESSAGE))
}
