package com.v2ray.ang.omni.lab

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OmniTargetParserTest {
    @Test
    fun parsesLocalizedPortsAndCount() {
        assertEquals(listOf(80, 443, 8443), OmniTargetParser.parsePorts("۸۰، 443 8443".replace('،', ',')))
        assertEquals(1234L, OmniTargetParser.parseCount("۱۲۳۴"))
    }

    @Test
    fun expandsIpv4RangeAndCidrLazily() {
        val plan = OmniTargetParser.parse("192.0.2.1-192.0.2.3\n198.51.100.0/30")
        assertEquals(7L, plan.targetCount)
        assertEquals(listOf("192.0.2.1", "192.0.2.2", "192.0.2.3", "198.51.100.0"), plan.targets.take(4).map { it.host }.toList())
        assertEquals(7L, plan.plannedProbeCount(listOf(443)))
        assertEquals(14L, plan.plannedProbeCount(listOf(80, 443)))
    }

    @Test
    fun hugeCidrDoesNotRequireHugeAllocation() {
        val plan = OmniTargetParser.parse("0.0.0.0/0")
        assertEquals(4_294_967_296L, plan.targetCount)
        assertEquals(listOf("0.0.0.0", "0.0.0.1", "0.0.0.2"), plan.targets.take(3).map { it.host }.toList())
        assertEquals(4_294_967_296L, plan.plannedProbeCount(listOf(443)))
    }

    @Test
    fun supportsIpv6AndMultiPortTargets() {
        val plan = OmniTargetParser.parse("[2001:db8::1]:443,8443 example.com:80")
        assertTrue(plan.invalid.isEmpty())
        assertEquals(listOf("[2001:db8::1]:443", "[2001:db8::1]:8443", "example.com:80"), plan.concreteTargets(listOf(443)).map { it.endpointId }.toList())
    }

    @Test
    fun invalidInputIsExplicit() {
        val plan = OmniTargetParser.parse("999.1.1.1 bad_host")
        assertFalse(plan.hasTargets)
        assertEquals(2, plan.invalid.size)
    }
}
