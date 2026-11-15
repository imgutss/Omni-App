package com.v2ray.ang.ui.compose

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.*

/** Decorative native counterparts of all eight omni-sx-js scenes. No simulated app data. */
enum class OmniScene { UPDATE, BACKUP, ABOUT, SUBSCRIPTIONS, PER_APP, ROUTING, ASSETS, SETTINGS }
internal data class OmniProjected(val x: Float, val y: Float, val depth: Float, val scale: Float) {
    val position get() = Offset(x, y)
}
internal fun omniProject(x0: Float, y0: Float, z0: Float, ay: Float, ax: Float, width: Float, height: Float): OmniProjected {
    val x = x0 * cos(ay) + z0 * sin(ay)
    val z1 = z0 * cos(ay) - x0 * sin(ay)
    val y = y0 * cos(ax) - z1 * sin(ax)
    val z = y0 * sin(ax) + z1 * cos(ax)
    val k = 300f / (300f + z)
    return OmniProjected(width / 2f + x * k, height / 2f + y * k, z, k)
}

@Composable
fun OmniSceneHero(scene: OmniScene, modifier: Modifier = Modifier,bottomSpacing:Dp=10.dp) {
    val appearance = LocalOmniAppearance.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by remember { mutableStateOf(true) }
    var time by remember { mutableFloatStateOf(1.4f) }
    val anchor = remember { System.nanoTime() }
    val animated = appearance.effects != OmniEffects.OFF && ValueAnimator.areAnimatorsEnabled()
    LaunchedEffect(lifecycle, animated, visible) {
        if (!animated) { time = 1.4f; return@LaunchedEffect }
        if (visible) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) withFrameNanos { time = (it - anchor) / 1_000_000_000f }
        }
    }
    val accent = appearance.skin.accent
    Canvas(modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = bottomSpacing)
        .height(196.dp).onGloballyPositioned { visible = it.boundsInWindow().height > 0f }
        .clip(RoundedCornerShape(30.dp)).drawBehind {
            val r = CornerRadius(30.dp.toPx())
            drawRoundRect(Color(0xCC181818), cornerRadius = r)
            drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = .078f), Color.White.copy(alpha = .016f))), cornerRadius = r)
            withTransform({ scale(1f, size.height * .9f / (size.width * .7f), Offset(size.width / 2f, 0f)) }) {
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .14f), Color.Transparent), Offset(size.width / 2f, 0f), size.width * .7f), size.width * .7f, Offset(size.width / 2f, 0f))
            }
            drawRoundRect(Color.White.copy(alpha = .07f), cornerRadius = r, style = Stroke(1.dp.toPx()))
        }) {
        val pixelScale = density
        val w = size.width / pixelScale; val h = size.height / pixelScale
        withTransform({ scale(pixelScale, pixelScale, Offset.Zero) }) { ReferenceScene(this, w, h, time).draw(scene) }
    }
}

private class ReferenceScene(val c: DrawScope, val w: Float, val h: Float, val t: Float) {
    val green = Color(0xFF1ED760); val blue = Color(0xFF539DF5); val pink = Color(0xFFF3727F)
    val colors = listOf(green, blue, pink)
    val tau = 6.2832f
    fun p(x: Float, y: Float, z: Float, ay: Float, ax: Float) = omniProject(x, y, z, ay, ax, w, h)
    fun ring(i: Int, a: Float, r: Float, ay: Float, ax: Float): OmniProjected {
        val q = r * cos(a); val v = r * sin(a)
        return when (i) { 0 -> p(q, v, 0f, ay, ax); 1 -> p(0f, q, v, ay, ax); else -> p(q, 0f, v, ay, ax) }
    }
    fun edge(a: OmniProjected, b: OmniProjected, color: Color, alpha: Float, width: Float) = c.drawLine(color.copy(alpha = alpha.coerceIn(0f, 1f)), a.position, b.position, width)
    fun glow(q: OmniProjected, radius: Float, color: Color, alpha: Float = 1f) {
        val a = alpha.coerceIn(0f, 1f); val r = max(.5f, radius * q.scale)
        c.drawCircle(Brush.radialGradient(listOf(color.copy(alpha = a * .28f), Color.Transparent), q.position, r + 14f), r + 14f, q.position)
        c.drawCircle(color.copy(alpha = a), r, q.position)
    }
    fun polygon(points: List<OmniProjected>, color: Color, fill: Float, stroke: Float = 1.7f, alpha: Float = 1f) {
        val path = Path(); points.forEachIndexed { i, q -> if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y) }; path.close()
        c.drawPath(path, color.copy(alpha = (fill * alpha).coerceIn(0f, 1f)))
        c.drawPath(path, color.copy(alpha = alpha.coerceIn(0f, 1f)), style = Stroke(stroke))
    }
    fun draw(scene: OmniScene) { when (scene) {
        OmniScene.UPDATE -> update(); OmniScene.BACKUP -> backup(); OmniScene.ABOUT -> about(); OmniScene.SUBSCRIPTIONS -> subscriptions()
        OmniScene.PER_APP -> apps(); OmniScene.ROUTING -> routing(); OmniScene.ASSETS -> assets(); OmniScene.SETTINGS -> settings()
    } }
    fun update() {
        val r = min(h * .36f, 64f)
        colors.forEachIndexed { i, col ->
            val ay = t * (.5f + i * .23f) + i * 2f; val ax = t * (.35f + i * .17f) + i
            var previous: OmniProjected? = null
            for (k in 0..72) { val q = ring(i, k / 72f * tau, r, ay, ax); previous?.let { edge(it, q, col, .25f + .6f * (1f - (q.depth + r) / (2f * r)), 1.7f) }; previous = q }
            glow(ring(i, t * (1.1f + i * .4f), r, ay, ax), 4.5f, col)
        }
        glow(OmniProjected(w / 2f, h / 2f, 0f, 1f), 6f + sin(t * 2f) * 1.5f, Color(0xFF4BDF80))
    }
    fun backup() {
        val x = min(w * .2f, 76f); val d = x * .6f; val g = 30f; val ay = sin(t * .5f) * .6f + .55f; val ax = .95f
        val layers = (0..3).map { i -> i to ((i - 1.5f) * g + sin(t * 1.3f + i) * 4f) }.sortedByDescending { p(0f, it.second, 0f, ay, ax).depth }
        val palette = listOf(green, blue, blue, pink)
        layers.forEach { (i, y) -> polygon(listOf(p(-x,y,-d,ay,ax),p(x,y,-d,ay,ax),p(x,y,d,ay,ax),p(-x,y,d,ay,ax)), palette[i], .2f) }
        repeat(18) { j -> val u = (t * .28f + j / 18f) % 1f; glow(p(sin(j * 12.9f) * .8f * x, (.5f - u) * g * 4f, cos(j * 7.3f) * .8f * d, ay, ax), 2.6f, green, sin(u * 3.1416f)) }
    }
    fun about() {
        val s = min(h * .25f, 48f)
        fun cube(scale: Float, ay: Float, ax: Float) = (0..7).map { i -> p((if (i and 1 != 0) 1f else -1f) * scale, (if (i and 2 != 0) 1f else -1f) * scale, (if (i and 4 != 0) 1f else -1f) * scale, ay, ax) }
        val a = cube(s, t * .6f, t * .45f); val b = cube(s * .5f, -t * .8f, t * .3f + 1f)
        repeat(8) { i -> edge(a[i], b[i], pink, .35f, 1f); repeat(3) { j -> val m = i xor (1 shl j); if (m > i) { edge(a[i], a[m], green, .85f, 1.7f); edge(b[i], b[m], blue, .9f, 1.4f) } } }
        a.forEach { glow(it, 3f, green) }; b.forEach { glow(it, 2.4f, blue) }
    }
    fun subscriptions() {
        val x = min(w * .16f, 60f); val y = x * .55f; val ay = sin(t * .5f) * .45f; val ax = -.18f
        data class Card(val i: Int, val x: Float, val y: Float, val z: Float)
        val cards = (0..2).map { i -> Card(i, (i - 1) * x * .85f, (1 - i) * 16f + sin(t * 1.2f + i * 2f) * 5f, (1 - i) * 28f) }
        cards.sortedByDescending { p(it.x, it.y, it.z, ay, ax).depth }.forEach { k ->
            fun at(dx: Float, dy: Float) = p(k.x + dx, k.y + dy, k.z, ay, ax)
            polygon(listOf(at(-x,-y),at(x,-y),at(x,y),at(-x,y)), colors[k.i], .18f)
            edge(at(-x*.7f,-y*.3f), at(x*.2f,-y*.3f), colors[k.i], .8f, 2f); edge(at(-x*.7f,y*.3f), at(x*.5f,y*.3f), colors[k.i], .5f, 2f)
        }
        val u = (t * .4f) % 1f * 2f; val a = cards[if (u < 1f) 0 else 1]; val b = cards[if (u < 1f) 1 else 2]; val f = u % 1f
        glow(p(a.x+(b.x-a.x)*f,a.y+(b.y-a.y)*f,a.z+(b.z-a.z)*f,ay,ax), 4f, Color(0xFF4BDF80))
    }
    fun apps() {
        val pitch = min(w * .075f, 31f); val s = pitch * .42f; val ay = sin(t * .5f) * .5f; val ax = -.32f
        data class Tile(val x: Float, val y: Float, val z: Float, val active: Boolean)
        val tiles = (0..5).flatMap { i -> (0..2).map { j -> Tile((i-2.5f)*pitch,(j-1)*pitch,sin(t*2f+i*.8f+j*1.3f)*14f,(i*2+j)%5==0) } }
        tiles.sortedByDescending { p(it.x,it.y,it.z,ay,ax).depth }.forEach { k ->
            fun at(x: Float,y: Float) = p(k.x+x,k.y+y,k.z,ay,ax)
            polygon(listOf(at(-s,-s),at(s,-s),at(s,s),at(-s,s)), if(k.active)green else blue, if(k.active).533f else .2f, 1.3f, .5f+.5f*(14f-k.z)/28f)
        }
    }
    fun routing() {
        val ay = sin(t * .45f) * .5f; val ax = .28f
        data class Route(val y: Float, val z: Float, val col: Color)
        listOf(Route(-46f,22f,green),Route(0f,-12f,green),Route(46f,22f,pink)).forEachIndexed { i,d ->
            fun at(u: Float): OmniProjected { val e = u*u*(3f-2f*u); return p(-86f+170f*u,d.y*e,d.z*u,ay,ax) }
            var previous: OmniProjected? = null
            for(k in 0..24) { val q=at(k/24f);previous?.let { edge(it,q,d.col,.5f,1.6f) };previous=q }
            glow(at((t*.35f+i*.3f)%1f),3.4f,d.col);glow(at(1f),7f,d.col,.9f)
        }
        glow(p(-86f,0f,0f,ay,ax),9f,Color(0xFF4BDF80))
    }
    fun assets() {
        val ay=t*.3f;val ax=.95f;val radii=floatArrayOf(64f,52f,40f)
        for(i in 2 downTo 0) {
            val y=(i-1)*26f+sin(t*1.2f+i)*3f;val a=t*(1.3f+i*.4f)
            polygon((0..63).map { k -> p(radii[i]*cos(k/64f*tau),y,radii[i]*sin(k/64f*tau),ay,ax) },colors[i],.149f,1.6f)
            glow(p(radii[i]*cos(a),y,radii[i]*sin(a),ay,ax),3.6f,colors[i])
        }
    }
    fun settings() {
        val ay=sin(t*.4f)*.5f;val ax=-.3f
        data class Gear(val teeth:Int,val outer:Float,val inner:Float,val angle:Float,val x:Float,val col:Color)
        listOf(Gear(12,52f,40f,t*.5f,-38f,green),Gear(8,35f,26f,.39f-t*.75f,38f,blue)).forEach { g ->
            fun teeth(z:Float)=(0 until g.teeth).flatMap { i -> listOf(g.inner to 0f,g.outer to .18f,g.outer to .5f,g.inner to .68f).map { (radius,f) -> val a=g.angle+(i+f)*tau/g.teeth;p(g.x+radius*cos(a),radius*sin(a),z,ay,ax) } }
            val front=teeth(8f);val back=teeth(-8f)
            for(i in front.indices step 4)edge(front[i],back[i],g.col,.3f,1f)
            polygon(back,g.col,.094f,1.2f,.5f);polygon(front,g.col,.2f)
        }
    }
}
