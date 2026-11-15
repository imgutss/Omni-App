package com.v2ray.ang.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OmniLatencyClassTest {
    @Test fun usesOriginalThresholdsOnlyForRealPositiveMeasurements() {
        assertEquals(0,omniLatencyClass(0));assertEquals(0,omniLatencyClass(-1))
        assertEquals(3,omniLatencyClass(1));assertEquals(3,omniLatencyClass(89))
        assertEquals(2,omniLatencyClass(90));assertEquals(2,omniLatencyClass(219))
        assertEquals(1,omniLatencyClass(220));assertEquals(1,omniLatencyClass(Long.MAX_VALUE))
    }
}
