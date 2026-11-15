package com.v2ray.ang.firefox.hub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The Quota Ring's arithmetic: the share spent, its colour band, and the words around it. */
class QuotaMathTest {

    @Test
    fun `used percent from limit and remaining`() {
        assertEquals(20.0, QuotaMath.usedPercent(100, 80), 0.001)
        assertEquals(0.0, QuotaMath.usedPercent(100, 100), 0.001)
        assertEquals(100.0, QuotaMath.usedPercent(100, 0), 0.001)
        // An allowance that has not been read is not "100% spent".
        assertEquals(0.0, QuotaMath.usedPercent(0, 0), 0.001)
    }

    @Test
    fun `colour bands follow the 60 and 85 thresholds`() {
        assertEquals(QuotaMath.Level.GREEN, QuotaMath.level(0.0))
        assertEquals(QuotaMath.Level.GREEN, QuotaMath.level(59.9))
        assertEquals(QuotaMath.Level.YELLOW, QuotaMath.level(60.0))
        assertEquals(QuotaMath.Level.YELLOW, QuotaMath.level(85.0))
        assertEquals(QuotaMath.Level.RED, QuotaMath.level(85.1))
        assertEquals(QuotaMath.Level.RED, QuotaMath.level(100.0))
    }

    @Test
    fun `unlimited and unknown are their own bands`() {
        assertEquals(QuotaMath.Level.UNLIMITED, QuotaMath.level(unlimited = true, limitBytes = 0, remainingBytes = 0))
        assertEquals(QuotaMath.Level.UNKNOWN, QuotaMath.level(unlimited = false, limitBytes = -1, remainingBytes = -1))
        assertEquals(QuotaMath.Level.GREEN, QuotaMath.level(unlimited = false, limitBytes = 100, remainingBytes = 90))
    }

    @Test
    fun `sizes are human readable`() {
        assertEquals("0 B", QuotaMath.formatBytes(0))
        assertEquals("1.0 GB", QuotaMath.formatBytes(1024L * 1024 * 1024))
        assertEquals("850 MB", QuotaMath.formatBytes(850L * 1024 * 1024))
    }

    @Test
    fun `rates carry a per-second suffix`() {
        assertEquals("0 B/s", QuotaMath.formatRate(0))
        assertEquals("1 MB/s", QuotaMath.formatRate(1024L * 1024))
    }

    @Test
    fun `reset countdown rounds a part day up`() {
        val day = 24L * 60 * 60 * 1000
        val now = 1_000_000_000_000L
        assertEquals(0L, QuotaMath.daysUntil(now - 1, now))
        assertEquals(1L, QuotaMath.daysUntil(now + 1, now))
        assertEquals(2L, QuotaMath.daysUntil(now + day + 1, now))
    }

    @Test
    fun `countdown formats days hours minutes`() {
        val now = 1_000_000_000_000L
        val hour = 60L * 60 * 1000
        assertEquals("—", QuotaMath.resetCountdown(now - 1, now))
        assertEquals("2d 3h", QuotaMath.resetCountdown(now + 51 * hour, now))
        assertEquals("5h 0m", QuotaMath.resetCountdown(now + 5 * hour, now))
    }
}
