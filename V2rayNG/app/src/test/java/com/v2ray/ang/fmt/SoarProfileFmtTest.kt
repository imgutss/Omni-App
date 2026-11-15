package com.v2ray.ang.fmt

import com.v2ray.ang.core.DnsttCore
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SoarProfileFmtTest {
    @Test
    fun legacySettingsDefaultToIranAndExplicitGlobalRemainsEmpty() {
        val legacy = SoarProfileFmt.normalizeSettings("{\"discovery\":false}") ?: error("legacy settings")
        assertTrue(legacy.contains("\"country\":\"IR\""))
        val global = SoarProfileFmt.normalizeSettings("{\"country\":\"\"}") ?: error("global settings")
        assertTrue(global.contains("\"country\":\"\""))
        val canonical = SoarProfileFmt.normalizeSettings("{\"country\":\" ir \"}") ?: error("canonical settings")
        assertTrue(canonical.contains("\"country\":\"IR\""))
        assertNull(SoarProfileFmt.normalizeSettings("{\"country\":\"US\"}"))
    }

    @Test
    fun coreConversionAlsoCanonicalizesOlderProfiles() {
        val profile = ProfileItem(
            configType = EConfigType.DNSTT,
            remarks = "synthetic",
            dnsttEngine = DnsttFmt.ENGINE_SOAR,
            dnsttTransport = "udp",
            dnsttResolver = "192.0.2.1:53",
            dnsttDomain = "t.example.com",
            dnsttPublicKey = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE",
            soarSettings = "{\"discovery\":false,\"transport\":\"off\"}",
        )
        val core = DnsttCore.of(profile)
        assertTrue(core.soarSettings.contains("\"country\":\"IR\""))
        assertEquals(profile.soarSettings?.contains("country"), false)
        val global = DnsttCore.of(profile.copy(soarSettings = SoarProfileFmt.normalizeSettings("{\"country\":\"\"}")))
        assertTrue(global.soarSettings.contains("\"country\":\"\""))
    }
}
