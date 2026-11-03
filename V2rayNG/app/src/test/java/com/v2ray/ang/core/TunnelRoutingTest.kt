package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TunnelRoutingTest {

    private fun configWith(rules: List<V2rayConfig.RoutingBean.RulesBean>) = V2rayConfig(
        log = V2rayConfig.LogBean(),
        inbounds = arrayListOf(),
        outbounds = arrayListOf(),
        routing = V2rayConfig.RoutingBean(domainStrategy = "AsIs", rules = ArrayList(rules)),
    )

    private fun userRule() = V2rayConfig.RoutingBean.RulesBean(domain = arrayListOf("domain:example.com"), outboundTag = "proxy")

    @Test
    fun quicAndStunAreRefusedAheadOfEveryOtherRule() {
        val config = configWith(listOf(userRule()))

        TunnelRouting.blockUnsupportedTransports(config)

        assertEquals(3, config.routing.rules.size)
        // First, so that a rule of the user cannot send QUIC into a tunnel that cannot carry it.
        val udp = config.routing.rules[0]
        assertEquals("udp", udp.network)
        assertEquals(AppConfig.TAG_BLOCKED, udp.outboundTag)
        assertTrue(udp.port!!.contains("443"))
        assertTrue(udp.port!!.contains("3478"))
        assertTrue(udp.port!!.contains("19302-19309"))
        assertEquals("example.com", (config.routing.rules[2].domain?.single()).toString().substringAfter("domain:"))
        // DNS itself is answered by Xray, so port 53 is never refused.
        assertFalse(udp.port!!.split(',').any { it.trim() == "53" })
    }

    @Test
    fun dohIsRefusedOnItsOwnPort() {
        val config = configWith(emptyList())

        TunnelRouting.blockUnsupportedTransports(config)

        val doh = config.routing.rules[1]
        assertEquals(AppConfig.TAG_BLOCKED, doh.outboundTag)
        assertEquals("443", doh.port)
        assertNull(doh.network)
        assertTrue(doh.domain!!.contains("domain:dns.google"))
        assertTrue(doh.domain!!.contains("domain:cloudflare-dns.com"))
        // A bare domain entry would also match a user's own site of that name; every entry is a domain: one.
        assertTrue(doh.domain!!.all { it.startsWith("domain:") })
    }

    @Test
    fun aConfigurationBuiltTwiceCarriesOneCopyOfEachRule() {
        val config = configWith(emptyList())

        TunnelRouting.blockUnsupportedTransports(config)
        TunnelRouting.blockUnsupportedTransports(config)

        assertEquals(2, config.routing.rules.size)
    }

    @Test
    fun onlyACoreWithoutUdpAsksForTheRules() {
        assertTrue(
            TunnelRouting.carriesTcpOnly(
                GutsCore.of(com.v2ray.ang.dto.entities.ProfileItem.create(com.v2ray.ang.enums.EConfigType.GUTS).apply {
                    gutsDoors = "https://script.google.com/macros/s/A/exec"
                    gutsKey = "key"
                }),
                null,
                null,
            )
        )
        assertFalse(TunnelRouting.carriesTcpOnly(null, null, null))
    }
}
