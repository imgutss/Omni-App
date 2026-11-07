package com.v2ray.ang.omni

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OmniDnsLabLogicTest {
    @Test
    fun dnsNameValidationIsDeterministic() {
        assertEquals("example.com", normalizeOmniDnsName(" Example.COM. "))
        assertEquals("under_score.example", normalizeOmniDnsName("under_score.example"))
        assertNull(normalizeOmniDnsName("bad name.example"))
        assertNull(normalizeOmniDnsName("bad..example"))
        assertNull(normalizeOmniDnsName("-bad.example"))
    }

    @Test
    fun dohParserPreservesStatusAnswersAndTtl() {
        val parsed = parseOmniDnsJson("""{"Status":0,"Answer":[{"name":"example.com.","type":1,"TTL":60,"data":"192.0.2.1"}]}""")
        assertEquals(0, parsed.responseStatus)
        assertEquals(1, parsed.answers.size)
        assertEquals("192.0.2.1", parsed.answers.single().data)
        assertEquals(60L, parsed.answers.single().ttl)
        val nxdomain = parseOmniDnsJson("""{"Status":3}""")
        assertEquals(3, nxdomain.responseStatus)
        assertEquals(0, nxdomain.answers.size)
    }
}
