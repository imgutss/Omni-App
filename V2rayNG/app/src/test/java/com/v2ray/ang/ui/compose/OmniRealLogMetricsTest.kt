package com.v2ray.ang.ui.compose

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OmniRealLogMetricsTest {
    @Test fun allTerminalAccentsRemainReadableInBothApplicationModes() {
        OmniSkin.entries.forEach { skin ->
            assertTrue(omniContrast(omniTerminalAccent(skin.accent),Color(0xFF1F1F1F)) >= 4.5,skin.id)
        }
    }
    @Test fun unchangedSnapshotIsNotAnArrival() {
        val snapshot=mapOf("real timestamped line" to 1,"duplicate" to 2)
        assertEquals(0,omniNewLogCount(snapshot,snapshot))
    }
    @Test fun repeatedRealLinesAreNotLost() {
        assertEquals(3,omniNewLogCount(mapOf("duplicate" to 2),mapOf("duplicate" to 4,"new" to 1)))
    }
    @Test fun BufferEvictionAndClearAreNeverNegativeArrivals() {
        assertEquals(0,omniNewLogCount(mapOf("old" to 4),emptyMap()))
        assertEquals(1,omniNewLogCount(mapOf("evicted" to 8),mapOf("fresh" to 1)))
        assertEquals(0,omniNewLogCount(mapOf("same" to 8),mapOf("same" to 2)))
    }
}
