package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConnectionAddressScannerTest {
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
            assertEquals("192.0.2.10", CoreOutboundBuilder.getServerAddress(candidate))
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
