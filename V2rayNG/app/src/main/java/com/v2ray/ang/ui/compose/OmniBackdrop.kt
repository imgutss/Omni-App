package com.v2ray.ang.ui.compose

import android.animation.ValueAnimator
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalOmniBackdropScroll = staticCompositionLocalOf<MutableFloatState?> { null }

/** Actual --art, final two overlay gradients, screen blur, device tilt and server-list parallax. */
@Composable
internal fun OmniBackdrop() {
    val appearance = LocalOmniAppearance.current
    val dark = LocalDarkTheme.current
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current.density
    val scroll = LocalOmniBackdropScroll.current
    var target by remember { mutableStateOf(Offset.Zero) }
    val motion = dark && appearance.effects != OmniEffects.OFF && ValueAnimator.areAnimatorsEnabled()
    val tiltX by animateFloatAsState(if (motion) target.x else 0f, tween(200), label = "reference-wall-x")
    val tiltY by animateFloatAsState(if (motion) target.y else 0f, tween(200), label = "reference-wall-y")
    DisposableEffect(lifecycle, motion) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val matrix = FloatArray(9); val angles = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                SensorManager.getOrientation(matrix, angles)
                val beta = -angles[1] * 180f / kotlin.math.PI.toFloat()
                val gamma = angles[2] * 180f / kotlin.math.PI.toFloat()
                target = Offset((gamma / 60f).coerceIn(-.5f, .5f) * -18f, ((beta - 50f) / 60f).coerceIn(-.5f, .5f) * -12f)
            }
        }
        var registered = false
        fun update() {
            val wanted = motion && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (wanted && !registered && sensor != null) registered = manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI) == true
            else if (!wanted && registered) { manager?.unregisterListener(listener); registered = false }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer); update()
        onDispose { lifecycle.removeObserver(observer); manager?.unregisterListener(listener) }
    }
    val image by produceState<ImageBitmap?>(null) {
        value = withContext(Dispatchers.IO) {
            context.assets.open("omni-reference/backdrop.webp").use { requireNotNull(BitmapFactory.decodeStream(it)).asImageBitmap() }
        }
    }
    Box(Modifier.fillMaxSize().background(if (dark) appearance.skin.referencePalette.background else Color.White)) {
        if (dark) {
            image?.let { art ->
                val blur = if (appearance.effects == OmniEffects.FULL) Modifier.blur(10.dp) else Modifier
                Image(art, contentDescription = null, contentScale = ContentScale.Crop,
                    alignment = BiasAlignment(-.36f, -1f),
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = 1.1124f; scaleY = 1.1124f
                        translationX = tiltX * density
                        translationY = (tiltY + if (motion) scroll?.floatValue ?: 0f else 0f) * density
                    }.then(blur))
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFF121212).copy(alpha = .2f), .4f to Color(0xFF121212).copy(alpha = .4f), 1f to Color(0xFF121212).copy(alpha = .7f))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFF0B0C0F).copy(alpha = .72f), .55f to Color(0xFF0B0C0F).copy(alpha = .95f), 1f to Color(0xFF0B0C0F).copy(alpha = .95f))))
        }
    }
}
