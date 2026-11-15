package com.v2ray.ang.firefox.hub

/**
 * The Quota Ring's arithmetic, kept apart from the UI so it can be tested.
 *
 * The ring shows how much of the month's allowance is spent and colours itself by how close to the
 * edge the account is: green while there is plenty, yellow as it gets close, red when it is nearly
 * gone. An unlimited account has no ring to fill, and an allowance that has not been read yet is
 * unknown rather than zero.
 */
object QuotaMath {

    const val GB: Double = 1024.0 * 1024.0 * 1024.0
    const val MB: Double = 1024.0 * 1024.0

    /** The ring's colour band. */
    enum class Level { GREEN, YELLOW, RED, UNLIMITED, UNKNOWN }

    /** How much of the allowance is spent, 0..100, from the limit and what is left. */
    fun usedPercent(limitBytes: Long, remainingBytes: Long): Double {
        if (limitBytes <= 0 || remainingBytes < 0) return 0.0
        val used = (limitBytes - remainingBytes).coerceAtLeast(0L)
        return (used * 100.0 / limitBytes).coerceIn(0.0, 100.0)
    }

    /** The colour band for a spent percentage: green <60, yellow 60–85, red >85. */
    fun level(usedPercent: Double): Level = when {
        usedPercent > 85.0 -> Level.RED
        usedPercent >= 60.0 -> Level.YELLOW
        else -> Level.GREEN
    }

    /** The band for an account's allowance, accounting for unlimited and not-yet-read. */
    fun level(unlimited: Boolean, limitBytes: Long, remainingBytes: Long): Level = when {
        unlimited -> Level.UNLIMITED
        limitBytes <= 0 -> Level.UNKNOWN
        else -> level(usedPercent(limitBytes, remainingBytes))
    }

    /** A short human size: "12.4 GB", "850 MB", "0 B". */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val b = bytes.toDouble()
        return when {
            b >= GB -> String.format(java.util.Locale.US, "%.1f GB", b / GB)
            b >= MB -> String.format(java.util.Locale.US, "%.0f MB", b / MB)
            b >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
            else -> "$bytes B"
        }
    }

    /** A speed in bytes/second as "1.2 MB/s". */
    fun formatRate(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "0 B/s"
        return formatBytes(bytesPerSecond) + "/s"
    }

    /** Whole days until a reset, rounding a part-day up; 0 when it is past or unknown. */
    fun daysUntil(resetAtMillis: Long, nowMillis: Long): Long {
        val left = resetAtMillis - nowMillis
        if (left <= 0) return 0
        val day = 24L * 60L * 60L * 1000L
        return left / day + if (left % day > 0) 1 else 0
    }

    /** A reset countdown as "3d 4h" / "5h 12m" / "9m", for the ring's centre. */
    fun resetCountdown(resetAtMillis: Long, nowMillis: Long): String {
        val left = resetAtMillis - nowMillis
        if (left <= 0) return "—"
        val hours = left / (60L * 60L * 1000L)
        val days = hours / 24
        val minutes = (left / (60L * 1000L)) % 60
        return when {
            days > 0 -> "${days}d ${hours % 24}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }
}
