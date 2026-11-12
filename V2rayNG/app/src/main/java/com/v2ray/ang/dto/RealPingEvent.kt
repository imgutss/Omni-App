package com.v2ray.ang.dto

sealed class RealPingEvent {

    /** Periodic progress update while the batch is still running. */
    data class Progress(val text: String) : RealPingEvent()

    /** A single server result is available. */
    data class Result(
        val guid: String,
        /** The fastest real response; see [com.v2ray.ang.util.OmniPingStats]. */
        val delayMillis: Long,
        val sampleCount: Int = 0,
        val successfulSamples: Int = 0,
        val jitterMillis: Long = 0L,
        val kind: String = "",
        val medianMillis: Long = 0L,
        val maxMillis: Long = 0L,
        val partial: Boolean = false,
    ) : RealPingEvent()

    /** The entire batch has finished or been cancelled. */
    data class Finish(val status: String) : RealPingEvent()
}

