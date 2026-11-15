package com.v2ray.ang.omni.dns

import com.v2ray.ang.omni.lab.OmniScanTarget
import com.v2ray.ang.omni.lab.OmniTargetKind
import com.v2ray.ang.omni.lab.OmniTargetParser
import kotlin.random.Random

private const val IPV4_SPACE = 0x1_0000_0000L
private const val MAX_LONG = Long.MAX_VALUE

data class OmniDnsResolverTargetPlan(
    val targets: List<OmniScanTarget>,
    val totalCandidates: Long,
    val invalidInputs: Int,
)

private sealed interface ResolverSource {
    val count: Long
    fun at(index: Long, ports: List<Int>): OmniScanTarget
}

private data class SingleWithPorts(val host: String, val kind: OmniTargetKind, val explicitPort: Int?) : ResolverSource {
    override val count: Long get() = 1L
    override fun at(index: Long, ports: List<Int>): OmniScanTarget =
        OmniScanTarget(host, explicitPort ?: ports[(index % ports.size).toInt()], kind)
}

private data class Ipv4RangeResolver(val start: Long, val addresses: Long) : ResolverSource {
    override val count: Long get() = addresses
    override fun at(index: Long, ports: List<Int>): OmniScanTarget {
        val perAddress = index / ports.size
        val port = ports[(index % ports.size).toInt()]
        return OmniScanTarget(ipv4String(start + perAddress), port, OmniTargetKind.IPV4)
    }
}

private data class CountedSource(val source: ResolverSource, val count: Long)

/** Lazy ranges are sampled by numeric index, so even a very large CIDR is never expanded in memory. */
object OmniDnsResolverTargets {
    fun parseAndSample(
        input: String,
        ports: List<Int>,
        limit: Int,
        random: Random = Random.Default,
    ): OmniDnsResolverTargetPlan {
        require(ports.isNotEmpty() && ports.all { it in 1..65535 })
        require(limit > 0)
        val sources = ArrayList<CountedSource>()
        var invalid = 0
        var total = 0L
        input.replace('،', ',').split(Regex("[\\s,;]+")).filter { it.isNotBlank() }.forEach { token ->
            val source = parseSource(token, ports.size)
            if (source == null) {
                invalid++
            } else {
                sources += source
                total = saturatedAdd(total, source.count)
            }
        }
        if (sources.isEmpty() || total == 0L) return OmniDnsResolverTargetPlan(emptyList(), total, invalid)

        val indices = when {
            total <= limit.toLong() -> (0L until total).toList()
            total <= maxOf(10_000L, limit.toLong() * 2L) -> (0L until total).shuffled(random).take(limit)
            else -> {
                val selected = HashSet<Long>(limit * 2)
                while (selected.size < limit) selected += random.nextLong(total)
                selected.sorted()
            }
        }
        val targets = indices.mapNotNull { index -> resolveIndex(sources, index, ports) }
            .distinctBy { it.endpointId }
            .take(limit)
        return OmniDnsResolverTargetPlan(targets, total, invalid)
    }

    private fun parseSource(token: String, portCount: Int): CountedSource? {
        parseIpv4Range(token)?.let { (start, count) ->
            val source = Ipv4RangeResolver(start, count)
            return CountedSource(source, saturatedMultiply(count, portCount.toLong()))
        }
        val parsed = OmniTargetParser.parse(token)
        if (!parsed.hasTargets || parsed.invalid.isNotEmpty() || parsed.targetCount != 1L) return null
        val seed = parsed.targets.single()
        if (seed.kind == OmniTargetKind.DOMAIN) return null
        val source = SingleWithPorts(seed.host, seed.kind, seed.port)
        return CountedSource(source, if (seed.port == null) portCount.toLong() else 1L)
    }

    private fun resolveIndex(sources: List<CountedSource>, index: Long, ports: List<Int>): OmniScanTarget? {
        var remaining = index
        sources.forEach { source ->
            if (remaining < source.count) return source.source.at(remaining, ports)
            remaining -= source.count
        }
        return null
    }

    private fun parseIpv4Range(value: String): Pair<Long, Long>? {
        val cidr = value.split('/')
        if (cidr.size == 2) {
            val address = ipv4Long(cidr[0]) ?: return null
            val prefix = cidr[1].toIntOrNull()?.takeIf { it in 0..32 } ?: return null
            val count = if (prefix == 0) IPV4_SPACE else 1L shl (32 - prefix)
            val mask = if (prefix == 0) 0L else (0xffff_ffffL shl (32 - prefix)) and 0xffff_ffffL
            return (address and mask) to count
        }
        val range = value.split('-', limit = 2)
        if (range.size != 2) return null
        val start = ipv4Long(range[0]) ?: return null
        val end = if (range[1].contains('.')) {
            ipv4Long(range[1]) ?: return null
        } else {
            val last = range[1].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            (start and 0xffff_ff00L) + last
        }
        if (end < start) return null
        return start to (end - start + 1L)
    }
}

private fun ipv4Long(value: String): Long? {
    val parts = value.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> !c.isDigit() } }) return null
    var result = 0L
    parts.forEach { part ->
        val octet = part.toIntOrNull() ?: return null
        if (octet !in 0..255) return null
        result = (result shl 8) or octet.toLong()
    }
    return result
}

private fun ipv4String(value: Long): String = listOf(24, 16, 8, 0).joinToString(".") { shift ->
    ((value ushr shift) and 255L).toString()
}

private fun saturatedAdd(left: Long, right: Long): Long = if (MAX_LONG - left < right) MAX_LONG else left + right
private fun saturatedMultiply(left: Long, right: Long): Long = when {
    left == 0L || right == 0L -> 0L
    left > MAX_LONG / right -> MAX_LONG
    else -> left * right
}
