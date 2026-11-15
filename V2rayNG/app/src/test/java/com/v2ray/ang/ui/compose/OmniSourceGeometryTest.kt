package com.v2ray.ang.ui.compose

import androidx.compose.ui.geometry.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OmniSourceGeometryTest {
    private fun close(expected:Float,actual:Float)=assertEquals(expected,actual,.001f)
    @Test fun cssVerticalAndHorizontalAnglesUseCssNotCanvasConvention() {
        val size=Size(200f,100f)
        val(upStart,upEnd)=cssGradientEndpoints(size,0f)
        close(100f,upStart.x);close(100f,upStart.y);close(100f,upEnd.x);close(0f,upEnd.y)
        val(rightStart,rightEnd)=cssGradientEndpoints(size,90f)
        close(0f,rightStart.x);close(50f,rightStart.y);close(200f,rightEnd.x);close(50f,rightEnd.y)
        val(downStart,downEnd)=cssGradientEndpoints(size,180f)
        close(100f,downStart.x);close(0f,downStart.y);close(100f,downEnd.x);close(100f,downEnd.y)
    }
    @Test fun wideCardHasProjectedMagicCornersNotABoundingBoxDiagonal() {
        val(start,end)=cssGradientEndpoints(Size(200f,100f),135f)
        close(25f,start.x);close(-25f,start.y);close(175f,end.x);close(125f,end.y)
    }
    @Test fun dialogMatchesOriginalPerspectiveEntryAndFinalPose() {
        val start=omniDialogPose(0f)
        close(0f,start.alpha);close(.94f,start.scale);close(14f,start.rotationX);close(30f,start.translateY)
        val end=omniDialogPose(1f)
        close(1f,end.alpha);close(1f,end.scale);close(0f,end.rotationX);close(0f,end.translateY)
    }
    @Test fun sourceOvershootIsPreservedButAlphaCannotExceedOne() {
        val over=omniDialogPose(1.1f)
        close(1f,over.alpha);close(1.006f,over.scale);close(-1.4f,over.rotationX);close(-3f,over.translateY)
        assertEquals(omniDialogPose(1f),omniDialogPose(Float.NaN))
    }
    @Test fun terminalWipeRecedesUnderActualParentPerspective() {
        val start=omniLogWipePose(0f)
        close(1f,start.alpha);close(1f,start.scale);close(0f,start.translateY);close(640f,start.camera)
        val end=omniLogWipePose(1f)
        close(0f,end.alpha)
        org.junit.jupiter.api.Assertions.assertTrue(end.scale<.68f && end.scale>.66f)
        org.junit.jupiter.api.Assertions.assertTrue(end.translateY>39f && end.translateY<41f)
        org.junit.jupiter.api.Assertions.assertTrue(end.camera>950f && end.camera<960f)
        assertEquals(start,omniLogWipePose(Float.NaN))
    }
    @Test fun originalPrimaryButtonInksAreNotReplacedByBodyContrastColors() {
        assertEquals(androidx.compose.ui.graphics.Color.White,omniSourceOnAccent(OmniSkin.VIOLET))
        assertEquals(androidx.compose.ui.graphics.Color.Black,omniSourceOnAccent(OmniSkin.SPOTIFY))
        assertEquals(androidx.compose.ui.graphics.Color(0xFF052B27),omniSourceOnAccent(OmniSkin.TEAL))
    }
}
