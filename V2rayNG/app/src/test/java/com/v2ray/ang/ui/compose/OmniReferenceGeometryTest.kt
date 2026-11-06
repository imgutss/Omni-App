package com.v2ray.ang.ui.compose

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OmniReferenceGeometryTest {
    @Test fun perspectiveMatchesOriginalSceneProjection() {
        val flat = omniProject(20f,10f,0f,0f,0f,400f,200f)
        assertEquals(220f,flat.x,.001f);assertEquals(110f,flat.y,.001f)
        val far = omniProject(20f,10f,100f,0f,0f,400f,200f)
        assertEquals(.75f,far.scale,.001f);assertEquals(215f,far.x,.001f);assertEquals(107.5f,far.y,.001f)
        val turned = omniProject(20f,0f,0f,(Math.PI/2).toFloat(),0f,400f,200f)
        assertEquals(200f,turned.x,.001f);assertEquals(-20f,turned.depth,.001f)
    }
    @Test fun allEightOriginalScenesArePresent() { assertEquals(8,OmniScene.entries.size) }
    @Test fun coronasHaveOriginalCountsSubdivisionsAndBounds() {
        val origin=Offset(200f,700f)
        listOf(true,false).forEach { on ->
            val arcs=omniCorona(origin,on,131)
            assertEquals(if(on)14 else 9,arcs.size)
            arcs.forEach { arc ->
                assertEquals(17,arc.points.size)
                assertEquals(22f,(arc.points.first()-origin).getDistance(),.001f)
                assertTrue((arc.points.last()-origin).getDistance() in 49.99f..106.01f)
                assertTrue(arc.duration in 70f..170f)
                assertTrue(arc.start in if(on)150f..950f else 110f..610f)
                assertTrue(arc.points.all { it.x.isFinite()&&it.y.isFinite() })
            }
            assertEquals(arcs,omniCorona(origin,on,131))
        }
    }
    @Test fun uidRatesUseRealElapsedTimeAndNeverInventUnsupportedOrResetSamples() {
        assertEquals(1024.0,omniByteRate(100L,1124L,1000L)!!,.00001)
        assertEquals(512.0,omniByteRate(100L,1124L,2000L)!!,.00001)
        assertEquals(0.0,omniByteRate(100L,100L,1000L)!!,.00001)
        assertNull(omniByteRate(-1L,100L,1000L));assertNull(omniByteRate(100L,-1L,1000L))
        assertNull(omniByteRate(100L,90L,1000L));assertNull(omniByteRate(100L,200L,0L))
        assertNull(omniByteRate(100L,200L,-100L))
    }
}
