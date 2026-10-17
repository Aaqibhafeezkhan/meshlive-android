package com.example.bluetoothtest

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.camera.viewfinder.core.ScaleType
import androidx.camera.viewfinder.core.ViewfinderSurfaceRequest
import androidx.camera.viewfinder.core.ViewfinderSurfaceSession
import androidx.camera.viewfinder.core.camera2.Camera2TransformationInfo
import androidx.camera.viewfinder.view.ViewfinderView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class CameraViewfinderSurface(
    context: Context,
    private val viewfinder: ViewfinderView,
    private val characteristics: CameraCharacteristics,
    private val width: Int,
    private val height: Int,
    private val scaleMode: VideoScaleMode,
    private val onSurface: (Surface) -> Unit,
    private val onError: (Throwable) -> Unit,
) : Closeable {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val closed = AtomicBoolean(false)
    private var future: ListenableFuture<ViewfinderSurfaceSession>? = null
    private var session: ViewfinderSurfaceSession? = null

    fun request() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainExecutor.execute {
                runCatching { request() }.onFailure { error ->
                    if (!closed.get()) onError(error)
                }
            }
            return
        }
        if (closed.get()) return
        viewfinder.scaleType = when (scaleMode) {
            VideoScaleMode.FILL -> ScaleType.FILL_CENTER
            VideoScaleMode.FIT -> ScaleType.FIT_CENTER
        }
        viewfinder.transformationInfo =
            Camera2TransformationInfo.createFromCharacteristics(characteristics)
        // V-21: the facts the preview transform follows (a SurfaceView is transformed by the viewfinder, never by
        // scaleX/scaleY), so a rotated or mis-framed preview on large-sensor phones can be read from the log.
        runCatching {
            val sensorOrientation = safeCameraRead("SENSOR_ORIENTATION") { characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) }
            val facing = safeCameraRead("LENS_FACING") { characteristics.get(CameraCharacteristics.LENS_FACING) }
            Log.i(
                "BluetoothVideo",
                "BENCHMARK camera_transform: scaleMode=$scaleMode | sensorOrientation=${sensorOrientation ?: "unknown"} | " +
                    "lensFacing=${facing ?: "unknown"} | surface=${width}x$height",
            )
        }
        val pending = viewfinder.requestSurfaceSessionAsync(
            ViewfinderSurfaceRequest(width, height),
        )
        future = pending
        pending.addListener({
            try {
                val activeSession = pending.get()
                if (closed.get()) {
                    activeSession.close()
                } else {
                    session = activeSession
                    onSurface(activeSession.surface)
                }
            } catch (_: CancellationException) {
                // Closing the camera cancels a pending surface request.
            } catch (error: Exception) {
                if (!closed.get()) onError(error.cause ?: error)
            }
        }, mainExecutor)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val release = Runnable {
            future?.cancel(true)
            future = null
            session?.close()
            session = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) release.run()
        else mainExecutor.execute(release)
    }
}
