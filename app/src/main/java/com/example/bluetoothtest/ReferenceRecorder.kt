package com.example.bluetoothtest

import android.media.Image
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

class ReferenceRecorder(
    directory: File,
    private val width: Int,
    private val height: Int,
    private val frameLimit: Int,
) : Closeable {
    private val videoOutput: BufferedOutputStream
    private val timestampOutput: java.io.BufferedWriter
    private var frames = 0
    private var closed = false

    init {
        directory.mkdirs()
        videoOutput = File(directory, "reference.yuv").outputStream().buffered(4 * 1024 * 1024)
        timestampOutput = File(directory, "reference.timestamps.csv").bufferedWriter().apply {
            write("frame,presentation_time_us\n")
        }
    }

    @Synchronized
    /**
     * Records the frame the encoder was given: the centre crop of [visible] (the encoder's
     * Image.getCropRect region, the whole image by default) scaled to width x height, as I420.
     */
    fun record(
        image: Image,
        visible: PlaneRegion = YuvPlaneCopy.visibleRegion(image.width, image.height, null),
    ) {
        if (closed || frames >= frameLimit) return
        val bytes = ByteArray(YuvPlaneCopy.i420Bytes(width, height))
        val target = ByteBuffer.wrap(bytes)
        var outputOffset = 0
        image.planes.forEachIndexed { planeIndex, plane ->
            val planeWidth = if (planeIndex == 0) width else YuvPlaneCopy.chromaSize(width)
            val planeHeight = if (planeIndex == 0) height else YuvPlaneCopy.chromaSize(height)
            val crop = YuvPlaneCopy.centerCropWithin(YuvPlaneCopy.planeRegion(visible, planeIndex), planeWidth, planeHeight)
            val buffer = plane.buffer
            check(
                YuvPlaneCopy.copyPlane(
                    buffer, buffer.position(), plane.rowStride, plane.pixelStride, crop,
                    target, outputOffset, planeWidth, 1, planeWidth, planeHeight,
                ),
            ) { "Reference plane $planeIndex layout is outside its buffer" }
            outputOffset += planeWidth * planeHeight
        }
        videoOutput.write(bytes)
        timestampOutput.write("$frames,${image.timestamp / 1_000}\n")
        frames++
        if (frames == frameLimit) {
            videoOutput.flush()
            timestampOutput.flush()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        videoOutput.close()
        timestampOutput.close()
    }
}
