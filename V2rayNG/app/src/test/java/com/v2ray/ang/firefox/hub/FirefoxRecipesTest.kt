package com.v2ray.ang.firefox.hub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Each recipe must actually set what its name promises, and leave the rest alone. */
class FirefoxRecipesTest {

    @Test
    fun `every recipe has a spec and an id`() {
        FirefoxRecipes.Recipe.entries.forEach { recipe ->
            assertNotNull(FirefoxRecipes.spec(recipe))
            assertEquals(recipe, FirefoxRecipes.byId(recipe.id))
        }
        assertNull(FirefoxRecipes.byId("does_not_exist"))
    }

    @Test
    fun `privacy uses encrypted DNS only and no system fallback`() {
        val spec = FirefoxRecipes.spec(FirefoxRecipes.Recipe.PRIVACY)
        assertEquals(false, spec.fallbackToSystem)
        assertNotNull(spec.dnsServers)
        assertTrue(spec.dnsServers!!.all { it.startsWith("https://") })
        assertEquals("sni", spec.helloSplit)
    }

    @Test
    fun `gaming races the fastest location`() {
        val spec = FirefoxRecipes.spec(FirefoxRecipes.Recipe.GAMING)
        assertEquals("fastest", spec.country)
        assertEquals("h2", spec.transport)
    }

    @Test
    fun `bypass arms every DNS path and the relay`() {
        val spec = FirefoxRecipes.spec(FirefoxRecipes.Recipe.BYPASS)
        assertEquals(true, spec.fallbackToSystem)
        assertEquals(true, spec.sharedRelayEnabled)
        assertTrue(spec.dnsServers!!.any { it.startsWith("udp://") })
        assertTrue(spec.dnsServers!!.any { it.startsWith("tls://") })
        assertTrue(spec.dnsServers!!.any { it.startsWith("https://") })
    }

    @Test
    fun `save quota raises the switch threshold`() {
        val spec = FirefoxRecipes.spec(FirefoxRecipes.Recipe.SAVE_QUOTA)
        assertEquals(95.0, spec.switchThresholdPercent)
        assertNull(spec.transport)
        assertNull(spec.dnsServers)
    }

    @Test
    fun `streaming keeps a stable tunnel with a self-rescuing DNS`() {
        val spec = FirefoxRecipes.spec(FirefoxRecipes.Recipe.STREAMING)
        assertEquals("h2", spec.transport)
        assertEquals(true, spec.fallbackToSystem)
        assertFalse(spec.helloSplit == "sni")
    }

    // Regression for the v1.2.0 Tools-tab bug: recipe fields were written at the top level of the
    // config, and the engine rejected them with `unknown field "transport"`. Every value must be
    // nested under its config section, and only the sections the engine knows may appear.

    @Test
    fun `every patch uses only the engine's config sections`() {
        val allowed = setOf("proxy", "network", "accounts", "dns")
        FirefoxRecipes.Recipe.entries.forEach { recipe ->
            val patch = FirefoxRecipes.patch(recipe)
            assertTrue(patch.keys.all { it in allowed }, "recipe $recipe wrote sections ${patch.keys}")
        }
    }

    @Test
    fun `no patch writes a flat top-level setting`() {
        // These are the exact keys that used to be written flat and crash the Tools tab.
        val flatKeys = setOf("transport", "country", "hello_split", "switch_threshold_percent", "shared_relay_enabled")
        FirefoxRecipes.Recipe.entries.forEach { recipe ->
            val patch = FirefoxRecipes.patch(recipe)
            assertTrue(patch.keys.none { it in flatKeys }, "recipe $recipe wrote a flat key")
        }
    }

    @Test
    fun `gaming nests transport and country under proxy`() {
        val patch = FirefoxRecipes.patch(FirefoxRecipes.Recipe.GAMING)
        assertEquals("h2", patch["proxy"]?.get("transport"))
        assertEquals("fastest", patch["proxy"]?.get("country"))
    }

    @Test
    fun `privacy nests hello split under network and DNS under dns`() {
        val patch = FirefoxRecipes.patch(FirefoxRecipes.Recipe.PRIVACY)
        assertEquals("sni", patch["network"]?.get("hello_split"))
        assertEquals(false, patch["dns"]?.get("fallback_to_system"))
        assertTrue(patch["dns"]?.get("servers") is List<*>)
    }

    @Test
    fun `save quota nests the threshold under accounts`() {
        val patch = FirefoxRecipes.patch(FirefoxRecipes.Recipe.SAVE_QUOTA)
        assertEquals(95.0, patch["accounts"]?.get("switch_threshold_percent"))
    }

    @Test
    fun `bypass nests the shared relay under network`() {
        val patch = FirefoxRecipes.patch(FirefoxRecipes.Recipe.BYPASS)
        assertEquals(true, patch["network"]?.get("shared_relay_enabled"))
        assertEquals("sni", patch["network"]?.get("hello_split"))
    }

    @Test
    fun `patch field names match the engine's JSON tags`() {
        val allowedFields = mapOf(
            "proxy" to setOf("transport", "country"),
            "network" to setOf("hello_split", "shared_relay_enabled"),
            "accounts" to setOf("switch_threshold_percent"),
            "dns" to setOf("servers", "fallback_to_system"),
        )
        FirefoxRecipes.Recipe.entries.forEach { recipe ->
            FirefoxRecipes.patch(recipe).forEach { (section, fields) ->
                assertTrue(fields.keys.all { it in allowedFields.getValue(section) },
                    "recipe $recipe wrote unknown field(s) ${fields.keys} in $section")
            }
        }
    }
}
