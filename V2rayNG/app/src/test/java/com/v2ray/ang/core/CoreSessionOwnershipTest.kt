package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreSessionOwnershipTest {
    @Test
    fun isolatedCallbacksDoNotDependOnTheForegroundSelection() {
        assertTrue(ownsCoreSession(isolated = true, selected = false, registered = true, stopRequested = false))
        assertFalse(ownsCoreSession(isolated = true, selected = true, registered = false, stopRequested = false))
        assertFalse(ownsCoreSession(isolated = true, selected = false, registered = true, stopRequested = true))
    }

    @Test
    fun foregroundCallbacksStillRequireTheExactSelectedSession() {
        assertTrue(ownsCoreSession(isolated = false, selected = true, registered = false, stopRequested = false))
        assertFalse(ownsCoreSession(isolated = false, selected = false, registered = true, stopRequested = false))
    }
}
