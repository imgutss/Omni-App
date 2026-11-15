package com.v2ray.ang.core

import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.MasterDnsFmt

/**
 * MasterDNS currently has one direct upstream resolver path. It is therefore accepted only as the
 * standalone primary profile. Other placements are refused rather than silently bypassing a chain.
 */
sealed interface MasterDnsDependency {
    data object None : MasterDnsDependency
    data class Single(val core: MasterDnsCore) : MasterDnsDependency
    data object Unsupported : MasterDnsDependency
    data class Invalid(val problem: MasterDnsFmt.Problem) : MasterDnsDependency

    companion object {
        fun of(outbounds: List<CoreConfigContext.ResolvedOutbound>): MasterDnsDependency {
            val occurrences = outbounds.flatMap { outbound ->
                outbound.resolvedProfiles.filter { it.configType == EConfigType.MASTERDNS }
            }
            if (occurrences.isEmpty()) return None
            val primary = outbounds.firstOrNull()
            val hasAether = outbounds.any { outbound -> outbound.resolvedProfiles.any { it.configType == EConfigType.AETHER } }
            if (hasAether || occurrences.size != 1 || primary?.resolvedType != CoreResolvedType.NORMAL ||
                primary.resolvedProfiles.singleOrNull()?.configType != EConfigType.MASTERDNS
            ) {
                return Unsupported
            }
            val problem = MasterDnsFmt.normalize(occurrences.single())
            return if (problem != null) Invalid(problem) else Single(MasterDnsCore.of(occurrences.single()))
        }
    }
}
