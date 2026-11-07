package com.v2ray.ang.omni

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OmniPrivacyCenterLogicTest {
    @Test
    fun onlyActiveCapabilitiesAreCountedAsRevokablePairings() {
        val scopes = mapOf("active" to setOf("templates:read"), "empty" to emptySet(), "expired" to emptySet())
        assertEquals(setOf("active"), omniActivePairedPanelIds(scopes))
        assertEquals(OmniPrivacyMessage.REVOKED, omniPrivacyRunMessage(1, 0))
        assertEquals(OmniPrivacyMessage.PARTIAL, omniPrivacyRunMessage(2, 1))
        assertEquals(OmniPrivacyMessage.NO_PAIRINGS, omniPrivacyRunMessage(0, 0))
    }
}
