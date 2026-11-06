package com.v2ray.ang.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.animation.ValueAnimator
import android.view.HapticFeedbackConstants
import android.os.Build
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Visual requests are independent of the VPN state machine: never manufacture a connected state. */
internal data class OmniStrike(val origin: Offset, val turningOn: Boolean, val nonce: Long = System.nanoTime())
internal val LocalOmniStrike = staticCompositionLocalOf<(OmniStrike, () -> Unit) -> Unit> { { _, action -> action() } }
internal data class BoltSegment(val a: Offset, val b: Offset, val distance: Float, val rootDistance: Float, val generation: Int, val weight: Float)

/** Native counterpart of omni-zap-js chan/tree. Coordinates are HTML CSS pixels (Android dp). */
internal fun omniBoltTree(start: Offset, end: Offset, seed: Int): List<BoltSegment> {
    val random = Random(seed)
    fun range(a: Float, b: Float) = a + random.nextFloat() * (b - a)
    fun channel(a: Offset, b: Offset, initial: Float, levels: Int): List<Offset> {
        var points = listOf(a, b)
        var amplitude = initial
        repeat(levels) {
            val next = ArrayList<Offset>(points.size * 2)
            next.add(points.first())
            points.zipWithNext().forEach { (u, v) ->
                val delta = v - u
                val length = hypot(delta.x, delta.y).coerceAtLeast(1f)
                val offset = range(-1f, 1f) * amplitude * length
                next.add((u + v) / 2f + Offset(-delta.y / length * offset, delta.x / length * offset))
                next.add(v)
            }
            points = next
            amplitude *= .7f
        }
        return points
    }
    val result = ArrayList<BoltSegment>()
    val main = channel(start, end, .2f, 7)
    fun add(points: List<Offset>, distance: Float, root: Float, generation: Int, weight: Float): Float {
        var d = distance
        points.zipWithNext().forEach { (u, v) ->
            d += (v - u).getDistance()
            result.add(BoltSegment(u, v, d, if (generation == 0) d else root, generation, weight))
        }
        return d
    }
    val length = add(main, 0f, 0f, 0, 1f)
    val angle = kotlin.math.atan2(end.y - start.y, end.x - start.x)
    fun fork(points: List<Offset>, distance: Float, root: Float, generation: Int, weight: Float) {
        if (generation > 2) return
        var d = distance
        for (i in 1 until points.lastIndex) {
            d += (points[i] - points[i - 1]).getDistance()
            if (i < points.size * .1f || random.nextFloat() > if (generation > 0) .04f else .075f) continue
            val direction = angle + (if (random.nextBoolean()) -1 else 1) * range(.3f, .95f)
            val branchLength = range(.06f, .26f) * length / (generation + 1)
            val q = points[i]
            val branch = channel(q, q + Offset(cos(direction), sin(direction)) * branchLength, .22f, 5)
            val attachment = if (generation > 0) root else d
            add(branch, d, attachment, generation + 1, weight * .55f)
            fork(branch, d, attachment, generation + 1, weight * .55f)
        }
    }
    fork(main, 0f, 0f, 0, 1f)
    return result
}

internal data class OmniCorona(val points: List<Offset>, val start: Float, val duration: Float)
internal fun omniCorona(origin: Offset, turningOn: Boolean, seed: Int): List<OmniCorona> {
    val random = Random(seed)
    fun range(a: Float, b: Float) = a + random.nextFloat() * (b - a)
    return List(if (turningOn) 14 else 9) {
        val angle = range(0f, 6.283f); val length = range(28f, 84f)
        val direction = Offset(cos(angle), sin(angle))
        var points = listOf(origin + direction * 22f, origin + direction * (22f + length))
        var amplitude = .25f
        repeat(4) {
            val next = ArrayList<Offset>(); next.add(points.first())
            points.zipWithNext().forEach { (a, b) ->
                val delta = b - a; val size = delta.getDistance().coerceAtLeast(1f)
                val offset = range(-1f, 1f) * amplitude * size
                next.add((a + b) / 2f + Offset(-delta.y / size * offset, delta.x / size * offset)); next.add(b)
            }
            points = next; amplitude *= .7f
        }
        OmniCorona(points, (if (turningOn) 150f else 110f) + range(0f, if (turningOn) 800f else 500f), range(70f, 170f))
    }
}

/** Full-window, non-intercepting native Canvas. No WebView and no synthetic network events. */
@Composable
internal fun OmniLightningHost(content: @Composable () -> Unit) {
    val effects = LocalOmniAppearance.current.effects
    val density = LocalDensity.current.density
    val context = LocalContext.current.applicationContext
    val view = LocalView.current
    LaunchedEffect(effects) {
        if (effects == OmniEffects.FULL) withContext(Dispatchers.IO) { OmniThunder.preload(context) }
    }
    var request by remember { mutableStateOf<OmniStrike?>(null) }
    var elapsed by remember { mutableStateOf(2000f) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(request?.nonce, effects) {
        val event = request ?: return@LaunchedEffect
        if (effects == OmniEffects.OFF || !ValueAnimator.areAnimatorsEnabled()) {
            val action = pending; pending = null; action?.invoke(); request = null; return@LaunchedEffect
        }
        val duration = if (event.turningOn) 1650f else 1260f
        // A real coroutine timer, not frame-counting: the genuine action cannot become stale if backgrounded.
        val impact = launch {
            delay(if (event.turningOn) 150L else 110L)
            val action = pending; pending = null
            if (effects == OmniEffects.FULL) OmniThunder.play(context, event.turningOn)
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) {
                if (event.turningOn) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT
            } else HapticFeedbackConstants.VIRTUAL_KEY)
            action?.invoke()
        }
        var beginning = 0L
        try {
            do {
                withFrameNanos { now ->
                    if (beginning == 0L) beginning = now
                    elapsed = (now - beginning) / 1_000_000f
                }
            } while (elapsed < duration && ValueAnimator.areAnimatorsEnabled())
            // Complete once even if OS animation settings change during the 110/150ms lead-in.
            val action = pending; pending = null; action?.invoke()
        } finally { impact.cancel() }
        request = null
    }
    val event = request
    val channel = remember(event, density) {
        event?.let {
            val target = it.origin / density
            val random = Random(it.nonce.toInt())
            val sky = Offset(target.x + random.nextFloat() * 140f - 70f, -20f)
            omniBoltTree(if (it.turningOn) sky else target, if (it.turningOn) target else sky, it.nonce.toInt())
        }.orEmpty()
    }
    val coronas = remember(event, density) { event?.let { omniCorona(it.origin / density, it.turningOn, it.nonce.toInt() + 1) }.orEmpty() }
    CompositionLocalProvider(LocalOmniStrike provides { strike, action ->
        if (effects == OmniEffects.OFF || !ValueAnimator.areAnimatorsEnabled()) action()
        else if (pending == null) { pending = action; elapsed = 0f; request = strike }
    }) {
        Box(Modifier.fillMaxSize()) {
            val impactTime = if (event?.turningOn == true) 150f else 110f
            val shakeAge = elapsed - impactTime
            val shake = if (event != null && effects == OmniEffects.FULL && shakeAge in 0f..420f) {
                val index = (shakeAge / (420f / 9f)).toInt().coerceAtMost(8)
                val random = Random(event.nonce.toInt() + index)
                val amplitude = (if (event.turningOn) 7f else 4f) * (1f - shakeAge / 420f) * density
                Offset((random.nextFloat() * 2f - 1f) * amplitude, (random.nextFloat() * 2f - 1f) * amplitude * .8f)
            } else Offset.Zero
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = shake.x; translationY = shake.y }) { content() }
            if (event != null && effects != OmniEffects.OFF) Canvas(Modifier.fillMaxSize()) {
                val on = event.turningOn
                val impact = if (on) 150f else 110f
                val age = elapsed - impact
                val length = channel.filter { it.generation == 0 }.lastOrNull()?.distance ?: 1f
                val leaderSpeed = length / impact
                val returnSpeed = length / (impact * .3f)
                val restrikes = if (on) listOf(95f to .7f, 190f to .5f, 310f to .32f) else listOf(110f to .45f, 230f to .25f)
                fun decay(t: Float, tau: Float) = if (t < 0f) 0f else exp(-t / tau)
                val flash = (decay(age, 55f) + restrikes.sumOf { (delay, strength) -> (strength * decay(age - delay, 35f)).toDouble() }.toFloat()).coerceAtMost(1.3f)
                val layers = if (on) listOf(Color(0xFF6E7DFF), Color(0xFF96AFFF), Color(0xFFCDDEFF)) else listOf(Color(0xFFFF5F96), Color(0xFFFF96BE), Color(0xFFFFD7E8))
                val tint = layers[1]
                fun glow(point: Offset, radius: Float, color: Color, alpha: Float) {
                    if (alpha < .01f) return
                    val a = alpha.coerceAtMost(1f)
                    drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = a), .3f to color.copy(alpha = a * .6f), 1f to Color.Transparent, center = point, radius = radius), radius, point, blendMode = BlendMode.Plus)
                }
                fun bolt(path: Path, brightness: Float, width: Float) {
                    listOf(9f to .07f, 4.5f to .16f, 2.2f to .6f, .9f to 1f).forEachIndexed { i, (spread, alpha) ->
                        val color = if (i == 3) Color.White.copy(alpha = (brightness * 1.15f).coerceIn(0f, 1f)) else layers[i].copy(alpha = (brightness * alpha).coerceIn(0f, 1f))
                        drawPath(path, color, style = Stroke(spread * width, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
                    }
                }
                val impactColor = if (on) Color(0xFF2EE59D) else Color(0xFFFF7850)
                if (!on && age > 0f) drawRect(Color(0xFF00000C).copy(alpha = .5f * exp(-age / 520f) * (.7f + .3f * sin(age * .045f))))
                drawRect((if (on) Color(0xFFBECDFF) else Color(0xFFFFBED7)).copy(alpha = (.22f * flash).coerceIn(0f, 1f)), blendMode = BlendMode.Plus)
                val sky = (if (on) channel.firstOrNull()?.a else channel.lastOrNull { it.generation == 0 }?.b)?.times(density) ?: Offset.Zero
                withTransform({ scale(2.6f, 1f, sky) }) { glow(sky, 150f * density, Color(0xFFAAB9FF), (if (elapsed < impact) .12f else 0f) + .55f * flash) }
                val groups = arrayOfNulls<Path>(24)
                val tips = ArrayList<BoltSegment>()
                channel.forEachIndexed { segmentIndex, segment ->
                    val step = (segment.distance / 46f).toInt()
                    val leaderTime = (step * 46f / leaderSpeed + step % 3 * 1.5f).coerceAtMost(impact * 1.15f)
                    if (elapsed >= leaderTime && elapsed - leaderTime < 7f && tips.size < 16) tips.add(segment)
                    val returnTime = impact + (length - segment.rootDistance) / returnSpeed + (segment.distance - segment.rootDistance) / returnSpeed * .4f
                    var brightness = .2f * decay(elapsed - leaderTime, 70f) + decay(elapsed - returnTime, 38f) + .28f * decay(elapsed - returnTime, 240f)
                    restrikes.forEach { (delay, strength) ->
                        val t = elapsed - impact - delay - (length - segment.rootDistance) / (returnSpeed * 1.5f)
                        brightness += strength * (decay(t, 30f) + .2f * decay(t, 120f))
                    }
                    brightness *= segment.weight.pow(.6f)
                    if (brightness >= .04f) {
                        if (segment.generation == 0 && segmentIndex % 12 == 0 && brightness > .25f) glow(segment.b * density, 100f * density, tint, (.14f * brightness).coerceAtMost(.5f))
                        val key = segment.generation.coerceAtMost(2) * 8 + (brightness / .2f).toInt().coerceAtMost(7)
                        val path = groups[key] ?: Path().also { groups[key] = it }
                        path.moveTo(segment.a.x * density, segment.a.y * density); path.lineTo(segment.b.x * density, segment.b.y * density)
                    }
                }
                groups.forEachIndexed { key, path -> if (path != null) bolt(path, ((key % 8) + .5f) * .2f, floatArrayOf(2.4f, 1.5f, .9f)[key / 8] * density) }
                tips.forEach { glow(it.b * density, (if (it.generation == 0) 30f else 16f) * density, Color(0xFFDCE6FF), .9f) }
                glow(event.origin, (if (on) 140f else 110f) * density, impactColor, .95f * flash)
                withTransform({ scale(6f, .16f, event.origin) }) { glow(event.origin, 60f * density, Color(0xFFD2E4FF), .9f * flash) }
                coronas.forEach { arc ->
                    val progress = (elapsed - arc.start) / arc.duration
                    if (progress in 0f..1f) {
                        val path = Path(); arc.points.forEachIndexed { i, point -> if (i == 0) path.moveTo(point.x * density, point.y * density) else path.lineTo(point.x * density, point.y * density) }
                        bolt(path, sin(kotlin.math.PI.toFloat() * progress) * (.6f + .4f * sin(elapsed * 1.7f + arc.duration)), .8f * density)
                    }
                }

                val expansion = age / 700f
                if (on && expansion > 0f && expansion < 1f) drawCircle(impactColor.copy(alpha = .8f * (1f - expansion)), (22f + expansion * 190f) * density, event.origin, style = androidx.compose.ui.graphics.drawscope.Stroke((4f * (1f - expansion) + 1f) * density), blendMode = BlendMode.Plus)
                // Ballistic hot particles, using the reference's gravity and lifetime ranges.
                val random = Random(event.nonce.toInt())
                repeat(if (on) 40 else 22) {
                    val angle = -3.3f + random.nextFloat() * 3.5f
                    val speed = 110f + random.nextFloat() * 410f
                    val lifetime = 500f + random.nextFloat() * 800f
                    val width = .8f + random.nextFloat() * 1.4f
                    if (age >= 0f && age <= lifetime) {
                        fun position(seconds: Float): Offset {
                            val drag = seconds * (1f - .5f * seconds)
                            return event.origin + Offset(cos(angle) * speed * drag, sin(angle) * speed * drag + 520f * seconds * seconds) * density
                        }
                        val progress = age / lifetime
                        drawLine(Color(1f, 1f - .59f * progress, .86f - .82f * progress, 1f - progress), position((age / 1000f - .025f).coerceAtLeast(0f)), position(age / 1000f), width * density, StrokeCap.Round, blendMode = BlendMode.Plus)
                    }
                }
            }
        }
    }
}
