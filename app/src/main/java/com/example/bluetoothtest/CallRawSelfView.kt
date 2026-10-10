package com.example.bluetoothtest

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DEBUG benchmark self-view: shows the raw I420 fixture frame that the call encoder is reading,
 * as a small thumbnail at a low rate. It reads the fixture file on its own background thread
 * from the encoder's published raw clock, so it never touches the encoder thread, its buffers or
 * its timing. Nothing is shown until the encoder's raw feed has started.
 */
internal class CallRawSelfView(
    private val file: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val displayFps: Int,
    private val clock: () -> RawReferenceClock?,
    private val onFrame: (Bitmap) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val framePending = AtomicBoolean(false)
    @Volatile private var paused = false
    private val thread = HandlerThread("CallRawSelfView", Process.THREAD_PRIORITY_BACKGROUND)
    private lateinit var handler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val step = rawSelfViewStep(width, height)
    private val outWidth = width / step
    private val outHeight = height / step
    private val frameBytes = width * height * 3 / 2
    private var input: RandomAccessFile? = null
    private var frame: ByteArray? = null
    private var pixels: IntArray? = null
    private val bitmaps = arrayOfNulls<Bitmap>(2)
    private var nextBitmap = 0

    fun start() {
        thread.start()
        handler = Handler(thread.looper)
        handler.post(tick)
    }

    /** Stops reading while the activity is in the background. */
    fun setPaused(value: Boolean) {
        paused = value
    }

    private val tick = object : Runnable {
        override fun run() {
            if (closed.get()) return
            if (!paused && !framePending.get()) {
                runCatching { renderCurrentFrame() }.onFailure { error ->
                    Log.w("BluetoothVideo", "Call raw self-view stopped: ${error.message}")
                    return
                }
            }
            handler.postDelayed(this, 1_000L / displayFps.coerceAtLeast(1))
        }
    }

    private fun renderCurrentFrame() {
        val rawClock = clock() ?: return
        val source = input ?: RandomAccessFile(file, "r").also { input = it }
        val frameCount = source.length() / frameBytes
        if (frameCount <= 0L) return
        val buffer = frame ?: ByteArray(frameBytes).also { frame = it }
        val argb = pixels ?: IntArray(outWidth * outHeight).also { pixels = it }
        val index = rawFixtureFrameIndex(System.nanoTime(), rawClock.monotonicOriginNs, fps, frameCount)
        source.seek(index * frameBytes)
        source.readFully(buffer)
        i420ToArgbSubsampled(buffer, width, height, step, argb)
        // Two bitmaps alternate: the one being written is never the one on screen, because a new
        // frame is prepared only after the main thread has taken the previous one.
        val bitmap = bitmaps[nextBitmap]
            ?: Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888).also { bitmaps[nextBitmap] = it }
        nextBitmap = 1 - nextBitmap
        bitmap.setPixels(argb, 0, outWidth, 0, 0, outWidth, outHeight)
        framePending.set(true)
        mainHandler.post {
            framePending.set(false)
            if (!closed.get()) onFrame(bitmap)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (!::handler.isInitialized) {
            thread.quit()
            return
        }
        handler.post {
            runCatching { input?.close() }
            input = null
            thread.quitSafely()
        }
    }
}
