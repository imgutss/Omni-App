package com.v2ray.ang.ui.compose

import android.animation.ValueAnimator
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.omni.OmniDoctorStage
import com.v2ray.ang.omni.OmniDoctorStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

@Composable
internal fun omniReferenceClock(active: Boolean = true): State<Float> {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val animated=active && LocalOmniAppearance.current.effects!=OmniEffects.OFF && ValueAnimator.areAnimatorsEnabled()
    return produceState(1.4f,lifecycle,animated) {
        if(animated) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val start=System.nanoTime()
            while(true)withFrameNanos { value=(it-start)/1_000_000_000f }
        }
    }
}

/** Original orbital map. Filled nodes count real panels; no fabricated reachable status or sync. */
@Composable
fun OmniHubOrbit(panelCount: Int, busy: Boolean, lastSyncAt: Long = 0L, modifier: Modifier = Modifier) {
    val time by omniReferenceClock()
    val context=LocalContext.current.applicationContext
    val bitmap by produceState<ImageBitmap?>(null) { value=withContext(Dispatchers.IO) { context.assets.open("omni-reference/logo.webp").use { requireNotNull(BitmapFactory.decodeStream(it)).asImageBitmap() } } }
    val green=Color(0xFF1ED760);val blue=Color(0xFF539DF5)
    Box(modifier.fillMaxWidth().height(300.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val unit=density;val w=size.width/unit;val h=size.height/unit
            withTransform({ scale(unit,unit,Offset.Zero) }) {
                val cx=w/2f;val cy=h*.42f;val radius=min(w*.38f,150f);val ry=radius*.42f
                val color=if(busy)Color(0xFFFFA42B)else green
                withTransform({ scale(1f,.42f,Offset(cx,cy)) }) {
                    drawCircle(Brush.radialGradient(0f to green.copy(alpha=.22f),.65f to green.copy(alpha=.09f),1f to Color.Transparent,center=Offset(cx,cy),radius=radius*1.4f),radius*1.4f,Offset(cx,cy))
                }
                drawOval(green.copy(alpha=.38f),Offset(cx-radius,cy-ry),Size(radius*2f,ry*2f),style=Stroke(1f))
                drawOval(blue.copy(alpha=.5f),Offset(cx-radius*.6f,cy-ry*.6f),Size(radius*1.2f,ry*1.2f),style=Stroke(1f,pathEffect=PathEffect.dashPathEffect(floatArrayOf(3f,6f))))
                data class Node(val index:Int,val position:Offset,val depth:Float)
                val nodes=(0..2).map { k -> val a=time*.35f+k*2.0944f;Node(k,Offset(cx+cos(a)*radius,cy+sin(a)*ry),(sin(a)+1f)/2f) }
                fun node(n:Node) {
                    val s=.7f+.55f*n.depth;val alpha=.5f+.5f*n.depth
                    if(n.index<panelCount) {
                        drawLine(Brush.linearGradient(listOf(color,green.copy(alpha=.1f)),n.position,Offset(cx,cy)),n.position,Offset(cx,cy),1.5f)
                        repeat(3) { i -> val u=(time*(if(busy)1.7f else .3f)+i/3f)%1f;val at=n.position+(Offset(cx,cy)-n.position)*u;drawCircle(Color(0xFF80E8A5),2.6f,at) }
                        drawCircle(Brush.radialGradient(listOf(color.copy(alpha=.4f),Color.Transparent),n.position,26f*s),26f*s,n.position)
                        drawCircle(color.copy(alpha=alpha),10f*s,n.position);drawCircle(Color(0xFF181818),4f*s,n.position)
                    } else {
                        drawCircle(Color(0xFF95C2F9).copy(alpha=.55f*alpha),9f*s,n.position,style=Stroke(1.2f,pathEffect=PathEffect.dashPathEffect(floatArrayOf(3f,3f))))
                        drawLine(blue.copy(alpha=.55f*alpha),n.position-Offset(3.5f*s,0f),n.position+Offset(3.5f*s,0f),1.2f)
                        drawLine(blue.copy(alpha=.55f*alpha),n.position-Offset(0f,3.5f*s),n.position+Offset(0f,3.5f*s),1.2f)
                    }
                }
                nodes.filter{it.depth<.5f}.forEach(::node)
                val phase=(time*.5f)%1f;val r=34f+phase*46f
                drawOval(green.copy(alpha=.5f*(1f-phase)),Offset(cx-r,cy-r*.42f*1.1f),Size(r*2f,r*.42f*2.2f),style=Stroke(1.5f))
                drawRoundRect(Color(0xFF1F1F1F),Offset(cx-32f,cy-32f),Size(64f,64f),CornerRadius(20f))
                bitmap?.let { image ->
                    val path=Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(cx-32f,cy-32f,cx+32f,cy+32f,CornerRadius(20f))) }
                    clipPath(path) { drawImage(image,dstOffset=IntOffset((cx-32f).toInt(),(cy-32f).toInt()),dstSize=IntSize(64,64)) }
                }
                drawRoundRect(Color(0xFF7FE8A5).copy(alpha=.6f),Offset(cx-32f,cy-32f),Size(64f,64f),CornerRadius(20f),style=Stroke(1.5f))
                nodes.filter{it.depth>=.5f}.forEach(::node)
            }
        }
        Text(stringResource(R.string.omni_hub_panel_count, panelCount),Modifier.align(Alignment.TopStart).padding(14.dp).referenceRaisedPanel(16.dp).padding(horizontal=13.dp,vertical=9.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
        Column(Modifier.align(Alignment.BottomCenter).padding(horizontal=26.dp,vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.title_omni_panel),style=MaterialTheme.typography.headlineSmall,color=LocalOmniAppearance.current.skin.accent,fontWeight=FontWeight.Bold)
            if(lastSyncAt > 0L) Text(stringResource(R.string.omni_hub_last_sync, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(lastSyncAt))),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(busy) Text(stringResource(R.string.omni_hub_working),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Nine real stage statuses feed the segmented ring. The ECG is decorative, never a test result. */
@Composable
fun OmniDoctorHero(stages: List<OmniDoctorStage>, running: Boolean, modifier: Modifier = Modifier) {
    val time by omniReferenceClock(running);val accent=LocalOmniAppearance.current.skin.accent
    val passed=stages.count{it.status==OmniDoctorStatus.PASS};val failed=stages.count{it.status==OmniDoctorStatus.FAIL}
    val skipped=stages.count{it.status==OmniDoctorStatus.SKIPPED};val warn=stages.count{it.status==OmniDoctorStatus.WARN}
    val error=MaterialTheme.colorScheme.error
    Column(modifier.fillMaxWidth().referenceRaisedPanel(28.dp).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(148.dp),contentAlignment=Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val radius=62.dp.toPx();val topLeft=center-Offset(radius,radius)
                    repeat(9) { i ->
                        val color=when(stages.getOrNull(i)?.status) {
                            OmniDoctorStatus.PASS,OmniDoctorStatus.RUNNING->accent
                            OmniDoctorStatus.FAIL->error;OmniDoctorStatus.WARN->Color(0xFFFFA42B)
                            OmniDoctorStatus.SKIPPED->Color(0xFFB3B3B3);else->Color.White.copy(alpha=.11f)
                        }
                        drawArc(color,-90f+i*40f+4.16f,26.14f,false,topLeft,Size(radius*2f,radius*2f),style=Stroke(9.dp.toPx(),cap=StrokeCap.Round))
                    }
                }
                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("$passed/9",style=MaterialTheme.typography.headlineMedium,color=accent,fontWeight=FontWeight.Bold)
                    Text(stringResource(R.string.omni_doctor_pass),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                listOf(Triple(passed,R.string.omni_doctor_pass,accent),Triple(failed,R.string.omni_doctor_fail,error),Triple(skipped,R.string.omni_doctor_skip,Color(0xFFB3B3B3)),Triple(warn,R.string.omni_doctor_warn,Color(0xFFFFA42B))).forEach { (count,label,color) ->
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Canvas(Modifier.size(10.dp)) { drawCircle(color) }
                        Text(count.toString(),fontWeight=FontWeight.Bold,color=color)
                        Text(stringResource(label),style=MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        if(running)Text(stringResource(R.string.omni_doctor_running),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val line=Path();val middle=size.height*.6f;val amplitude=size.height*.5f
            fun gaussian(x:Float,mean:Float,sigma:Float):Float { val d=(x-mean)/sigma;return exp(-d*d) }
            val cycle=if(running)(time%1.8f)else 0f
            var x=0f
            while(x<=size.width) {
                val d=cycle-(size.width-x)/density/110f
                val v=if(running && d in 0f.. .5f) .12f*gaussian(d,.05f,.03f)-.12f*gaussian(d,.17f,.012f)+gaussian(d,.2f,.014f)-.25f*gaussian(d,.23f,.014f)+.25f*gaussian(d,.38f,.05f) else 0f
                val y=middle-v*amplitude;if(x==0f)line.moveTo(x,y)else line.lineTo(x,y);x+=2.dp.toPx()
            }
            drawPath(line,Brush.horizontalGradient(listOf(Color.Transparent,accent.copy(alpha=.85f),accent)),style=Stroke(2.dp.toPx(),join=StrokeJoin.Round))
        }
    }
}
