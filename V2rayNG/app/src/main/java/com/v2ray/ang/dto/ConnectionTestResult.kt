package com.v2ray.ang.dto

import java.io.Serializable

/** Locale-neutral result sent from the daemon for presentation by the UI process. */
data class ConnectionTestResult(
    val delayMillis: Long,
    val errorMessage: String = "",
    val country: String? = null,
    val ipAddress: String? = null,
    val sampleCount: Int = 0,
    val successfulSamples: Int = 0,
    val jitterMillis: Long = 0L,
    val kind: String = "",
) : Serializable
