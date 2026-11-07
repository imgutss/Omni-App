package com.v2ray.ang.omni.lab

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OmniEndpointConfigFactoryTest {
    @Test
    fun buildChangesEndpointButKeepsCredentialsAndTransport() {
        val template = ProfileItem(
            configType = EConfigType.VLESS,
            remarks = "Template",
            description = "old.example.*** : 443",
            subscriptionId = "sub-1",
            server = "old.example.com",
            serverPort = "443",
            password = "123e4567-e89b-12d3-a456-426614174000",
            network = "ws",
            host = "cdn.example.com",
            path = "/ray",
            security = "tls",
            sni = "old.example.com",
        )
        val built = OmniEndpointConfigFactory.build(template, OmniScanTarget("192.0.2.10", 8443, OmniTargetKind.IPV4), OmniBuildOptions(hostHeader = "cdn.example.com"))
        assertEquals("192.0.2.10", built.server)
        assertEquals("8443", built.serverPort)
        assertEquals("Template · 192.0.2.10:8443", built.remarks)
        assertNull(built.description, "the server list must regenerate the old endpoint description")
        assertEquals("sub-1", built.subscriptionId)
        assertEquals(template.password, built.password)
        assertEquals("ws", built.network)
        assertEquals("/ray", built.path)
        assertEquals("cdn.example.com", built.host)
        assertEquals("old.example.com", built.sni)
    }

    @Test
    fun batchDeduplicatesByEndpointAndFingerprintIsStable() {
        val template = ProfileItem(configType = EConfigType.TROJAN, password = "secret", server = "a", serverPort = "443")
        val target = OmniScanTarget("192.0.2.1", 443, OmniTargetKind.IPV4)
        val built = OmniEndpointConfigFactory.buildBatch(template, listOf(target, target))
        assertEquals(1, built.size)
        assertEquals(built.first().fingerprint, OmniEndpointConfigFactory.fingerprint(built.first().profile.copy(remarks = "another", addedTime = 1L)))
        assertNotEquals(built.first().fingerprint, OmniEndpointConfigFactory.fingerprint(built.first().profile.copy(serverPort = "8443")))
    }
}
