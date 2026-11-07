package com.v2ray.ang.fmt

import com.google.gson.JsonObject
import com.v2ray.ang.core.DnsttCore
import com.v2ray.ang.core.DnsttCoreManager
import com.v2ray.ang.core.MasterDnsCore
import com.v2ray.ang.core.MasterDnsCoreManager
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Base64

class WhiteDnsProfileFmtTest {
    private val syntheticKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })

    private fun envelope(engine: String, name: String, server: JsonObject, resolvers: List<String>? = listOf("192.0.2.1", "198.51.100.2:5353")): String {
        val profile = JsonObject().apply {
            addProperty("id", "synthetic-$engine")
            addProperty("name", name)
            addProperty("engine", engine)
            add("server", server)
            resolvers?.let { add("resolvers", WhiteDnsProfileFmt.strings(it)) }
        }
        return WhiteDnsProfileFmt.toPayload(profile)
    }

    private fun encryptedServer(domain: String): JsonObject = JsonObject().apply {
        addProperty("domain", domain)
        addProperty("encryption_key", "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
        addProperty("encryption_method", 5)
    }

    @Test
    fun masterDnsOriginalLinkImportsDirectlyIntoRealOfficialCore() {
        val link = "masterdns://" + envelope("masterdns", "Synthetic Master", encryptedServer("mdns-1.tunnel.example"))
        val profile = requireNotNull(MasterDnsFmt.parse(link))
        assertNotNull(profile)
        assertEquals(EConfigType.MASTERDNS, profile?.configType)
        assertEquals(MasterDnsFmt.ENGINE_MASTERDNS, profile?.masterDnsEngine)
        assertEquals("mdns-1.tunnel.example", profile?.server)
        assertEquals("192.0.2.1\n198.51.100.2:5353\n", profile?.masterDnsResolvers)
        val core = MasterDnsCore.of(profile!!)
        assertEquals(MasterDnsFmt.ENGINE_MASTERDNS, core.engine)
        assertTrue(core.config.contains("\"DATA_ENCRYPTION_METHOD\":5"))
        val restored = MasterDnsFmt.parse("masterdns://" + MasterDnsFmt.toUri(profile))
        assertEquals(profile.masterDnsConfig, restored?.masterDnsConfig)
        assertEquals(profile.masterDnsResolvers, restored?.masterDnsResolvers)
    }

    @Test
    fun masterDnsPanelLinkWithoutResolverFieldNeedsNoManualConversion() {
        val link = "masterdns://" + envelope(
            "masterdns", "Synthetic panel Master", encryptedServer("mdns-1.panel.example"), resolvers = null,
        )
        val profile = requireNotNull(MasterDnsFmt.parse(link))
        assertEquals(MasterDnsFmt.ENGINE_MASTERDNS, profile.masterDnsEngine)
        assertEquals("mdns-1.panel.example", profile.server)
        assertEquals("8.8.8.8\n", profile.masterDnsResolvers)
        assertEquals(MasterDnsFmt.ENGINE_MASTERDNS, MasterDnsCore.of(profile).engine)
    }

    @Test
    fun cottenDnsOriginalLinkUsesCottenBinaryAndTomlWithoutConversionByUser() {
        val link = "cottendns://" + envelope("cottendns", "Synthetic Cotten", encryptedServer("cot.tunnel.example"))
        val profile = requireNotNull(MasterDnsFmt.parse(link))
        assertNotNull(profile)
        assertEquals(MasterDnsFmt.ENGINE_COTTENDNS, profile?.masterDnsEngine)
        assertEquals("cot.tunnel.example", profile?.server)
        assertEquals(MasterDnsFmt.Format.TOML, MasterDnsFmt.document(profile!!.masterDnsConfig!!)?.format)
        val core = MasterDnsCore.of(profile)
        val args = MasterDnsCoreManager.processArguments(
            File("/data/app/lib/libcottendns.so"), File("/private/client_config.toml"),
            File("/private/client_resolvers.txt"), core, "@omni-test",
        )
        assertTrue("-startup-mode=resolvers" in args)
        assertTrue("-terminal-ui=plain" in args)
        assertTrue("-log-to-file=false" in args)
        assertTrue("-fd-control-unix-socket=@omni-test" in args)
        assertFalse(args.any { it.startsWith("-save-mtu-servers-to-file") })
        assertFalse(args.joinToString(" ").contains("001122334455"))
        val restored = MasterDnsFmt.parse("cottendns://" + MasterDnsFmt.toUri(profile))
        assertEquals(MasterDnsFmt.ENGINE_COTTENDNS, restored?.masterDnsEngine)
        assertEquals(profile.masterDnsConfig, restored?.masterDnsConfig)
        assertEquals(profile.masterDnsResolvers, restored?.masterDnsResolvers)
    }

    @Test
    fun soarOriginalLinkPreservesTuningAndSupportsDiscoveryWithoutResolverFile() {
        val profileJson = JsonObject().apply {
            addProperty("id", "synthetic-soar")
            addProperty("name", "Synthetic Soar")
            add("server", JsonObject().apply {
                addProperty("domain", "soar.tunnel.example")
                addProperty("public_key", syntheticKey)
            })
            add("soarSettings", JsonObject().apply {
                addProperty("transport", "only")
                addProperty("discovery", true)
                addProperty("discoveryWant", 7)
                addProperty("discoveryConcurrency", 4)
                addProperty("discoveryPaceMs", 250)
                addProperty("safeQName", 111)
                addProperty("maxQName", 240)
                addProperty("maxAnswer", 1200)
                addProperty("holdMs", 600)
                addProperty("maxInflight", 99)
                addProperty("maxPathWindow", 22)
                addProperty("maxStreams", 77)
                addProperty("streamWindowKiB", 512)
                addProperty("sendBufferKiB", 128)
                addProperty("sessionIdleSeconds", 80)
                addProperty("dialTimeoutSeconds", 45)
            })
        }
        val link = "soar://" + WhiteDnsProfileFmt.toPayload(profileJson)
        val profile = requireNotNull(DnsttFmt.parse(link))
        assertNotNull(profile)
        assertEquals(DnsttFmt.ENGINE_SOAR, profile?.dnsttEngine)
        assertEquals("tcp", profile?.dnsttTransport)
        assertEquals("", profile?.dnsttResolver)
        assertEquals(syntheticKey, profile?.dnsttPublicKey)
        assertTrue(profile?.soarSettings!!.contains("\"discoveryWant\":7"))
        assertTrue(profile.soarSettings!!.contains("\"dialTimeoutSeconds\":45"))
        val core = DnsttCore.of(profile)
        assertEquals(core.soarSettings, profile.soarSettings)
        val args = DnsttCoreManager.processArguments(File("/data/app/lib/libsoar.so"), core, "@omni-test")
        assertTrue("-domain=soar.tunnel.example" in args)
        assertTrue("-public-key=$syntheticKey" in args)
        assertTrue("-resolvers=soar_resolvers.txt" in args)
        assertTrue("-settings=soar_settings.json" in args)
        assertTrue("-listen=127.0.0.1:18001" in args)
        assertTrue("-fd-control-unix-socket=@omni-test" in args)
        val restored = DnsttFmt.parse("soar://" + DnsttFmt.toUri(profile))
        assertEquals(profile.soarSettings, restored?.soarSettings)
        assertEquals(profile.dnsttPublicKey, restored?.dnsttPublicKey)
    }

    @Test
    fun standardBase64EnvelopeWithPlusSurvivesDirectOpaqueImport() {
        var encoded = ""
        for (index in 0..9) {
            val payload = envelope("masterdns", "Synthetic ${"x".repeat(index)}😀", encryptedServer("standard.example"))
            val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
            encoded = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
            if ('+' in encoded) break
        }
        assertTrue('+' in encoded)
        val profile = MasterDnsFmt.parse("masterdns://$encoded")
        assertNotNull(profile)
        assertEquals("standard.example", profile?.server)
        assertEquals(MasterDnsFmt.ENGINE_MASTERDNS, profile?.masterDnsEngine)
    }

    @Test
    fun malformedEnvelopesAndContradictorySettingsAreRejected() {
        assertNull(MasterDnsFmt.parse("masterdns://" + envelope("cottendns", "wrong", encryptedServer("x.example"))))
        assertNull(DnsttFmt.parse("soar://not-base64"))
        val badKey = JsonObject().apply {
            addProperty("name", "bad")
            add("server", JsonObject().apply {
                addProperty("domain", "soar.example")
                addProperty("public_key", "c2hvcnQ=")
            })
            add("soarSettings", JsonObject().apply { addProperty("discovery", false) })
        }
        assertNull(DnsttFmt.parse("soar://" + WhiteDnsProfileFmt.toPayload(badKey)))
        val noBootstrap = JsonObject().apply {
            addProperty("name", "invalid discovery-only")
            add("server", JsonObject().apply {
                addProperty("domain", "soar.example")
                addProperty("public_key", syntheticKey)
            })
            add("soarSettings", JsonObject().apply { addProperty("discovery", false) })
        }
        assertNull(DnsttFmt.parse("soar://" + WhiteDnsProfileFmt.toPayload(noBootstrap)))
        val invalidSettings = SoarProfileFmt.normalizeSettings("{\"maxInflight\":7}")
        assertNull(invalidSettings)
    }
}
