package com.v2ray.ang.core

import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.MasterDnsFmt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MasterDnsDependencyTest {
    private val master = ProfileItem.create(EConfigType.MASTERDNS).apply {
        remarks = "dns"
        masterDnsConfig = """{"DOMAINS":["v.example.com"],"DATA_ENCRYPTION_METHOD":1,"ENCRYPTION_KEY":"secret"}"""
        masterDnsResolvers = "8.8.8.8"
    }
    private val vless = ProfileItem.create(EConfigType.VLESS).apply {
        remarks = "vless"
        server = "192.0.2.1"
        serverPort = "443"
    }
    private val aether = ProfileItem.create(EConfigType.AETHER).apply { remarks = "aether" }

    private fun outbound(type: CoreResolvedType, vararg profiles: ProfileItem) =
        CoreConfigContext.ResolvedOutbound("proxy", profiles.first(), profiles.toList(), type)

    @Test
    fun noMasterDnsProfileNeedsNoCore() {
        assertEquals(MasterDnsDependency.None, MasterDnsDependency.of(emptyList()))
        assertEquals(MasterDnsDependency.None, MasterDnsDependency.of(listOf(outbound(CoreResolvedType.NORMAL, vless))))
    }

    @Test
    fun aStandalonePrimaryProfileGetsTheOfficialCore() {
        val dependency = MasterDnsDependency.of(listOf(outbound(CoreResolvedType.NORMAL, master)))
        assertTrue(dependency is MasterDnsDependency.Single)
        assertEquals(18000, (dependency as MasterDnsDependency.Single).core.port)
        assertEquals("8.8.8.8\n", dependency.core.resolvers)
    }

    @Test
    fun masterDnsIsNotSilentlyPlacedInAChainGroupOrRoutingTarget() {
        assertEquals(
            MasterDnsDependency.Unsupported,
            MasterDnsDependency.of(listOf(outbound(CoreResolvedType.PROXYCHAIN, vless, master))),
        )
        assertEquals(
            MasterDnsDependency.Unsupported,
            MasterDnsDependency.of(listOf(outbound(CoreResolvedType.POLICYGROUP, master, vless))),
        )
        assertEquals(
            MasterDnsDependency.Unsupported,
            MasterDnsDependency.of(listOf(outbound(CoreResolvedType.NORMAL, vless), outbound(CoreResolvedType.NORMAL, master))),
        )
    }

    @Test
    fun aSecondMasterOrAnAetherPlacementIsRefused() {
        assertEquals(
            MasterDnsDependency.Unsupported,
            MasterDnsDependency.of(listOf(outbound(CoreResolvedType.NORMAL, master, master))),
        )
        assertEquals(
            MasterDnsDependency.Unsupported,
            MasterDnsDependency.of(listOf(outbound(CoreResolvedType.NORMAL, master), outbound(CoreResolvedType.NORMAL, aether))),
        )
    }

    @Test
    fun invalidDocumentsAreReturnedAsALocalizedRefusalProblem() {
        val invalid = master.copy(masterDnsConfig = """{"DOMAINS":[],"ENCRYPTION_KEY":"secret"}""")
        val dependency = MasterDnsDependency.of(listOf(outbound(CoreResolvedType.NORMAL, invalid)))
        assertEquals(MasterDnsDependency.Invalid(MasterDnsFmt.Problem.DOMAINS_MISSING), dependency)
    }
}
