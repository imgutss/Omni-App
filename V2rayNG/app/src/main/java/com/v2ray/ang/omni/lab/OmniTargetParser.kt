package com.v2ray.ang.omni.lab

import java.net.InetAddress

private const val MAX_COUNTER = Long.MAX_VALUE

enum class OmniTargetKind { IPV4, IPV6, DOMAIN }
enum class OmniTargetError { EMPTY, INVALID_HOST, INVALID_PORT, INVALID_RANGE }

data class OmniTargetSeed(
    val host: String,
    val port: Int?,
    val kind: OmniTargetKind,
)

data class OmniScanTarget(
    val host: String,
    val port: Int,
    val kind: OmniTargetKind,
) {
    val endpointId: String
        get() = if (kind == OmniTargetKind.IPV6 && !host.startsWith("[")) "[$host]:$port" else "$host:$port"
}

data class OmniInvalidTarget(val raw: String, val error: OmniTargetError)

data class OmniTargetPlan(
    val targets: Sequence<OmniTargetSeed>,
    val invalid: List<OmniInvalidTarget>,
    val targetCount: Long,
    val explicitTargetCount: Long = 0L,
) {
    val hasTargets: Boolean get() = targetCount > 0L

    fun plannedProbeCount(defaultPorts: List<Int>): Long {
        val implicit = (targetCount - explicitTargetCount).coerceAtLeast(0L)
        return saturatedAdd(explicitTargetCount, saturatedMultiply(implicit, defaultPorts.size.toLong()))
    }

    fun concreteTargets(defaultPorts: List<Int>): Sequence<OmniScanTarget> = sequence {
        targets.forEach { seed ->
            if (seed.port != null) {
                yield(OmniScanTarget(seed.host, seed.port, seed.kind))
            } else {
                defaultPorts.forEach { port -> yield(OmniScanTarget(seed.host, port, seed.kind)) }
            }
        }
    }
}

private data class Expansion(val count: Long, val seeds: Sequence<OmniTargetSeed>, val explicitCount: Long = 0L)

object OmniTargetParser {
    fun parse(input: String): OmniTargetPlan {
        val tokens = input.trim().replace('؛', ';').split(Regex("[\\s;]+")).filter { it.isNotBlank() }
        val expansions = ArrayList<Expansion>(tokens.size)
        val invalid = ArrayList<OmniInvalidTarget>()
        var count = 0L
        var explicitCount = 0L
        tokens.forEach { token ->
            val parsed = parseToken(token)
            if (parsed == null) {
                invalid += OmniInvalidTarget(token, OmniTargetError.INVALID_HOST)
            } else {
                expansions += parsed
                count = saturatedAdd(count, parsed.count)
                explicitCount = saturatedAdd(explicitCount, parsed.explicitCount)
            }
        }
        return OmniTargetPlan(
            targets = sequence { expansions.forEach { expansion -> yieldAll(expansion.seeds) } },
            invalid = invalid,
            targetCount = count,
            explicitTargetCount = explicitCount,
        )
    }

    fun parsePorts(input: String): List<Int> {
        val normalized = normalizeDigits(input).replace('،', ',')
        val values = normalized.split(Regex("[,\\s]+")).filter { it.isNotBlank() }
        require(values.isNotEmpty()) { "At least one port is required" }
        return values.map { value ->
            val port = value.toIntOrNull() ?: throw IllegalArgumentException("Invalid port")
            require(port in 1..65535) { "Port is outside 1..65535" }
            port
        }.distinct().sorted()
    }

    fun parseCount(input: String): Long? = normalizeDigits(input).trim().toLongOrNull()

    private fun parseToken(token: String): Expansion? {
        if (token.contains(',')) {
            val pieces = token.split(',')
            if (pieces.size > 1) {
                val first = parseSingle(pieces[0])
                val ports = pieces.drop(1).map { it.toIntOrNull()?.takeIf { port -> port in 1..65535 } }
                if (first != null && ports.all { it != null }) {
                    val hostPorts = sequence {
                        yieldAll(first.seeds)
                        first.seeds.forEach { seed ->
                            ports.filterNotNull().forEach { port -> yield(seed.copy(port = port)) }
                        }
                    }
                    return Expansion(
                        saturatedAdd(first.count, saturatedMultiply(first.count, ports.size.toLong())),
                        hostPorts,
                        saturatedAdd(first.explicitCount, saturatedMultiply(first.count, ports.size.toLong())),
                    )
                }
            }
            val expansions = pieces.map { parseSingle(it) ?: return null }
            return Expansion(
                expansions.fold(0L) { sum, item -> saturatedAdd(sum, item.count) },
                sequence { expansions.forEach { yieldAll(it.seeds) } },
                expansions.fold(0L) { sum, item -> saturatedAdd(sum, item.explicitCount) },
            )
        }
        return parseSingle(token)
    }

    private fun parseSingle(raw: String): Expansion? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val cidr = value.split('/')
        if (cidr.size == 2) {
            val prefix = cidr[1].toIntOrNull() ?: return null
            val start = ipv4ToLong(cidr[0]) ?: return null
            if (prefix !in 0..32) return null
            val size = if (prefix == 0) 4_294_967_296L else 1L shl (32 - prefix)
            val mask = if (prefix == 0) 0L else (0xffff_ffffL shl (32 - prefix)) and 0xffff_ffffL
            val normalizedNetwork = start and mask
            return Expansion(size, sequence {
                for (index in 0L until size) {
                    val address = normalizedNetwork + index
                    if (address > 0xffff_ffffL) break
                    yield(OmniTargetSeed(longToIpv4(address), null, OmniTargetKind.IPV4))
                }
            })
        }
        val range = value.split('-', limit = 2)
        if (range.size == 2 && range[0].contains('.') && range[1].contains('.')) {
            val start = ipv4ToLong(range[0]) ?: return null
            val end = ipv4ToLong(range[1]) ?: return null
            if (end < start) return null
            val size = end - start + 1L
            return Expansion(size, sequence {
                for (address in start..end) yield(OmniTargetSeed(longToIpv4(address), null, OmniTargetKind.IPV4))
            })
        }
        val (host, port) = splitHostPort(value) ?: return null
        val kind = hostKind(host) ?: return null
        if (port != null && port !in 1..65535) return null
        val normalizedHost = if (kind == OmniTargetKind.IPV6) host.removeSurrounding("[", "]") else host
        return Expansion(1L, sequenceOf(OmniTargetSeed(normalizedHost, port, kind)), if (port == null) 0L else 1L)
    }

    private fun splitHostPort(value: String): Pair<String, Int?>? {
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            if (close < 0) return null
            val host = value.substring(0, close + 1)
            val suffix = value.substring(close + 1)
            if (suffix.isEmpty()) return host to null
            if (!suffix.startsWith(":")) return null
            return host to (suffix.substring(1).toIntOrNull() ?: return null)
        }
        val colonCount = value.count { it == ':' }
        if (colonCount == 1) {
            val host = value.substringBefore(':')
            val port = value.substringAfter(':').toIntOrNull() ?: return null
            return host to port
        }
        if (colonCount > 1) return value to null
        return value to null
    }

    private fun hostKind(rawHost: String): OmniTargetKind? {
        val host = rawHost.removeSurrounding("[", "]")
        ipv4ToLong(host)?.let { return OmniTargetKind.IPV4 }
        if (host.matches(Regex("^[0-9.]+$"))) return null
        if (host.contains(':')) {
            return try {
                val address = InetAddress.getByName(host)
                if (address.hostAddress.contains(':')) OmniTargetKind.IPV6 else null
            } catch (_: Exception) { null }
        }
        val domain = Regex("(?i)^(?=.{1,253}$)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$")
        return if (domain.matches(host) && !host.contains("..") && host.split('.').all { it.length <= 63 && !it.startsWith('-') && !it.endsWith('-') }) {
            OmniTargetKind.DOMAIN
        } else null
    }

    private fun ipv4ToLong(host: String): Long? {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> !c.isDigit() } }) return null
        var result = 0L
        parts.forEach { part ->
            val number = part.toIntOrNull() ?: return null
            if (number !in 0..255) return null
            result = (result shl 8) or number.toLong()
        }
        return result
    }

    private fun longToIpv4(value: Long): String = listOf(24, 16, 8, 0).joinToString(".") { ((value ushr it) and 255L).toString() }

    private fun normalizeDigits(value: String): String = buildString(value.length) {
        value.forEach { char ->
            val digit = when (char) {
                in '0'..'9' -> char - '0'
                in '۰'..'۹' -> char - '۰'
                in '٠'..'٩' -> char - '٠'
                in '０'..'９' -> char - '０'
                else -> null
            }
            append(digit?.toString() ?: char)
        }
    }
}

private fun saturatedAdd(left: Long, right: Long): Long = if (MAX_COUNTER - left < right) MAX_COUNTER else left + right
private fun saturatedMultiply(left: Long, right: Long): Long = when {
    left == 0L || right == 0L -> 0L
    left > MAX_COUNTER / right -> MAX_COUNTER
    else -> left * right
}
