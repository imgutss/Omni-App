package com.v2ray.ang.ui.compose

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.*
import kotlin.random.Random

/** Real native GLES renderer. Meshes, UVs and PBR GLSL are exported from the embedded Three r128.
 * No WebView, JS runtime or approximated flat logo is used in the Android UI.
 */
@Composable
internal fun OmniLogo3d(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val effects = LocalOmniAppearance.current.effects
    if (effects == OmniEffects.OFF) {
        val bitmap = remember { context.assets.open("omni-reference/logo.webp").use { requireNotNull(BitmapFactory.decodeStream(it)).asImageBitmap() } }
        Image(bitmap, contentDescription = "Omni", modifier = modifier)
        return
    }
    var view by remember { mutableStateOf<ReferenceLogoView?>(null) }
    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, _ -> view?.foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        view?.foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        onDispose { lifecycle.removeObserver(observer) }
    }
    AndroidView(
        factory = { ReferenceLogoView(it).also { v -> view = v } },
        modifier = modifier.onGloballyPositioned { coordinates ->
            val rect = coordinates.boundsInWindow()
            view?.visibleInViewport = rect.width > 0f && rect.height > 0f
        },
        onRelease = { it.shutdown() }
    )
}

internal class ReferenceLogoView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    @Volatile var foreground = true
    @Volatile var visibleInViewport = true
    @Volatile private var alive = false
    @Volatile private var widthPixels = 1
    @Volatile private var heightPixels = 1
    private var renderThread: Thread? = null
    init { isOpaque = false; surfaceTextureListener = this; contentDescription = "Omni 3D" }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        widthPixels = width; heightPixels = height; alive = true
        renderThread = Thread({ render(surface) }, "Omni-reference-logo").also { it.start() }
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { widthPixels = width; heightPixels = height }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { shutdown(); return false }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    fun shutdown() { alive = false; renderThread?.interrupt(); renderThread = null }
    private data class Mesh(val buffer: FloatBuffer, val count: Int, val frontCount: Int = 0)
    private fun mesh(name: String): Mesh {
        val bytes = context.assets.open("omni-reference/gl/$name.bin").use { it.readBytes() }
        val data = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).put(bytes).apply { position(0) }.asFloatBuffer()
        val info = context.assets.open("omni-reference/gl/$name.json").bufferedReader().use { JSONObject(it.readText()) }
        return Mesh(data, info.getInt("vertices"), if (name == "slab") info.getJSONArray("groups").getJSONObject(0).getInt("count") else 0)
    }
    private fun shader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, code); GLES20.glCompileShader(shader)
        val ok = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }
    private fun program(name: String): Int {
        fun source(suffix: String) = context.assets.open("omni-reference/gl/$name.$suffix").bufferedReader().use { it.readText() }
        val v = shader(GLES20.GL_VERTEX_SHADER, source("vert")); val f = shader(GLES20.GL_FRAGMENT_SHADER, source("frag"))
        val p = GLES20.glCreateProgram(); GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0); check(ok[0] != 0) { GLES20.glGetProgramInfoLog(p) }
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f); return p
    }
    private fun render(surface: SurfaceTexture) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        var eglSurface = EGL14.EGL_NO_SURFACE
        var eglContext = EGL14.EGL_NO_CONTEXT
        try {
            check(EGL14.eglInitialize(display, null, 0, null, 0))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val count = IntArray(1)
            fun choose(samples: Int): Boolean {
                val attributes = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0, EGL14.EGL_SAMPLES, samples, EGL14.EGL_NONE)
                return EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0
            }
            check(choose(4) || choose(0)); val config = requireNotNull(configs[0])
            eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext))
            val front = program("front"); val side = program("side"); val basic = program("basic"); val points = program("points")
            val random = Random(131)
            val dust = FloatArray(70 * 8)
            repeat(70) { i -> dust[i * 8] = (random.nextFloat() - .5f) * 7f; dust[i * 8 + 1] = (random.nextFloat() - .5f) * 5f; dust[i * 8 + 2] = (random.nextFloat() - .5f) * 4f }
            val initialDust = dust.copyOf()
            val dustMesh = Mesh(ByteBuffer.allocateDirect(dust.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer(), 70)
            val slab = mesh("slab"); val ringA = mesh("ring-a"); val ringB = mesh("ring-b"); val satellite = mesh("satellite")
            val textures = IntArray(1); GLES20.glGenTextures(1, textures, 0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            context.assets.open("omni-reference/logo.webp").use { requireNotNull(BitmapFactory.decodeStream(it)).also { bitmap -> GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0); bitmap.recycle() } }
            GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            val projection = FloatArray(16); val view = FloatArray(16); val model = FloatArray(16); val mv = FloatArray(16)
            val inverse = FloatArray(16); val normal = FloatArray(9)
            fun draw(p: Int, mesh: Mesh, matrix: FloatArray, start: Int, amount: Int, color: FloatArray, opacity: Float = 1f, mode: Int = GLES20.GL_TRIANGLES) {
                GLES20.glUseProgram(p)
                fun loc(n: String) = GLES20.glGetUniformLocation(p, n)
                fun f(n: String, x: Float) = GLES20.glUniform1f(loc(n), x)
                fun v(n: String, r: Float, g: Float, b: Float) = GLES20.glUniform3f(loc(n), r, g, b)
                Matrix.multiplyMM(mv, 0, view, 0, matrix, 0); Matrix.invertM(inverse, 0, mv, 0)
                for (row in 0..2) for (col in 0..2) normal[col * 3 + row] = inverse[row * 4 + col]
                GLES20.glUniformMatrix4fv(loc("modelMatrix"), 1, false, matrix, 0)
                GLES20.glUniformMatrix4fv(loc("modelViewMatrix"), 1, false, mv, 0)
                GLES20.glUniformMatrix4fv(loc("viewMatrix"), 1, false, view, 0)
                GLES20.glUniformMatrix4fv(loc("projectionMatrix"), 1, false, projection, 0)
                GLES20.glUniformMatrix3fv(loc("normalMatrix"), 1, false, normal, 0)
                GLES20.glUniformMatrix3fv(loc("uvTransform"), 1, false, floatArrayOf(1f,0f,0f,0f,-1f,0f,0f,1f,1f), 0)
                v("diffuse", color[0], color[1], color[2]); f("opacity", opacity)
                f("size", .045f * context.resources.displayMetrics.density); f("scale", heightPixels * .5f)
                f("roughness", if (p == front) .38f else .2f); f("metalness", if (p == front) .05f else .6f)
                val e = if (p == front) .22f else .4862745f * .7f; v("emissive", e, e, e)
                v("ambientLightColor", .6196078f * .75f, .7843137f * .75f, .9764706f * .75f)
                val length = sqrt(61f); v("directionalLights[0].direction", -3f / length, 4f / length, 6f / length)
                v("directionalLights[0].color", 1.15f, 1.15f, 1.15f)
                val cameraZ = -view[14]
                v("pointLights[0].position", -4f, 1f, 4f - cameraZ); v("pointLights[0].color", .7843137f * 1.6f, .8784314f * 1.6f, 1.6f)
                v("pointLights[1].position", 4f, -2f, 3f - cameraZ); v("pointLights[1].color", .9529412f * 1.8f, .4470588f * 1.8f, .4980392f * 1.8f)
                for (i in 0..1) { f("pointLights[$i].distance", 14f); f("pointLights[$i].decay", 1f) }
                GLES20.glUniform1i(loc("map"), 0); GLES20.glUniform1i(loc("emissiveMap"), 0)
                for ((attribute, offset, dimensions) in listOf(Triple("position", 0, 3), Triple("normal", 3, 3), Triple("uv", 6, 2))) {
                    val a = GLES20.glGetAttribLocation(p, attribute)
                    if (a >= 0) { mesh.buffer.position(offset); GLES20.glEnableVertexAttribArray(a); GLES20.glVertexAttribPointer(a, dimensions, GLES20.GL_FLOAT, false, 32, mesh.buffer) }
                }
                GLES20.glDrawArrays(mode, start, amount)
            }
            val beginning = System.nanoTime(); var lastStatic = false; var ready = false; var lastWidth = 0; var lastHeight = 0
            while (alive) {
                if (!foreground || !visibleInViewport) { Thread.sleep(100); continue }
                val reduced = !ValueAnimator.areAnimatorsEnabled()
                if (reduced && lastStatic && lastWidth == widthPixels && lastHeight == heightPixels) { Thread.sleep(100); continue }; lastStatic = reduced
                lastWidth = widthPixels; lastHeight = heightPixels
                val t = (System.nanoTime() - beginning) / 1_000_000_000f
                val aspect = widthPixels.toFloat() / heightPixels.coerceAtLeast(1)
                val cameraZ = 9.5f * max(1f, 1.25f / aspect)
                GLES20.glViewport(0, 0, widthPixels, heightPixels); GLES20.glClearColor(0f,0f,0f,0f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                Matrix.perspectiveM(projection, 0, 32f, aspect, .1f, 50f); Matrix.setIdentityM(view, 0); Matrix.translateM(view, 0, 0f, 0f, -cameraZ)
                Matrix.setIdentityM(model, 0); Matrix.translateM(model, 0, 0f, if (reduced) 0f else sin(t * 1.4f) * .2f, 0f)
                Matrix.rotateM(model, 0, (if (reduced) .06f else cos(t * .55f) * .1f) * 180f / PI.toFloat(), 1f,0f,0f)
                Matrix.rotateM(model, 0, (if (reduced) -.3f else sin(t * .7f) * .42f) * 180f / PI.toFloat(), 0f,1f,0f)
                Matrix.rotateM(model, 0, (if (reduced) 0f else sin(t * .9f) * .03f) * 180f / PI.toFloat(), 0f,0f,1f)
                draw(front, slab, model, 0, slab.frontCount, floatArrayOf(1f,1f,1f))
                draw(side, slab, model, slab.frontCount, slab.count - slab.frontCount, floatArrayOf(.2784314f,.8705882f,.4901961f))
                for (i in 0..1) {
                    val ring = if (i == 0) ringA else ringB
                    val color = if (i == 0) floatArrayOf(.117647f,.843137f,.376471f) else floatArrayOf(.952941f,.447059f,.498039f)
                    Matrix.setIdentityM(model, 0); Matrix.rotateM(model, 0, (if (i == 0) 1.25f else 1.05f) * 180f / PI.toFloat(), 1f,0f,0f)
                    Matrix.rotateM(model, 0, (if (i == 0) .25f else -.45f) * 180f / PI.toFloat(), 0f,1f,0f)
                    Matrix.rotateM(model, 0, (if (reduced) 0f else t * if (i == 0) .9f else -.62f) * 180f / PI.toFloat(), 0f,0f,1f)
                    draw(basic, ring, model, 0, ring.count, color, .75f)
                    Matrix.translateM(model, 0, if (i == 0) 2.6f else 3f, 0f, 0f)
                    draw(basic, satellite, model, 0, satellite.count, color)
                }
                repeat(70) { i -> dust[i * 8 + 1] = if (reduced) initialDust[i * 8 + 1] else ((initialDust[i * 8 + 1] + 2.8f + t * .3f * (.5f + i % 3 * .4f)) % 5.6f) - 2.8f }
                dustMesh.buffer.position(0); dustMesh.buffer.put(dust); dustMesh.buffer.position(0)
                Matrix.setIdentityM(model, 0); GLES20.glDepthMask(false)
                GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE, GLES20.GL_ONE)
                draw(points, dustMesh, model, 0, 70, floatArrayOf(.364706f,.886275f,.549020f), .8f, GLES20.GL_POINTS)
                GLES20.glDepthMask(true)
                GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                check(EGL14.eglSwapBuffers(display, eglSurface))
                if (!ready) { Log.i("OmniReferenceGL", "Original mesh and PBR shaders rendered successfully"); ready = true }
                Thread.sleep(16)
            }
        } catch (_: InterruptedException) {
            // The owner left the screen; do not keep rendering or hold its SurfaceTexture.
        } catch (error: Exception) {
            Log.e("OmniReferenceGL", "Original 3D logo renderer failed", error)
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
            surface.release()
        }
    }
}
