package com.v2ray.ang.core

/** Slipstream QUIC session options from the original profile. */
data class SlipstreamConfig(
    val keepAliveIntervalMs: Int = 5_000,
    val congestionControl: String = "bbr",
    val gsoEnabled: Boolean = false,
) {
    fun identityValues(): List<String> = listOf(
        keepAliveIntervalMs.toString(), congestionControl, gsoEnabled.toString(),
    )
}
