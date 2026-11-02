package com.v2ray.ang.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OmniPingStatisticsTest {
    @Test
    fun medianUsesOnlyRealSuccessfulSamples() {
        val result = OmniPingStatistics.summarize(listOf(400L, 100L, 200L), OmniPingStatistics.KIND_HTTP)
        assertEquals(200L, result.delayMillis)
        assertEquals(3, result.sampleCount)
        assertEquals(3, result.successfulSamples)
        assertEquals(150L, result.jitterMillis)
    }

    @Test
    fun failuresAreNotConvertedIntoZeroLatency() {
        val result = OmniPingStatistics.summarize(listOf(-1L, -1L, -1L), OmniPingStatistics.KIND_HTTP)
        assertEquals(-1L, result.delayMillis)
        assertEquals(0, result.successfulSamples)
        assertEquals(3, result.sampleCount)
    }

    @Test
    fun untestedRemainsDistinctFromFailure() {
        val result = OmniPingStatistics.summarize(listOf(0L), OmniPingStatistics.KIND_HTTP)
        assertEquals(0L, result.delayMillis)
        assertEquals(0, result.successfulSamples)
    }

    @Test
    fun partialSuccessRetainsTheAttemptCount() {
        val result = OmniPingStatistics.summarize(listOf(-1L, 250L, 300L), OmniPingStatistics.KIND_HTTP)
        assertEquals(275L, result.delayMillis)
        assertEquals(2, result.successfulSamples)
        assertEquals(3, result.sampleCount)
        assertEquals(50L, result.jitterMillis)
    }

    @Test
    fun jitterDoesNotDependOnTheOrderSamplesArriveIn() {
        val together = OmniPingStatistics.summarize(listOf(120L, 180L, 150L), OmniPingStatistics.KIND_HTTP)
        val reversed = OmniPingStatistics.summarize(listOf(150L, 180L, 120L), OmniPingStatistics.KIND_HTTP)
        assertEquals(150L, together.delayMillis)
        assertEquals(together, reversed)
    }
}
