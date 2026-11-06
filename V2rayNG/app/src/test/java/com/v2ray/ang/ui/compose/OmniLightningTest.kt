package com.v2ray.ang.ui.compose

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class OmniLightningTest {
    @Test fun referenceChannelHas128MainSegmentsAndExactEndpoints() {
        val start = Offset(150f, -20f)
        val end = Offset(300f, 700f)
        val segments = omniBoltTree(start, end, 42)
        val main = segments.filter { it.generation == 0 }
        assertEquals(128, main.size)
        assertEquals(start, main.first().a)
        assertEquals(end, main.last().b)
        assertTrue(main.zipWithNext().all { (a, b) -> a.b == b.a && b.distance > a.distance })
    }
    @Test fun seededGeometryIsStableAndFinite() {
        val start = Offset(1f, -20f); val end = Offset(180f, 600f)
        assertEquals(omniBoltTree(start, end, 123), omniBoltTree(start, end, 123))
        repeat(30) { seed ->
            val segments = omniBoltTree(start, end, seed)
            assertTrue(segments.all { it.a.x.isFinite() && it.a.y.isFinite() && it.b.x.isFinite() && it.b.y.isFinite() })
            assertTrue(segments.all { it.generation in 0..3 && it.weight > 0f })
            assertTrue(segments.any { it.generation > 0 })
        }
    }
}
