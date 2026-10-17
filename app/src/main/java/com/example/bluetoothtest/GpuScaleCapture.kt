package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.camera.viewfinder.view.ViewfinderView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor

/**
 * The one camera of a GPU-scaled session (GPU scale ladder, [GpuScaleFeature]). It opens the camera
 * once at one capture mode ([GpuScaleMath.chooseCapture]), feeds a SurfaceTexture that
 * [GpuFrameRenderer] draws from, and keeps running while encoders come and go: a ladder rung change is
 * `old encoder close()` + `new CameraVideoEncoder.start()` on the same capture, so only the MediaCodec
 * is rebuilt (tens of milliseconds) and the camera, its preview and its frame timestamps are untouched.
 *
 * Lifecycle: [obtain] returns the live capture for the same lens, viewfinder, session size and frame
 * rate, or closes the old one and opens a new one. An encoder that is closed for a rung change calls
 * [detach] with a keep-alive so the next encoder finds the camera still open; any other close releases
 * the camera at once (camera off, flip, stop).
 *
 * Threads: the camera and its callbacks run on [cameraThread]; EGL, GL and the frame pump on [glThread].
 */
internal class GpuScaleCapture private constructor(
    private val context: Context,
    private val viewfinder: ViewfinderView,
    val lens: CameraLens,
    private val scaleMode: VideoScaleMode,
    private val cameraId: String,
    private val characteristics: CameraCharacteristics,
    val capture: GpuScaleMath.CaptureMode,
    val request: GpuScaleRequest,
    private var previewSurface: Surface?,
    private var viewfinderSurface: CameraViewfinderSurface?,
) {
    interface Listener {
        fun onStatus(message: String)
        fun onFailure(error: Throwable)

        /** The capture session is running (also called at once when an encoder attaches to a running capture). */
        fun onConfigured(cameraFpsText: String)
    }

    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val glThread = HandlerThread("GpuScaleGl", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val cameraThread = HandlerThread("GpuScaleCamera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val closed = AtomicBoolean(false)
    private val renderer = GpuFrameRenderer(capture.width, capture.height)
    private val lock = Any()

    private var cameraOutputSurface: Surface? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var generation = 0
    private var availableFpsRanges = emptyList<Range<Int>>()
    private var targetFpsRange: Range<Int>? = null
    private var continuousAutoFocus = false
    private var cameraFpsText: String? = null
    private var cameraStarted = false
    private var hardwareLevel: Int? = null
    private var closingSession: CameraCaptureSession? = null
    private val rebuildMode = CameraRebuildPolicy.modeFor(DeviceTraits.current())
    private val openRetry = CameraOpenRetryState { SystemClock.elapsedRealtime() }
    private val rebuildHost: CameraRebuildHost = object : CameraRebuildHost {
        override fun hasSession(): Boolean = session != null

        override fun closeSession() {
            val old = session
            session = null
            generation++
            closingSession = old
            runCatching { old?.close() }
        }

        override fun closeDevice() {
            val old = camera
            camera = null
            if (old == null) cameraHandler.post { rebuilder.onDeviceClosed() } else runCatching { old.close() }
        }

        override fun openDevice() {
            openCamera()
        }

        override fun createSession() {
            configureSession()
        }

        override fun log(message: String) {
            Log.i(TAG, "BENCHMARK camera_rebuild: path=gpu_scale | mode=$rebuildMode | $message")
        }

        override fun rebuildFailed(message: String) {
            fail(IllegalStateException(message))
        }
    }
    private val rebuilder: CameraSessionRebuilder = CameraSessionRebuilder(
        rebuildHost,
        object : CameraRebuildScheduler {
            override fun postDelayed(delayMs: Long, action: Runnable) {
                cameraHandler.postDelayed(action, delayMs)
            }

            override fun remove(action: Runnable) {
                cameraHandler.removeCallbacks(action)
            }
        },
        { SystemClock.elapsedRealtime() },
    )

    // Guarded by [lock].
    private var attachedToken: Any? = null
    private var listener: Listener? = null
    private var keepAliveClose: Runnable? = null

    @Volatile private var lastDraw = GpuFrameRenderer.Draw.NO_OUTPUT

    /** True once [close] ran or the camera failed between two encoders; such a capture is never handed out again. */
    val isClosed: Boolean get() = closed.get() || broken

    @Volatile private var broken = false

    /**
     * A camera or GL failure: the attached encoder is told; with none attached (between two rungs) the
     * capture retires itself so the next encoder opens a fresh one.
     */
    private fun fail(error: Throwable) {
        val target = currentListener()
        if (target != null) {
            target.onFailure(error)
        } else if (!closed.get()) {
            Log.w(TAG, "GPU scale capture failed with no encoder attached: ${error.message}")
            broken = true
            Thread { close() }.start()
        }
    }

    /** CPU time per draw+swap and true GPU time per sampled frame, for the encoder's BENCHMARK line. */
    fun statsLine(): String {
        val draw = renderer.drawStats.snapshot()
        val finish = renderer.finishStats.snapshot()
        val crop = renderer.currentCrop()
        return "capture=${capture.width}x${capture.height} | crop=" +
            (crop?.let { if (it.isFull) "none" else "%.3f,%.3f,%.3f,%.3f".format(it.left, it.top, it.width, it.height) } ?: "n/a") +
            " | gpuMs/frame submit p50/p95/max %.2f/%.2f/%.2f (n=%d), finish p50/p95 %.2f/%.2f (n=%d) | drawn=%d | idle=%d".format(
                draw.p50Ms, draw.p95Ms, draw.maxMs, draw.samples, finish.p50Ms, finish.p95Ms, finish.samples,
                renderer.framesDrawn, renderer.framesWithoutEncoder,
            )
    }

    private fun initialize() {
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        glHandler.post {
            try {
                cameraOutputSurface = renderer.create(glHandler) { drawOnGlThread() }
            } catch (error: Throwable) {
                failure = error
            } finally {
                ready.countDown()
            }
        }
        check(ready.await(GL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "GPU scale: GL setup timed out" }
        failure?.let { throw IllegalStateException("GPU scale: GL setup failed: ${it.message}", it) }
        hardwareLevel = safeCameraRead("INFO_SUPPORTED_HARDWARE_LEVEL") {
            characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        }
        val ranges = safeCameraRead("CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES") {
            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        }.orEmpty().toList()
        availableFpsRanges = ranges
        targetFpsRange = selectFpsRange(request.fps)
        continuousAutoFocus = safeCameraRead("CONTROL_AF_AVAILABLE_MODES") {
            characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        }?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true
    }

    private fun drawOnGlThread() {
        if (closed.get()) return
        try {
            val result = renderer.drawFrame()
            lastDraw = result
            if (result == GpuFrameRenderer.Draw.OUTPUT_LOST) {
                // The encoder's surface is gone (codec released or failed): stop drawing into it; the
                // encoder reports its own error.
                renderer.detach()
                currentListener()?.onStatus("GPU scale: encoder surface lost; frames dropped until a new encoder attaches")
            }
        } catch (error: Throwable) {
            if (!closed.get()) fail(error)
        }
    }

    private fun currentListener(): Listener? = synchronized(lock) { listener }

    /**
     * Starts drawing into [surface] at [width] x [height] for the encoder [token] and, on the first
     * attach, opens the camera. Blocks until the draw target exists, so the encoder may be started
     * (and later released) safely.
     */
    fun attach(token: Any, surface: Surface, width: Int, height: Int, newListener: Listener) {
        check(!closed.get()) { "GPU scale capture is closed" }
        synchronized(lock) {
            keepAliveClose?.let(cameraHandler::removeCallbacks)
            keepAliveClose = null
            attachedToken = token
            listener = newListener
        }
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        glHandler.post {
            try {
                renderer.attach(surface, width, height)
            } catch (error: Throwable) {
                failure = error
            } finally {
                ready.countDown()
            }
        }
        check(ready.await(GL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "GPU scale: attach timed out" }
        failure?.let { throw IllegalStateException("GPU scale: attach failed: ${it.message}", it) }
        if (!cameraStarted) {
            cameraStarted = true
            startCamera()
        } else {
            cameraFpsText?.let(newListener::onConfigured)
        }
    }

    /**
     * Stops drawing into the encoder [token]'s surface (before its codec is released). With
     * [keepAliveMs] > 0 and no encoder attached afterwards the camera stays open that long for the next
     * encoder (a rung change); otherwise it is released now.
     */
    fun detach(token: Any, keepAliveMs: Long) {
        val mine = synchronized(lock) {
            if (attachedToken !== token) false else {
                attachedToken = null
                listener = null
                true
            }
        }
        if (!mine) return // a newer encoder already took over
        val done = CountDownLatch(1)
        if (!glHandler.post { runCatching { renderer.detach() }; done.countDown() }) done.countDown()
        runCatching { done.await(GL_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        if (keepAliveMs <= 0L) {
            close()
            return
        }
        val expire = Runnable {
            val idle = synchronized(lock) { attachedToken == null }
            // close() waits for the camera thread, so it must not run on it.
            if (idle) Thread({ close() }, "GpuScaleClose").start()
        }
        synchronized(lock) { keepAliveClose = expire }
        cameraHandler.postDelayed(expire, keepAliveMs)
    }

    /** Frame-rate ceiling for the camera ([CameraVideoEncoder.updateFrameRate]); the capture mode stays. */
    fun setMaxFps(maximumFps: Int) {
        cameraHandler.post {
            val next = selectFpsRange(maximumFps)
            if (next == targetFpsRange) return@post
            targetFpsRange = next
            applyRepeatingRequest()
        }
    }

    /** Replaces a preview surface Android discarded while the activity was backgrounded. */
    fun refreshViewfinderSurface() {
        if (closed.get() || (camera == null && !rebuilder.busy)) return
        viewfinderSurface?.close()
        viewfinderSurface = null
        previewSurface = null
        CameraViewfinderSurface(
            context, viewfinder, characteristics, capture.width, capture.height, scaleMode,
            { surface ->
                if (!closed.get()) {
                    previewSurface = surface
                    cameraHandler.post { if (!closed.get()) rebuilder.rebuild(rebuildMode) }
                }
            },
            { error -> if (!closed.get()) fail(error) },
        ).also { replacement ->
            viewfinderSurface = replacement
            replacement.request()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        if (previewSurface != null) {
            openCamera()
            return
        }
        viewfinderSurface = CameraViewfinderSurface(
            context, viewfinder, characteristics, capture.width, capture.height, scaleMode,
            { surface ->
                cameraHandler.post {
                    if (!closed.get()) {
                        previewSurface = surface
                        openCamera()
                    }
                }
            },
            { error -> fail(IllegalStateException("Camera preview surface failed: ${error.message}", error)) },
        ).also(CameraViewfinderSurface::request)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        runCatching { cameraManager.openCamera(cameraId, cameraCallback, cameraHandler) }.onFailure { error ->
            fail(IllegalStateException("Camera could not open: ${error.message}", error))
        }
    }

    private val cameraCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(openedCamera: CameraDevice) {
            if (closed.get()) {
                openedCamera.close()
                return
            }
            camera = openedCamera
            openRetry.reset()
            // A reopen for a session rebuild creates its session through the rebuilder (V-01).
            if (rebuilder.onDeviceOpened()) return
            configureSession()
        }

        override fun onClosed(closedCamera: CameraDevice) {
            rebuilder.onDeviceClosed()
        }

        override fun onDisconnected(disconnectedCamera: CameraDevice) {
            disconnectedCamera.close()
            if (!closed.get()) fail(IllegalStateException("Camera disconnected."))
        }

        override fun onError(failedCamera: CameraDevice, error: Int) {
            failedCamera.close()
            if (closed.get()) return
            // Transient errors are retried, policy errors explained (V-05).
            val decision = openRetry.next(error)
            Log.i(TAG, CameraOpenPolicy.benchmarkLine(decision, error, openRetry.attemptOf(error), openRetry.elapsedMs) + " | path=gpu_scale")
            val delayMs = decision.retryDelayMs
            if (delayMs == null) {
                fail(IllegalStateException(decision.message))
                return
            }
            generation++
            session = null
            if (camera === failedCamera) camera = null
            rebuilder.cancel()
            cameraHandler.postDelayed({ if (!closed.get()) openCamera() }, delayMs)
        }
    }

    @Suppress("DEPRECATION")
    private fun configureSession() {
        val openedCamera = camera ?: return
        val glSurface = cameraOutputSurface ?: return
        val targets = listOfNotNull(previewSurface, glSurface)
        val mine = ++generation
        session?.close()
        session = null
        runCatching {
            openedCamera.createCaptureSession(
                targets,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (closed.get() || mine != generation) {
                            configured.close()
                            return
                        }
                        session = configured
                        applyRepeatingRequest()
                        Log.i(
                            TAG,
                            "BENCHMARK camera_session: path=gpu_scale | hardwareLevel=${CameraLevelCodes.name(hardwareLevel)} | " +
                                "streams=${listOfNotNull("preview".takeIf { previewSurface != null }, "gl").joinToString("+")} | " +
                                "rebuildMode=$rebuildMode | aeRange=${targetFpsRange ?: "default"} | requestedFps=${request.fps}",
                        )
                        val text = targetFpsRange?.let { "camera ${it.lower}–${it.upper} FPS" } ?: "camera default FPS"
                        val described = "$text, GPU scale ladder capture ${capture.width}×${capture.height}"
                        cameraFpsText = described
                        currentListener()?.onConfigured(described)
                    }

                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        failed.close()
                        if (mine == generation && !closed.get()) {
                            fail(IllegalStateException("Camera capture session could not be configured."))
                        }
                    }

                    override fun onClosed(closedSession: CameraCaptureSession) {
                        if (closedSession === closingSession) {
                            closingSession = null
                            rebuilder.onSessionClosed()
                        }
                    }
                },
                cameraHandler,
            )
        }.onFailure { error ->
            fail(IllegalStateException("Camera capture session could not start: ${error.message}", error))
        }
    }

    private fun applyRepeatingRequest() {
        val activeCamera = camera ?: return
        val activeSession = session ?: return
        val glSurface = cameraOutputSurface ?: return
        runCatching {
            val request = activeCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                previewSurface?.let(::addTarget)
                addTarget(glSurface)
                if (continuousAutoFocus) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
                targetFpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }.build()
            activeSession.setRepeatingRequest(request, null, cameraHandler)
        }.onFailure { error ->
            fail(IllegalStateException("Camera frame-rate update failed: ${error.message}", error))
        }
    }

    /** The same range choice as [CameraVideoEncoder] ([CameraFpsRanges]): the highest ceiling not above the limit. */
    private fun selectFpsRange(maximumFps: Int): Range<Int>? {
        val legacy = CameraSwitches.legacyAeRange && CameraLevelCodes.isLegacy(hardwareLevel)
        val chosen = CameraFpsRanges.select(availableFpsRanges.map { FpsRange(it.lower, it.upper) }, maximumFps, legacy)
            ?: return null
        return availableFpsRanges.first { it.lower == chosen.lower && it.upper == chosen.upper }
    }

    /** Releases the camera, the preview surface and the GL context. Idempotent. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            if (current === this) current = null
            keepAliveClose?.let(cameraHandler::removeCallbacks)
            keepAliveClose = null
        }
        val released = CountDownLatch(2)
        cameraHandler.post {
            try {
                generation++
                rebuilder.cancel()
                runCatching { session?.stopRepeating() }
                runCatching { session?.close() }
                session = null
                runCatching { camera?.close() }
                camera = null
            } finally {
                released.countDown()
            }
        }
        glHandler.post {
            try {
                runCatching { renderer.release() }
            } finally {
                released.countDown()
            }
        }
        runCatching { released.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        viewfinderSurface?.close()
        viewfinderSurface = null
        previewSurface = null
        cameraThread.quitSafely()
        glThread.quitSafely()
        Log.i(TAG, "BENCHMARK gpu_scale: capture released | capture=${capture.width}x${capture.height} | lens=$lens")
    }

    companion object {
        private const val TAG = "BluetoothVideo"
        private const val GL_TIMEOUT_SECONDS = 3L
        private const val CLOSE_TIMEOUT_SECONDS = 5L

        /** How long a rung change may leave the camera without an encoder before it is released. */
        const val RESTART_KEEP_ALIVE_MS = 2_500L

        private val registryLock = Any()
        @Volatile private var current: GpuScaleCapture? = null

        /** Camera output sizes for a SurfaceTexture consumer and the frame rate each sustains. */
        fun captureModes(characteristics: CameraCharacteristics): List<GpuScaleMath.CaptureMode> {
            val map = safeCameraRead("SCALER_STREAM_CONFIGURATION_MAP") {
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            } ?: return emptyList()
            val aeRanges = safeCameraRead("CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES") {
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            }?.toList().orEmpty()
            return map.getOutputSizes(SurfaceTexture::class.java).orEmpty().map { size ->
                val minDuration = runCatching { map.getOutputMinFrameDuration(SurfaceTexture::class.java, size) }.getOrDefault(0L)
                val maxFps = if (minDuration > 0L) {
                    floor(1_000_000_000.0 / minDuration).toInt().coerceAtLeast(1)
                } else {
                    aeRanges.maxOfOrNull(Range<Int>::getUpper) ?: 30
                }
                GpuScaleMath.CaptureMode(size.width, size.height, maxFps)
            }
        }

        /**
         * The live capture for these arguments, or a new one (the previous capture, if any, is released
         * first: one camera at a time). A surface the caller hands over ([reusableViewfinderSurface]) is
         * adopted by a new capture and closed when an existing one is reused.
         */
        fun obtain(
            context: Context,
            viewfinder: ViewfinderView,
            lens: CameraLens,
            scaleMode: VideoScaleMode,
            request: GpuScaleRequest,
            reusableViewfinderSurface: Pair<Surface, CameraViewfinderSurface>?,
        ): GpuScaleCapture = synchronized(registryLock) {
            val existing = current
            if (existing != null && !existing.isClosed && existing.lens == lens && existing.viewfinder === viewfinder &&
                existing.request == request
            ) {
                reusableViewfinderSurface?.second?.close()
                return@synchronized existing
            }
            existing?.close()
            val manager = context.getSystemService(CameraManager::class.java)
            val cameraId = manager.cameraIdFor(lens) ?: error("${lens.displayName} camera is unavailable")
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val mode = GpuScaleMath.chooseCapture(captureModes(characteristics), request.session, request.fps)
                ?: error("${lens.displayName} camera offers no capture mode for GPU scaling")
            val created = GpuScaleCapture(
                context, viewfinder, lens, scaleMode, cameraId, characteristics, mode, request,
                reusableViewfinderSurface?.first, reusableViewfinderSurface?.second,
            )
            try {
                created.initialize()
            } catch (error: Throwable) {
                created.close()
                throw error
            }
            Log.i(
                TAG,
                "BENCHMARK gpu_scale: capture opened | session=${request.session.width}x${request.session.height}@${request.fps} | " +
                    "capture=${mode.width}x${mode.height} (max ${mode.maxFps} FPS) | lens=$lens",
            )
            current = created
            created
        }
    }
}
