package com.v2ray.ang.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.v2ray.ang.ui.compose.OmniTextButton as TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import java.util.Locale

/** Projected terminal slab. Every line, count and measured rate comes from the real log viewer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniLogSurface(live: Boolean, onLive: (Boolean) -> Unit, onRefresh: () -> Unit, count: Int, rate: Double?, wipeProgress:Float=0f, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val accent=LocalOmniAppearance.current.skin.accent
    val ink=omniTerminalAccent(accent)
    val time by omniReferenceClock()
    val fx=LocalOmniAppearance.current.effects
    var x by remember { mutableFloatStateOf(0f) };var y by remember { mutableFloatStateOf(0f) }
    val rx by animateFloatAsState(if(fx==OmniEffects.OFF)0f else 5f-y*14f,label="log-slab-x")
    val ry by animateFloatAsState(if(fx==OmniEffects.OFF)0f else x*16f,label="log-slab-y")
    Column(modifier.fillMaxSize()
        .graphicsLayer { rotationX=rx;rotationY=ry;cameraDistance=1100f*density;translationY=if(fx==OmniEffects.OFF)0f else (-2.5f*(1f-kotlin.math.cos(time*2f*kotlin.math.PI.toFloat()/7f)))*density }
        .pointerInput(fx) {
            if(fx!=OmniEffects.OFF) try {
                awaitEachGesture {
                    val first=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                    fun aim(at:Offset) { x=(at.x/size.width.coerceAtLeast(1)-.5f).coerceIn(-.5f,.5f);y=(at.y/size.height.coerceAtLeast(1)-.5f).coerceIn(-.5f,.5f) }
                    aim(first.position)
                    do { val event=awaitPointerEvent(PointerEventPass.Initial);event.changes.firstOrNull { it.pressed }?.let { aim(it.position) } } while(event.changes.any { it.pressed })
                    x=0f;y=0f
                }
            } finally { x=0f;y=0f }
        }
        .drawBehind {
            if(fx!=OmniEffects.OFF) {
                val r=CornerRadius(35.dp.toPx())
                repeat(3) { i -> val spread=(3-i)*4.dp.toPx();drawRoundRect(accent.copy(alpha=.04f+i*.025f),Offset(-spread,-spread),Size(size.width+spread*2f,size.height+spread*2f),r) }
            }
        }
        .clip(RoundedCornerShape(32.dp))
        .background(Color(0xFF121212)).drawBehind {
            val radius=size.width*1.2f;val at=Offset(size.width/2f,0f)
            withTransform({ scale(1f,size.height*.7f/radius,at) }) {
                drawCircle(Brush.radialGradient(0f to Color(0xFF1F1F1F),.6f to Color(0xFF121212),1f to Color(0xFF121212),center=at,radius=radius),radius,at)
            }
        }
        .border(1.dp,Color.White.copy(alpha=.15f),RoundedCornerShape(32.dp))) {
        Row(Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,top=14.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            repeat(3) { Canvas(Modifier.size(10.dp)) { drawCircle(accent) } }
            Text("GoLog / Android",Modifier.weight(1f).padding(start=6.dp),fontFamily=FontFamily.Monospace,fontSize=11.sp,color=Color(0xFFB3B3B3),maxLines=1)
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                TextButton(onClick={onLive(!live)},modifier=Modifier.height(26.dp).border(1.dp,if(live)accent.copy(alpha=.4f)else Color(0x66FFA42B),RoundedCornerShape(99.dp)).background(if(live)accent.copy(alpha=.12f)else Color(0x1FFFA42B),RoundedCornerShape(99.dp)),contentPadding=PaddingValues(horizontal=10.dp,vertical=0.dp)) {
                    Canvas(Modifier.padding(end=6.dp).size(7.dp)) {
                        val ink=if(live)accent else Color(0xFFFFA42B)
                        if(live && fx!=OmniEffects.OFF) { val phase=(time%1.5f)/1.5f;drawCircle(ink.copy(alpha=.6f*(1f-phase)),size.width/2f+phase*9.dp.toPx(),style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())) }
                        drawCircle(ink)
                    }
                    Text(stringResource(if(live)R.string.omni_log_live else R.string.omni_log_paused),color=if(live)ink else Color(0xFFFFA42B),fontSize=10.sp,fontWeight=FontWeight.ExtraBold)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().graphicsLayer { compositingStrategy=CompositingStrategy.Offscreen }.drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(0f to Color.Transparent,.42f to Color.Black,1f to Color.Black),blendMode=BlendMode.DstIn)
        }) {
            val wipe=omniLogWipePose(wipeProgress)
            Box(Modifier.fillMaxSize().graphicsLayer {
                rotationX=if(fx==OmniEffects.OFF)0f else 16f;cameraDistance=wipe.camera*density
                transformOrigin=TransformOrigin(.5f,1f);alpha=wipe.alpha
                scaleX=wipe.scale;scaleY=wipe.scale;translationY=wipe.translateY*density
            }) {
                content()
                Canvas(Modifier.align(Alignment.BottomStart).padding(start=18.dp,bottom=6.dp).size(width=8.dp,height=15.dp)) {
                    if(fx==OmniEffects.OFF || (time*2f).toInt()%2==0)drawRect(accent)
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                var at=0f;val step=3.dp.toPx()
                while(at<size.height) { drawLine(Color.White.copy(alpha=.027f),Offset(0f,at),Offset(size.width,at),1.dp.toPx());at+=step }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start=18.dp,end=18.dp,top=8.dp,bottom=14.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(stringResource(R.string.omni_log_lines,count),fontSize=10.5.sp,fontFamily=FontFamily.Monospace,color=Color(0xFFB3B3B3))
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                IconButton(onClick=onRefresh,modifier=Modifier.size(16.dp)) {
                    Icon(painterResource(R.drawable.ic_restore_24dp),contentDescription=stringResource(R.string.acc_refresh),modifier=Modifier.size(14.dp),tint=Color(0xFFB3B3B3))
                }
            }
            Text(rate?.let { String.format(Locale.ROOT,"%.1f/s",it) }?:"—/s",fontSize=10.5.sp,fontFamily=FontFamily.Monospace,color=Color(0xFFB3B3B3))
        }
    }
}

/** Counts duplicate real arrivals too; historic bootstrap/filter changes are excluded by the caller. */
internal fun omniNewLogCount(previous: Map<String,Int>, current: Map<String,Int>): Int = current.entries.sumOf { (line,count) -> (count-(previous[line]?:0)).coerceAtLeast(0) }

/** The original diagonal clear-button highlight; it is only decoration, never a loading status. */
@Composable
fun Modifier.omniLogClearSheen(): Modifier {
    val fx=LocalOmniAppearance.current.effects
    val time by omniReferenceClock()
    return drawWithContent {
        drawContent()
        if(fx!=OmniEffects.OFF) {
            val phase=(time%4.5f)/4.5f
            val x=size.width*(.5f-1.2f+2.4f*(phase/.6f).coerceAtMost(1f))
            drawRect(Brush.linearGradient(listOf(Color.Transparent,Color.White.copy(alpha=.33f),Color.Transparent),Offset(x-size.width*.15f,0f),Offset(x+size.width*.15f,size.height)))
        }
    }
}

/** The terminal stays dark in both application modes; never render a light-scheme black label on it. */
internal fun omniTerminalAccent(accent:Color):Color {
    var ink=accent
    repeat(20) { if(omniContrast(ink,Color(0xFF1F1F1F))<4.5)ink=androidx.compose.ui.graphics.lerp(ink,Color.White,.1f) }
    return ink
}

internal data class OmniLogWipePose(val alpha:Float,val scale:Float,val translateY:Float,val camera:Float)
/** Parent perspective is 640px, origin at the list bottom; project the source
 * rotateX(16deg) translateZ(-320px) translateY(-30px), without fake 3D depth. */
internal fun omniLogWipePose(progress:Float):OmniLogWipePose {
    val p=if(progress.isFinite())progress.coerceIn(0f,1f)else 0f
    val angle=16f*kotlin.math.PI.toFloat()/180f
    val z=(320f*kotlin.math.cos(angle)+30f*kotlin.math.sin(angle))*p
    val y=(320f*kotlin.math.sin(angle)-30f*kotlin.math.cos(angle))*p
    val scale=640f/(640f+z)
    return OmniLogWipePose(1f-p,scale,y*scale,640f+z)
}
