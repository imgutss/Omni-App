package com.v2ray.ang.omni

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OmniModelsTest {
    @Test
    fun legacyStatusDetailCannotRenderSubscriptionOrBearerMaterial() {
        assertEquals("", omniSafePanelStatusDetail("HTTP failure https://panel.example/s/secret"))
        assertEquals("", omniSafePanelStatusDetail("Bearer secret-token"))
        assertEquals("", omniSafePanelStatusDetail("token=secret"))
        assertEquals("HTTP 200; 42 ms; endpoint=/api/version", omniSafePanelStatusDetail("HTTP 200; 42 ms; endpoint=/api/version"))
    }
}
