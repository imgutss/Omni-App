package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The scanner never invents an answer: it reads what the client printed, and a scan that printed
 * nothing usable is an empty result — not a profile rewritten to an address nobody tested.
 */
class GutsEdgeScannerTest {

    @Test
    fun theAddressScanIsReadAsTheClientPrintsIt() {
        val answer = """
            gutsclient: using edge 108.177.14.120
            [
              {
                "ip": "108.177.14.120",
                "rtt_ms": 4,
                "fronted": true,
                "status": 302
              },
              {
                "ip": "142.250.185.78",
                "e": "dial tcp 142.250.185.78:443: connect: connection refused"
              }
            ]
        """.trimIndent()

        val edges = GutsEdgeScanner.parseEdges(answer)
        assertEquals(2, edges.size)
        assertTrue(edges[0].fronted)
        assertEquals("108.177.14.120", edges[0].ip)
        assertEquals(4L, edges[0].rttMs)
        assertNull(edges[0].error)

        assertFalse(edges[1].fronted)
        assertTrue(edges[1].error.orEmpty().contains("connection refused"))
    }

    @Test
    fun theNameScanIsReadAsTheClientPrintsIt() {
        val answer = """
            [
              {"ip": "108.177.14.120", "sni": "www.google.com", "rtt_ms": 4, "fronted": true, "status": 302},
              {"ip": "108.177.14.120", "sni": "meet.google.com", "e": "tls: handshake failure"}
            ]
        """.trimIndent()

        val names = GutsEdgeScanner.parseNames(answer)
        assertEquals(2, names.size)
        assertEquals("www.google.com", names[0].sni)
        assertTrue(names[0].fronted)
        assertFalse(names[1].fronted)
        assertTrue(names[1].error.orEmpty().contains("handshake"))
    }

    @Test
    fun theMatrixScanKeepsAddressAndNameTogetherAsPairs() {
        // The pair is the unit a profile pins: an address that only answers under
        // fonts.gstatic.com must be reported with that name, never as a bare address to be
        // combined again with a name that was filtered.
        val answer = """
            gutsclient: protecting sockets through @omni-guts-1
            [{"ip":"108.177.14.120","sni":"fonts.gstatic.com","rtt_ms":936,"fronted":true,"status":302},
             {"ip":"108.177.14.120","sni":"www.google.com","rtt_ms":3007,"e":"tls: handshake timeout"},
             {"ip":"142.250.185.78","e":"connect: i/o timeout"}]
        """.trimIndent()

        val routes = GutsEdgeScanner.parseRoutes(answer)
        assertEquals(3, routes.size)
        assertEquals("108.177.14.120", routes[0].ip)
        assertEquals("fonts.gstatic.com", routes[0].sni)
        assertTrue(routes[0].fronted)
        assertEquals(936L, routes[0].rttMs)
        assertFalse(routes[1].fronted)
        assertTrue(routes[1].error.orEmpty().contains("handshake"))
        assertEquals("", routes[2].sni)
        assertFalse(routes[2].fronted)
    }

    @Test
    fun anUnusableAnswerIsEmptyRatherThanAGuess() {
        listOf("", "gutsclient: no Google edge address carried a fronted call", "[]", "{}").forEach { answer ->
            assertTrue(GutsEdgeScanner.parseEdges(answer).isEmpty(), "edges for ${answer.take(20)}")
            assertTrue(GutsEdgeScanner.parseNames(answer).isEmpty(), "names for ${answer.take(20)}")
            assertTrue(GutsEdgeScanner.parseRoutes(answer).isEmpty(), "routes for ${answer.take(20)}")
        }
    }

    @Test
    fun anEntryWithoutAnAddressOrANameIsDropped() {
        val edges = GutsEdgeScanner.parseEdges("""[{"rtt_ms": 5, "fronted": true}]""")
        assertTrue(edges.isEmpty())
        val names = GutsEdgeScanner.parseNames("""[{"fronted": true}]""")
        assertTrue(names.isEmpty())
    }

    @Test
    fun aResultIsOnlyAnAnswerWhenSomethingWasFound() {
        assertFalse(GutsEdgeScanner.Result().answered)
        assertTrue(GutsEdgeScanner.Result(bestEdge = "108.177.14.120").answered)
        assertTrue(GutsEdgeScanner.Result(bestSni = "www.google.com").answered)
    }
}
