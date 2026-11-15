package com.v2ray.ang.dto

import java.io.Serializable

/** One persisted delay-test result delivered from the task process to the UI. */
data class RealPingResult(
    val guid: String,
    /** The fastest real response: the lowest honest ping the link achieved. */
    val delayMillis: Long,
    /** Number of real attempts, including failed or temporarily unmeasurable attempts. */
    val sampleCount: Int = 0,
    val successfulSamples: Int = 0,
    /** Standard deviation of the successful samples, not half of their range. */
    val jitterMillis: Long = 0L,
    /** HTTP for full proxy delay, TCP for reachability; blank for legacy results. */
    val kind: String = "",
    /** Typical response of the successful samples; 0 when none answered. */
    val medianMillis: Long = 0L,
    /** Slowest response of the successful samples; 0 when none answered. */
    val maxMillis: Long = 0L,
    /** True when some sample got no answer, so [delayMillis] is the best of a lossy set. */
    val partial: Boolean = false,
) : Serializable
