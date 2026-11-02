package com.v2ray.ang.omni.dns

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.DnsttFmt
import com.v2ray.ang.fmt.MasterDnsFmt
import com.v2ray.ang.fmt.SoarProfileFmt
import com.v2ray.ang.omni.lab.OmniLatencyStats
import com.v2ray.ang.omni.lab.OmniScanTarget
import com.v2ray.ang.omni.lab.OmniTargetKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class OmniDnsResolverTargetsTest {
    @Test
    fun listsRangesAndActualPortsRemainCanonical() {
        val plan = OmniDnsResolverTargets.parseAndSample(
            "1.1.1.1, 8.8.8.8:5353 192.0.2.0/30 198.51.100.1-3 [::1]:53",
            listOf(53), 20, Random(7),
        )
        assertEquals(0, plan.invalidInputs)
        assertEquals(10L, plan.totalCandidates)
        assertEquals(10, plan.targets.size)
        assertTrue(plan.targets.any { it.endpointId == "8.8.8.8:5353" })
        assertTrue(plan.targets.any { it.endpointId == "[::1]:53" })
        assertTrue(plan.targets.any { it.endpointId == "198.51.100.3:53" })
        assertFalse(plan.targets.any { it.endpointId.endsWith(":53:0") })
    }

    @Test
    fun veryLargeCidrIsRandomlySampledWithoutExpansion() {
        val plan = OmniDnsResolverTargets.parseAndSample("0.0.0.0/0", listOf(53), 32, Random(42))
        assertEquals(4_294_967_296L, plan.totalCandidates)
        assertEquals(32, plan.targets.size)
        assertEquals(32, plan.targets.map { it.endpointId }.distinct().size)
    }

    @Test
    fun candidatePreservesAuthoritySeparatelyFromVisibleResolver() {
        val template = ProfileItem(
            configType = EConfigType.DNSTT,
            remarks = "test",
            dnsttEngine = DnsttFmt.ENGINE_DNSTT,
            dnsttTransport = "udp",
            dnsttResolver = "1.1.1.1:53",
            dnsttResolverAuthorities = "1",
            dnsttDomain = "t.example.com",
            dnsttPublicKey = "a".repeat(64),
        )
        val candidate = dnsCandidateProfile(template, OmniScanTarget("9.9.9.9", 5353, OmniTargetKind.IPV4))
        assertEquals("9.9.9.9:5353", candidate.dnsttResolver)
        assertEquals("1", candidate.dnsttResolverAuthorities)
        assertEquals(1, candidate.dnsttRrSpreadCount)
        assertEquals(null, DnsttFmt.normalize(candidate.copy()))
    }

    @Test
    fun officialCandidateKeepsTheOfficialEngineDefaults() {
        val template = ProfileItem(
            configType = EConfigType.DNSTT,
            remarks = "official",
            dnsttEngine = DnsttFmt.ENGINE_OFFICIAL,
            dnsttTransport = "udp",
            dnsttResolver = "1.1.1.1:53",
            dnsttDomain = "t.example.com",
            dnsttPublicKey = "a".repeat(64),
        )
        val candidate = dnsCandidateProfile(template, OmniScanTarget("9.9.9.9", 53, OmniTargetKind.IPV4))
        assertEquals("9.9.9.9:53", candidate.dnsttResolver)
        assertEquals(null, candidate.dnsttResolverAuthorities)
        assertEquals(3, candidate.dnsttRrSpreadCount)
        assertEquals(null, DnsttFmt.normalize(candidate.copy()))
        val legacy = dnsCandidateProfile(template.copy(dnsttEngine = null), OmniScanTarget("9.9.9.9", 53, OmniTargetKind.IPV4))
        assertEquals(DnsttFmt.ENGINE_OFFICIAL, legacy.dnsttEngine)
        assertEquals(3, legacy.dnsttRrSpreadCount)
    }

    @Test
    fun candidateKeepsTheMatchingAuthorityInMixedResolverProfiles() {
        val template = ProfileItem(
            configType = EConfigType.DNSTT,
            remarks = "mixed",
            dnsttEngine = DnsttFmt.ENGINE_SLIPSTREAM,
            dnsttTransport = "udp",
            dnsttResolver = "1.1.1.1:53,9.9.9.9:53",
            dnsttResolverAuthorities = "0,1",
            dnsttAuthoritative = true,
            dnsttDomain = "t.example.com",
        )
        val original = dnsCandidateProfile(template, OmniScanTarget("9.9.9.9", 53, OmniTargetKind.IPV4))
        assertEquals("1", original.dnsttResolverAuthorities)
        assertTrue(original.dnsttAuthoritative == true)

        val newResolver = dnsCandidateProfile(template, OmniScanTarget("8.8.8.8", 53, OmniTargetKind.IPV4))
        assertEquals("0", newResolver.dnsttResolverAuthorities)
        assertFalse(newResolver.dnsttAuthoritative == true)
    }

    @Test
    fun cottenAndSoarCandidatesPreserveTheirRealEngineConfiguration() {
        val cotten = ProfileItem(
            configType = EConfigType.MASTERDNS,
            remarks = "cotten",
            masterDnsEngine = MasterDnsFmt.ENGINE_COTTENDNS,
            masterDnsConfig = "DOMAINS = [\"cot.example\"]\nDATA_ENCRYPTION_METHOD = 5\nENCRYPTION_KEY = \"00112233445566778899aabbccddeeff\"\n",
            masterDnsResolvers = "1.1.1.1\n",
        )
        val cottenCandidate = dnsCandidateProfile(cotten, OmniScanTarget("9.9.9.9", 5353, OmniTargetKind.IPV4))
        assertEquals("9.9.9.9:5353\n", cottenCandidate.masterDnsResolvers)
        assertEquals(cotten.masterDnsConfig?.trim(), cottenCandidate.masterDnsConfig)
        assertEquals(MasterDnsFmt.ENGINE_COTTENDNS, cottenCandidate.masterDnsEngine)
        assertEquals(null, MasterDnsFmt.normalize(cottenCandidate.copy()))

        val soar = ProfileItem(
            configType = EConfigType.DNSTT,
            remarks = "soar",
            dnsttEngine = DnsttFmt.ENGINE_SOAR,
            dnsttTransport = "udp",
            dnsttResolver = "1.1.1.1:53",
            dnsttDomain = "soar.example",
            dnsttPublicKey = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE",
            soarSettings = SoarProfileFmt.normalizeSettings("{\"transport\":\"only\",\"discovery\":false}"),
        )
        val soarCandidate = dnsCandidateProfile(soar, OmniScanTarget("9.9.9.9", 53, OmniTargetKind.IPV4))
        assertEquals("9.9.9.9:53", soarCandidate.dnsttResolver)
        assertEquals("tcp", soarCandidate.dnsttTransport)
        assertEquals(soar.soarSettings, soarCandidate.soarSettings)
        assertEquals(null, soarCandidate.dnsttResolverAuthorities)
        assertEquals(null, DnsttFmt.normalize(soarCandidate.copy()))
    }

    @Test
    fun bestResolverRequiresE2eAndRanksMeasuredQuality() {
        val targets = listOf("1.1.1.1:53", "8.8.8.8:53").map {
            OmniDnsResolverResult(OmniScanTarget(it.substringBefore(':'), 53, OmniTargetKind.IPV4), OmniLatencyStats(listOf(5), 1, 1))
        }
        assertEquals(null, bestDnsResolver(targets, emptyMap()))
        val e2e = mapOf(
            targets[0].target.endpointId to OmniDnsE2eResult(OmniLatencyStats(listOf(50, 60), 2, 2)),
            targets[1].target.endpointId to OmniDnsE2eResult(OmniLatencyStats(listOf(20L, 30L), 3, 2)),
        )
        assertEquals(targets[0].target.endpointId, bestDnsResolver(targets, e2e)?.target?.endpointId)
    }

    @Test
    fun dnsResponseValidationRequiresMatchingTransactionAndQuestion() {
        val query = buildDnsQuery("t.example.com", 0x1234)
        val response = query.copyOf(query.size + 12).also { it[2] = (it[2].toInt() or 0x80).toByte() }
        assertTrue(validDnsResponse(query, response))
        assertFalse(validDnsResponse(query, response.copyOf().also { it[0] = 0 }))
        assertFalse(validDnsResponse(query, query))
    }
}
