package com.v2ray.ang.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Presentation only. Pointer observers do not consume clicks, scrolling, editing or drag events. */
@Composable
fun Modifier.referencePressTilt(): Modifier {
    if (LocalOmniAppearance.current.effects == OmniEffects.OFF) return this
    var pressed by remember { mutableStateOf(false) }
    var x by remember { mutableStateOf(0f) }
    var y by remember { mutableStateOf(0f) }
    val rx by animateFloatAsState(if (pressed) -y * 8f else 0f, label = "reference-tilt-x")
    val ry by animateFloatAsState(if (pressed) x * 8f else 0f, label = "reference-tilt-y")
    val depth by animateFloatAsState(if (pressed) 3f else 0f, label = "reference-press-depth")
    return graphicsLayer { rotationX = rx; rotationY = ry; translationY = depth * density; cameraDistance = 800f * density }
        .pointerInput(Unit) {
            try {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pressed = true
                    x = (first.position.x / size.width.coerceAtLeast(1) - .5f).coerceIn(-.5f, .5f)
                    y = (first.position.y / size.height.coerceAtLeast(1) - .5f).coerceIn(-.5f, .5f)
                    do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
                    pressed = false
                }
            } finally { pressed = false }
        }
}

@Composable
fun Modifier.referenceRaisedPanel(radius: Dp = 22.dp): Modifier {
    val colors = MaterialTheme.colorScheme
    val accent = LocalOmniAppearance.current.skin.accent
    val shape = RoundedCornerShape(radius)
    return referencePressTilt().drawBehind {
        val r = CornerRadius(radius.toPx())
        drawRoundRect(Color.Black.copy(alpha = .6f), Offset(0f, 3.dp.toPx()), size, r)
        drawRoundRect(colors.surfaceContainerLow.copy(alpha = .8f), cornerRadius = r)
        drawRoundRect(Brush.linearGradient(listOf(accent.copy(alpha = .12f), colors.onSurface.copy(alpha = .03f))), cornerRadius = r)
        drawRoundRect(Brush.verticalGradient(listOf(colors.onSurface.copy(alpha = .16f), colors.outlineVariant.copy(alpha = .4f))), cornerRadius = r, style = Stroke(1.dp.toPx()))
    }.clip(shape)
}

@Composable
fun Modifier.referenceRaisedControl(selected: Boolean, accent: Color = LocalOmniAppearance.current.skin.accent): Modifier {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    return referencePressTilt().drawBehind {
        val radius = CornerRadius(16.dp.toPx())
        drawRoundRect(Color.Black.copy(alpha = .55f), Offset(0f, 3.dp.toPx()), size, radius)
        drawRoundRect(if (selected) Brush.linearGradient(listOf(accent, lerp(accent, Color.Black, .28f))) else Brush.linearGradient(listOf(colors.onSurface.copy(alpha = .08f), colors.onSurface.copy(alpha = .025f))), cornerRadius = radius)
        drawRoundRect(if (selected) accent else colors.onSurface.copy(alpha = .12f), cornerRadius = radius, style = Stroke(1.dp.toPx()))
        drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .22f), Color.Transparent, Color.Black.copy(alpha = .25f))), cornerRadius = radius, style = Stroke(1.dp.toPx()))
    }.clip(shape)
}

@Composable
fun Modifier.omniIconChrome(): Modifier = padding(3.dp).referenceRaisedControl(false)
