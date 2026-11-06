package com.v2ray.ang.ui.main

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.LocalOmniAppearance
import com.v2ray.ang.ui.compose.OmniEffects
import com.v2ray.ang.ui.compose.rememberOmniTelemetry
import java.util.Locale

/** Final .st-card geometry, with real OS service uptime and UID traffic, never HTML random metrics. */
@Composable
internal fun MainConnectionCard(displayText: String, isRunning: Boolean) {
    val accent = LocalOmniAppearance.current.skin.accent
    val telemetry = rememberOmniTelemetry(isRunning)
    val uptimeDescription = stringResource(R.string.omni_service_uptime)
    val trafficDescription = stringResource(R.string.omni_app_traffic_speed)
    val clock = telemetry.uptimeSeconds?.let { String.format(Locale.ROOT, "%02d:%02d", it / 60L, it % 60L) } ?: "—"
    val speed = telemetry.bytesPerSecond?.let { if (it >= 1048576.0) String.format(Locale.ROOT,"%.1f MB/s",it/1048576.0) else String.format(Locale.ROOT,"%.0f KB/s",it/1024.0) } ?: "—"
    val effects = LocalOmniAppearance.current.effects
    val pulse = if (isRunning && effects != OmniEffects.OFF) {
        val transition = rememberInfiniteTransition(label = "reference-status-ring")
        val opacity by transition.animateFloat(1f, .45f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "ring-opacity")
        opacity
    } else 1f
    Surface(Modifier.fillMaxWidth().padding(start = 26.dp, end = 26.dp, top = 8.dp, bottom = 10.dp),
        shape = RoundedCornerShape(22.dp), color = Color.White.copy(alpha = .04f), border = BorderStroke(1.dp, Color.White.copy(alpha = .063f))) {
        Box(Modifier.height(82.dp)) {
            Row(Modifier.fillMaxSize().padding(start = 14.dp,end = 14.dp,top = 12.dp,bottom = 22.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Canvas(Modifier.size(46.dp)) {
                    drawCircle(Color.White.copy(alpha = .078f), 18f / 44f * size.width, style = Stroke(3.dp.toPx()))
                    if (isRunning) drawCircle(accent.copy(alpha = pulse), 18f / 44f * size.width, style = Stroke(3.dp.toPx()))
                    if (isRunning && effects != OmniEffects.OFF) drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .6f),Color.Transparent), center, 21.dp.toPx()), 21.dp.toPx())
                    drawCircle(if (isRunning) accent else Color.White.copy(alpha = .15f), 7.dp.toPx())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(displayText, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp,fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (isRunning) stringResource(R.string.omni_app_traffic_short) else stringResource(R.string.omni_connect_hint), style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(clock, Modifier.semantics { contentDescription = "$uptimeDescription: $clock" }, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp,fontWeight = FontWeight.Bold))
                    Text(speed, Modifier.semantics { contentDescription = "$trafficDescription: $speed" }, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Canvas(Modifier.fillMaxWidth().height(30.dp).align(Alignment.BottomCenter)) {
                if (telemetry.bytesPerSecond == null) return@Canvas
                val values = List((40 - telemetry.history.size).coerceAtLeast(0)) { 0.0 } + telemetry.history
                if (values.size < 2) return@Canvas
                val maximum = values.maxOrNull()?.coerceAtLeast(51200.0) ?: 51200.0
                fun point(i: Int) = Offset(size.width * i / (values.size - 1), size.height * (31f - (values[i] / maximum).toFloat() * 26f) / 34f)
                val line = Path(); val first = point(0); line.moveTo(first.x, first.y)
                for (i in 1 until values.size) { val a=point(i-1);val b=point(i);line.quadraticBezierTo(a.x,a.y,(a.x+b.x)/2f,(a.y+b.y)/2f) }
                val last=point(values.lastIndex);line.lineTo(last.x,last.y)
                val area=Path().apply { addPath(line);lineTo(size.width,size.height);lineTo(0f,size.height);close() }
                drawPath(area,accent.copy(alpha=.126f));drawPath(line,accent.copy(alpha=.9f),style=Stroke(1.6.dp.toPx()))
            }
        }
    }
}
