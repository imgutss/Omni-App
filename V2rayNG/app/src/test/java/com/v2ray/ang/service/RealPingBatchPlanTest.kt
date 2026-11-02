package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RealPingBatchPlanTest {

    @Test
    fun everyRequestedSampleRuns() {
        assertEquals(1, RealPingBatchPlan.samples(1))
        assertEquals(3, RealPingBatchPlan.samples(3))
        assertEquals(5, RealPingBatchPlan.samples(5))
    }

    @Test
    fun samplesStayInsideTheSupportedRange() {
        assertEquals(1, RealPingBatchPlan.samples(0))
        assertEquals(5, RealPingBatchPlan.samples(9))
    }

    @Test
    fun thePoolHoldsEverySampleThatRunsTogether() {
        assertEquals(48, RealPingBatchPlan.threads(concurrency = 16, samples = 3, onlyTcp = false))
    }

    @Test
    fun thePoolIsBoundedForLargeBatches() {
        assertEquals(
            RealPingBatchPlan.MAX_THREADS,
            RealPingBatchPlan.threads(concurrency = 128, samples = 5, onlyTcp = false),
        )
    }

    @Test
    fun tcpPingLeavesNoRoomForSamplesItDoesNotRun() {
        assertEquals(32, RealPingBatchPlan.threads(concurrency = 16, samples = 3, onlyTcp = true))
    }
}
