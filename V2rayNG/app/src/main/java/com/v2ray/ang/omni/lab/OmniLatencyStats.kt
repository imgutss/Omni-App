package com.v2ray.ang.omni.lab

data class OmniLatencyStats(
    val samples: List<Long>,
    val attempted: Int,
    val succeeded: Int,
) {
    val medianMs: Long?
        get() = samples.sorted().let { sorted ->
            if (sorted.isEmpty()) null else if (sorted.size % 2 == 1) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2L
        }

    val jitterMs: Long?
        get() = samples.zipWithNext { a, b -> kotlin.math.abs(b - a) }.let { differences ->
            if (differences.isEmpty()) null else differences.average().toLong()
        }

    val lossPercent: Double
        get() = if (attempted == 0) 100.0 else (attempted - succeeded).coerceAtLeast(0) * 100.0 / attempted

    val successRate: Double
        get() = if (attempted <= 0) 0.0 else succeeded.coerceIn(0, attempted).toDouble() / attempted

    companion object {
        fun from(samples: List<Long?>, attempts: Int = samples.size): OmniLatencyStats {
            val successful = samples.filterNotNull().filter { it >= 0L }
            return OmniLatencyStats(successful, attempts, successful.size)
        }
    }
}
