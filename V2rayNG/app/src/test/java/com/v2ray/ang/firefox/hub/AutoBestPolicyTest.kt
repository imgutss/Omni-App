package com.v2ray.ang.firefox.hub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Auto Best must be conservative: it moves on a real problem, never on a small improvement, and never
 * while the person is using the link.
 */
class AutoBestPolicyTest {

    private fun candidate(code: String, ping: Long, ok: Boolean = true) =
        AutoBestPolicy.Candidate(code, ping, ok)

    private fun situation(
        current: String = "DE",
        currentOk: Boolean = true,
        currentPing: Long = 200,
        busy: Boolean = false,
        sinceSwitch: Long = Long.MAX_VALUE,
        quotaExhausted: Boolean = false,
        candidates: List<AutoBestPolicy.Candidate> = listOf(
            candidate("DE", 200),
            candidate("NL", 90),
            candidate("US", 300),
        ),
    ) = AutoBestPolicy.Situation(
        current = current,
        currentOk = currentOk,
        currentPingMs = currentPing,
        candidates = candidates,
        busy = busy,
        sinceLastSwitchMs = sinceSwitch,
        quotaExhausted = quotaExhausted,
    )

    @Test
    fun `disabled never moves`() {
        assertNull(AutoBestPolicy.decide(AutoBestPolicy.Config(enabled = false), situation(currentOk = false)))
    }

    @Test
    fun `never moves while busy downloading`() {
        val config = AutoBestPolicy.Config(enabled = true)
        assertNull(AutoBestPolicy.decide(config, situation(currentOk = false, busy = true)))
    }

    @Test
    fun `never moves during the cooldown`() {
        val config = AutoBestPolicy.Config(enabled = true, cooldownMs = 300_000)
        assertNull(AutoBestPolicy.decide(config, situation(currentOk = false, sinceSwitch = 1_000)))
    }

    @Test
    fun `quota exhaustion is not a location problem`() {
        val config = AutoBestPolicy.Config(enabled = true)
        assertNull(AutoBestPolicy.decide(config, situation(currentOk = false, quotaExhausted = true)))
    }

    @Test
    fun `a disconnect moves to the best reachable country`() {
        val config = AutoBestPolicy.Config(enabled = true)
        assertEquals("NL", AutoBestPolicy.decide(config, situation(currentOk = false)))
    }

    @Test
    fun `a small improvement does not move`() {
        val config = AutoBestPolicy.Config(enabled = true)
        assertNull(AutoBestPolicy.decide(config, situation(currentPing = 200)))
    }

    @Test
    fun `a severe drop moves to the best country`() {
        val config = AutoBestPolicy.Config(enabled = true)
        assertEquals("NL", AutoBestPolicy.decide(config, situation(currentPing = 800)))
    }

    @Test
    fun `a country lock is never left`() {
        val config = AutoBestPolicy.Config(enabled = true, lockedCountry = "US")
        assertEquals("US", AutoBestPolicy.decide(config, situation(currentOk = false)))
    }

    @Test
    fun `favourites are preferred when reachable`() {
        val config = AutoBestPolicy.Config(enabled = true, favorites = setOf("US"))
        assertEquals("US", AutoBestPolicy.decide(config, situation(currentOk = false)))
    }

    @Test
    fun `a dead favourite does not strand the user`() {
        val config = AutoBestPolicy.Config(enabled = true, favorites = setOf("JP"))
        val sit = situation(
            currentOk = false,
            candidates = listOf(candidate("JP", 0, ok = false), candidate("NL", 90)),
        )
        assertEquals("NL", AutoBestPolicy.decide(config, sit))
    }

    @Test
    fun `pool respects the lock over favourites`() {
        val config = AutoBestPolicy.Config(enabled = true, lockedCountry = "US", favorites = setOf("NL"))
        val pool = AutoBestPolicy.pool(config, listOf(candidate("US", 300), candidate("NL", 90)))
        assertEquals(listOf("US"), pool.map { it.code })
    }
}
