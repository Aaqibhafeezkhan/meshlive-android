package com.example.bluetoothtest

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the camera's frames into a MediaCodec encoder's input surface at the encoder's own size
 * (GPU scale ladder, [GpuScaleFeature]; Grafika `ContinuousCaptureActivity` / libwebrtc
 * `HardwareVideoEncoder.encodeTextureBuffer` pattern). One EGL context, one external (camera) texture
 * and one draw per frame: the crop and scale are folded into the texture matrix ([GpuScaleMath]), so
 * the copy into the encoder surface is also the downscale.
 *
 * Every method except [probe] must run on the one GL thread ([GpuScaleCapture] owns it). The
 * presentation time of each frame is the camera's sensor timestamp, as when the camera fed the encoder
 * directly, so timestamps stay continuous across an encoder reset.
 */
internal class GpuFrameRenderer(
    val captureWidth: Int,
    val captureHeight: Int,
) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var idleSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var uTexMatrix = -1
    private var uTap = -1
    private var aPosition = -1
    private var aSt = -1
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private val stMatrix = FloatArray(16)
    private var frames = 0L

    /** The encoder surface being drawn into, or null between two encoders. */
    private class Output(
        val eglSurface: EGLSurface,
        val width: Int,
        val height: Int,
        val matrixCrop: GpuScaleMath.CropRect,
        val tapX: Float,
        val tapY: Float,
    )

    private var output: Output? = null

    /** CPU time to submit one draw and swap (the GPU runs behind it); see [finishStats] for true GPU time. */
    val drawStats = RollingLatencyStats(512)

    /** Time `glFinish` blocked on every [FINISH_SAMPLE_EVERY]th frame after its draw: the GPU's own work. */
    val finishStats = RollingLatencyStats(128)

    @Volatile var framesDrawn = 0L
        private set

    @Volatile var framesWithoutEncoder = 0L
        private set

    enum class Draw { DRAWN, NO_OUTPUT, OUTPUT_LOST }

    /** Creates the context and the camera texture; returns the Surface the camera writes into. */
    fun create(handler: Handler, onFrameAvailable: () -> Unit): Surface {
        initEgl()
        program = buildProgram()
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aSt = GLES20.glGetAttribLocation(program, "aSt")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTap = GLES20.glGetUniformLocation(program, "uTap")
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGl("texture setup")
        val st = SurfaceTexture(texture)
        st.setDefaultBufferSize(captureWidth, captureHeight)
        st.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
        surfaceTexture = st
        return Surface(st).also { cameraSurface = it }
    }

    /** Starts drawing into [surface] (an encoder input surface) at [width] x [height]. */
    fun attach(surface: Surface, width: Int, height: Int) {
        detach()
        val eglSurface = EGL14.eglCreateWindowSurface(display, checkNotNull(config), surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: ${EGL14.eglGetError()}" }
        val crop = GpuScaleMath.cropFor(captureWidth, captureHeight, width, height)
        val (tapX, tapY) = GpuScaleMath.tapOffset(crop, captureWidth, width, height)
        output = Output(eglSurface, width, height, crop, tapX, tapY)
    }

    /** The crop applied to the current output (log evidence), or null when none is attached. */
    fun currentCrop(): GpuScaleMath.CropRect? = output?.matrixCrop

    /** Stops drawing into the encoder surface and destroys its EGL surface (before the codec is released). */
    fun detach() {
        val out = output ?: return
        output = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)
            EGL14.eglDestroySurface(display, out.eglSurface)
        }
    }

    /** Takes the newest camera frame and, when an encoder is attached, draws it into the encoder surface. */
    fun drawFrame(): Draw {
        val st = surfaceTexture ?: return Draw.NO_OUTPUT
        val out = output
        val target = out?.eglSurface ?: idleSurface
        if (!EGL14.eglMakeCurrent(display, target, target, context)) {
            return if (out != null) Draw.OUTPUT_LOST else Draw.NO_OUTPUT
        }
        st.updateTexImage()
        if (out == null) {
            framesWithoutEncoder++
            return Draw.NO_OUTPUT
        }
        st.getTransformMatrix(stMatrix)
        val matrix = GpuScaleMath.textureMatrix(stMatrix, out.matrixCrop)
        val startNs = System.nanoTime()
        GLES20.glViewport(0, 0, out.width, out.height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, matrix, 0)
        GLES20.glUniform2f(uTap, out.tapX, out.tapY)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, POSITIONS)
        GLES20.glEnableVertexAttribArray(aSt)
        GLES20.glVertexAttribPointer(aSt, 2, GLES20.GL_FLOAT, false, 0, ST)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aSt)
        EGLExt.eglPresentationTimeANDROID(display, out.eglSurface, st.timestamp)
        val swapped = EGL14.eglSwapBuffers(display, out.eglSurface)
        drawStats.recordNanos(System.nanoTime() - startNs)
        if (!swapped) return Draw.OUTPUT_LOST
        framesDrawn++
        if (++frames % FINISH_SAMPLE_EVERY == 0L) {
            // Diagnostic: glFinish after the swap waits for the GPU to complete this frame's work.
            val finishStartNs = System.nanoTime()
            GLES20.glFinish()
            finishStats.recordNanos(System.nanoTime() - finishStartNs)
        }
        return Draw.DRAWN
    }

    fun release() {
        detach()
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            if (program != 0) GLES20.glDeleteProgram(program)
        }
        texture = 0
        program = 0
        cameraSurface?.release()
        cameraSurface = null
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        surfaceTexture = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (idleSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, idleSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        idleSurface = EGL14.EGL_NO_SURFACE
    }

    private fun initEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val withPbuffer = chooseConfig(display, pbuffer = true)
        val chosen = withPbuffer ?: chooseConfig(display, pbuffer = false) ?: error("no recordable RGBA8888 EGL config")
        config = chosen
        context = EGL14.eglCreateContext(
            display, chosen, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: ${EGL14.eglGetError()}" }
        // The camera texture is updated on this thread even between two encoders: a 1x1 pbuffer keeps a
        // surface current then (or the surfaceless context where the config has no pbuffer support).
        idleSurface = if (withPbuffer != null) {
            EGL14.eglCreatePbufferSurface(display, chosen, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                ?: EGL14.EGL_NO_SURFACE
        } else {
            EGL14.EGL_NO_SURFACE
        }
        check(EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)) { "eglMakeCurrent failed: ${EGL14.eglGetError()}" }
    }

    private fun buildProgram(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val handle = GLES20.glCreateProgram()
        GLES20.glAttachShader(handle, vertex)
        GLES20.glAttachShader(handle, fragment)
        GLES20.glLinkProgram(handle)
        val status = IntArray(1)
        GLES20.glGetProgramiv(handle, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "GL program link failed: ${GLES20.glGetProgramInfoLog(handle)}" }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return handle
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "GL shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun checkGl(what: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "GL error 0x${error.toString(16)} after $what" }
    }

    companion object {
        private const val TAG = "BluetoothVideo"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val FINISH_SAMPLE_EVERY = 30L

        private val POSITIONS: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val ST: FloatBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }

        /**
         * The vertex stage passes the quad's 0..1 coordinates through; the fragment stage applies the
         * texture matrix per sample, so each of its taps is offset in the output's own pixel units.
         */
        private const val VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec2 aSt;
varying vec2 vSt;
void main() {
    gl_Position = aPosition;
    vSt = aSt;
}
"""

        /**
         * uTap is zero for a 1:1 copy (one sample). For a downscale it is a quarter of an output pixel:
         * four bilinear taps around the pixel centre average the source footprint, so a 5x reduction is
         * not an aliased single tap. highp where the GPU has it: mediump texture coordinates (10 bits)
         * would be off by about a source pixel at 1280 wide.
         */
        private const val FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vSt;
uniform samplerExternalOES sTexture;
uniform mat4 uTexMatrix;
uniform vec2 uTap;
vec4 tap(vec2 offset) {
    return texture2D(sTexture, (uTexMatrix * vec4(vSt + offset, 0.0, 1.0)).xy);
}
void main() {
    if (uTap.x == 0.0) {
        gl_FragColor = tap(vec2(0.0));
    } else {
        gl_FragColor = 0.25 * (tap(vec2(uTap.x, uTap.y)) + tap(vec2(-uTap.x, uTap.y)) +
            tap(vec2(uTap.x, -uTap.y)) + tap(vec2(-uTap.x, -uTap.y)));
    }
}
"""

        private fun chooseConfig(display: EGLDisplay, pbuffer: Boolean): EGLConfig? {
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or if (pbuffer) EGL14.EGL_PBUFFER_BIT else 0,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] < 1) return null
            return configs[0]
        }

        /**
         * Whether this phone can run the GPU path at all: a recordable RGBA8888 ES2 config and a context
         * that links the external-texture shader. Cheap, and run once per process (see [GpuScaleSupport]).
         */
        fun probe(): Boolean {
            val ok = java.util.concurrent.atomic.AtomicBoolean(false)
            // EGL state is thread-bound, so the whole probe runs on one short-lived thread.
            val thread = Thread {
                val renderer = GpuFrameRenderer(2, 2)
                try {
                    renderer.initEgl()
                    renderer.buildProgram()
                    ok.set(true)
                } catch (error: Throwable) {
                    Log.w(TAG, "GPU scale probe failed: ${error.message}")
                } finally {
                    runCatching { renderer.release() }
                }
            }
            thread.start()
            thread.join(PROBE_TIMEOUT_MS)
            return ok.get()
        }

        private const val PROBE_TIMEOUT_MS = 3_000L
    }
}

/** The result of [GpuFrameRenderer.probe], computed once. */
internal object GpuScaleSupport {
    val supported: Boolean by lazy { GpuFrameRenderer.probe() }
}
