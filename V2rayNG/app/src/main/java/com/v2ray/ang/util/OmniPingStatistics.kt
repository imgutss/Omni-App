package com.v2ray.ang.util

/**
 * The result of a real measurement batch. A positive delay is a measured response; zero retains
 * the existing "not measurable right now" state and -1 means that no sample received a response.
 */
data class OmniPingStats(
    val delayMillis: Long,
    val sampleCount: Int,
    val successfulSamples: Int,
    val jitterMillis: Long,
    val kind: String,
)

object OmniPingStatistics {
    const val KIND_HTTP = "HTTP"
    const val KIND_TCP = "TCP"

    /**
     * Summarizes actual samples without replacing failures with a made-up number. The median is
     * calculated over successful responses only, while sampleCount and successfulSamples retain
     * the complete outcome. Jitter is the mean absolute difference between adjacent successful
     * samples, in milliseconds; it is not a second latency estimate.
     *
     * The samples of one profile run together, so the order in which they arrive says nothing about
     * the connection: the spread is read from the sorted samples, which makes jitter independent of
     * arrival order and of the order the caller collected the results in.
     */
    fun summarize(samples: List<Long>, kind: String): OmniPingStats {
        require(samples.isNotEmpty()) { "At least one real sample is required" }
        val successful = samples.filter { it > 0L }
        val sorted = successful.sorted()
        val median = when {
            sorted.isEmpty() && samples.any { it == 0L } -> 0L
            sorted.isEmpty() -> -1L
            sorted.size % 2 == 1 -> sorted[sorted.size / 2]
            else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2L
        }
        val jitter = if (sorted.size < 2) {
            0L
        } else {
            sorted.zipWithNext { first, second -> second - first }
                .average()
                .toLong()
        }
        return OmniPingStats(
            delayMillis = median,
            sampleCount = samples.size,
            successfulSamples = successful.size,
            jitterMillis = jitter,
            kind = kind,
        )
    }
}
