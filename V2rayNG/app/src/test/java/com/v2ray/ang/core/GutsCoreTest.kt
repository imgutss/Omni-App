package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class GutsCoreTest {

    private fun profile(
        doors: String? = "https://script.google.com/macros/s/A/exec",
        key: String? = "a-long-enough-shared-secret",
        edge: String? = null,
        sni: String? = null,
    ) = ProfileItem.create(EConfigType.GUTS).apply {
        gutsDoors = doors
        gutsKey = key
        gutsGoogleIp = edge
        gutsSni = sni
    }

    @Test
    fun doorsAreSplitEveryWayAListIsPastedAndKeepTheirOrder() {
        val core = GutsCore.of(
            profile(doors = "https://one/exec, https://two/exec\nhttps://three/exec;https://one/exec\t https://four/exec")
        )

        assertEquals(
            listOf("https://one/exec", "https://two/exec", "https://three/exec", "https://four/exec"),
            core.doors,
        )
    }

    @Test
    fun aProfileWithoutADoorOrKeyReportsTheFirstProblemInTheEditorsOrder() {
        assertEquals(GutsCore.Problem.DOOR_MISSING, GutsCore.normalize(profile(doors = "   ")))
        assertEquals(GutsCore.Problem.DOOR_INVALID, GutsCore.normalize(profile(doors = "ftp://example.com/x")))
        assertEquals(GutsCore.Problem.DOOR_INVALID, GutsCore.normalize(profile(doors = "not a url")))
        assertEquals(GutsCore.Problem.KEY_MISSING, GutsCore.normalize(profile(key = " ")))
        assertEquals(GutsCore.Problem.KEY_INVALID, GutsCore.normalize(profile(key = "two words")))
        assertEquals(GutsCore.Problem.EDGE_INVALID, GutsCore.normalize(profile(edge = "1.2.3.4/24")))
        assertEquals(GutsCore.Problem.SNI_INVALID, GutsCore.normalize(profile(sni = "www.google.com/x")))
        assertNull(GutsCore.normalize(profile()))
    }

    @Test
    fun defaultsPinNoEdgeButAGoogleSniAndTheGutsPort() {
        val core = GutsCore.of(profile())

        assertEquals(GutsCore.AUTO_EDGE, core.googleIp)
        assertEquals("www.google.com", core.sni)
        assertEquals(18002, core.port)
        assertEquals(listOf(18002), core.sessionPorts)
    }

    @Test
    fun anEdgeIsEitherAutoOrAPinnedAddress() {
        assertTrue(GutsCore.validEdge("auto"))
        assertTrue(GutsCore.validEdge("216.239.38.120"))
        assertTrue(GutsCore.validEdge("172.217.16.142"))
        assertTrue(GutsCore.validEdge("[2001:4860:4860::8888]"))
        assertTrue(GutsCore.validEdge("www.google.com"))
        assertFalse(GutsCore.validEdge(""))
        assertFalse(GutsCore.validEdge("216.239.38.120:443/x"))
    }

    @Test
    fun identityTellsSessionsApartWithoutShowingTheKey() {
        val first = GutsCore.of(profile())
        val same = GutsCore.of(profile())
        val otherKey = GutsCore.of(profile(key = "another-long-enough-secret"))
        val otherEdge = GutsCore.of(profile(edge = "216.239.38.120"))

        assertEquals(first.identity, same.identity)
        assertNotEquals(first.identity, otherKey.identity)
        assertNotEquals(first.identity, otherEdge.identity)
        assertFalse(first.identity.contains(first.key))
    }

    @Test
    fun aLogLineNeverCarriesTheKey() {
        val core = GutsCore.of(profile(key = "s3cret-value"))

        assertEquals("[guts] door ok", core.redact("[guts] door ok"))
        assertEquals("[guts] key *** refused", core.redact("[guts] key s3cret-value refused"))
    }

    @Test
    fun theCommandLineCarriesEveryDoorButNeverTheKey() {
        val core = GutsCore.of(
            profile(
                doors = "https://one/exec,https://two/exec",
                key = "a-long-enough-shared-secret",
                edge = "216.239.38.120",
            )
        )

        val arguments = GutsCoreManager.processArguments(
            binary = File("/data/app/lib/libguts.so"),
            core = core,
            protectAddress = "@omni-guts-1",
        )

        assertEquals("/data/app/lib/libguts.so", arguments.first())
        assertTrue("-listen=127.0.0.1:18002" in arguments)
        assertEquals(2, arguments.count { it.startsWith("-door=") })
        assertTrue("-door=https://one/exec" in arguments)
        assertTrue("-door=https://two/exec" in arguments)
        assertTrue("-google-ip=216.239.38.120" in arguments)
        assertTrue("-sni=www.google.com" in arguments)
        assertTrue("-fd-control-unix-socket=@omni-guts-1" in arguments)
        // The key reaches the client through GUTS_KEY in its environment instead.
        assertTrue(arguments.none { it.contains(core.key) || it.startsWith("-key") })
    }

    @Test
    fun theCommandLineCarriesThisLinesOwnEdgeCandidates() {
        val arguments = GutsCoreManager.processArguments(
            binary = File("/data/app/lib/libguts.so"),
            core = GutsCore.of(profile(edge = "auto")),
            protectAddress = null,
            edgeCandidates = listOf("108.177.14.120", "142.250.72.14"),
        )

        assertTrue("-edge-candidates=108.177.14.120,142.250.72.14" in arguments)

        val without = GutsCoreManager.processArguments(
            binary = File("/data/app/lib/libguts.so"),
            core = GutsCore.of(profile(edge = "auto")),
            protectAddress = null,
        )
        assertTrue(without.none { it.startsWith("-edge-candidates") })
    }

    @Test
    fun withoutVpnProtectionTheSocketFlagStaysEmptyRatherThanDisappearing() {
        val arguments = GutsCoreManager.processArguments(
            binary = File("/data/app/lib/libguts.so"),
            core = GutsCore.of(profile()),
            protectAddress = null,
        )

        assertTrue("-fd-control-unix-socket=" in arguments)
    }

    @Test
    fun theOutboundIsTheLoopbackSocksListenerOfTheClient() {
        val outbound = CoreOutboundBuilder.toOutboundGuts(profile())

        assertEquals("socks", outbound?.protocol)
        assertEquals("127.0.0.1", outbound?.settings?.address)
        assertEquals(18002, outbound?.settings?.port)
    }

    @Test
    fun anInvalidProfileCannotBeBuiltIntoACore() {
        assertThrows(IllegalArgumentException::class.java) { GutsCore.of(profile(key = "")) }
    }
}
