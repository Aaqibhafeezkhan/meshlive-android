package com.example.bluetoothtest

import android.media.MediaCodec
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Design VX smoke test: the bundled libvpx encodes 30 generated I420 frames into a standard VP9 stream (one frame per
 * packet, one keyframe, error-resilient frame headers) that the phone's own MediaCodec VP9 decoder decodes in full, and
 * its CBR holds the requested bitrate within 15% over 90 frames of complex generated motion.
 */
@RunWith(AndroidJUnit4::class)
class NativeVpxEncoderSmokeTest {
    private val width = 320
    private val height = 240
    private val fps = 15
    private val frames = 30

    /** A moving gradient with a bright square, so inter frames carry real motion. */
    private fun frame(index: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(width * height * 3 / 2)
        for (y in 0 until height) for (x in 0 until width) {
            val square = x in (index * 6) % width until (index * 6) % width + 40 && y in 80 until 120
            buffer.put(y * width + x, (if (square) 235 else (x + y + index * 3) and 0xff).toByte())
        }
        val chroma = width * height
        for (i in 0 until width * height / 4) {
            buffer.put(chroma + i, (128 + (i % 16)).toByte())
            buffer.put(chroma + width * height / 4 + i, (128 - (i % 16)).toByte())
        }
        return buffer
    }

    private class Bits(private val bytes: ByteArray) {
        private var position = 0
        fun read(count: Int): Int {
            var value = 0
            repeat(count) {
                val bit = (bytes[position / 8].toInt() shr (7 - position % 8)) and 1
                value = (value shl 1) or bit
                position++
            }
            return value
        }
    }

    /** VP9 uncompressed header (VP9 bitstream specification 6.2) of a profile 0 frame. */
    private data class Vp9Header(val keyFrame: Boolean, val errorResilient: Boolean, val width: Int?, val height: Int?)

    private fun parseHeader(bytes: ByteArray): Vp9Header {
        val bits = Bits(bytes)
        assertEquals("frame_marker", 2, bits.read(2))
        val profile = bits.read(1) or (bits.read(1) shl 1)
        assertEquals("profile", 0, profile)
        assertEquals("show_existing_frame", 0, bits.read(1))
        val keyFrame = bits.read(1) == 0
        assertEquals("show_frame", 1, bits.read(1))
        val errorResilient = bits.read(1) == 1
        if (!keyFrame) return Vp9Header(false, errorResilient, null, null)
        assertEquals("frame_sync_code", 0x498342, bits.read(24))
        val colorSpace = bits.read(3)
        if (colorSpace != 7) bits.read(1) // color_range (profile 0 has no subsampling bits)
        return Vp9Header(true, errorResilient, bits.read(16) + 1, bits.read(16) + 1)
    }

    @Test
    fun encodesThirtyFramesIntoADecodableVp9Stream() {
        assertTrue("native library loaded", NativeVpxBridge.available)
        assertTrue(NativeVpxBridge.nativeVersion().contains("1.17.0"))
        val packets = NativeVpxEncoder(
            width, height, fps, bitrate = 300_000, cpuUsed = 8, threads = 2,
            variableBitrate = false, dropFrames = false,
        ).use { encoder ->
            (0 until frames).flatMap { index ->
                encoder.encode(frame(index), index * 1_000_000L / fps, forceKeyFrame = index == 0, layer = -1).toList()
            } + encoder.flush().toList()
        }
        assertEquals("one packet per frame, none dropped", frames, packets.size)
        val headers = packets.map { parseHeader(it.bytes) }
        assertTrue(packets.first().keyFrame)
        assertEquals(Vp9Header(true, true, width, height), headers.first())
        assertFalse("kf_mode is disabled: only the requested keyframe", packets.drop(1).any { it.keyFrame || parseHeader(it.bytes).keyFrame })
        assertTrue("error resilient", headers.all { it.errorResilient })
        assertTrue(packets.all { it.quantizer in 0..63 })
        assertEquals((0 until frames).map { it * 1_000_000L / fps }, packets.map { it.presentationTimeUs })

        assertEquals(frames, decodeWithMediaCodec(packets))
    }

    /**
     * A fine random texture (2x2-pixel cells, full luma range) panning 5 px right and 3 px down per frame: hard for
     * real-time motion search, so its rate depends on the quantizer ceiling. Same integer math as the host calibration
     * (FFmpeg's libvpx-vp9 at this file's settings, 300 kbps, cpu-used 8, 90 frames): max quantizer 63 -> 311.8 kbps,
     * the former cap of 56 -> 486.9 kbps.
     */
    private fun panningTexture(index: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(width * height * 3 / 2)
        for (y in 0 until height) for (x in 0 until width) {
            var a = ((x + 5 * index) shr 1) * 0x9E3779B1.toInt() xor ((y + 3 * index) shr 1) * 0x85EBCA77.toInt()
            a = a xor (a ushr 16)
            a *= 0x7FEB352D
            a = a xor (a ushr 15)
            a *= 0x846CA68B.toInt()
            a = a xor (a ushr 16)
            buffer.put(y * width + x, (16 + (a and 0xff) * 219 / 255).toByte())
        }
        for (i in width * height until width * height * 3 / 2) buffer.put(i, 128.toByte())
        return buffer
    }

    /** CBR holds the requested rate on complex motion without dropping frames (the 2.2x overshoot of max QP 56). */
    @Test
    fun cbrHoldsTheTargetBitrateOnComplexMotion() {
        assertTrue("native library loaded", NativeVpxBridge.available)
        val targetBps = 300_000
        val count = 90
        val packets = NativeVpxEncoder(
            width, height, fps, bitrate = targetBps, cpuUsed = 8, threads = 2,
            variableBitrate = false, dropFrames = false,
        ).use { encoder ->
            (0 until count).flatMap { index ->
                encoder.encode(panningTexture(index), index * 1_000_000L / fps, forceKeyFrame = index == 0, layer = -1)
                    .toList()
            } + encoder.flush().toList()
        }
        assertEquals("one packet per frame, none dropped", count, packets.size)
        val achievedBps = packets.sumOf { it.bytes.size.toLong() } * 8.0 * fps / count
        assertTrue(
            "achieved ${"%.1f".format(achievedBps / 1000)} kbps for ${targetBps / 1000} kbps",
            achievedBps in targetBps * 0.85..targetBps * 1.15,
        )
    }

    private fun decodeWithMediaCodec(packets: List<NativeAomPacket>): Int {
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_VP9)
        try {
            decoder.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_VP9, width, height), null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var queued = 0
            var decoded = 0
            var endQueued = false
            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                if (!endQueued) {
                    val input = decoder.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        if (queued < packets.size) {
                            val packet = packets[queued++]
                            checkNotNull(decoder.getInputBuffer(input)).put(packet.bytes)
                            decoder.queueInputBuffer(input, 0, packet.bytes.size, packet.presentationTimeUs, 0)
                        } else {
                            decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            endQueued = true
                        }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, 10_000)
                if (output >= 0) {
                    if (info.size > 0) decoded++
                    decoder.releaseOutputBuffer(output, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return decoded
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
        }
    }
}
