package com.v2ray.ang.omni

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OmniScopedModelsTest {
    private val session = OmniScopedSession(
        panelId = "panel-1",
        accessToken = "token",
        userUuid = "user-1",
        scopes = setOf("templates:read", "scanner:publish"),
        expiresAt = 2_000L,
    )

    @Test
    fun scopeRequiresLiveBoundSession() {
        assertTrue(session.hasScope("scanner:publish", 1_000L))
        assertTrue(session.hasScope("templates:read", 1_999L))
        assertFalse(session.hasScope("device:report", 1_000L))
        assertFalse(session.hasScope("scanner:publish", 2_000L))
        assertFalse(session.hasScope("scanner:publish", 2_001L))
    }

    @Test
    fun templateCountIsReadOnlyMetadata() {
        val templates = OmniScopedTemplates(
            userUuid = "user-1",
            username = "ali",
            subscriptionUrl = "https://panel.example/s/token",
            groups = listOf(
                OmniScopedTemplateGroup("manual", listOf("vless://one", "vmess://two")),
                OmniScopedTemplateGroup("scanner", listOf("trojan://three")),
            ),
        )
        assertTrue(templates.uriCount == 3)
        assertTrue(templates.groups.map { it.source } == listOf("manual", "scanner"))
    }

    @Test
    fun publicSessionMetadataHasNoTokenAndDistinguishesExpiry() {
        val info = session.publicInfo()
        assertTrue(info.panelId == "panel-1")
        assertTrue(info.scopes == session.scopes)
        assertTrue(info.isActive(1_999L))
        assertFalse(info.isActive(2_000L))
        assertFalse(info.toString().contains("token"))
    }

    @Test
    fun blankTokenOrUserCannotAuthorize() {
        assertFalse(session.copy(accessToken = "").validAt(1_000L))
        assertFalse(session.copy(userUuid = "").validAt(1_000L))
        assertFalse(session.copy(accessToken = "").hasScope("scanner:publish", 1_000L))
    }
}
