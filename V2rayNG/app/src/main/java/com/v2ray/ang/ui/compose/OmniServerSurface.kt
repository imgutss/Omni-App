package com.v2ray.ang.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** Original server-row perspective and pointer reflection; never consumes selection/reorder gestures. */
@Composable
internal fun Modifier.referenceServerSurface(selected:Boolean):Modifier {
    if(!LocalDarkTheme.current)return referenceRaisedPanel(22.dp)
    val appearance=LocalOmniAppearance.current
    val accent=appearance.skin.accent
    val motion=appearance.effects!=OmniEffects.OFF && android.animation.ValueAnimator.areAnimatorsEnabled()
    var pressed by remember { mutableStateOf(false) }
    var point by remember { mutableStateOf(Offset(.5f,0f)) }
    val time by omniReferenceClock(selected)
    val rx by animateFloatAsState(if(motion && pressed)(point.y-.5f)*-10f else 0f,tween(if(pressed)80 else 450),label="server-rx")
    val ry by animateFloatAsState(if(motion && pressed)(point.x-.5f)*14f else 0f,tween(if(pressed)80 else 450),label="server-ry")
    val depth by animateFloatAsState(if(!motion){if(selected)12f else 0f}else if(pressed){if(selected)8f else -8f}else if(selected)12f else 0f,tween(if(pressed)80 else 450),label="server-depth")
    return graphicsLayer { rotationX=rx;rotationY=ry;cameraDistance=800f*density;scaleX=800f/(800f-depth);scaleY=scaleX }
        .pointerInput(motion) {
            if(motion)try {
                awaitEachGesture {
                    val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                    fun aim(at:Offset) { point=Offset((at.x/size.width.coerceAtLeast(1)).coerceIn(0f,1f),(at.y/size.height.coerceAtLeast(1)).coerceIn(0f,1f)) }
                    pressed=true;aim(down.position)
                    do { val event=awaitPointerEvent(PointerEventPass.Initial);event.changes.firstOrNull { it.pressed }?.let { aim(it.position) } } while(event.changes.any { it.pressed })
                    pressed=false
                }
            } finally { pressed=false }
        }.drawBehind {
            val r=CornerRadius(22.dp.toPx())
            drawRoundRect(Color.Black.copy(alpha=.6f),Offset(0f,3.dp.toPx()),size,r)
            drawRoundRect(Color(0xFF1F1F1F).copy(alpha=if(selected).65f else .6f),cornerRadius=r)
            drawRoundRect(cssLinearGradient(size,150f,0f to (if(selected)accent.copy(alpha=.18f)else Color.White.copy(alpha=.102f)),(if(selected).7f else .62f) to (if(selected)androidx.compose.ui.graphics.lerp(accent,Color.Black,.13f).copy(alpha=.1f)else Color.White.copy(alpha=.024f)),1f to (if(selected)androidx.compose.ui.graphics.lerp(accent,Color.Black,.13f).copy(alpha=.1f)else Color.White.copy(alpha=.024f))),cornerRadius=r)
            drawRoundRect(if(selected)accent.copy(alpha=.53f)else Color.White.copy(alpha=.12f),cornerRadius=r,style=Stroke(if(selected)1.5.dp.toPx()else 1.dp.toPx()))
        }.clip(RoundedCornerShape(22.dp)).drawWithContent {
            drawContent()
            if(motion && pressed) drawRect(Brush.radialGradient(0f to Color.White.copy(alpha=.18f),.62f to Color.Transparent,center=Offset(point.x*size.width,point.y*size.height),radius=200.dp.toPx()))
            if(motion && selected) {
                val phase=(time%4.8f)/4.8f
                val x=size.width*(-.7f+2.4f*(phase/.6f).coerceAtMost(1f))
                drawRect(Brush.linearGradient(listOf(Color.Transparent,Color.White.copy(alpha=.12f),Color.Transparent),Offset(x-size.width*.15f,0f),Offset(x+size.width*.15f,size.height)))
            }
        }
}

/** Latency class, not radio strength or a synthetic quality measurement. */
internal fun omniLatencyClass(milliseconds:Long):Int=when { milliseconds<=0L->0;milliseconds<90L->3;milliseconds<220L->2;else->1 }

@Composable
internal fun OmniLatencyBars(milliseconds:Long) {
    val quality=omniLatencyClass(milliseconds)
    val neutral=MaterialTheme.colorScheme.onSurface.copy(alpha=.15f)
    Canvas(Modifier.width(13.dp).height(12.dp)) {
        val color=when(quality) { 3->Color(0xFF3DDC97);2->Color(0xFFF5C451);1->Color(0xFFFF6B6B);else->neutral }
        listOf(5f,8f,12f).forEachIndexed { index,height ->
            drawRoundRect(if(index<quality)color else neutral,Offset(index*5.dp.toPx(),size.height-height.dp.toPx()),androidx.compose.ui.geometry.Size(3.dp.toPx(),height.dp.toPx()),CornerRadius(1.dp.toPx()))
        }
    }
}

/** Only genuinely observed, successful result changes are drawn. Never invent a ping waveform. */
@Composable
internal fun OmniObservedLatency(samples:List<Long>) {
    val ink=MaterialTheme.colorScheme.onSurface.copy(alpha=.45f)
    Canvas(Modifier.width(48.dp).height(14.dp)) {
        if(samples.isEmpty())return@Canvas
        val low=samples.minOrNull()!!.toDouble();val high=samples.maxOrNull()!!.toDouble()
        fun at(index:Int):Offset=Offset(if(samples.size==1)size.width else index*size.width/(samples.size-1),if(high==low)size.height/2f else (size.height*(.85-.7*(samples[index]-low)/(high-low))).toFloat())
        val color=ink
        if(samples.size==1) { drawCircle(color,1.4.dp.toPx(),at(0));return@Canvas }
        val path=Path();samples.indices.forEach { i->val p=at(i);if(i==0)path.moveTo(p.x,p.y)else path.lineTo(p.x,p.y) }
        drawPath(path,color,style=Stroke(1.4.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round,join=androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}
