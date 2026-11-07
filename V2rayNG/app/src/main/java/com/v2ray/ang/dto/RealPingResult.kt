package com.v2ray.ang.dto

import java.io.Serializable

/** One persisted delay-test result delivered from the task process to the UI. */
data class RealPingResult(
    val guid: String,
    val delayMillis: Long,
    /** Number of real attempts, including failed or temporarily unmeasurable attempts. */
    val sampleCount: Int = 0,
    val successfulSamples: Int = 0,
    val jitterMillis: Long = 0L,
    /** HTTP for full proxy delay, TCP for reachability; blank for legacy results. */
    val kind: String = "",
) : Serializable
