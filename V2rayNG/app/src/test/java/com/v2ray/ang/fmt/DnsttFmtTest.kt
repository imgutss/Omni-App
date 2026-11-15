package com.v2ray.ang.fmt

import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DnsttFmtTest {
    private val key = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

    private fun profile(transport: String = "doh", resolver: String = "https://cloudflare-dns.com/dns-query") =
        DnsttFmt.fromValues("DNS node", transport, resolver, "T.Example.com.", key)!!

    @Test
    fun uriRoundTripCarriesTransportResolverDomainKeyAndName() {
        val original = profile().apply { remarks = "My DNSTT / node" }
        val uri = EConfigType.DNSTT.protocolScheme + DnsttFmt.toUri(original)
        val restored = DnsttFmt.parse(uri)

        assertNotNull(restored)
        assertEquals(EConfigType.DNSTT, restored?.configType)
        assertEquals("doh", restored?.dnsttTransport)
        assertEquals(original.dnsttResolver, restored?.dnsttResolver)
        assertEquals("t.example.com", restored?.dnsttDomain)
        assertEquals(key, restored?.dnsttPublicKey)
        assertEquals("My DNSTT / node", restored?.remarks)
        assertTrue(uri.startsWith("dnstt://?"))
        assertFalse(uri.contains("T.Example.com."))
    }

    @Test
    fun officialTransportAliasesAndDefaultResolverPortsAreAccepted() {
        val udp = DnsttFmt.parse("dnstt://?udp=1.1.1.1&domain=t.example.com&pubkey=$key")
        val dot = DnsttFmt.parse("dnstt://?transport=dot&resolver=[2001:db8::1]&domain=t.example.com&pubkey=$key")
        assertEquals("1.1.1.1:53", udp?.dnsttResolver)
        assertEquals("[2001:db8::1]:853", dot?.dnsttResolver)
        assertEquals("udp", udp?.dnsttTransport)
        assertEquals("dot", dot?.dnsttTransport)
    }

    @Test
    fun malformedProfilesAreRejectedInsteadOfBecomingDirectOutbounds() {
        assertNull(DnsttFmt.parseAny("not a dnstt profile"))
        assertNull(DnsttFmt.parse("dnstt://?transport=doh&resolver=https://dns.example/dns-query&domain=t.example.com"))
        assertNull(DnsttFmt.fromValues("bad", "tcp", "1.1.1.1", "t.example.com", key))
        assertNull(DnsttFmt.fromValues("bad", "udp", "1.1.1.1:0", "t.example.com", key))
        assertNull(DnsttFmt.fromValues("bad", "doh", "https://user:pass@dns.example/dns-query", "t.example.com", key))
        assertNull(DnsttFmt.fromValues("bad", "doh", "https://dns.example/dns-query", "bad_domain", key))
        assertNull(DnsttFmt.fromValues("bad", "doh", "https://dns.example/dns-query", "t.example.com", "abc"))
        assertNull(DnsttFmt.fromValues("bad", "doh", "https://dns.example/dns-query", "t.example.com", key, "bad\nvalue"))
    }

    @Test
    fun normalizationRejectsNonAsciiDnsLabelsAndNormalizesKeyCase() {
        val unicode = profile().apply { dnsttDomain = "t.مثال.com" }
        assertEquals(DnsttFmt.Problem.DOMAIN_INVALID, DnsttFmt.normalize(unicode))
        val upper = profile().apply { dnsttPublicKey = key.uppercase() }
        assertNull(DnsttFmt.normalize(upper))
        assertEquals(key, upper.dnsttPublicKey)
        assertEquals("t.example.com", upper.server)
        assertNull(upper.serverPort)
    }

    @Test
    fun unsupportedOrContradictoryNoizControlsAreRejectedNotSilentlyDropped() {
        val stealthWithoutNoiz = profile("udp", "1.1.1.1:53").apply {
            dnsttEngine = DnsttFmt.ENGINE_NOIZDNS
            dnsttStealth = true
        }
        assertEquals(DnsttFmt.Problem.TRANSPORT_INVALID, DnsttFmt.normalize(stealthWithoutNoiz))

        val badPayload = profile("udp", "1.1.1.1:53").apply {
            dnsttEngine = DnsttFmt.ENGINE_NOIZDNS
            dnsttMaxPayload = -1
        }
        assertEquals(DnsttFmt.Problem.TRANSPORT_INVALID, DnsttFmt.normalize(badPayload))

        val officialWithNoizTuning = profile("udp", "1.1.1.1:53").apply {
            dnsttResolverMode = "roundrobin"
        }
        assertEquals(DnsttFmt.Problem.TRANSPORT_INVALID, DnsttFmt.normalize(officialWithNoizTuning))

        val officialWithSsh = profile("udp", "1.1.1.1:53").apply {
            dnsttSshEnabled = true
            dnsttSshUsername = "ssh-user"
        }
        assertEquals(DnsttFmt.Problem.TRANSPORT_INVALID, DnsttFmt.normalize(officialWithSsh))

        assertNull(DnsttFmt.parse("dnstt://?transport=doh&resolver=http://dns.example/dns-query&domain=t.example.com&pubkey=$key"))
    }

    @Test
    fun noizResolverAuthorityMarkersAreValidatedAndSeparatedFromEngineEndpoints() {
        val mixed = profile().apply {
            dnsttTransport = "udp"
            dnsttResolver = "1.1.1.1:53:0,[2001:db8::1]:53:1"
            dnsttEngine = DnsttFmt.ENGINE_NOIZDNS
            dnsttNoiz = true
        }
        assertNull(DnsttFmt.normalize(mixed))
        assertEquals("1.1.1.1:53,[2001:db8::1]:53", mixed.dnsttResolver)
        assertEquals("0,1", mixed.dnsttResolverAuthorities)
        assertEquals(listOf(false, true), DnsttFmt.resolverAuthorities(mixed))
        assertEquals("1.1.1.1:53,[2001:db8::1]:53", DnsttFmt.engineResolver(mixed.dnsttResolver!!))

        val partiallyMarked = profile().apply {
            dnsttTransport = "udp"
            dnsttResolver = "1.1.1.1:53:0,9.9.9.9:53"
            dnsttEngine = DnsttFmt.ENGINE_NOIZDNS
            dnsttNoiz = true
        }
        assertEquals(DnsttFmt.Problem.RESOLVER_INVALID, DnsttFmt.normalize(partiallyMarked))
    }

    @Test
    fun coreIdentityIncludesEveryEngineInputButNotTheProfileName() {
        val first = com.v2ray.ang.core.DnsttCore.of(profile())
        val same = com.v2ray.ang.core.DnsttCore.of(profile().apply { remarks = "another name" })
        val different = com.v2ray.ang.core.DnsttCore.of(profile().apply { dnsttResolver = "https://dns.google/dns-query" })
        assertEquals(first.key, same.key)
        assertFalse(first.key == different.key)
        assertEquals(18001, first.port)
    }
}
