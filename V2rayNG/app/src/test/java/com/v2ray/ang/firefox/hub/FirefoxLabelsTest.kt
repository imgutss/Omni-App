package com.v2ray.ang.firefox.hub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Hub must never show a raw engine code where a person needs a sentence. */
class FirefoxLabelsTest {

    @Test
    fun `every doctor step has a Persian title`() {
        listOf(
            "engine", "account", "entitlement", "quota", "pass", "serverlist",
            "dns", "tunnel", "exit", "country", "transport",
        ).forEach { assertNotEquals(it, FirefoxLabels.doctorStepTitle(it)) }
    }

    @Test
    fun `failure codes are worded, never raw`() {
        assertEquals("سهمیهٔ ماهانه تمام شده.", FirefoxLabels.failure("quota"))
        assertTrue(FirefoxLabels.failure("region").isNotBlank())
        assertEquals("", FirefoxLabels.failure(""))
        // An unknown code is still explained, and keeps the code so it can be reported.
        assertTrue(FirefoxLabels.failure("brand_new_code").contains("brand_new_code"))
    }

    @Test
    fun `account health reports a spent allowance first`() {
        val health = FirefoxLabels.accountHealth(
            status = "ready", reasonCode = "", active = true,
            unlimited = false, limitBytes = 100, remainingBytes = 0,
        )
        assertEquals(FirefoxLabels.HealthTone.ERROR, health.tone)
    }

    @Test
    fun `account health flags reauth and quota threshold`() {
        assertEquals(
            FirefoxLabels.HealthTone.ERROR,
            FirefoxLabels.accountHealth("reauth_required", "", false, false, 100, 50).tone,
        )
        assertEquals(
            FirefoxLabels.HealthTone.WARN,
            FirefoxLabels.accountHealth("reserved", "quota_threshold", false, false, 100, 10).tone,
        )
        assertEquals(
            FirefoxLabels.HealthTone.OK,
            FirefoxLabels.accountHealth("ready", "", true, false, 100, 90).tone,
        )
    }

    @Test
    fun `network dna verdicts are worded`() {
        assertTrue(FirefoxLabels.networkDna("dns_filtered").isNotBlank())
        assertTrue(FirefoxLabels.networkDna("bootstrap_blocked_use_relay").isNotBlank())
        assertEquals("", FirefoxLabels.networkDna("unknown_code"))
    }

    @Test
    fun `selection names the reserved choices`() {
        assertEquals("پیشنهادی (نزدیک‌ترین)", FirefoxLabels.selectionName("", ""))
        assertEquals("سریع‌ترین (انتخاب خودکار)", FirefoxLabels.selectionName("fastest", ""))
        assertEquals("آلمان", FirefoxLabels.selectionName("DE", "آلمان"))
    }
}
