package com.v2ray.ang.ui.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/** omni-skin .connect-fab plus omni-main's raised edges, conic border and active pulse. */
@Composable
internal fun Modifier.omniRaisedPower(running: Boolean): Modifier {
    val appearance = LocalOmniAppearance.current
    val accent = appearance.skin.accent
    val effects = appearance.effects
    val angle = if (effects != OmniEffects.OFF) {
        val transition = rememberInfiniteTransition(label = "reference-power-ring")
        val value by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(if (running) 2600 else 7000, easing = LinearEasing), RepeatMode.Restart), label = "conic-angle")
        value
    } else 0f
    val pulse = if (running && effects == OmniEffects.FULL) {
        val transition = rememberInfiniteTransition(label = "reference-power-pulse")
        val value by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "pulse-radius")
        value
    } else 0f
    return drawBehind {
        val radius = CornerRadius(16.dp.toPx())
        val depth = 4.5.dp.toPx()
        if (effects != OmniEffects.OFF) {
            val glowRadius = size.maxDimension * if (running) 1.5f else 1.1f
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = if (running) .45f else .12f), Color.Transparent), center, glowRadius), glowRadius)
            val inset = 7.dp.toPx()
            val stops = Array(65) { index ->
                val p = ((index / 64f - angle) % 1f + 1f) % 1f
                val alpha = when {
                    p < .38f -> 1f - p / .38f
                    p < .62f -> (p - .38f) / .24f * .5f
                    else -> .5f + (p - .62f) / .38f * .5f
                }
                index / 64f to accent.copy(alpha = alpha)
            }
            drawRoundRect(Brush.sweepGradient(*stops, center = center), Offset(-inset, -inset), Size(size.width + 2 * inset, size.height + 2 * inset), CornerRadius(23.dp.toPx()), style = Stroke(2.dp.toPx()))
            if (running && effects == OmniEffects.FULL) {
                val expansion = inset + pulse * 8.dp.toPx()
                drawRoundRect(accent.copy(alpha = (1f - pulse) * .4f), Offset(-expansion, -expansion), Size(size.width + 2 * expansion, size.height + 2 * expansion), CornerRadius(23.dp.toPx() + pulse * 8.dp.toPx()), style = Stroke(2.dp.toPx()))
            }
        }
        drawRoundRect(Color(0xFF121212), Offset(0f, depth), size, radius)
        drawRoundRect(Color(0xFF181818), Offset(0f, depth * .66f), size, radius)
        drawRoundRect(Brush.radialGradient(listOf(if (running) accent else Color(0xFF4D4D4D), if (running) lerp(accent, Color.Black, .22f) else Color(0xFF252525)), Offset(size.width * .3f, size.height * .22f), size.maxDimension * .8f), cornerRadius = radius)
        drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (running) .5f else .25f), Color.Transparent, Color.Black.copy(alpha = .5f))), cornerRadius = radius, style = Stroke(1.dp.toPx()))
    }
}
