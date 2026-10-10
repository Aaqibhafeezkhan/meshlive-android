package com.example.bluetoothtest

import android.hardware.camera2.CameraManager
import androidx.camera.viewfinder.core.ScaleType
import androidx.camera.viewfinder.view.ViewfinderView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraViewfinderSurfaceTest {
    @Test
    fun backgroundRequestClosedBeforeMainExecutionDoesNotTouchViewfinder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val cameraManager = context.getSystemService(CameraManager::class.java)
        val characteristics = cameraManager.getCameraCharacteristics(cameraManager.cameraIdList.first())
        val callbacks = AtomicInteger()
        val workerFailure = AtomicReference<Throwable?>()
        lateinit var viewfinder: ViewfinderView

        instrumentation.runOnMainSync {
            viewfinder = ViewfinderView(context).apply { scaleType = ScaleType.FIT_CENTER }
            val owner = CameraViewfinderSurface(
                context,
                viewfinder,
                characteristics,
                640,
                360,
                VideoScaleMode.FILL,
                { callbacks.incrementAndGet() },
                { callbacks.incrementAndGet() },
            )
            val worker = Thread({
                try {
                    owner.request()
                } catch (error: Throwable) {
                    workerFailure.set(error)
                } finally {
                    owner.close()
                }
            }, "ViewfinderCancellationTest")
            worker.start()
            // Hold main until cancellation so its queued request cannot run first.
            worker.join(2_000)
            assertFalse("Background request must not wait for main", worker.isAlive)
        }
        instrumentation.waitForIdleSync()

        assertNull(workerFailure.get())
        assertEquals(0, callbacks.get())
        instrumentation.runOnMainSync {
            assertEquals(ScaleType.FIT_CENTER, viewfinder.scaleType)
        }
    }
}
