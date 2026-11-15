package com.v2ray.ang.omni

import com.v2ray.ang.dto.entities.RulesetItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OmniRoutingSimulatorTest {
    @Test
    fun tracesFirstMatchingDomainPortAndNetworkRule() {
        val rules = listOf(
            RulesetItem(id = "disabled", domain = listOf("full:example.com"), outboundTag = "blocked", enabled = false),
            RulesetItem(id = "other", domain = listOf("domain:other.example"), outboundTag = "direct"),
            RulesetItem(id = "match", domain = listOf("domain:example.com"), port = "443,8443", network = "tcp", outboundTag = "proxy"),
            RulesetItem(id = "later", domain = listOf("full:www.example.com"), outboundTag = "blocked"),
        )
        val result = simulateOmniRouting(rules, OmniRoutingRequest("WWW.EXAMPLE.COM.", 443, "tcp"))
        assertEquals(OmniRoutingSimulationStatus.MATCHED, result.status)
        assertEquals("proxy", result.outboundTag)
        assertEquals(listOf("disabled", "other", "match"), result.traces.map { it.ruleId })
        assertEquals(OmniRouteMatch.MATCH, result.traces.last().match)
    }

    @Test
    fun cidrAndRangeRulesUseLiteralAddressesWithoutDns() {
        val rule = RulesetItem(id = "lan", ip = listOf("192.0.2.0/24"), port = "8000-9000", outboundTag = "direct")
        assertEquals(OmniRoutingSimulationStatus.MATCHED, simulateOmniRouting(listOf(rule), OmniRoutingRequest("192.0.2.44", 8443)).status)
        assertEquals(OmniRoutingSimulationStatus.UNMATCHED, simulateOmniRouting(listOf(rule), OmniRoutingRequest("198.51.100.1", 8443)).status)
        assertEquals(OmniRoutingSimulationStatus.UNMATCHED, simulateOmniRouting(listOf(rule), OmniRoutingRequest("192.0.2.44", 443)).status)
    }

    @Test
    fun unavailableGeoAndRegexDataProducesIndeterminateNotAFalseDefault() {
        val geo = RulesetItem(id = "geo", domain = listOf("geosite:category-ads-all"), outboundTag = "blocked")
        val regex = RulesetItem(id = "regex", domain = listOf("regexp:.*example.*"), outboundTag = "blocked")
        val result = simulateOmniRouting(listOf(geo, regex), OmniRoutingRequest("unknown.example"))
        assertEquals(OmniRoutingSimulationStatus.INDETERMINATE, result.status)
        assertEquals(listOf(OmniRouteMatch.UNSUPPORTED, OmniRouteMatch.UNSUPPORTED), result.traces.map { it.match })
        assertEquals(OmniRoutingSimulationStatus.INVALID, simulateOmniRouting(listOf(geo), OmniRoutingRequest("", 443)).status)
    }

    @Test
    fun missingProtocolContextCannotBeCalledAMatch() {
        val rule = RulesetItem(id = "http", protocol = listOf("http"), outboundTag = "proxy")
        assertEquals(OmniRoutingSimulationStatus.INDETERMINATE, simulateOmniRouting(listOf(rule), OmniRoutingRequest("example.com", protocol = "")).status)
        assertEquals(OmniRoutingSimulationStatus.MATCHED, simulateOmniRouting(listOf(rule), OmniRoutingRequest("example.com", protocol = "HTTP")).status)
    }
}
