package com.v2ray.ang.omni

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.omni.lab.OmniTransferResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OmniSpeedLabLogicTest {
    @Test
    fun transferSizesAreBoundedAndNeverDefaultToAClaim() {
        assertEquals(1_024, parseOmniTransferSize("1024"))
        assertEquals(16 * 1_024 * 1_024, parseOmniTransferSize("16777216"))
        assertNull(parseOmniTransferSize("1023"))
        assertNull(parseOmniTransferSize("16777217"))
        assertNull(parseOmniTransferSize("not-a-number"))
    }

    @Test
    fun plannedDataIncludesEveryProfileAndBoundsFallbackTraffic() {
        assertEquals(18_432L, omniSpeedPlannedDataBytes(3, "1024", "2048", OMNI_SPEED_CLOUDFLARE_UPLOAD, OMNI_SPEED_CLOUDFLARE_DOWNLOAD))
        assertEquals(9_216L, omniSpeedPlannedDataBytes(3, "1024", "2048", OMNI_SPEED_CLOUDFLARE_UPLOAD, OMNI_SPEED_OVH_DOWNLOAD))
        assertEquals(6_144L, omniSpeedPlannedDataBytes(3, "1024", "2048", "", OMNI_SPEED_CLOUDFLARE_DOWNLOAD))
        assertNull(omniSpeedPlannedDataBytes(3, "bad", "2048", OMNI_SPEED_CLOUDFLARE_UPLOAD, OMNI_SPEED_CLOUDFLARE_DOWNLOAD))
    }

    @Test
    fun ranksRealDownloadRateFirstAndKeepsFailuresVisible() {
        val slow = result("slow", OmniSpeedStatus.SUCCESS, transfer(1_048_576, 2_000))
        val fast = result("fast", OmniSpeedStatus.SUCCESS, transfer(4_194_304, 1_000))
        val untested = result("untested", OmniSpeedStatus.UNTESTED, null)
        val failed = result("failed", OmniSpeedStatus.FAILED, transfer(16_777_216, 1).copy(success = false))
        assertEquals(listOf("fast", "slow", "untested", "failed"), rankOmniSpeedResults(listOf(slow, failed, fast, untested)).map { it.profile.guid })
    }

    @Test
    fun fasterTtfbBreaksEqualThroughputWithoutReplacingSpeedRanking() {
        val highTtfb = result("high", OmniSpeedStatus.SUCCESS, transfer(1_048_576, 1_000).copy(downloadTtfbMs = 900))
        val lowTtfb = result("low", OmniSpeedStatus.SUCCESS, transfer(1_048_576, 1_000).copy(downloadTtfbMs = 100))
        assertEquals(listOf("low", "high"), rankOmniSpeedResults(listOf(highTtfb, lowTtfb)).map { it.profile.guid })
    }

    private fun result(guid: String, status: OmniSpeedStatus, transfer: OmniTransferResult?) = OmniSpeedProfileResult(
        profile = OmniSpeedProfile(guid, ProfileItem(configType = EConfigType.VLESS, remarks = guid)),
        status = status,
        transfer = transfer,
    )

    private fun transfer(bytes: Long, durationMs: Long) = OmniTransferResult(
        success = true,
        downloadBytes = bytes,
        downloadDurationMs = durationMs,
        downloadLimitBytes = bytes.toInt(),
    )
}
