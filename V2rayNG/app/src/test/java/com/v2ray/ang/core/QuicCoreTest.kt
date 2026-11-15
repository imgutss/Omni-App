package com.v2ray.ang.core

import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuicCoreTest {

    private fun tuic() = ProfileItem.create(EConfigType.TUIC).apply {
        server = "example.com"
        serverPort = "443"
        username = "8f7a1c2e-0b3d-4e5f-9a6b-1c2d3e4f5a6b"
        password = "secret"
        sni = "example.com"
        alpn = "h3"
    }

    private fun shadowquic() = ProfileItem.create(EConfigType.SHADOWQUIC).apply {
        server = "example.com"
        serverPort = "443"
        username = "user"
        password = "pass"
        sni = "example.com"
    }

    @Test
    fun shadowquicSupportsAbstractProtectAddress() {
        assertTrue(QuicCore.of(shadowquic()).configText("@omni-test")
            .contains("protect-path: \"@omni-test\""))
    }

    @Test
    fun engineMapping() {
        assertEquals(QuicCore.Engine.SING_BOX, QuicCore.engineOf(EConfigType.TUIC))
        assertEquals(QuicCore.Engine.NAIVE, QuicCore.engineOf(EConfigType.NAIVE))
        assertEquals(QuicCore.Engine.SHADOW_QUIC, QuicCore.engineOf(EConfigType.SHADOWQUIC))
        assertNull(QuicCore.engineOf(EConfigType.VLESS))
        assertTrue(QuicCore.isQuicType(EConfigType.TUIC))
        assertFalse(QuicCore.isQuicType(EConfigType.WIREGUARD))
    }

    @Test
    fun normalizeRejectsMissingFields() {
        assertEquals(QuicCore.Problem.SERVER_MISSING, QuicCore.normalize(tuic().apply { server = "" }))
        assertEquals(QuicCore.Problem.PORT_INVALID, QuicCore.normalize(tuic().apply { serverPort = "abc" }))
        assertEquals(QuicCore.Problem.CREDENTIALS_MISSING, QuicCore.normalize(tuic().apply { password = "" }))
        assertEquals(QuicCore.Problem.PARAMS_INVALID, QuicCore.normalize(tuic().apply { tuicParameters = "not json" }))
        assertNull(QuicCore.normalize(tuic()))
        assertNull(QuicCore.normalize(shadowquic()))
    }

    @Test
    fun tuicConfigIsValidSingBoxJson() {
        val core = QuicCore.of(tuic())
        val root = JsonParser.parseString(core.configText("/data/protect.sock")).asJsonObject
        val outbound = root.getAsJsonArray("outbounds")[0].asJsonObject
        assertEquals("tuic", outbound.get("type").asString)
        assertEquals("example.com", outbound.get("server").asString)
        assertEquals(443, outbound.get("server_port").asInt)
        assertEquals("8f7a1c2e-0b3d-4e5f-9a6b-1c2d3e4f5a6b", outbound.get("uuid").asString)
        assertEquals("bbr", outbound.get("congestion_control").asString)
        assertEquals("/data/protect.sock", outbound.get("protect_path").asString)
        assertEquals("quic-out", root.getAsJsonObject("route").get("final").asString)
        val inbound = root.getAsJsonArray("inbounds")[0].asJsonObject
        assertEquals(QuicCore.DEFAULT_SOCKS_PORT, inbound.get("listen_port").asInt)
    }

    @Test
    fun shadowquicConfigIsGamingYaml() {
        val core = QuicCore.of(shadowquic())
        val yaml = core.configText("/data/protect.sock")
        assertTrue(yaml.contains("type: shadowquic"))
        assertTrue(yaml.contains("type: socks"))
        assertTrue(yaml.contains("over-stream: false"))
        assertTrue(yaml.contains("congestion-control: bbr"))
        assertTrue(yaml.contains("keep-alive-interval: 10000"))
        assertTrue(yaml.contains("protect-path: \"/data/protect.sock\""))
        assertTrue(yaml.contains("default-outbound: quic-out"))
    }

    @Test
    fun redactHidesSecrets() {
        val core = QuicCore.of(tuic())
        val redacted = core.redact("connecting with secret and 8f7a1c2e-0b3d-4e5f-9a6b-1c2d3e4f5a6b")
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("8f7a1c2e"))
    }

    @Test
    fun addressesGetPrefixes() {
        assertEquals(listOf("10.0.0.2/32", "fd00::2/128"), QuicCore.addressesOf("10.0.0.2, fd00::2"))
        assertEquals(listOf("10.0.0.2/24"), QuicCore.addressesOf("10.0.0.2/24"))
    }
}
