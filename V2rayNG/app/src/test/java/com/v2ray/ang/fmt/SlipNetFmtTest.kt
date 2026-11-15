package com.v2ray.ang.fmt

import com.v2ray.ang.core.AetherCore
import com.v2ray.ang.core.AetherCoreManager
import com.v2ray.ang.core.DnsttCore
import com.v2ray.ang.enums.AetherTor
import com.v2ray.ang.enums.AetherTorBridges
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class SlipNetFmtTest {
    private val key = "f49e4f032b927429e1e0cf825b02cddadef90882e01ac99bb6940f39a2629a3a"

    private fun fixture(mode: String = "dnstt"): MutableList<String> = MutableList(82) { "" }.also {
        it[0] = "29"
        it[1] = mode
        it[2] = "SlipNet test"
        it[3] = "ns2.motoclub.site"
        it[4] = "192.168.88.118:53:1"
        it[5] = "0"
        it[6] = "5000"
        it[7] = "bbr"
        it[8] = "1080"
        it[9] = "127.0.0.1"
        it[10] = "0"
        it[11] = key
        it[14] = "0"
        it[22] = "udp"
        it[27] = "1"
        it[38] = "0"
        it[39] = "0"
        it[60] = "roundrobin"
        it[61] = "3"
    }

    private fun uri(fields: List<String>): String =
        "slipnet://" + Base64.getEncoder().encodeToString(fields.joinToString("|").toByteArray(StandardCharsets.UTF_8))

    @Test
    fun originalSlipNetDnsttLinkImportsWithoutManualConversion() {
        val profile = SlipNetFmt.parse(uri(fixture()))
        assertNotNull(profile)
        assertEquals(EConfigType.DNSTT, profile?.configType)
        assertEquals("ns2.motoclub.site", profile?.dnsttDomain)
        assertEquals("192.168.88.118:53", profile?.dnsttResolver)
        assertEquals("1", profile?.dnsttResolverAuthorities)
        assertEquals(key, profile?.dnsttPublicKey)
        assertEquals(DnsttFmt.ENGINE_DNSTT, profile?.dnsttEngine)
        assertTrue(profile?.dnsttAuthoritative == true)
        assertFalse(profile?.dnsttNoiz == true)
        assertEquals("roundrobin", profile?.dnsttResolverMode)
        assertEquals(3, profile?.dnsttRrSpreadCount)
    }

    @Test
    fun noizAndMultiResolverFieldsSurviveImport() {
        val fields = fixture("sayedns").also {
            it[4] = "1.1.1.1:53:0,8.8.8.8:53:0"
            it[38] = "1"
            it[39] = "80"
            it[60] = "fanout"
        }
        val profile = SlipNetFmt.parse(uri(fields))
        assertNotNull(profile)
        assertEquals("1.1.1.1:53,8.8.8.8:53", profile?.dnsttResolver)
        assertEquals("0,0", profile?.dnsttResolverAuthorities)
        assertEquals(DnsttFmt.ENGINE_NOIZDNS, profile?.dnsttEngine)
        assertTrue(profile?.dnsttNoiz == true)
        assertTrue(profile?.dnsttStealth == true)
        assertEquals(80, profile?.dnsttMaxPayload)
    }

    @Test
    fun uniformResolverAuthorityReachesTheCoreWithoutCollapsingMixedLists() {
        val uniform = fixture("sayedns").also {
            it[4] = "1.1.1.1:53:1,9.9.9.9:53:1"
            it[27] = "0"
        }
        val authoritative = SlipNetFmt.parse(uri(uniform)) ?: error("Uniform profile should import")
        assertTrue(authoritative.dnsttAuthoritative == true)
        assertTrue(DnsttCore.of(authoritative).authoritative)

        val mixedFields = fixture("sayedns").also {
            it[4] = "1.1.1.1:53:0,9.9.9.9:53:1"
            it[27] = "0"
        }
        val mixed = SlipNetFmt.parse(uri(mixedFields)) ?: error("Mixed profile should import")
        assertEquals("1.1.1.1:53,9.9.9.9:53", mixed.dnsttResolver)
        assertEquals("0,1", mixed.dnsttResolverAuthorities)
        assertEquals(listOf(false, true), DnsttCore.of(mixed).resolverAuthorities)
        assertFalse(mixed.dnsttAuthoritative == true)
        assertFalse(DnsttCore.of(mixed).authoritative)
    }

    @Test
    fun mixedResolverAuthoritySurvivesOmniUriRoundTrip() {
        val fields = fixture("sayedns").also {
            it[4] = "1.1.1.1:53:0,9.9.9.9:53:1"
            it[27] = ""
        }
        val imported = SlipNetFmt.parse(uri(fields)) ?: error("Profile should import")
        val omniUri = EConfigType.DNSTT.protocolScheme + DnsttFmt.toUri(imported)
        val restored = DnsttFmt.parse(omniUri) ?: error("Omni URI should parse")
        assertFalse(restored.dnsttAuthoritative == true)
        assertFalse(DnsttCore.of(restored).authoritative)
        assertEquals(imported.dnsttResolver, restored.dnsttResolver)
        assertEquals("1.1.1.1:53,9.9.9.9:53", restored.dnsttResolver)
        assertEquals("0,1", restored.dnsttResolverAuthorities)
        assertFalse(omniUri.contains("authoritative=1"))
    }

    @Test
    fun hiddenResolverAuthoritativeFlagIsAlsoPreserved() {
        val hidden = fixture("sayedns").also {
            it[4] = ""
            it[27] = ""
            it[36] = "1"
            it[37] = "[2001:db8::1]:53:1,9.9.9.9:53:1"
        }
        val profile = SlipNetFmt.parse(uri(hidden)) ?: error("Hidden profile should import")
        assertEquals("[2001:db8::1]:53,9.9.9.9:53", profile.dnsttResolver)
        assertEquals("1,1", profile.dnsttResolverAuthorities)
        assertTrue(profile.dnsttAuthoritative == true)
        assertTrue(DnsttCore.of(profile).authoritative)
    }

    @Test
    fun hiddenResolversAndDohAreReadFromTheirOriginalFields() {
        val hidden = fixture().also {
            it[4] = ""
            it[36] = "1"
            it[37] = "9.9.9.9:53:0"
        }
        assertEquals("9.9.9.9:53", SlipNetFmt.parse(uri(hidden))?.dnsttResolver)

        val doh = fixture().also {
            it[4] = ""
            it[21] = "https://dns.google/dns-query"
            it[22] = "doh"
        }
        assertEquals("https://dns.google/dns-query", SlipNetFmt.parse(uri(doh))?.dnsttResolver)
        assertEquals("doh", SlipNetFmt.parse(uri(doh))?.dnsttTransport)

        val dot = fixture().also {
            it[4] = "1.1.1.1:53:0"
            it[22] = "dot"
        }
        assertEquals("1.1.1.1:853", SlipNetFmt.parse(uri(dot))?.dnsttResolver)
    }

    @Test
    fun olderKnownVersionsAndTcpUseTheSameDirectPath() {
        val old = fixture().take(28).toMutableList().also { it[0] = "13" }
        assertNotNull(SlipNetFmt.parse(uri(old)))
        val tcp = fixture().also { it[22] = "tcp" }
        assertEquals("tcp", SlipNetFmt.parse(uri(tcp))?.dnsttTransport)
    }

    @Test
    fun originalDnsttSshLinkImportsWithTheRealChainSettings() {
        val fields = fixture("dnstt_ssh").also {
            it[0] = "28"
            it[14] = "1"
            it[15] = "test-user"
            it[16] = "test-password"
            it[17] = "22"
            it[18] = "1"
            it[19] = "127.0.0.1"
            it[23] = "password"
        }
        val profile = SlipNetFmt.parse(uri(fields))
        assertNotNull(profile)
        assertTrue(profile?.dnsttSshEnabled == true)
        assertEquals("test-user", profile?.dnsttSshUsername)
        assertEquals("test-password", profile?.dnsttSshPassword)
        assertTrue(profile?.dnsttSshForwardDns == true)
        assertEquals("127.0.0.1", profile?.dnsttSshHost)
        assertEquals(22, profile?.dnsttSshPort)
        val core = DnsttCore.of(profile!!)
        assertEquals(18_001, core.port)
        assertEquals(18_002, core.tunnelPort)
        assertEquals("test-user", core.ssh?.username)
        assertTrue(core.ssh?.forwardDns == true)
        val omniUri = EConfigType.DNSTT.protocolScheme + DnsttFmt.toUri(profile)
        val restored = DnsttFmt.parse(omniUri)
        assertNotNull(restored)
        assertEquals(core.ssh, DnsttCore.of(restored!!).ssh)
    }

    @Test
    fun sshKeyFieldsAreDecodedFromTheOriginalSlipNetRecord() {
        val privateKey = "test-private-key"
        val passphrase = "test-passphrase"
        val fields = fixture("dnstt_ssh").also {
            it[14] = "1"
            it[15] = "key-user"
            it[17] = "2222"
            it[23] = "key"
            it[24] = Base64.getEncoder().encodeToString(privateKey.toByteArray(StandardCharsets.UTF_8))
            it[25] = Base64.getEncoder().encodeToString(passphrase.toByteArray(StandardCharsets.UTF_8))
        }
        val profile = SlipNetFmt.parse(uri(fields))
        assertNotNull(profile)
        assertEquals(privateKey, profile?.dnsttSshPrivateKey)
        assertEquals(passphrase, profile?.dnsttSshKeyPassphrase)
        assertEquals("key", profile?.dnsttSshAuthType)
        assertEquals(2222, profile?.dnsttSshPort)
    }

    @Test
    fun originalVaydnsRecordImportsItsOwnEngineAndAdvancedWireSettings() {
        val fields = fixture("vaydns").also {
            it[12] = "socks-user"
            it[13] = "socks-pass"
            it[41] = "1"
            it[42] = "cname"
            it[43] = "120"
            it[44] = "25.5"
            it[45] = "30"
            it[46] = "3"
            it[47] = "700"
            it[48] = "4"
            it[49] = "4"
        }
        val profile = SlipNetFmt.parse(uri(fields)) ?: error("VayDNS profile should import")
        assertEquals(DnsttFmt.ENGINE_VAYDNS, profile.dnsttEngine)
        assertTrue(profile.vaydnsDnsttCompat == true)
        assertEquals("cname", profile.vaydnsRecordType)
        assertEquals(120, profile.vaydnsMaxQnameLen)
        assertEquals(25.5, profile.vaydnsRps)
        assertEquals(30, profile.vaydnsIdleTimeout)
        assertEquals(3, profile.vaydnsKeepAlive)
        assertEquals(700, profile.vaydnsUdpTimeout)
        assertEquals(4, profile.vaydnsMaxNumLabels)
        assertEquals(4, profile.vaydnsClientIdSize)
        val core = DnsttCore.of(profile)
        assertEquals(18_002, core.enginePort)
        assertEquals(listOf(18_001, 18_002), core.sessionPorts)

        val restored = DnsttFmt.parse(EConfigType.DNSTT.protocolScheme + DnsttFmt.toUri(profile))
        assertNotNull(restored)
        assertEquals(profile.vaydnsRecordType, restored?.vaydnsRecordType)
        assertEquals(profile.vaydnsRps, restored?.vaydnsRps)
        assertEquals(profile.vaydnsClientIdSize, restored?.vaydnsClientIdSize)
    }

    @Test
    fun vaydnsAndNoizSshRecordsKeepTheirDistinctEnginesAndRealSshChain() {
        val vay = SlipNetFmt.parse(uri(fixture("vaydns_ssh").also {
            it[14] = "1"
            it[15] = "vay-user"
            it[16] = "vay-pass"
            it[19] = "127.0.0.1"
        })) ?: error("VayDNS+SSH should import")
        assertEquals(DnsttFmt.ENGINE_VAYDNS, vay.dnsttEngine)
        assertEquals("vay-user", vay.dnsttSshUsername)
        assertEquals(18_002, DnsttCore.of(vay).tunnelPort)

        val noiz = SlipNetFmt.parse(uri(fixture("sayedns_ssh").also {
            it[14] = "1"
            it[15] = "noiz-user"
            it[16] = "noiz-pass"
            it[19] = "127.0.0.1"
        })) ?: error("NoizDNS+SSH should import")
        assertEquals(DnsttFmt.ENGINE_NOIZDNS, noiz.dnsttEngine)
        assertTrue(noiz.dnsttNoiz == true)
        assertEquals("noiz-user", noiz.dnsttSshUsername)
    }

    @Test
    fun originalSlipstreamRecordImportsWithoutPublicKeyOrAuthoritySuffixes() {
        val fields = fixture("ss").also {
            it[4] = "1.1.1.1:53:0,9.9.9.9:53:1"
            it[6] = "4000"
            it[7] = "dcubic"
            it[10] = "1"
            it[11] = ""
            it[12] = "socks-user"
            it[13] = "socks-pass"
        }
        val profile = SlipNetFmt.parse(uri(fields)) ?: error("Slipstream profile should import")
        assertEquals(DnsttFmt.ENGINE_SLIPSTREAM, profile.dnsttEngine)
        assertEquals("", profile.dnsttPublicKey)
        assertEquals("1.1.1.1:53,9.9.9.9:53", profile.dnsttResolver)
        assertEquals("0,1", profile.dnsttResolverAuthorities)
        assertEquals(4_000, profile.slipstreamKeepAlive)
        assertEquals("dcubic", profile.slipstreamCongestionControl)
        assertTrue(profile.slipstreamGsoEnabled == true)
        val core = DnsttCore.of(profile)
        assertEquals(18_002, core.enginePort)
        assertEquals(listOf(18_001, 18_002), core.sessionPorts)

        val restored = DnsttFmt.parse(EConfigType.DNSTT.protocolScheme + DnsttFmt.toUri(profile))
        assertNotNull(restored)
        assertEquals(DnsttFmt.ENGINE_SLIPSTREAM, restored?.dnsttEngine)
        assertEquals(profile.slipstreamCongestionControl, restored?.slipstreamCongestionControl)
        assertEquals(profile.dnsttResolverAuthorities, restored?.dnsttResolverAuthorities)
    }

    @Test
    fun slipstreamSshUsesTheSameRawEnginePortAndRealSshConfiguration() {
        val profile = SlipNetFmt.parse(uri(fixture("slipstream_ssh").also {
            it[11] = ""
            it[14] = "1"
            it[15] = "slip-user"
            it[16] = "slip-pass"
            it[19] = "127.0.0.1"
        })) ?: error("Slipstream+SSH should import")
        assertEquals(DnsttFmt.ENGINE_SLIPSTREAM, profile.dnsttEngine)
        assertEquals("slip-user", profile.dnsttSshUsername)
        val core = DnsttCore.of(profile)
        assertEquals(18_002, core.tunnelPort)
        assertEquals(18_002, core.enginePort)
        assertEquals("slip-user", core.ssh?.username)
    }

    @Test
    fun originalSnowflakeRecordImportsCustomObfs4VerbatimIntoTheRealTorCore() {
        val bridge = "obfs4 192.0.2.55:38114 316E643333645F6D79216558614D3931657A5F5F cert=test-cert iat-mode=0"
        val fields = fixture("snowflake").also {
            it[2] = "Original Snowflake"
            it[3] = ""
            it[4] = ""
            it[11] = ""
            it[26] = Base64.getEncoder().encodeToString(bridge.toByteArray(StandardCharsets.UTF_8))
        }
        val profile = SlipNetFmt.parse(uri(fields)) ?: error("Snowflake profile should import")
        assertEquals(EConfigType.AETHER, profile.configType)
        assertEquals(AetherTor.ONLY.type, profile.aetherTor)
        assertEquals(AetherTorBridges.OWN.type, profile.aetherTorBridges)
        assertEquals(bridge, profile.aetherTorBridgeLines)
        val arguments = AetherCore.of(profile).arguments
        assertTrue("--tor-only" in arguments)
        assertTrue(arguments.windowed(2).any { it[0] == "--tor-bridge" && it[1] == bridge })
        assertFalse("--protocol" in arguments)
        assertTrue(AetherCoreManager.readyNeedsWord(arguments))
    }

    @Test
    fun blankSnowflakeUsesPinnedSnowflakeBridgesAndDirectUsesNoBridges() {
        val blank = fixture("snowflake").also {
            it[3] = ""
            it[4] = ""
            it[11] = ""
            it[26] = ""
        }
        val profile = SlipNetFmt.parse(uri(blank)) ?: error("Blank Snowflake profile should import")
        assertEquals(AetherTorBridges.OWN.type, profile.aetherTorBridges)
        assertEquals(1, AetherFmt.bridgeLines(profile.aetherTorBridgeLines).size)
        assertTrue(AetherFmt.bridgeLines(profile.aetherTorBridgeLines).all { it.startsWith("snowflake ") })
        assertTrue(profile.aetherTorBridgeLines.orEmpty().contains("fronts=www.cdn77.com"))
        assertFalse(profile.aetherTorBridgeLines.orEmpty().contains("ampcache="))

        val direct = SlipNetFmt.parse(uri(blank.also {
            it[26] = Base64.getEncoder().encodeToString("DIRECT".toByteArray(StandardCharsets.UTF_8))
        })) ?: error("Direct Tor profile should import")
        assertEquals(AetherTorBridges.NEVER.type, direct.aetherTorBridges)
        assertNull(direct.aetherTorBridgeLines)
    }

    @Test
    fun ampAndSmartSnowflakeModesKeepTheirRealNativeBehavior() {
        val ampFields = fixture("snowflake").also {
            it[3] = ""
            it[4] = ""
            it[11] = ""
            it[26] = Base64.getEncoder().encodeToString("SNOWFLAKE_AMP".toByteArray(StandardCharsets.UTF_8))
        }
        val amp = SlipNetFmt.parse(uri(ampFields)) ?: error("AMP Snowflake should import")
        assertEquals(AetherTorBridges.OWN.type, amp.aetherTorBridges)
        assertTrue(amp.aetherTorBridgeLines.orEmpty().contains("ampcache=https://cdn.ampproject.org/"))
        assertTrue(amp.aetherTorBridgeLines.orEmpty().contains("url=https://snowflake-broker.torproject.net/"))
        assertTrue(AetherCore.of(amp).arguments.windowed(2).any {
            it[0] == "--tor-bridge" && it[1].contains("ampcache=")
        })

        val smartFields = fixture("snowflake").also {
            it[3] = ""
            it[4] = ""
            it[11] = ""
            it[26] = Base64.getEncoder().encodeToString("SMART".toByteArray(StandardCharsets.UTF_8))
        }
        val smart = SlipNetFmt.parse(uri(smartFields)) ?: error("Smart Snowflake should import")
        assertEquals(AetherTorBridges.SMART.type, smart.aetherTorBridges)
        assertNull(smart.aetherTorBridgeLines)
        assertTrue("--tor-only" in AetherCore.of(smart).arguments)
    }

    @Test
    fun malformedRecordsAreNotFalselyImported() {
        assertNull(SlipNetFmt.parse(uri(fixture().also { it[14] = "1" })))
        assertNull(SlipNetFmt.parse(uri(fixture("dnstt_ssh"))))
        assertNull(SlipNetFmt.parse("slipnet://not-base64"))
        assertNull(SlipNetFmt.parse(uri(fixture().also { it[11] = "short" })))
        assertNull(SlipNetFmt.parse(uri(fixture().also { it[4] = "1.1.1.1:53:2" })))
        assertNull(SlipNetFmt.parse(uri(fixture().also { it[4] = "[2001:db8::1]:53:1:extra" })))
        assertNull(SlipNetFmt.parse("dnstt://?transport=udp&resolver=1.1.1.1&domain=t.example.com&pubkey=$key"))
    }
}
