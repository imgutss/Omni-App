package com.v2ray.ang.fmt

import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StructuredImportTest {

    private val clashYaml = """
        mixed-port: 7890
        proxies:
          - name: tuic-node
            type: tuic
            server: example.com
            port: 443
            uuid: 8f7a1c2e-0b3d-4e5f-9a6b-1c2d3e4f5a6b
            password: secret
            sni: example.com
            alpn: [h3]
            congestion-controller: bbr
          - name: awg-node
            type: wireguard
            server: example.com
            port: 51820
            private-key: AAA=
            public-key: BBB=
            ip: 10.0.0.2
            amnezia-wg-option:
              jc: 4
              jmin: 40
              h1: 1
          - name: sq-node
            type: shadowquic
            server: example.com
            port: 443
            username: user
            password: pass
            server-name: example.com
            udp-over-stream: false
          - name: vless-node
            type: vless
            server: example.com
            port: 443
            uuid: aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
            tls: true
            network: ws
            ws-opts:
              path: /path
              headers:
                Host: example.com
            reality-opts:
              public-key: pbk
              short-id: sid
          - name: unsupported
            type: snell
            server: example.com
            port: 443
        proxy-groups:
          - name: auto
            type: select
            proxies: [tuic-node]
    """.trimIndent()

    @Test
    fun clashYamlImportsTheQuicFamilyAndSkipsUnsupported() {
        assertTrue(ClashFmt.looksLikeClash(clashYaml))
        val profiles = ClashFmt.parse(clashYaml)
        assertEquals(4, profiles.size)
        val tuic = profiles.first { it.remarks == "tuic-node" }
        assertEquals(EConfigType.TUIC, tuic.configType)
        assertEquals("8f7a1c2e-0b3d-4e5f-9a6b-1c2d3e4f5a6b", tuic.username)
        assertEquals("bbr", tuic.tuicParameters?.let { com.google.gson.JsonParser.parseString(it).asJsonObject.get("congestion_control").asString })
        val awg = profiles.first { it.remarks == "awg-node" }
        assertEquals(EConfigType.AMNEZIAWG, awg.configType)
        assertTrue(awg.awgParameters!!.contains("\"jc\":4"))
        val sq = profiles.first { it.remarks == "sq-node" }
        assertEquals(EConfigType.SHADOWQUIC, sq.configType)
        assertEquals("example.com", sq.sni)
        val vless = profiles.first { it.remarks == "vless-node" }
        assertEquals(EConfigType.VLESS, vless.configType)
        assertEquals("reality", vless.security)
        assertEquals("pbk", vless.publicKey)
        assertEquals("/path", vless.path)
    }

    @Test
    fun clashDetectionRejectsShareLinksAndJson() {
        assertFalse(ClashFmt.looksLikeClash("vless://x@y:443"))
        assertFalse(ClashFmt.looksLikeClash("{\"outbounds\":[]}"))
    }

    private val singBoxJson = """
        {
          "outbounds": [
            {"type":"tuic","tag":"t","server":"example.com","server_port":443,"uuid":"u","password":"p","congestion_control":"bbr","udp_relay_mode":"native","tls":{"enabled":true,"server_name":"example.com","alpn":["h3"]}},
            {"type":"vless","tag":"v","server":"example.com","server_port":443,"uuid":"u","flow":"xtls-rprx-vision","tls":{"enabled":true,"server_name":"example.com","reality":{"enabled":true,"public_key":"pbk","short_id":"sid"},"utls":{"fingerprint":"chrome"}}},
            {"type":"selector","tag":"sel","outbounds":["t"]}
          ],
          "endpoints": [
            {"type":"wireguard","tag":"w","private_key":"AAA=","address":["10.0.0.2/32"],"mtu":1420,"peers":[{"public_key":"BBB=","address":"example.com","port":51820,"persistent_keepalive_interval":25}],"jc":4,"jmin":40,"h1":1}
          ]
        }
    """.trimIndent()

    @Test
    fun singBoxJsonImportsOutboundsAndEndpoints() {
        assertTrue(SingBoxFmt.looksLikeSingBox(singBoxJson))
        val profiles = SingBoxFmt.parse(singBoxJson)
        assertEquals(3, profiles.size)
        val tuic = profiles.first { it.remarks == "t" }
        assertEquals(EConfigType.TUIC, tuic.configType)
        assertEquals("u", tuic.username)
        assertEquals("example.com", tuic.sni)
        val vless = profiles.first { it.remarks == "v" }
        assertEquals(EConfigType.VLESS, vless.configType)
        assertEquals("reality", vless.security)
        assertEquals("xtls-rprx-vision", vless.flow)
        assertEquals("chrome", vless.fingerPrint)
        val awg = profiles.first { it.remarks == "w" }
        assertEquals(EConfigType.AMNEZIAWG, awg.configType)
        assertEquals("example.com", awg.server)
        assertEquals("51820", awg.serverPort)
        assertTrue(awg.awgParameters!!.contains("\"jc\":4"))
    }

    @Test
    fun singBoxDetectionRejectsXrayCustomConfig() {
        val xray = """{"inbounds":[],"outbounds":[{"protocol":"vless","settings":{}}],"routing":{}}"""
        assertFalse(SingBoxFmt.looksLikeSingBox(xray))
    }
}
