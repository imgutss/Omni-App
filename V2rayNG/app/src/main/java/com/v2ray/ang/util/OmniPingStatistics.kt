package com.v2ray.ang.util

import kotlin.math.sqrt

/**
 * The result of a real measurement batch.
 *
 * A positive delay is the **fastest** measured response, which is what a user means by "my ping": the
 * best time the connection did achieve, not an average that no single request ever took. Zero keeps the
 * existing "not measurable right now" state and -1 means that no sample received a response.
 *
 * The honest spread is reported next to it, so the optimistic number never stands alone:
 * [medianMillis] is the typical response and [jitterMillis] the standard deviation of the raw samples.
 * [partial] is set when fewer samples answered than were asked for: the delay is then real, but it is
 * the best of a lossy set and the UI says so instead of hiding it.
 */
data class OmniPingStats(
    val delayMillis: Long,
    val sampleCount: Int,
    val successfulSamples: Int,
    val jitterMillis: Long,
    val kind: String,
    /** Typical response of the samples that answered; 0 when none did. */
    val medianMillis: Long = 0L,
    /** Slowest response of the samples that answered; 0 when none did. */
    val maxMillis: Long = 0L,
    /** True when at least one sample got no response, so [delayMillis] is the best of a lossy set. */
    val partial: Boolean = false,
)

object OmniPingStatistics {
    const val KIND_HTTP = "HTTP"
    const val KIND_TCP = "TCP"

    /** A sample that could not be measured at all; -1 stays the sample that got no response. */
    const val UNTESTED_SAMPLE = 0L

    /**
     * The samples of one profile on the one connection that carries them, one after the other: behind a
     * SOCKS port a sample is one request, and through a tunnel they are the requests of that tunnel, which
     * must not queue on the wire and report their waiting as latency.
     */
    suspend fun probeSamples(
        probe: suspend (port: Int, deadline: Long) -> Long,
        port: Int,
        deadline: Long,
        samples: Int,
    ): List<Long> {
        val delays = ArrayList<Long>(samples)
        repeat(samples) { delays += probe(port, deadline) }
        return delays
    }

    /** Nothing was measurable: [UNTESTED_SAMPLE] for every sample the caller asked for. */
    fun untestedSamples(samples: Int): List<Long> = List(samples) { UNTESTED_SAMPLE }

    /**
     * Summarizes the actual samples without replacing failures with a made-up number.
     *
     * - [OmniPingStats.delayMillis] is the **minimum** of the successful samples: the real best-case
     *   round trip, which is the lowest honest figure a ping can report.
     * - [OmniPingStats.medianMillis] is the typical response and [OmniPingStats.maxMillis] the worst one,
     *   so the number a user acts on can be checked against what the link actually did.
     * - [OmniPingStats.jitterMillis] is the population standard deviation of the raw successful samples,
     *   in milliseconds. It is the ordinary meaning of jitter and not a second latency estimate, and it no
     *   longer depends on the order the samples were collected in (the old code averaged the gaps of the
     *   sorted samples, which reported about half the observed range as "±").
     * - [OmniPingStats.partial] keeps a lossy result honest rather than presenting a best-of-N as if every
     *   request had answered.
     */
    fun summarize(samples: List<Long>, kind: String): OmniPingStats {
        require(samples.isNotEmpty()) { "At least one real sample is required" }
        val successful = samples.filter { it > 0L }
        if (successful.isEmpty()) {
            val delay = when {
                samples.any { it == 0L } -> 0L
                else -> -1L
            }
            return OmniPingStats(
                delayMillis = delay,
                sampleCount = samples.size,
                successfulSamples = 0,
                jitterMillis = 0L,
                kind = kind,
                medianMillis = 0L,
                maxMillis = 0L,
                partial = true,
            )
        }
        val sorted = successful.sorted()
        val minimum = sorted.first()
        val median = when {
            sorted.size % 2 == 1 -> sorted[sorted.size / 2]
            else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2L
        }
        val jitter = standardDeviation(successful)
        return OmniPingStats(
            delayMillis = minimum,
            sampleCount = samples.size,
            successfulSamples = successful.size,
            jitterMillis = jitter,
            kind = kind,
            medianMillis = median,
            maxMillis = sorted.last(),
            partial = successful.size < samples.size,
        )
    }

    /**
     * The population standard deviation of the samples, rounded to whole milliseconds.
     *
     * It is computed over the raw samples in the order they were measured, so a sample that is 120 ms fast
     * and one that is 120 ms slow count the same; a single sample has no spread and reports 0.
     */
    fun standardDeviation(samples: List<Long>): Long {
        if (samples.size < 2) return 0L
        val mean = samples.sumOf { it.toDouble() } / samples.size
        val variance = samples.sumOf { sample ->
            val delta = sample - mean
            delta * delta
        } / samples.size
        return sqrt(variance).toLong()
    }
}
