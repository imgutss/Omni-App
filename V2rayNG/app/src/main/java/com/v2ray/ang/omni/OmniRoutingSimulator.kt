package com.v2ray.ang.omni

import com.v2ray.ang.dto.entities.RulesetItem
import java.net.InetAddress
import java.util.Locale

/** A deterministic, offline trace. It never turns an unavailable geosite/geoip/RE2 decision into a match. */
enum class OmniRouteMatch { MATCH, NO_MATCH, UNSUPPORTED }

enum class OmniRoutingReason {
    MATCHED,
    DISABLED,
    DOMAIN_MISMATCH,
    IP_MISMATCH,
    PORT_MISMATCH,
    NETWORK_MISMATCH,
    PROTOCOL_MISMATCH,
    PROCESS_MISMATCH,
    MISSING_PROTOCOL_CONTEXT,
    MISSING_PROCESS_CONTEXT,
    UNSUPPORTED_GEOSITE,
    UNSUPPORTED_GEOIP,
    UNSUPPORTED_REGEXP,
    INVALID_RULE,
    MISSING_OUTBOUND,
}

enum class OmniRoutingSimulationStatus { MATCHED, UNMATCHED, INDETERMINATE, INVALID }

data class OmniRoutingRequest(
    val host: String,
    val port: Int = 443,
    val network: String = "tcp",
    val protocol: String = "",
    val process: String = "",
)

data class OmniRoutingTrace(
    val ruleId: String,
    val remarks: String,
    val outboundTag: String,
    val match: OmniRouteMatch,
    val reason: OmniRoutingReason,
)

data class OmniRoutingSimulation(
    val status: OmniRoutingSimulationStatus,
    val outboundTag: String? = null,
    val traces: List<OmniRoutingTrace> = emptyList(),
)

fun simulateOmniRouting(rules: List<RulesetItem>, request: OmniRoutingRequest): OmniRoutingSimulation {
    val host = request.host.trim().trim('[', ']').trimEnd('.').lowercase(Locale.ROOT)
    if (host.isEmpty() || request.port !in 1..65_535 || request.network.isBlank()) {
        return OmniRoutingSimulation(OmniRoutingSimulationStatus.INVALID)
    }
    val normalized = request.copy(host = host, network = request.network.trim().lowercase(Locale.ROOT), protocol = request.protocol.trim().lowercase(Locale.ROOT), process = request.process.trim())
    val traces = mutableListOf<OmniRoutingTrace>()
    var indeterminate = false
    rules.forEachIndexed { index, rule ->
        val result = evaluateRule(rule, normalized)
        traces += OmniRoutingTrace(
            ruleId = rule.id.ifBlank { "rule-$index" },
            remarks = rule.remarks.orEmpty(),
            outboundTag = rule.outboundTag,
            match = result.first,
            reason = result.second,
        )
        when (result.first) {
            OmniRouteMatch.MATCH -> return if (indeterminate) {
                OmniRoutingSimulation(OmniRoutingSimulationStatus.INDETERMINATE, traces = traces)
            } else if (rule.outboundTag.isBlank()) {
                OmniRoutingSimulation(OmniRoutingSimulationStatus.INVALID, traces = traces)
            } else {
                OmniRoutingSimulation(OmniRoutingSimulationStatus.MATCHED, rule.outboundTag, traces)
            }
            OmniRouteMatch.UNSUPPORTED -> indeterminate = true
            OmniRouteMatch.NO_MATCH -> Unit
        }
    }
    return OmniRoutingSimulation(if (indeterminate) OmniRoutingSimulationStatus.INDETERMINATE else OmniRoutingSimulationStatus.UNMATCHED, traces = traces)
}

private fun evaluateRule(rule: RulesetItem, request: OmniRoutingRequest): Pair<OmniRouteMatch, OmniRoutingReason> {
    if (!rule.enabled) return OmniRouteMatch.NO_MATCH to OmniRoutingReason.DISABLED
    var unsupported: OmniRoutingReason? = null
    // RulesetItem is mutable because the existing editor uses it; take one consistent snapshot for this trace.
    val domains = rule.domain
    val ips = rule.ip
    val portRule = rule.port
    val networkRule = rule.network
    val protocols = rule.protocol
    val processes = rule.process

    if (!domains.isNullOrEmpty()) {
        when (val result = matchDomains(domains, request.host)) {
            OmniRouteMatch.NO_MATCH -> return result to OmniRoutingReason.DOMAIN_MISMATCH
            OmniRouteMatch.UNSUPPORTED -> unsupported = unsupportedDomainReason(domains)
            OmniRouteMatch.MATCH -> Unit
        }
    }
    if (!ips.isNullOrEmpty()) {
        when (matchIps(ips, request.host)) {
            OmniRouteMatch.NO_MATCH -> return OmniRouteMatch.NO_MATCH to OmniRoutingReason.IP_MISMATCH
            OmniRouteMatch.UNSUPPORTED -> unsupported = OmniRoutingReason.UNSUPPORTED_GEOIP
            OmniRouteMatch.MATCH -> Unit
        }
    }
    if (!portRule.isNullOrBlank()) {
        when (matchPorts(portRule, request.port)) {
            OmniRouteMatch.NO_MATCH -> return OmniRouteMatch.NO_MATCH to OmniRoutingReason.PORT_MISMATCH
            OmniRouteMatch.UNSUPPORTED -> unsupported = OmniRoutingReason.INVALID_RULE
            OmniRouteMatch.MATCH -> Unit
        }
    }
    if (!networkRule.isNullOrBlank() && !matchList(networkRule.split(','), request.network)) {
        return OmniRouteMatch.NO_MATCH to OmniRoutingReason.NETWORK_MISMATCH
    }
    if (!protocols.isNullOrEmpty()) {
        if (request.protocol.isBlank()) {
            unsupported = OmniRoutingReason.MISSING_PROTOCOL_CONTEXT
        } else if (!matchList(protocols, request.protocol)) {
            return OmniRouteMatch.NO_MATCH to OmniRoutingReason.PROTOCOL_MISMATCH
        }
    }
    if (!processes.isNullOrEmpty()) {
        if (request.process.isBlank()) {
            unsupported = OmniRoutingReason.MISSING_PROCESS_CONTEXT
        } else if (!processes.any { it.trim() == request.process }) {
            return OmniRouteMatch.NO_MATCH to OmniRoutingReason.PROCESS_MISMATCH
        }
    }
    if (unsupported != null) return OmniRouteMatch.UNSUPPORTED to unsupported
    if (rule.outboundTag.isBlank()) return OmniRouteMatch.UNSUPPORTED to OmniRoutingReason.MISSING_OUTBOUND
    return OmniRouteMatch.MATCH to OmniRoutingReason.MATCHED
}

private fun matchDomains(patterns: List<String>, host: String): OmniRouteMatch {
    var unsupported = false
    patterns.forEach { raw ->
        val pattern = raw.trim().lowercase(Locale.ROOT)
        if (pattern.isBlank()) return@forEach
        when {
            pattern.startsWith("geosite:") || pattern.startsWith("regexp:") -> unsupported = true
            pattern.startsWith("full:") -> if (host == pattern.removePrefix("full:").trimEnd('.')) return OmniRouteMatch.MATCH
            pattern.startsWith("domain:") -> {
                val domain = pattern.removePrefix("domain:").trimStart('.').trimEnd('.')
                if (domain.isNotEmpty() && (host == domain || host.endsWith(".$domain"))) return OmniRouteMatch.MATCH
            }
            else -> if (pattern.isNotEmpty() && host.contains(pattern)) return OmniRouteMatch.MATCH
        }
    }
    return if (unsupported) OmniRouteMatch.UNSUPPORTED else OmniRouteMatch.NO_MATCH
}

private fun unsupportedDomainReason(patterns: List<String>): OmniRoutingReason =
    if (patterns.any { it.trim().startsWith("regexp:", ignoreCase = true) }) OmniRoutingReason.UNSUPPORTED_REGEXP else OmniRoutingReason.UNSUPPORTED_GEOSITE

private fun matchIps(patterns: List<String>, host: String): OmniRouteMatch {
    val address = parseLiteralIp(host) ?: return if (patterns.any { it.trim().startsWith("geoip:", true) }) OmniRouteMatch.UNSUPPORTED else OmniRouteMatch.NO_MATCH
    var unsupported = false
    patterns.forEach { raw ->
        val pattern = raw.trim()
        if (pattern.startsWith("geoip:", ignoreCase = true)) {
            unsupported = true
        } else if (cidrMatches(pattern, address)) {
            return OmniRouteMatch.MATCH
        } else if (!validCidr(pattern)) {
            unsupported = true
        }
    }
    return if (unsupported) OmniRouteMatch.UNSUPPORTED else OmniRouteMatch.NO_MATCH
}

private fun matchPorts(raw: String, port: Int): OmniRouteMatch {
    var invalid = false
    raw.split(',').forEach { part ->
        val value = part.trim()
        if (value.isEmpty()) return@forEach
        val bounds = value.split('-', limit = 2)
        val first = bounds[0].toIntOrNull()
        val last = bounds.getOrElse(1) { bounds[0] }.toIntOrNull()
        if (first == null || last == null || first !in 1..65_535 || last !in first..65_535) invalid = true
        else if (port in first..last) return OmniRouteMatch.MATCH
    }
    return if (invalid) OmniRouteMatch.UNSUPPORTED else OmniRouteMatch.NO_MATCH
}

private fun matchList(patterns: List<String>, value: String): Boolean = patterns.any { it.trim().equals(value, ignoreCase = true) }

private data class IpAddress(val bytes: ByteArray)

private fun parseLiteralIp(value: String): IpAddress? {
    val input = value.trim().trim('[', ']')
    return when {
        IPV4.matches(input) -> {
            val parts = input.split('.')
            val numbers = parts.map { it.toIntOrNull() ?: return null }
            if (numbers.any { it !in 0..255 }) null else IpAddress(numbers.map { it.toByte() }.toByteArray())
        }
        input.contains(':') -> runCatching { InetAddress.getByName(input) }.getOrNull()?.let { IpAddress(it.address) }
        else -> null
    }
}

private fun validCidr(value: String): Boolean {
    val parts = value.split('/', limit = 2)
    val address = parseLiteralIp(parts[0]) ?: return false
    val prefix = parts.getOrNull(1)?.toIntOrNull() ?: return parts.size == 1
    return prefix in 0..address.bytes.size * 8
}

private fun cidrMatches(value: String, address: IpAddress): Boolean {
    val parts = value.split('/', limit = 2)
    val network = parseLiteralIp(parts[0]) ?: return false
    if (network.bytes.size != address.bytes.size) return false
    val prefix = parts.getOrNull(1)?.toIntOrNull() ?: return network.bytes.contentEquals(address.bytes)
    if (prefix !in 0..network.bytes.size * 8) return false
    for (bit in 0 until prefix) {
        val mask = 1 shl (7 - bit % 8)
        if ((network.bytes[bit / 8].toInt() and mask) != (address.bytes[bit / 8].toInt() and mask)) return false
    }
    return true
}

private val IPV4 = Regex("[0-9]+(\\.[0-9]+){3}")
