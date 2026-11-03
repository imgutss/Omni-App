package com.v2ray.ang.core

import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where a GUTS profile may sit in a configuration.
 *
 * It may be the profile itself, or the last step of a chain — the tunnel a VLESS, VMess or Trojan
 * server is dialled through, which is the whole point of having a TCP tunnel that is not a proxy
 * protocol. It may not be a hop in the middle, a member of a group, or a second native core in a
 * session, and these tests say so one placement at a time.
 */
class GutsDependencyTest {

    private fun guts(doors: String? = "https://script.google.com/macros/s/A/exec", key: String? = "a-long-enough-shared-secret") =
        ProfileItem.create(EConfigType.GUTS).apply {
            gutsDoors = doors
            gutsKey = key
        }

    private fun proxy(type: EConfigType = EConfigType.VLESS) =
        ProfileItem.create(type).apply {
            server = "203.0.113.7"
            serverPort = "443"
        }

    private fun resolved(
        vararg profiles: ProfileItem,
        type: CoreResolvedType = CoreResolvedType.NORMAL,
    ): CoreConfigContext.ResolvedOutbound = CoreConfigContext.ResolvedOutbound(
        tag = "proxy",
        profile = profiles.first(),
        resolvedProfiles = profiles.toList(),
        resolvedType = type,
    )

    @Test
    fun gutsAloneIsTheOneCoreTheConfigurationNeeds() {
        val dependency = GutsDependency.of(listOf(resolved(guts())))
        assertTrue(dependency is GutsDependency.Single, "got $dependency")
    }

    @Test
    fun gutsAsTheLastStepOfAChainCarriesTheProxyAboveIt() {
        val dependency = GutsDependency.of(
            listOf(resolved(proxy(), guts(), type = CoreResolvedType.PROXYCHAIN))
        )
        assertTrue(dependency is GutsDependency.Single, "a chain ending in GUTS was refused: $dependency")
    }

    @Test
    fun aChainWithGutsInTheMiddleIsRefused() {
        val dependency = GutsDependency.of(
            listOf(resolved(proxy(), guts(), proxy(EConfigType.TROJAN), type = CoreResolvedType.PROXYCHAIN))
        )
        assertEquals(GutsDependency.Unsupported, dependency)
    }

    @Test
    fun aChainThatDialsGutsFirstIsRefused() {
        // The first hop is the front of the chain, not its transport: nothing would carry it.
        val dependency = GutsDependency.of(
            listOf(resolved(guts(), proxy(), type = CoreResolvedType.PROXYCHAIN))
        )
        assertEquals(GutsDependency.Unsupported, dependency)
    }

    @Test
    fun aGroupHoldingGutsIsRefused() {
        val dependency = GutsDependency.of(
            listOf(resolved(guts(), proxy(), type = CoreResolvedType.POLICYGROUP))
        )
        assertEquals(GutsDependency.Unsupported, dependency)
    }

    @Test
    fun gutsBesideAnotherNativeCoreIsRefused() {
        val aether = ProfileItem.create(EConfigType.AETHER)
        val dependency = GutsDependency.of(
            listOf(resolved(proxy()), resolved(aether), resolved(guts(), type = CoreResolvedType.NORMAL))
        )
        assertEquals(GutsDependency.Unsupported, dependency)
    }

    @Test
    fun twoGutsProfilesCannotShareOneSession() {
        val dependency = GutsDependency.of(
            listOf(
                resolved(proxy(), guts(), type = CoreResolvedType.PROXYCHAIN),
                resolved(guts(key = "another-secret-entirely"), type = CoreResolvedType.NORMAL),
            )
        )
        assertEquals(GutsDependency.Unsupported, dependency)
    }

    @Test
    fun aChainEndingInAnUnusableGutsProfileSaysWhatIsMissing() {
        val dependency = GutsDependency.of(
            listOf(resolved(proxy(), guts(key = null), type = CoreResolvedType.PROXYCHAIN))
        )
        assertEquals(GutsDependency.Invalid(GutsCore.Problem.KEY_MISSING), dependency)
    }

    @Test
    fun aConfigurationWithoutGutsNeedsNoSession() {
        assertEquals(GutsDependency.None, GutsDependency.of(listOf(resolved(proxy()))))
        assertEquals(GutsDependency.None, GutsDependency.of(emptyList()))
    }
}
