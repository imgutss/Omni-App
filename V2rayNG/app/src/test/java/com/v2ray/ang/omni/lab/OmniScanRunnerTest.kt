package com.v2ray.ang.omni.lab

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OmniScanRunnerTest {
    @Test
    fun maxTargetsBoundsLazyInputAndResultsCanBeRanked() = runBlocking {
        val plan = OmniTargetParser.parse("0.0.0.0/0")
        val results = OmniScanRunner(OmniEdgeProbe { target, options ->
            OmniEdgeProbeResult(target, options.mode, OmniLatencyStats.from(listOf(1L), 1))
        }).scan(plan, OmniScanOptions(concurrency = 2, maxTargets = 3)).toList()
        assertEquals(3, results.size)
        assertEquals(3, results.rankByEdgeQuality().size)
    }

    @Test
    fun completeSamplesOutrankOneFastButLossyResponse() {
        val lossy = OmniEdgeProbeResult(
            OmniScanTarget("192.0.2.1", 443, OmniTargetKind.IPV4),
            OmniProbeMode.TCP,
            OmniLatencyStats.from(listOf(10L, null, null), 3),
        )
        val complete = OmniEdgeProbeResult(
            OmniScanTarget("192.0.2.2", 443, OmniTargetKind.IPV4),
            OmniProbeMode.TCP,
            OmniLatencyStats.from(listOf(80L, 90L, 100L), 3),
        )
        assertFalse(lossy.verified)
        assertTrue(complete.verified)
        assertEquals(listOf(complete, lossy), listOf(lossy, complete).rankByEdgeQuality())
    }
}
