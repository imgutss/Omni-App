package com.v2ray.ang.core

import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType

/** Only one standalone primary GUTS outbound is currently safe to start. */
sealed interface GutsDependency {
    data object None : GutsDependency
    data class Single(val core: GutsCore) : GutsDependency
    data object Unsupported : GutsDependency
    data class Invalid(val problem: GutsCore.Problem) : GutsDependency

    companion object {
        fun of(outbounds: List<CoreConfigContext.ResolvedOutbound>): GutsDependency {
            val occurrences = outbounds.flatMap { outbound ->
                outbound.resolvedProfiles.filter { it.configType == EConfigType.GUTS }
            }
            if (occurrences.isEmpty()) return None
            val primary = outbounds.firstOrNull()
            val hasOtherNative = outbounds.any { outbound ->
                outbound.resolvedProfiles.any {
                    it.configType == EConfigType.AETHER ||
                        it.configType == EConfigType.MASTERDNS ||
                        it.configType == EConfigType.DNSTT
                }
            }
            if (hasOtherNative || occurrences.size != 1 || primary?.resolvedType != CoreResolvedType.NORMAL ||
                primary.resolvedProfiles.singleOrNull()?.configType != EConfigType.GUTS
            ) {
                return Unsupported
            }
            val normalized = occurrences.single().copy()
            val problem = GutsCore.normalize(normalized)
            return if (problem != null) Invalid(problem) else Single(GutsCore.of(normalized))
        }
    }
}
