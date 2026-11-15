package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SidecarProtectServerTest {
    @Test
    fun onlySameUidAndExactlyOneDescriptorAreAccepted() {
        assertTrue(SidecarProtectServer.accepts(0, 1, true))
        assertTrue(SidecarProtectServer.accepts(1, 1, true))
        assertFalse(SidecarProtectServer.accepts(2, 1, true))
        assertFalse(SidecarProtectServer.accepts(-1, 1, true))
        assertFalse(SidecarProtectServer.accepts(1, 0, true))
        assertFalse(SidecarProtectServer.accepts(1, 2, true))
        assertFalse(SidecarProtectServer.accepts(1, 1, false))
    }
}
