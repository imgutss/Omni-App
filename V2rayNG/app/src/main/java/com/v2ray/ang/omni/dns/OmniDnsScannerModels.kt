package com.v2ray.ang.omni.dns

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.DnsttFmt
import com.v2ray.ang.fmt.MasterDnsFmt
import com.v2ray.ang.omni.lab.OmniLatencyStats
import com.v2ray.ang.omni.lab.OmniScanTarget

data class OmniDnsE2eResult(
    val stats: OmniLatencyStats,
    val httpStatus: Int? = null,
    val error: String? = null,
) {
    val success: Boolean get() = stats.succeeded > 0
}

fun dnsCandidateProfile(template: ProfileItem, target: OmniScanTarget): ProfileItem {
    if (template.configType == EConfigType.MASTERDNS) {
        val candidate = template.copy(masterDnsResolvers = target.endpointId + "\n")
        require(MasterDnsFmt.normalize(candidate) == null) { "The resolver candidate is not valid for this profile" }
        return candidate
    }
    require(template.configType == EConfigType.DNSTT) { "Unsupported DNS tunnel profile" }
    val engine = template.dnsttEngine?.trim()?.lowercase()?.ifEmpty { DnsttFmt.ENGINE_OFFICIAL } ?: DnsttFmt.ENGINE_OFFICIAL
    val originalEndpoints = DnsttFmt.engineResolver(template.dnsttResolver.orEmpty()).split(',')
    val originalAuthorities = DnsttFmt.resolverAuthorities(template)
    val matchedIndex = originalEndpoints.indexOfFirst { it == target.endpointId }
    val matchedAuthority = originalAuthorities.getOrNull(matchedIndex)
    val hasPerResolverAuthorities = originalEndpoints.isNotEmpty() && originalAuthorities.size == originalEndpoints.size
    val sharedAuthority = originalAuthorities.takeIf { hasPerResolverAuthorities && it.distinct().size == 1 }?.first()
    val authoritative = matchedAuthority ?: sharedAuthority
        ?: if (hasPerResolverAuthorities) false else template.dnsttAuthoritative == true
    val candidate = template.copy(
        dnsttEngine = engine,
        dnsttTransport = DnsttFmt.Transport.UDP.type,
        dnsttResolver = target.endpointId,
        dnsttResolverAuthorities = if (engine == DnsttFmt.ENGINE_OFFICIAL) null else if (authoritative) "1" else "0",
        dnsttAuthoritative = authoritative,
        dnsttResolverMode = "fanout",
        dnsttRrSpreadCount = if (engine == DnsttFmt.ENGINE_OFFICIAL) 3 else 1,
    )
    require(DnsttFmt.normalize(candidate) == null) { "The resolver candidate is not valid for this profile" }
    return candidate
}

fun bestDnsResolver(
    results: List<OmniDnsResolverResult>,
    e2e: Map<String, OmniDnsE2eResult>,
): OmniDnsResolverResult? = results.filter { e2e[it.target.endpointId]?.success == true }.minWithOrNull(
    compareByDescending<OmniDnsResolverResult> { e2e[it.target.endpointId]?.stats?.successRate ?: 0.0 }
        .thenBy { e2e[it.target.endpointId]?.stats?.medianMs ?: Long.MAX_VALUE }
        .thenBy { e2e[it.target.endpointId]?.stats?.jitterMs ?: Long.MAX_VALUE }
        .thenBy { it.target.endpointId },
)

fun rankDnsResolverResults(
    results: List<OmniDnsResolverResult>,
    e2e: Map<String, OmniDnsE2eResult>,
): List<OmniDnsResolverResult> = results.sortedWith(
    compareByDescending<OmniDnsResolverResult> { e2e[it.target.endpointId]?.success == true }
        .thenByDescending { e2e[it.target.endpointId]?.stats?.successRate ?: 0.0 }
        .thenBy { e2e[it.target.endpointId]?.stats?.medianMs ?: Long.MAX_VALUE }
        .thenByDescending { it.working }
        .thenByDescending { it.stats.successRate }
        .thenBy { it.stats.medianMs ?: Long.MAX_VALUE }
        .thenBy { it.stats.jitterMs ?: Long.MAX_VALUE }
        .thenBy { it.target.endpointId },
)
