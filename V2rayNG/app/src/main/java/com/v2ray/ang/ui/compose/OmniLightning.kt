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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Visual requests are independent of the VPN state machine: never manufacture a connected state. */
internal data class OmniStrike(val origin: Offset, val turningOn: Boolean, val nonce: Long = System.nanoTime())
internal val LocalOmniStrike = staticCompositionLocalOf<(OmniStrike) -> Unit> { {} }
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

/** Full-window, non-intercepting native Canvas. No WebView and no synthetic network events. */
@Composable
internal fun OmniLightningHost(content: @Composable () -> Unit) {
    val effects = LocalOmniAppearance.current.effects
    val density = LocalDensity.current.density
    var request by remember { mutableStateOf<OmniStrike?>(null) }
    var elapsed by remember { mutableStateOf(2000f) }
    LaunchedEffect(request?.nonce, effects) {
        val event = request ?: return@LaunchedEffect
        if (effects == OmniEffects.OFF) { request = null; return@LaunchedEffect }
        val duration = if (event.turningOn) 1650f else 1260f
        var beginning = 0L
        do {
            withFrameNanos { now ->
                if (beginning == 0L) beginning = now
                elapsed = (now - beginning) / 1_000_000f
            }
        } while (elapsed < duration)
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
    CompositionLocalProvider(LocalOmniStrike provides { strike ->
        if (effects != OmniEffects.OFF) { elapsed = 0f; request = strike }
    }) {
        Box(Modifier.fillMaxSize()) {
            content()
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
                val tint = if (on) Color(0xFF96AFFF) else Color(0xFFFF96BE)
                val impactColor = if (on) Color(0xFF2EE59D) else Color(0xFFFF7850)
                if (effects == OmniEffects.FULL) drawRect(tint.copy(alpha = (.22f * flash).coerceIn(0f, 1f)), blendMode = BlendMode.Plus)
                channel.forEach { segment ->
                    val step = (segment.distance / 46f).toInt()
                    val leaderTime = (step * 46f / leaderSpeed + step % 3 * 1.5f).coerceAtMost(impact * 1.15f)
                    val returnTime = impact + (length - segment.rootDistance) / returnSpeed + (segment.distance - segment.rootDistance) / returnSpeed * .4f
                    var brightness = .2f * decay(elapsed - leaderTime, 70f) + decay(elapsed - returnTime, 38f) + .28f * decay(elapsed - returnTime, 240f)
                    restrikes.forEach { (delay, strength) ->
                        val t = elapsed - impact - delay - (length - segment.rootDistance) / (returnSpeed * 1.5f)
                        brightness += strength * (decay(t, 30f) + .2f * decay(t, 120f))
                    }
                    brightness *= segment.weight.pow(.6f)
                    if (brightness >= .04f) {
                        val width = when (segment.generation) { 0 -> 2.4f; 1 -> 1.5f; else -> .9f } * density
                        listOf(9f to .07f, 4.5f to .16f, 2.2f to .6f, .9f to 1f).forEachIndexed { index, (spread, alpha) ->
                            drawLine(if (index == 3) Color.White.copy(alpha = (brightness * 1.15f).coerceIn(0f, 1f)) else tint.copy(alpha = (brightness * alpha).coerceIn(0f, 1f)), segment.a * density, segment.b * density, spread * width, StrokeCap.Round, blendMode = BlendMode.Plus)
                        }
                    }
                }
                val radius = (if (on) 140f else 110f) * density
                drawCircle(Brush.radialGradient(listOf(impactColor.copy(alpha = (.6f * flash).coerceIn(0f, 1f)), Color.Transparent), event.origin, radius), radius, event.origin, blendMode = BlendMode.Plus)
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
