package com.v2ray.ang.fmt

import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuicFmtTest {

    @Test
    fun tuicRoundTrip() {
        val uri = "tuic://8f7a1c2e-0b3d-4e5f-9a6b-1c2d3e4f5a6b:secret@example.com:443?sni=example.com&alpn=h3&congestion_control=bbr&udp_relay_mode=native#node"
        val config = TuicFmt.parse(uri)
        assertNotNull(config)
        config!!
        assertEquals(EConfigType.TUIC, config.configType)
        assertEquals("example.com", config.server)
        assertEquals("443", config.serverPort)
        assertEquals("8f7a1c2e-0b3d-4e5f-9a6b-1c2d3e4f5a6b", config.username)
        assertEquals("secret", config.password)
        assertEquals("example.com", config.sni)
        assertEquals("node", config.remarks)
        assertTrue(config.tuicParameters!!.contains("bbr"))

        val reparsed = TuicFmt.parse(TuicFmt.toUri(config))
        assertNotNull(reparsed)
        assertEquals(config.username, reparsed!!.username)
        assertEquals(config.password, reparsed.password)
        assertEquals(config.server, reparsed.server)
    }

    @Test
    fun shadowquicRoundTrip() {
        val uri = "shadowquic://user:pass@example.com:443?sni=example.com&over_stream=0&congestion_control=bbr#game"
        val config = ShadowQuicFmt.parse(uri)
        assertNotNull(config)
        config!!
        assertEquals(EConfigType.SHADOWQUIC, config.configType)
        assertEquals("user", config.username)
        assertEquals("pass", config.password)
        assertEquals("example.com", config.sni)
        assertTrue(config.shadowquicParameters!!.contains("\"over_stream\":false"))

        val reparsed = ShadowQuicFmt.parse(ShadowQuicFmt.toUri(config))
        assertEquals(config.username, reparsed!!.username)
        assertEquals(config.password, reparsed.password)
    }

    @Test
    fun retiredAwgIsNotImportedAsPlainWireguard() {
        assertNull(WireguardFmt.parse("wireguard://key@example.com:51820?publickey=key&jc=4"))
        assertTrue(RetiredProtocol.isAwgConf("[Interface]\nJc = 4"))
        assertTrue(RetiredProtocol.isRetiredProfile("{\"configType\":\"AMNEZIAWG\"}"))
    }

    @Test
    fun nonMatchingSchemesReturnNull() {
        assertNull(TuicFmt.parse("vless://x@y:443"))
        assertNull(ShadowQuicFmt.parse("tuic://x:y@z:443"))
        assertNull(NaiveFmt.parse("trojan://x@y:443"))
    }
}
