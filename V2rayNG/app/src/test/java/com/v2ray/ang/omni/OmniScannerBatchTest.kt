package com.v2ray.ang.omni

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.omni.lab.OmniBuiltConfiguration
import com.v2ray.ang.omni.lab.OmniProbeMode
import com.v2ray.ang.omni.lab.OmniScanTarget
import com.v2ray.ang.omni.lab.OmniTargetKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OmniScannerBatchTest {
    @Test
    fun ranksOnlyPositiveCoreEvidenceAndKeepsUntestedVisible() {
        val first = built("192.0.2.1", "a")
        val second = built("192.0.2.2", "b")
        val third = built("192.0.2.3", "c")
        val ranked = rankOmniBuiltConfigurations(
            listOf(first, second, third),
            mapOf(
                first.fingerprint to OmniBuiltPing(240L, 3, 2, 20L),
                second.fingerprint to OmniBuiltPing(80L, 3, 3, 8L),
                third.fingerprint to OmniBuiltPing(null, 3, 0, null),
            ),
        )
        assertEquals(listOf(second, first, third), ranked)
    }

    @Test
    fun zeroLatencyIsNotRankedAsSuccessfulCoreEvidence() {
        val zero = built("192.0.2.4", "zero")
        val positive = built("192.0.2.5", "positive")
        val ranked = rankOmniBuiltConfigurations(
            listOf(zero, positive),
            mapOf(
                zero.fingerprint to OmniBuiltPing(0L, 1, 1, 0L),
                positive.fingerprint to OmniBuiltPing(900L, 1, 1, 0L),
            ),
        )
        assertEquals(listOf(positive, zero), ranked)
    }

    @Test
    fun templateTransportDrivesProbeInsteadOfUnrelatedPortDefaults() {
        val tlsWs = omniScannerProbeDefaults(ProfileItem(
            configType = EConfigType.VLESS,
            network = "ws",
            security = "tls",
            sni = "edge.example",
            host = "origin.example",
            path = "/ray",
        ))
        assertEquals(OmniProbeMode.WEBSOCKET, tlsWs.mode)
        assertEquals(true, tlsWs.useTls)
        assertEquals("edge.example", tlsWs.sni)
        assertEquals("origin.example", tlsWs.hostHeader)
        assertEquals("/ray", tlsWs.path)

        val plainWs = omniScannerProbeDefaults(ProfileItem(configType = EConfigType.TROJAN, network = "ws", security = "none"))
        assertEquals(OmniProbeMode.WEBSOCKET, plainWs.mode)
        assertEquals(false, plainWs.useTls)

        val plain = omniScannerProbeDefaults(ProfileItem(configType = EConfigType.TROJAN, network = "ws"))
        assertEquals(OmniProbeMode.WEBSOCKET, plain.mode)
        assertEquals(false, plain.useTls)
    }

    private fun built(host: String, fingerprint: String) = OmniBuiltConfiguration(
        target = OmniScanTarget(host, 443, OmniTargetKind.IPV4),
        profile = ProfileItem(configType = EConfigType.VLESS, server = host, serverPort = "443"),
        fingerprint = fingerprint,
    )
}
