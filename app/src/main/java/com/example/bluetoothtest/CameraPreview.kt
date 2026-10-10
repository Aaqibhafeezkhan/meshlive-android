package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import androidx.camera.viewfinder.view.ViewfinderView
import android.view.Surface
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class CameraPreview(
    private val context: Context,
    private val viewfinder: ViewfinderView,
    private val cameraLens: CameraLens,
    private val resolution: VideoResolution,
    private val scaleMode: VideoScaleMode,
    private val onError: (String) -> Unit,
) : Closeable {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val workerThread = HandlerThread("CameraPreview").apply { start() }
    private val worker = Handler(workerThread.looper)
    private val closed = AtomicBoolean(false)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var viewfinderSurface: CameraViewfinderSurface? = null
    private var continuousAutoFocusAvailable = false

    @SuppressLint("MissingPermission")
    fun start() {
        val cameraId = cameraManager.cameraIdFor(cameraLens)
            ?: error("${cameraLens.displayName} camera is unavailable")
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        continuousAutoFocusAvailable = characteristics
            .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true
        viewfinderSurface = CameraViewfinderSurface(
            context,
            viewfinder,
            characteristics,
            resolution.width,
            resolution.height,
            scaleMode,
            { surface ->
                if (!closed.get()) {
                    previewSurface = surface
                    cameraManager.openCamera(cameraId, cameraCallback, worker)
                }
            },
            { error -> onError("Camera preview surface failed: ${error.message ?: error.javaClass.simpleName}") },
        ).also(CameraViewfinderSurface::request)
    }

    private val cameraCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(openedCamera: CameraDevice) {
            if (closed.get()) {
                openedCamera.close()
                return
            }
            camera = openedCamera
            val target = previewSurface ?: return
            openedCamera.createCaptureSession(
                listOf(target),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configuredSession: CameraCaptureSession) {
                        if (closed.get()) {
                            configuredSession.close()
                            return
                        }
                        session = configuredSession
                        val request = openedCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(target)
                            if (continuousAutoFocusAvailable) {
                                set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                                )
                            }
                        }.build()
                        configuredSession.setRepeatingRequest(request, null, worker)
                    }

                    override fun onConfigureFailed(failedSession: CameraCaptureSession) {
                        onError("Camera preview could not be configured.")
                    }
                },
                worker,
            )
        }

        override fun onDisconnected(disconnectedCamera: CameraDevice) {
            disconnectedCamera.close()
            onError("Camera preview disconnected.")
        }

        override fun onError(failedCamera: CameraDevice, error: Int) {
            failedCamera.close()
            onError("Camera preview error $error")
        }
    }

    fun detachViewfinderSurface(): Pair<Surface, CameraViewfinderSurface>? {
        val surface = previewSurface ?: return null
        val owner = viewfinderSurface ?: return null
        previewSurface = null
        viewfinderSurface = null
        return surface to owner
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        session?.close()
        session = null
        camera?.close()
        camera = null
        previewSurface = null
        viewfinderSurface?.close()
        viewfinderSurface = null
        workerThread.quitSafely()
    }
}
