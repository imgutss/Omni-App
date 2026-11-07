package com.v2ray.ang.omni.lab

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OmniExitConfigurationTest {
    @Test
    fun replacesFirstOutboundAndPreservesRoutingAndFreedom() {
        val original = """
            {"outbounds":[{"protocol":"vless","settings":{"id":"u"},"streamSettings":{"network":"ws"},"tag":"original"},{"protocol":"freedom","tag":"direct"}],"routing":{"rules":[{"type":"field","outboundTag":"direct"}]},"dns":{"servers":["1.1.1.1"]}}
        """.trimIndent()
        val root = JsonParser.parseString(createOmniExitConfiguration(original, 10808)).asJsonObject
        val outbounds = root.getAsJsonArray("outbounds")
        assertEquals(3, outbounds.size())
        assertEquals("proxy", outbounds[0].asJsonObject.get("tag").asString)
        assertEquals("vless", outbounds[0].asJsonObject.get("protocol").asString)
        assertEquals("omni-exit-loopback", outbounds[0].asJsonObject.getAsJsonObject("streamSettings").getAsJsonObject("sockopt").get("tag").asString)
        assertEquals("direct", outbounds[1].asJsonObject.get("tag").asString)
        assertEquals("omni-exit-loopback", outbounds[2].asJsonObject.get("tag").asString)
        assertTrue(root.has("routing"))
        assertTrue(root.has("dns"))
    }

    @Test
    fun nativeOpenExitKeepsDependenciesAndUsesExitNodeTag() {
        val root = JsonParser.parseString(createOmniNativeExitConfiguration("{\"outbounds\":[{\"protocol\":\"vless\",\"tag\":\"proxy\"},{\"protocol\":\"freedom\",\"tag\":\"direct\"}],\"routing\":{}}")).asJsonObject
        assertEquals("exit-node", root.getAsJsonArray("outbounds")[0].asJsonObject.get("tag").asString)
        assertEquals("direct", root.getAsJsonArray("outbounds")[1].asJsonObject.get("tag").asString)
        assertTrue(root.has("routing"))
    }

    @Test
    fun rejectsConfigurationWithoutOutbounds() {
        assertThrows(IllegalArgumentException::class.java) {
            createOmniExitConfiguration("{}", 10808)
        }
    }
}
