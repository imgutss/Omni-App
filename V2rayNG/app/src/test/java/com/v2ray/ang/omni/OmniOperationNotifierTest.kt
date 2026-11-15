package com.v2ray.ang.omni

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OmniOperationNotifierTest {
    @Test
    fun notificationsRequireBothOptInAndSystemPermission() {
        assertFalse(omniOperationNotificationAllowed(false, false))
        assertFalse(omniOperationNotificationAllowed(false, true))
        assertFalse(omniOperationNotificationAllowed(true, false))
        assertTrue(omniOperationNotificationAllowed(true, true))
    }
}
