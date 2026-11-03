package com.v2ray.ang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun anUnusableAnswerIsEmptyRatherThanAGuess() {
        listOf("", "gutsclient: no Google edge address carried a fronted call", "[]", "{}").forEach { answer ->
            assertTrue("edges for ${answer.take(20)}", GutsEdgeScanner.parseEdges(answer).isEmpty())
            assertTrue("names for ${answer.take(20)}", GutsEdgeScanner.parseNames(answer).isEmpty())
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
