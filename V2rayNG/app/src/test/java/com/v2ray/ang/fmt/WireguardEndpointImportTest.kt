package com.v2ray.ang.fmt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WireguardEndpointImportTest {
    private fun conf(endpoint: String) = """
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address = 172.16.0.2/32
        [Peer]
        PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
        Endpoint = $endpoint
    """.trimIndent()

    @Test
    fun ipv6EndpointKeepsWholeHostAndPort() {
        val profile = WireguardFmt.parseWireguardConfFile(conf("[2606:4700:d0::a29f:c001]:2408"))
        assertEquals("2606:4700:d0::a29f:c001", profile.server)
        assertEquals("2408", profile.serverPort)
    }

    @Test
    fun ipv4EndpointStillWorks() {
        val profile = WireguardFmt.parseWireguardConfFile(conf("188.114.96.128:878"))
        assertEquals("188.114.96.128", profile.server)
        assertEquals("878", profile.serverPort)
    }
}
