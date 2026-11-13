package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * One measurement, one description.
 *
 * The row and the status bar read the same readout now, because they used to disagree: the row showed
 * the fastest response and the bar showed the median, so a person looking at both saw two pings for one
 * link. These tests pin what "the ping" is — the lowest figure that actually came back — and that a
 * profile with no answer is never described as a slow one.
 */
class PingReadoutTest {

    @Test
    fun theFastestRealResponseIsTheNumberCalledThePing() {
        val readout = PingReadout(
            delayMillis = 120L,
            medianMillis = 180L,
            maxMillis = 260L,
            sampleCount = 3,
            successfulSamples = 3,
            kind = "HTTP",
        )
        assertEquals(120L, readout.fastest)
        assertFalse(readout.unanswered)
        assertFalse(readout.untested)
        // The slowest sample is 140 ms above the fastest: what the row shows after the ≤ sign.
        assertEquals(140L, readout.spreadMillis)
    }

    @Test
    fun aProfileThatWasMeasuredAndSaidNothingIsSilentNotSlow() {
        val readout = PingReadout(delayMillis = -1L, sampleCount = 3, successfulSamples = 0, kind = "HTTP")
        assertTrue(readout.unanswered)
        assertNull(readout.fastest)
        assertEquals(0L, readout.spreadMillis)
    }

    @Test
    fun aProfileThatWasNotMeasuredHasNothingToSay() {
        val readout = PingReadout(delayMillis = 0L)
        assertTrue(readout.untested)
        assertFalse(readout.unanswered)
        assertNull(readout.fastest)
    }

    @Test
    fun aLossySampleCountIsVisibleRatherThanRoundedAway() {
        val readout = PingReadout(
            delayMillis = 210L,
            medianMillis = 240L,
            maxMillis = 300L,
            sampleCount = 3,
            successfulSamples = 2,
            kind = "HTTP",
            partial = true,
        )
        assertTrue(readout.partial)
        assertEquals(2, readout.successfulSamples)
        assertEquals(3, readout.sampleCount)
        // The fastest answer still describes the link, and the row says two of three answered.
        assertEquals(210L, readout.fastest)
    }
}
