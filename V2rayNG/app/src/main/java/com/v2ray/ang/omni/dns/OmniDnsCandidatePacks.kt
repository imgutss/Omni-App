package com.v2ray.ang.omni.dns

import android.content.Context
import java.security.SecureRandom

private const val IPV4_MAX = 0xffff_ffffL

data class OmniDnsCidrRange(val start: Long, val end: Long) {
    init {
        require(start in 0..IPV4_MAX && end in start..IPV4_MAX)
    }

    val size: Long get() = end - start + 1L
}

data class OmniDnsRangeGroup(
    val key: String,
    val label: String,
    val ranges: List<OmniDnsCidrRange>,
) {
    val totalIps: Long = ranges.sumOf(OmniDnsCidrRange::size)
}

enum class OmniDnsCandidateSource(val asset: String?) {
    MANUAL(null),
    IR_FULL("dns/iran_full.cidr"),
    IR_LITE("dns/iran_lite.cidr"),
}

/** Offline address-space sampling. Generated IPs are candidates, never measured resolvers. */
object OmniDnsCandidatePacks {
    const val MAX_CUSTOM_COUNT = 100_000

    fun load(context: Context, source: OmniDnsCandidateSource): List<OmniDnsCidrRange> {
        val asset = source.asset ?: return emptyList()
        val ranges = context.assets.open(asset).bufferedReader().useLines { lines ->
            lines.mapNotNull { parse(it) }.toList()
        }
        return normalize(ranges)
    }

    fun groups(ranges: List<OmniDnsCidrRange>, source: OmniDnsCandidateSource): List<OmniDnsRangeGroup> =
        ranges.groupBy { range ->
            when (source) {
                OmniDnsCandidateSource.IR_FULL -> "${range.start ushr 24}"
                OmniDnsCandidateSource.IR_LITE -> "${range.start ushr 24}.${(range.start ushr 16) and 255}"
                OmniDnsCandidateSource.MANUAL -> ""
            }
        }.map { (key, members) ->
            OmniDnsRangeGroup(key, "$key.x.x" + if (source == OmniDnsCandidateSource.IR_FULL) ".x" else "", members)
        }.sortedBy { group -> group.ranges.minOf(OmniDnsCidrRange::start) }

    fun generate(
        ranges: List<OmniDnsCidrRange>,
        count: Int,
        random: SecureRandom = SecureRandom(),
    ): List<String> {
        require(count in 1..MAX_CUSTOM_COUNT)
        val clean = normalize(ranges)
        if (clean.isEmpty()) return emptyList()
        val cumulative = LongArray(clean.size)
        var total = 0L
        clean.forEachIndexed { index, range ->
            total += range.size
            cumulative[index] = total
        }
        val wanted = minOf(count.toLong(), total).toInt()
        val selected = LinkedHashSet<Long>(wanted * 2)
        val maxAttempts = wanted * 20 + 1024
        var attempts = 0
        while (selected.size < wanted && attempts < maxAttempts) {
            attempts++
            val position = (random.nextLong() and Long.MAX_VALUE) % total
            var low = 0
            var high = cumulative.lastIndex
            while (low < high) {
                val middle = (low + high) ushr 1
                if (cumulative[middle] <= position) low = middle + 1 else high = middle
            }
            val before = if (low == 0) 0L else cumulative[low - 1]
            selected += clean[low].start + (position - before)
        }
        // Dense or heavily overlapping packs can make random collision retries
        // underfill. A bounded deterministic pass guarantees the requested count.
        if (selected.size < wanted) {
            var rangeIndex = 0
            var address = clean.first().start
            while (selected.size < wanted) {
                val range = clean[rangeIndex]
                if (address > range.end) {
                    rangeIndex++
                    address = clean[rangeIndex].start
                }
                selected += address
                address++
            }
        }
        val output = selected.map(::ipv4String).toMutableList()
        for (index in output.lastIndex downTo 1) {
            val other = random.nextInt(index + 1)
            val swap = output[index]
            output[index] = output[other]
            output[other] = swap
        }
        return output
    }

    internal fun parse(line: String): OmniDnsCidrRange? {
        val token = line.substringBefore('#').trim()
        if (token.isEmpty()) return null
        val parts = token.split('/')
        if (parts.size > 2) return null
        val address = ipv4Long(parts[0]) ?: return null
        val prefix = if (parts.size == 2) parts[1].toIntOrNull()?.takeIf { it in 0..32 } ?: return null else 32
        val mask = if (prefix == 0) 0L else (IPV4_MAX shl (32 - prefix)) and IPV4_MAX
        val start = address and mask
        val size = 1L shl (32 - prefix)
        return OmniDnsCidrRange(start, start + size - 1)
    }

    internal fun normalize(ranges: List<OmniDnsCidrRange>): List<OmniDnsCidrRange> {
        val sorted = ranges.sortedWith(compareBy(OmniDnsCidrRange::start, OmniDnsCidrRange::end))
        val result = mutableListOf<OmniDnsCidrRange>()
        sorted.forEach { range ->
            val previous = result.lastOrNull()
            if (previous != null && range.start <= previous.end) {
                if (range.end > previous.end) {
                    // Clip only overlaps; do not merge adjacent ranges across
                    // operator/group boundaries or change their advertised shape.
                    result += OmniDnsCidrRange(previous.end + 1, range.end)
                }
            } else {
                result += range
            }
        }
        return result
    }
}

private fun ipv4Long(value: String): Long? {
    val parts = value.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> !c.isDigit() } }) return null
    var result = 0L
    parts.forEach { part ->
        if (part.length > 1 && part.startsWith('0')) return null
        val octet = part.toIntOrNull() ?: return null
        if (octet !in 0..255) return null
        result = (result shl 8) or octet.toLong()
    }
    return result
}

private fun ipv4String(value: Long): String = listOf(24, 16, 8, 0).joinToString(".") { shift ->
    ((value ushr shift) and 255L).toString()
}
