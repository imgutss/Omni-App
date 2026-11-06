package com.v2ray.ang.ui.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** CSS angles are clockwise from up, and their line endpoints use projected magic corners.
 * A bounding-box diagonal is not the same gradient on a wide native card. */
internal fun cssGradientEndpoints(size:Size,degrees:Float):Pair<Offset,Offset> {
    val a=Math.toRadians(degrees.toDouble())
    val direction=Offset(sin(a).toFloat(),-cos(a).toFloat())
    val half=(size.width*abs(direction.x)+size.height*abs(direction.y))/2f
    val center=Offset(size.width/2f,size.height/2f)
    return (center-direction*half)to(center+direction*half)
}
internal fun cssLinearGradient(size:Size,degrees:Float,vararg stops:Pair<Float,Color>):Brush {
    val(start,end)=cssGradientEndpoints(size,degrees)
    return Brush.linearGradient(*stops,start=start,end=end)
}
