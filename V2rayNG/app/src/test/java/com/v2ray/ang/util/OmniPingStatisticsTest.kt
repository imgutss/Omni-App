package com.v2ray.ang.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OmniPingStatisticsTest {
    @Test
    fun theReportedDelayIsTheFastestRealResponse() {
        val result = OmniPingStatistics.summarize(listOf(500L, 300L, 480L), OmniPingStatistics.KIND_HTTP)
        // The lowest honest figure: one request really answered in 300 ms.
        assertEquals(300L, result.delayMillis)
        // The typical and the slowest response are reported next to it, so the figure above is checkable.
        assertEquals(480L, result.medianMillis)
        assertEquals(500L, result.maxMillis)
        assertEquals(3, result.sampleCount)
        assertEquals(3, result.successfulSamples)
        assertFalse(result.partial)
    }

    @Test
    fun failuresAreNotConvertedIntoZeroLatency() {
        val result = OmniPingStatistics.summarize(listOf(-1L, -1L, -1L), OmniPingStatistics.KIND_HTTP)
        assertEquals(-1L, result.delayMillis)
        assertEquals(0, result.successfulSamples)
        assertEquals(3, result.sampleCount)
        assertTrue(result.partial)
    }

    @Test
    fun untestedRemainsDistinctFromFailure() {
        val result = OmniPingStatistics.summarize(listOf(0L), OmniPingStatistics.KIND_HTTP)
        assertEquals(0L, result.delayMillis)
        assertEquals(0, result.successfulSamples)
        assertTrue(result.partial)
    }

    @Test
    fun aLossyResultSaysSoInsteadOfShowingTheBestAsTheTruth() {
        val result = OmniPingStatistics.summarize(listOf(-1L, 250L, 300L), OmniPingStatistics.KIND_HTTP)
        assertEquals(250L, result.delayMillis)
        assertEquals(275L, result.medianMillis)
        assertEquals(300L, result.maxMillis)
        assertEquals(2, result.successfulSamples)
        assertEquals(3, result.sampleCount)
        assertTrue(result.partial, "one of three requests got no answer, so the figure is best-of-two")
    }

    @Test
    fun jitterIsTheStandardDeviationOfTheRawSamples() {
        // mean = 150, squared deviations 900 + 900 + 0 -> variance 600 -> sd 24.49 -> 24
        val result = OmniPingStatistics.summarize(listOf(120L, 180L, 150L), OmniPingStatistics.KIND_HTTP)
        assertEquals(24L, result.jitterMillis)
        assertEquals(150L, result.medianMillis)
    }

    @Test
    fun jitterDoesNotDependOnTheOrderSamplesArriveIn() {
        val together = OmniPingStatistics.summarize(listOf(120L, 180L, 150L), OmniPingStatistics.KIND_HTTP)
        val reversed = OmniPingStatistics.summarize(listOf(150L, 180L, 120L), OmniPingStatistics.KIND_HTTP)
        assertEquals(together, reversed)
    }

    @Test
    fun jitterOfOneSampleIsZero() {
        assertEquals(0L, OmniPingStatistics.standardDeviation(listOf(42L)))
        assertEquals(0L, OmniPingStatistics.standardDeviation(emptyList()))
    }

    @Test
    fun anEvenSpreadIsTheMedianOfBothMiddleSamples() {
        val result = OmniPingStatistics.summarize(listOf(100L, 200L, 300L, 400L), OmniPingStatistics.KIND_HTTP)
        assertEquals(100L, result.delayMillis)
        assertEquals(250L, result.medianMillis)
        assertEquals(400L, result.maxMillis)
    }
}
