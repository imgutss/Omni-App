package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RealPingBatchPlanTest {

    @Test
    fun everyRequestedSampleRuns() {
        assertEquals(1, RealPingBatchPlan.samples(1))
        assertEquals(2, RealPingBatchPlan.samples(2))
        assertEquals(3, RealPingBatchPlan.samples(3))
    }

    @Test
    fun samplesStayInsideTheSupportedRange() {
        assertEquals(1, RealPingBatchPlan.samples(0))
        // One native sample is already the best of two requests, so five samples meant ten round trips.
        assertEquals(3, RealPingBatchPlan.samples(5))
        assertEquals(3, RealPingBatchPlan.samples(9))
    }

    @Test
    fun aBatchNeverOpensMoreThreadsThanItCanFinish() {
        // The old plan asked for concurrency × samples (48 at the defaults, 640 with the settings at their
        // maximum); the samples of a profile now run one after the other, so a batch needs one thread each.
        assertEquals(1, RealPingBatchPlan.threads(1))
        assertEquals(16, RealPingBatchPlan.threads(16))
        assertEquals(RealPingBatchPlan.MAX_THREADS, RealPingBatchPlan.threads(128))
    }

    @Test
    fun concurrencyIsClampedToWhatTheCoreCanCarry() {
        assertEquals(1, RealPingBatchPlan.concurrency(0))
        assertEquals(16, RealPingBatchPlan.concurrency(16))
        assertEquals(RealPingBatchPlan.MAX_CONCURRENCY, RealPingBatchPlan.concurrency(128))
    }

    @Test
    fun aWholeBatchHasABudgetAndSoHasOneProfile() {
        // A profile that never answers must not hold a thread for twelve seconds.
        assertTrue(RealPingBatchPlan.PROFILE_BUDGET_MS in 1..12_000)
        // The batch as a whole gives up in about a minute, not in minutes.
        assertTrue(RealPingBatchPlan.BATCH_BUDGET_MS <= 120_000)
        assertTrue(RealPingBatchPlan.BATCH_BUDGET_MS > RealPingBatchPlan.PROFILE_BUDGET_MS)
    }
}
