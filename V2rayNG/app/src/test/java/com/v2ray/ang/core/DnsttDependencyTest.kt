package com.v2ray.ang.core

import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.DnsttFmt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DnsttDependencyTest {
    private val key = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    private val dnstt = DnsttFmt.fromValues(
        "dns", "udp", "1.1.1.1", "t.example.com", key,
    )!!
    private val vless = ProfileItem.create(EConfigType.VLESS).apply {
        remarks = "vless"
        server = "192.0.2.1"
        serverPort = "443"
    }
    private val master = ProfileItem.create(EConfigType.MASTERDNS)
    private val aether = ProfileItem.create(EConfigType.AETHER)

    private fun outbound(type: CoreResolvedType, vararg profiles: ProfileItem) =
        CoreConfigContext.ResolvedOutbound("proxy", profiles.first(), profiles.toList(), type)

    @Test
    fun noDnsttProfileNeedsNoCore() {
        assertEquals(DnsttDependency.None, DnsttDependency.of(emptyList()))
        assertEquals(DnsttDependency.None, DnsttDependency.of(listOf(outbound(CoreResolvedType.NORMAL, vless))))
    }

    @Test
    fun standalonePrimaryProfileGetsTheOfficialCoreAndLoopbackPort() {
        val dependency = DnsttDependency.of(listOf(outbound(CoreResolvedType.NORMAL, dnstt)))
        assertTrue(dependency is DnsttDependency.Single)
        assertEquals(18001, (dependency as DnsttDependency.Single).core.port)
        assertEquals("1.1.1.1:53", dependency.core.resolver)
        assertEquals(DnsttFmt.Transport.UDP, dependency.core.transport)
    }

    @Test
    fun dnsttIsNotSilentlyPlacedInAChainGroupOrBesideAnotherNativeCore() {
        assertEquals(
            DnsttDependency.Unsupported,
            DnsttDependency.of(listOf(outbound(CoreResolvedType.PROXYCHAIN, vless, dnstt))),
        )
        assertEquals(
            DnsttDependency.Unsupported,
            DnsttDependency.of(listOf(outbound(CoreResolvedType.POLICYGROUP, dnstt, vless))),
        )
        assertEquals(
            DnsttDependency.Unsupported,
            DnsttDependency.of(listOf(outbound(CoreResolvedType.NORMAL, dnstt), outbound(CoreResolvedType.NORMAL, master))),
        )
        assertEquals(
            DnsttDependency.Unsupported,
            DnsttDependency.of(listOf(outbound(CoreResolvedType.NORMAL, dnstt), outbound(CoreResolvedType.NORMAL, aether))),
        )
    }

    @Test
    fun invalidProfileReturnsTheSpecificLocalizedProblem() {
        val invalid = dnstt.copy(dnsttPublicKey = "not-a-key")
        assertEquals(
            DnsttDependency.Invalid(DnsttFmt.Problem.PUBLIC_KEY_INVALID),
            DnsttDependency.of(listOf(outbound(CoreResolvedType.NORMAL, invalid))),
        )
    }
}
