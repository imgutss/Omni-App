package com.v2ray.ang.omni.lab

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OmniLatencyStatsTest {
    @Test
    fun noResponseIsNotZeroLatency() {
        val stats = OmniLatencyStats.from(listOf(null, null), 2)
        assertNull(stats.medianMs)
        assertNull(stats.jitterMs)
        assertEquals(0, stats.succeeded)
        assertEquals(100.0, stats.lossPercent, 0.0)
    }

    @Test
    fun medianJitterAndLossUseRealSamples() {
        val stats = OmniLatencyStats.from(listOf(10L, 30L, null, 20L), 4)
        assertEquals(20L, stats.medianMs)
        assertEquals(3, stats.succeeded)
        assertEquals(25.0, stats.lossPercent, 0.0)
        assertEquals(15L, stats.jitterMs)
    }
}
