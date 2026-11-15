package com.v2ray.ang.omni.dns

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.SecureRandom

class OmniDnsCandidatePacksTest {
    @Test
    fun cidrParserAcceptsZeroOctetsAndRejectsMalformedRanges() {
        val range = requireNotNull(OmniDnsCandidatePacks.parse("2.0.0.0/8 # synthetic"))
        assertEquals(2L shl 24, range.start)
        assertEquals((2L shl 24) or 0xff_ffffL, range.end)
        assertEquals(1L shl 24, range.size)
        assertEquals("203.0.113.0", OmniDnsCandidatePacks.parse("203.0.113.0/32")?.let(::addressForTest))
        assertEquals(null, OmniDnsCandidatePacks.parse("203.0.113.1/33"))
        assertEquals(null, OmniDnsCandidatePacks.parse("203.0.113.01/32"))
        assertEquals(null, OmniDnsCandidatePacks.parse("not-an-address"))
    }

    @Test
    fun fullAndLiteGroupsKeepTheirOwnBoundaries() {
        val ranges = listOf(
            requireNotNull(OmniDnsCandidatePacks.parse("2.16.0.0/16")),
            requireNotNull(OmniDnsCandidatePacks.parse("2.17.0.0/16")),
            requireNotNull(OmniDnsCandidatePacks.parse("5.1.0.0/16")),
        )
        val full = OmniDnsCandidatePacks.groups(ranges, OmniDnsCandidateSource.IR_FULL)
        assertEquals(listOf("2", "5"), full.map(OmniDnsRangeGroup::key))
        assertEquals("2.x.x.x", full.first().label)
        assertEquals(2, full.first().ranges.size)
        val lite = OmniDnsCandidatePacks.groups(ranges, OmniDnsCandidateSource.IR_LITE)
        assertEquals(listOf("2.16", "2.17", "5.1"), lite.map(OmniDnsRangeGroup::key))
        assertEquals("2.16.x.x", lite.first().label)
    }

    @Test
    fun samplingIsUniqueWeightedAndBoundedBySmallAddressSpaces() {
        val ranges = listOf(requireNotNull(OmniDnsCandidatePacks.parse("198.51.100.0/24")))
        val sampled = OmniDnsCandidatePacks.generate(ranges, 100, SecureRandom())
        assertEquals(100, sampled.size)
        assertEquals(100, sampled.toSet().size)
        assertTrue(sampled.all { it.startsWith("198.51.100.") })
        val small = OmniDnsCandidatePacks.generate(
            listOf(requireNotNull(OmniDnsCandidatePacks.parse("192.0.2.0/30"))), 100, SecureRandom(),
        )
        assertEquals(4, small.size)
        assertEquals(setOf("192.0.2.0", "192.0.2.1", "192.0.2.2", "192.0.2.3"), small.toSet())
    }

    @Test
    fun overlapDoesNotDuplicateCandidatesAndCustomSupportsOneHundredThousand() {
        val ranges = OmniDnsCandidatePacks.normalize(listOf(
            requireNotNull(OmniDnsCandidatePacks.parse("10.0.0.0/9")),
            requireNotNull(OmniDnsCandidatePacks.parse("10.0.0.0/8")),
        ))
        assertEquals((1L shl 24), ranges.sumOf(OmniDnsCidrRange::size))
        val sampled = OmniDnsCandidatePacks.generate(ranges, OmniDnsCandidatePacks.MAX_CUSTOM_COUNT, SecureRandom())
        assertEquals(OmniDnsCandidatePacks.MAX_CUSTOM_COUNT, sampled.size)
        assertEquals(OmniDnsCandidatePacks.MAX_CUSTOM_COUNT, sampled.toSet().size)
        assertTrue(sampled.all { it.startsWith("10.") })
        assertThrows<IllegalArgumentException> {
            OmniDnsCandidatePacks.generate(ranges, OmniDnsCandidatePacks.MAX_CUSTOM_COUNT + 1)
        }
    }
}

private fun addressForTest(range: OmniDnsCidrRange): String = listOf(24, 16, 8, 0).joinToString(".") {
    ((range.start ushr it) and 255L).toString()
}
