package com.v2ray.ang.fmt

import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class MasterDnsFmtTest {
    private val json = """
        {
          "DOMAINS": ["V.Example.com.", "", "v.example.com", "second.example"],
          "DATA_ENCRYPTION_METHOD": 1,
          "ENCRYPTION_KEY": "s3cr3t#key",
          "SOCKS5_PASS": "p#ss"
        }
    """.trimIndent()

    @Test
    fun officialJsonImportsAndNormalizesDomainsWithoutChangingTheDocument() {
        val profile = MasterDnsFmt.parseAny(json)
        assertNotNull(profile)
        assertEquals(EConfigType.MASTERDNS, profile?.configType)
        assertEquals("second.example", profile?.server)
        assertEquals(json.trim(), profile?.masterDnsConfig)
        assertEquals("8.8.8.8\n", profile?.masterDnsResolvers)
        assertNull(MasterDnsFmt.normalize(profile!!))
    }

    @Test
    fun standardAndUrlBase64JsonAreAccepted() {
        val standard = Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        val url = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        assertEquals("second.example", MasterDnsFmt.parseAny(standard)?.server)
        assertEquals("second.example", MasterDnsFmt.parseAny(url)?.server)
    }

    @Test
    fun uriRoundTripCarriesTheOfficialDocumentResolversAndName() {
        val original = MasterDnsFmt.parseAny(json)!!.apply {
            remarks = "My DNS / node"
            masterDnsResolvers = "1.1.1.1:5353\n[2001:db8::1]:53"
        }
        val uri = EConfigType.MASTERDNS.protocolScheme + MasterDnsFmt.toUri(original)
        val restored = MasterDnsFmt.parse(uri)
        assertNotNull(restored)
        assertEquals(original.masterDnsConfig, restored?.masterDnsConfig)
        assertEquals("1.1.1.1:5353\n[2001:db8::1]:53\n", restored?.masterDnsResolvers)
        assertEquals("My DNS / node", restored?.remarks)
        assertFalse(uri.contains("s3cr3t"))
    }

    @Test
    fun tomlCommentsAndQuotedHashesAreNotParsedAsExtraValues() {
        val toml = """
            DOMAINS = [
              "V.Example.com.", # "comment.example"
              'second.example',
            ]
            DATA_ENCRYPTION_METHOD = 1
            ENCRYPTION_KEY = "key#with#hash" # a comment with "fake"
            SOCKS5_PASS = 'pass#word'
        """.trimIndent()
        val profile = MasterDnsFmt.parseAny(toml)
        assertNotNull(profile)
        assertEquals("second.example", profile?.server)
        val document = MasterDnsFmt.document(toml)
        assertEquals(listOf("second.example", "v.example.com"), document?.domains)
        assertEquals("key#with#hash", document?.encryptionKey)
        assertEquals("pass#word", document?.socksPass)
        assertNull(MasterDnsFmt.normalize(profile!!))
    }

    @Test
    fun encryptionZeroDoesNotRequireAKeyButOtherMethodsDo() {
        val withoutKey = """{"DOMAINS":["v.example.com"],"DATA_ENCRYPTION_METHOD":0}"""
        assertNotNull(MasterDnsFmt.parseAny(withoutKey))
        assertNotNull(MasterDnsFmt.parseAny("DOMAINS = [\"v.example.com\"]\nDATA_ENCRYPTION_METHOD = 0"))

        val missing = MasterDnsFmt.parseAny(json)!!.apply { masterDnsConfig = """{"DOMAINS":["v.example.com"],"DATA_ENCRYPTION_METHOD":2}""" }
        assertEquals(MasterDnsFmt.Problem.KEY_MISSING, MasterDnsFmt.normalize(missing))
        val invalidMethod = MasterDnsFmt.parseAny(json)!!.apply { masterDnsConfig = """{"DOMAINS":["v.example.com"],"DATA_ENCRYPTION_METHOD":6,"ENCRYPTION_KEY":"x"}""" }
        assertEquals(MasterDnsFmt.Problem.CONFIG_MALFORMED, MasterDnsFmt.normalize(invalidMethod))
    }

    @Test
    fun explicitEnginesRejectAnIncompatibleDocumentFormat() {
        val jsonProfile = MasterDnsFmt.parseAny(json)!!.apply { masterDnsEngine = MasterDnsFmt.ENGINE_COTTENDNS }
        assertEquals(MasterDnsFmt.Problem.CONFIG_MALFORMED, MasterDnsFmt.normalize(jsonProfile))
        val tomlProfile = MasterDnsFmt.parseAny("DOMAINS = [\"v.example.com\"]\nENCRYPTION_KEY = \"secret\"")!!.apply {
            masterDnsEngine = MasterDnsFmt.ENGINE_MASTERDNS
        }
        assertEquals(MasterDnsFmt.Problem.CONFIG_MALFORMED, MasterDnsFmt.normalize(tomlProfile))
    }

    @Test
    fun malformedOrUnrelatedDocumentsAreNotClaimedAsMasterDns() {
        assertNull(MasterDnsFmt.parseAny("""{"DOMAINS":["v.example.com"]}"""))
        assertNull(MasterDnsFmt.parseAny("""{"DOMAINS":"v.example.com","ENCRYPTION_KEY":"x"}"""))
        assertNull(MasterDnsFmt.parseAny("""{"DOMAINS":["v.example.com"],"DATA_ENCRYPTION_METHOD":1.5,"ENCRYPTION_KEY":"x"}"""))
        assertNull(MasterDnsFmt.parseAny("DOMAINS = [\"v.example.com\"]\nDATA_ENCRYPTION_METHOD = 1.5\nENCRYPTION_KEY = \"x\""))
        assertNull(MasterDnsFmt.parseAny("DOMAINS = [\"v.example.com\"] trailing"))
        assertNull(MasterDnsFmt.parseAny("not a masterdns document"))
    }

    @Test
    fun resolverValidationMatchesTheUpstreamAddressAndPrefixBoundary() {
        val valid = listOf(
            "8.8.8.8", "1.1.1.1:5353", "192.0.2.0/24", "192.0.2.0/31",
            "2001:db8::1", "[2001:db8::1]:53", "2001:db8::/112", "::ffff:192.0.2.1",
        )
        val invalid = listOf(
            "dns.example", "8.8.8.8:0", "8.8.8.8:65536", "8.8.8.8/", "0.0.0.0/0", "192.0.2.0/15",
            "2001:db8::/64", "1::2::3", "1:2:3:4:5:6:7:8::", "2001:db8::1:53:xyz", "[2001:db8::1]",
        )
        valid.forEach { assertTrue(MasterDnsFmt.validResolver(it), it) }
        invalid.forEach { assertFalse(MasterDnsFmt.validResolver(it), it) }
        assertTrue(MasterDnsFmt.validResolvers("# only a comment\n"))
        assertEquals("# only a comment\n8.8.8.8\n", MasterDnsFmt.normalizeResolvers("# only a comment\n"))
    }

    @Test
    fun secretsAreRedactedFromCoreDiagnostics() {
        val line = "failed key=s3cr3t#key pass=p#ss"
        val redacted = MasterDnsFmt.redact(line, json)
        assertEquals("failed key=*** pass=***", redacted)
        assertFalse(redacted.contains("s3cr3t"))
        assertFalse(redacted.contains("p#ss"))
    }
}
