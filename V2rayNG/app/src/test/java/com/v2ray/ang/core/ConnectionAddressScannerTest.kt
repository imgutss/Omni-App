package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConnectionAddressScannerTest {
    @Test
    fun redirectIsPrivateIpv6SafeCollisionFreeAndIdempotent() {
        val p = ProfileItem.create(EConfigType.VLESS).apply {
            server = "origin.example.com"; serverPort = "8443"; network = "ws"
            dialAddress = "2001:db8::1"
        }
        val outbound = OutboundBean(protocol = "vless",
            settings = OutboundBean.OutSettingsBean(address = p.server, port = 8443),
            streamSettings = OutboundBean.StreamSettingsBean(network = "ws"))
        DialAddressOverride.attach(outbound, p)
        assertFalse(com.google.gson.Gson().toJson(outbound).contains("connectionIpRedirect"))
        val outbounds = arrayListOf(outbound, OutboundBean(tag = "proxy-connection-ip", protocol = "blackhole"))
        DialAddressOverride.materialize(outbounds)
        assertEquals("[2001:db8::1]:8443", outbounds.last().settings?.redirect)
        assertEquals("proxy-connection-ip-ip", outbounds.last().tag)
        assertEquals("origin.example.com", outbound.settings?.address)
        DialAddressOverride.materialize(outbounds)
        assertEquals(3, outbounds.size)
    }

    @Test
    fun onlyDialIpChangesForVlessAndTrojan() {
        for (type in listOf(EConfigType.VLESS, EConfigType.TROJAN)) {
            val original = ProfileItem.create(type).apply {
                server = "origin.example.com"; serverPort = "8443"; password = "secret"
                security = "tls"; sni = "tls.example.com"; host = "http.example.com"
                network = "ws"; path = "/path"; alpn = "h2"; fingerPrint = "chrome"
            }
            val candidate = ConnectionAddressScanner.candidate(original, "192.0.2.10")
            assertEquals(original, candidate.copy(dialAddress = null))
            assertEquals("8443", candidate.serverPort)
            val outbound = OutboundBean(protocol = type.name.lowercase(),
                settings = OutboundBean.OutSettingsBean(address = original.server, port = 8443),
                streamSettings = OutboundBean.StreamSettingsBean(network = "ws", sockopt =
                    OutboundBean.StreamSettingsBean.SockoptBean(dialerProxy = "existing-hop")))
            DialAddressOverride.attach(outbound, candidate)
            val outbounds = arrayListOf(outbound)
            DialAddressOverride.materialize(outbounds)
            assertEquals(original.server, outbound.settings?.address)
            assertEquals(8443, outbound.settings?.port)
            assertEquals("192.0.2.10:8443", outbounds.last().settings?.redirect)
            assertEquals("existing-hop", outbounds.last().streamSettings?.sockopt?.dialerProxy)
            assertEquals(outbounds.last().tag, outbound.streamSettings?.sockopt?.dialerProxy)
            assertNull(outbound.connectionIpRedirect)
            val before = OutboundBean.StreamSettingsBean()
            val after = OutboundBean.StreamSettingsBean()
            CoreOutboundBuilder.populateTransportSettings(before, original)
            CoreOutboundBuilder.populateTransportSettings(after, candidate)
            CoreOutboundBuilder.populateTlsSettings(before, original, original.host)
            CoreOutboundBuilder.populateTlsSettings(after, candidate, candidate.host)
            assertEquals(before, after)
        }
    }
    @Test
    fun rejectsHostPortAndInvalidCandidate() {
        val p = ProfileItem.create(EConfigType.VLESS)
        assertThrows(IllegalArgumentException::class.java) { ConnectionAddressScanner.candidate(p, "192.0.2.1:443") }
        assertThrows(IllegalArgumentException::class.java) { ConnectionAddressScanner.candidate(p, "not-an-ip") }
    }
}
