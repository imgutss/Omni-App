package com.v2ray.ang.firefox

/**
 * One Firefox account as this phone keeps it.
 *
 * The account itself never leaves the device: [accessToken] and [refreshToken] live in the app's own
 * encrypted-grade MMKV storage, are written only by the sign-in flow and are never sent anywhere except to
 * Mozilla's own endpoints. No Omni panel, bot or relay sees them; there is no field for them in any payload
 * this app sends.
 */
data class FirefoxAccount(
    val id: String,
    /** The address the user signed in with; shown so a person can tell their own accounts apart. */
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    /** Epoch milliseconds after which [accessToken] has to be refreshed. */
    val expiresAt: Long = 0L,
    /** Epoch milliseconds of the last successful quota reading; 0 when never read. */
    val quotaReadAt: Long = 0L,
    /** Bytes the account's monthly allowance allows; -1 when the account has no limit. */
    val quotaLimitBytes: Long = -1L,
    /** Bytes left in the current period; -1 when unknown. */
    val quotaRemainingBytes: Long = -1L,
    /** Epoch milliseconds when the allowance resets; 0 when unknown. */
    val quotaResetsAt: Long = 0L,
    /** True while this account is the one the proxy reports usage for. */
    val active: Boolean = false,
    /** Why the account cannot be used right now; blank when it is healthy. */
    val problem: String = "",
) {
    val usedBytes: Long get() = if (quotaLimitBytes > 0 && quotaRemainingBytes >= 0) {
        (quotaLimitBytes - quotaRemainingBytes).coerceAtLeast(0L)
    } else {
        0L
    }

    val unlimited: Boolean get() = quotaLimitBytes < 0

    /** Share of the allowance already used, 0..1; 0 when it is unknown or unlimited. */
    val usedFraction: Double get() = if (quotaLimitBytes > 0) {
        (usedBytes.toDouble() / quotaLimitBytes).coerceIn(0.0, 1.0)
    } else {
        0.0
    }

    val expired: Boolean get() = expiresAt > 0L && System.currentTimeMillis() >= expiresAt
}

/**
 * What a quota reading found. Kept apart from [FirefoxAccount] so the storage layer never has to guess
 * whether a missing number means "unlimited" or "not read yet".
 */
data class FirefoxQuota(
    val limitBytes: Long,
    val remainingBytes: Long,
    val resetsAt: Long,
    val unlimited: Boolean,
)

/** A failure the sign-in or the quota request ran into, phrased for a person. */
class FirefoxError(val kind: Kind, message: String) : Exception(message) {
    enum class Kind {
        /** Mozilla's anti-bot page answered; the browser sign-in is the way through. */
        CHALLENGE,

        /** Wrong password, wrong code, or a broken session. */
        CREDENTIALS,

        /** The account has no free VPN allowance. */
        NOT_ENTITLED,

        /** The monthly allowance is spent. */
        QUOTA_EXCEEDED,

        /** Mozilla is not reachable from this network right now (HTTP 451 and friends). */
        REGION,

        /** No usable network. */
        NETWORK,

        /** Anything the core or the platform answered that has no better category. */
        OTHER,
    }
}
