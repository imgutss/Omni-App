package com.v2ray.ang.core

import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.DnsttFmt

/** Only one standalone primary DNSTT outbound is currently safe to start. */
sealed interface DnsttDependency {
    data object None : DnsttDependency
    data class Single(val core: DnsttCore) : DnsttDependency
    data object Unsupported : DnsttDependency
    data class Invalid(val problem: DnsttFmt.Problem) : DnsttDependency

    companion object {
        fun of(outbounds: List<CoreConfigContext.ResolvedOutbound>): DnsttDependency {
            val occurrences = outbounds.flatMap { outbound ->
                outbound.resolvedProfiles.filter { it.configType == EConfigType.DNSTT }
            }
            if (occurrences.isEmpty()) return None
            val primary = outbounds.firstOrNull()
            val hasOtherNative = outbounds.any { outbound ->
                outbound.resolvedProfiles.any {
                    it.configType == EConfigType.AETHER || it.configType == EConfigType.MASTERDNS
                }
            }
            if (hasOtherNative || occurrences.size != 1 || primary?.resolvedType != CoreResolvedType.NORMAL ||
                primary.resolvedProfiles.singleOrNull()?.configType != EConfigType.DNSTT
            ) {
                return Unsupported
            }
            val normalized = occurrences.single().copy()
            val problem = DnsttFmt.normalize(normalized)
            return if (problem != null) Invalid(problem) else Single(DnsttCoreManager.coreOf(normalized))
        }
    }
}
