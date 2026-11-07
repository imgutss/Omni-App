package com.v2ray.ang.fmt

import com.google.gson.JsonParser
import com.v2ray.ang.core.QuicCore
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NaiveFmtTest {
    @Test
    fun encodedAndShortUrlCredentialsAreRedacted() {
        val profile = NaiveFmt.parse("naive+https://u:p%40ss@proxy.example.com")!!
        val core = QuicCore.of(profile)!!
        val line = core.redact("failed https://u:p%40ss@proxy.example.com p%40ss")
        assertFalse(line.contains("u:p"))
        assertFalse(line.contains("p%40ss"))
        assertTrue(line.contains("proxy.example.com"))
    }

    @Test
    fun importExportAndProtectedRelayConfig() {
        val profile = NaiveFmt.parse("naive+https://alice:p%40ss@192.0.2.1:8443?sni=proxy.example.com#node")!!
        assertEquals(EConfigType.NAIVE, profile.configType)
        assertEquals("p@ss", profile.password)
        val roundTrip = NaiveFmt.parse(NaiveFmt.toUri(profile))!!
        assertEquals(profile.server, roundTrip.server)
        assertEquals(profile.password, roundTrip.password)
        assertEquals(profile.sni, roundTrip.sni)
        val config = JsonParser.parseString(QuicCore.of(profile).configText(null, 53000)).asJsonObject
        assertEquals("MAP proxy.example.com 127.0.0.1:53000", config.get("host-resolver-rules").asString)
        assertEquals("https://alice:p%40ss@proxy.example.com:8443", config.get("proxy").asString)
        assertEquals("socks://127.0.0.1:18003", config.get("listen").asString)
    }
    @Test
    fun onlySupportedTlsModeAndCredentialsAreAccepted() {
        assertNull(NaiveFmt.parse("naive+quic://a:b@host:443"))
        assertNull(NaiveFmt.parse("naive+https://a@host:443"))
        assertNull(NaiveFmt.parse("naive+https://a:b@host:443?insecure=1"))
    }
}
