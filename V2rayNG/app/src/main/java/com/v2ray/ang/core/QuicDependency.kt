package com.v2ray.ang.core

import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType

/**
 * The one QUIC-family sidecar session a configuration needs, if it needs one at all.
 *
 * TUIC, NaiveProxy and ShadowQUIC each ride a native client that opens a loopback SOCKS listener, so
 * like GUTS they may be the proxy itself or the transport a chain is dialled through (the last hop),
 * but not a hop in the middle of a chain, a member of a policy group, or a session beside another
 * native core. Only one QUIC sidecar owns a session, whatever mix of the three protocols appears.
 */
sealed interface QuicDependency {
    data object None : QuicDependency
    data class Single(val core: QuicCore) : QuicDependency
    data object Unsupported : QuicDependency
    data class Invalid(val problem: QuicCore.Problem) : QuicDependency

    companion object {
        /** The cores that own a session the way a QUIC sidecar does, and can therefore not share one. */
        private val OTHER_NATIVE = setOf(EConfigType.AETHER, EConfigType.MASTERDNS, EConfigType.DNSTT, EConfigType.GUTS)

        fun of(outbounds: List<CoreConfigContext.ResolvedOutbound>): QuicDependency {
            val occurrences = outbounds.flatMap { outbound ->
                outbound.resolvedProfiles.filter { QuicCore.isQuicType(it.configType) }
            }
            if (occurrences.isEmpty()) return None
            val primary = outbounds.firstOrNull() ?: return Unsupported
            val sharesWithAnotherNative = outbounds.any { outbound ->
                outbound.resolvedProfiles.any { it.configType in OTHER_NATIVE }
            }
            val placeable = primary.resolvedType == CoreResolvedType.NORMAL ||
                primary.resolvedType == CoreResolvedType.PROXYCHAIN
            val lastHopIsQuic = primary.resolvedProfiles.lastOrNull()?.let { QuicCore.isQuicType(it.configType) } == true
            if (sharesWithAnotherNative || occurrences.size != 1 || !placeable || !lastHopIsQuic) {
                return Unsupported
            }
            val normalized = occurrences.single().copy()
            val problem = QuicCore.normalize(normalized)
            return if (problem != null) Invalid(problem) else Single(QuicCore.of(normalized))
        }
    }
}
