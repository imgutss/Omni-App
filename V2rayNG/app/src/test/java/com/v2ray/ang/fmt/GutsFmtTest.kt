package com.v2ray.ang.fmt

import com.v2ray.ang.core.GutsCore
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class GutsFmtTest {

    /**
     * Printed by the client itself:
     * `gutsclient -door … -door … -key s3cret-AuthKey -google-ip 216.239.38.120 -export`.
     * The app must read exactly what the desktop writes, or a QR code from the panel would be
     * useless on the phone.
     */
    private val exportedByTheGoClient =
        "guts://eyJkb29yIjpbImh0dHBzOi8vc2NyaXB0Lmdvb2dsZS5jb20vbWFjcm9zL3MvQUtmeWNieEVYQU1QTEUv" +
            "ZXhlYyIsImh0dHBzOi8vc2NyaXB0Lmdvb2dsZS5jb20vbWFjcm9zL3MvQUtmeWNieFNFQ09ORC9leGVjIl0s" +
            "ImtleSI6InMzY3JldC1BdXRoS2V5IiwiZ29vZ2xlX2lwIjoiMjE2LjIzOS4zOC4xMjAiLCJzbmkiOiJ3d3cu" +
            "Z29vZ2xlLmNvbSIsImxpc3RlbiI6IjEyNy4wLjAuMToxMDgwIn0"

    private fun profile() = ProfileItem.create(EConfigType.GUTS).apply {
        gutsDoors =
            "https://script.google.com/macros/s/AKfycbxEXAMPLE/exec\nhttps://script.google.com/macros/s/AKfycbxSECOND/exec"
        gutsKey = "s3cret-AuthKey"
        gutsGoogleIp = "216.239.38.120"
        gutsSni = "www.google.com"
        remarks = "door + exit"
    }

    private fun requiredBody(profile: ProfileItem): String {
        val body = GutsFmt.toUri(profile)
        assertTrue(body != null, "the profile has a door, so it has a URI")
        return body!!
    }

    private fun requiredUri(profile: ProfileItem): String = GutsFmt.SCHEME + requiredBody(profile)

    private fun requiredParse(uri: String): ProfileItem {
        val parsed = GutsFmt.parse(uri)
        assertTrue(parsed != null, "$uri should read back as a GUTS profile")
        return parsed!!
    }

    @Test
    fun theProfileTheDesktopExportsReadsBackHere() {
        val restored = requiredParse(exportedByTheGoClient)

        assertEquals(EConfigType.GUTS, restored.configType)
        assertEquals(
            listOf(
                "https://script.google.com/macros/s/AKfycbxEXAMPLE/exec",
                "https://script.google.com/macros/s/AKfycbxSECOND/exec",
            ),
            GutsCore.doorsOf(restored),
        )
        assertEquals("s3cret-AuthKey", restored.gutsKey)
        assertEquals("216.239.38.120", restored.gutsGoogleIp)
        assertEquals("www.google.com", restored.gutsSni)
        assertEquals("script.google.com", restored.server)
        assertNull(GutsCore.normalize(restored))
    }

    @Test
    fun aProfileWrittenHereReadsBackIntoTheSameProfile() {
        val original = profile()

        val restored = requiredParse(requiredUri(original))

        assertEquals(GutsCore.doorsOf(original), GutsCore.doorsOf(restored))
        assertEquals(original.gutsKey, restored.gutsKey)
        assertEquals(original.gutsGoogleIp, restored.gutsGoogleIp)
        assertEquals(original.gutsSni, restored.gutsSni)
        assertEquals(original.remarks, restored.remarks)
        assertEquals(GutsCore.of(original).identity, GutsCore.of(restored).identity)
    }

    @Test
    fun theBodyIsTheSameJsonTheClientWrites() {
        val json = String(Base64.getUrlDecoder().decode(requiredBody(profile())), StandardCharsets.UTF_8)

        // The same key names as guts/config, and the default SNI left out, as the client does.
        assertTrue(json.contains("\"door\":[\"https://script.google.com/macros/s/AKfycbxEXAMPLE/exec\","))
        assertTrue(json.contains("\"key\":\"s3cret-AuthKey\""))
        assertTrue(json.contains("\"google_ip\":\"216.239.38.120\""))
        assertTrue(json.contains("\"note\":\"door + exit\""))
        assertFalse(json.contains("www.google.com"), "the default SNI is left out of the URI")
    }

    @Test
    fun anSniOfItsOwnIsWrittenAndADefaultOneComesBack() {
        val pinned = profile().apply {
            remarks = ""
            gutsSni = "www.google.co.uk"
        }

        val json = String(Base64.getUrlDecoder().decode(requiredBody(pinned)), StandardCharsets.UTF_8)
        assertTrue(json.contains("\"sni\":\"www.google.co.uk\""))
        assertFalse(json.contains("note"))

        val plain = profile().apply { gutsSni = "" }
        assertEquals(GutsCore.DEFAULT_SNI, GutsCore.of(requiredParse(requiredUri(plain))).sni)
    }

    @Test
    fun paddingSomeScannersKeepIsAccepted() {
        val body = requiredBody(profile())
        val padded = body + "=".repeat((4 - body.length % 4) % 4)

        assertEquals("s3cret-AuthKey", requiredParse(GutsFmt.SCHEME + padded).gutsKey)
    }

    @Test
    fun whatCannotConnectIsRefused() {
        // Not a guts URI, empty, not base64, not JSON, no door, no key, and a door that is not a
        // web app URL: the app refuses each here instead of letting the core fail after saving.
        assertNull(GutsFmt.parse("dnstt://abc"))
        assertNull(GutsFmt.parse("guts://"))
        assertNull(GutsFmt.parse("guts://not-base64-!!"))
        assertNull(GutsFmt.parse("guts://" + base64("{\"key\":\"k\"}")))
        assertNull(GutsFmt.parse("guts://" + base64("{\"door\":[\"https://a/exec\"]}")))
        assertNull(GutsFmt.parse("guts://" + base64("{\"door\":[\"ftp://a/x\"],\"key\":\"k\"}")))
        assertFalse(GutsFmt.looksLikeUri("https://example.com"))
        assertTrue(GutsFmt.looksLikeUri(exportedByTheGoClient))
    }

    @Test
    fun aDoorWithoutARemarkIsNamedAfterItsHost() {
        val restored = requiredParse(
            "guts://" + base64("{\"door\":[\"https://script.google.com/macros/s/AKfycbxEXAMPLE/exec\"],\"key\":\"k\"}")
        )

        assertEquals("script.google.com", restored.remarks)
        assertEquals(GutsCore.AUTO_EDGE, GutsCore.of(restored).googleIp)
    }

    private fun base64(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(StandardCharsets.UTF_8))
}
