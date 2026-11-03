package com.v2ray.ang.core

import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType

/**
 * The one GUTS session a configuration needs, if it needs one at all.
 *
 * GUTS is a TCP tunnel to an exit node: it can carry any TCP stream, so it does not have to be the
 * proxy itself. It may also be the transport a chain is dialled through — VLESS, VMess, Trojan or
 * Shadowsocks at the front, this tunnel underneath, the exit node out at the end. What it may not
 * be is a hop in the middle of a chain (every hop before the last is dialled through the one after
 * it, so a tunnel in the middle carries a tunnel that carries a tunnel, which nobody asked for), a
 * member of a policy group (a group needs its members to be dialable on their own), or a session
 * beside another native core (only one native core owns a session).
 */
sealed interface GutsDependency {
    data object None : GutsDependency
    data class Single(val core: GutsCore) : GutsDependency
    data object Unsupported : GutsDependency
    data class Invalid(val problem: GutsCore.Problem) : GutsDependency

    companion object {
        /** The cores that own a session the way GUTS does, and can therefore not share one. */
        private val OTHER_NATIVE = setOf(EConfigType.AETHER, EConfigType.MASTERDNS, EConfigType.DNSTT)

        fun of(outbounds: List<CoreConfigContext.ResolvedOutbound>): GutsDependency {
            val occurrences = outbounds.flatMap { outbound ->
                outbound.resolvedProfiles.filter { it.configType == EConfigType.GUTS }
            }
            if (occurrences.isEmpty()) return None
            val primary = outbounds.firstOrNull() ?: return Unsupported
            val sharesWithAnotherNative = outbounds.any { outbound ->
                outbound.resolvedProfiles.any { it.configType in OTHER_NATIVE }
            }
            val placeable = primary.resolvedType == CoreResolvedType.NORMAL ||
                primary.resolvedType == CoreResolvedType.PROXYCHAIN
            // The last hop is the one Xray dials itself; every hop before it is dialled through the
            // hop after it. So GUTS can be the transport of a chain only at its end.
            val lastHopIsGuts = primary.resolvedProfiles.lastOrNull()?.configType == EConfigType.GUTS
            if (sharesWithAnotherNative || occurrences.size != 1 || !placeable || !lastHopIsGuts) {
                return Unsupported
            }
            val normalized = occurrences.single().copy()
            val problem = GutsCore.normalize(normalized)
            return if (problem != null) Invalid(problem) else Single(GutsCore.of(normalized))
        }
    }
}
