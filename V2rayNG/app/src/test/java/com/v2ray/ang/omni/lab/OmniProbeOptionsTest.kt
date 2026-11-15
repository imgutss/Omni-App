package com.v2ray.ang.omni.lab

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class OmniProbeOptionsTest {
    @Test
    fun preservesExplicitModeAndTransportInputs() {
        val options = OmniProbeOptions(
            mode = OmniProbeMode.WEBSOCKET,
            samples = 4,
            sni = "edge.example",
            hostHeader = "origin.example",
            httpPath = "/ws",
        )
        assertEquals(OmniProbeMode.WEBSOCKET, options.mode)
        assertEquals(4, options.samples)
        assertEquals("/ws", options.httpPath)
    }

    @Test
    fun rejectsRequestHeaderInjection() {
        assertThrows(IllegalArgumentException::class.java) {
            OmniProbeOptions(hostHeader = "example.com\r\nX-Injected: yes")
        }
    }
}
