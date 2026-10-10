package com.example.bluetoothtest

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Host round trip through the real JNI bridge and libopus. Android's library cannot load on the host
 * JVM, so this runs only with a desktop build: configure `app/src/main/cpp` with
 * `-DBTV_OPUS_HOST_TEST=ON` (needs a host C/C++ compiler and the JDK's JNI headers) and set
 * `BTV_NATIVE_OPUS_HOST_LIB` to the built library before `:app:testDebugUnitTest`. Skipped otherwise.
 */
class NativeOpusHostRoundTripTest {
    private val sampleRate = 48_000
    private val frameSamples = 960

    /** Voiced-speech-like test tone: a 140 Hz harmonic series under a 4 Hz syllable envelope. */
    private fun speechLike(frames: Int): ShortArray = ShortArray(frames * frameSamples) { index ->
        val t = index.toDouble() / sampleRate
        val envelope = 0.55 + 0.45 * sin(2 * PI * 4.0 * t)
        var value = 0.0
        for (harmonic in 1..12) value += sin(2 * PI * 140.0 * harmonic * t + harmonic) / harmonic
        (value * envelope * 6_000).toInt().coerceIn(-32_768, 32_767).toShort()
    }

    private fun frameBytes(signal: ShortArray, frame: Int): ByteArray {
        val buffer = ByteBuffer.allocate(frameSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameSamples) buffer.putShort(signal[frame * frameSamples + i])
        return buffer.array()
    }

    private fun samples(pcm: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(pcm.size / 2) { buffer.short }
    }

    private fun rms(values: ShortArray): Double = sqrt(values.sumOf { it.toDouble() * it } / values.size.coerceAtLeast(1))

    private fun errorRms(a: ShortArray, b: ShortArray): Double =
        sqrt(a.indices.sumOf { (a[it] - b[it]).toDouble().let { d -> d * d } } / a.size)

    @Test
    fun voipEncodeDecodeConcealAndFecRecover() {
        assumeTrue("native Opus host library not built (${NativeOpusBridge.HOST_LIBRARY_ENV})", NativeOpusBridge.available)
        assertTrue(NativeOpusBridge.nativeOpusVersion().contains("1.5.2"))
        val frames = 60
        val lost = 40
        val signal = speechLike(frames)
        val encoder = NativeOpusEncoder(sampleRate, 1, 32_000, 10, NativeOpusSettings())
        val packets = encoder.use { active ->
            assertEquals(312, active.lookaheadSamples)
            List(frames) { active.encode(frameBytes(signal, it), frameSamples) }
        }
        assertTrue(packets.all { it.size in 3..400 })

        // Reference decode without loss, and a decode with packet `lost` missing.
        val reference = NativeOpusDecoder(sampleRate, 1).use { decoder -> packets.map { samples(decoder.decode(it)) } }
        assertTrue(reference.all { it.size == frameSamples })
        assertTrue(rms(reference[lost]) > 1_000.0)
        val (plcFrame, fecFrame) = Pair(
            NativeOpusDecoder(sampleRate, 1).use { decoder ->
                for (i in 0 until lost) decoder.decode(packets[i])
                samples(decoder.conceal(frameSamples))
            },
            NativeOpusDecoder(sampleRate, 1).use { decoder ->
                for (i in 0 until lost) decoder.decode(packets[i])
                assertTrue("loss hint 10% must put LBRR in the next packet", decoder.packetHasFec(packets[lost + 1]))
                samples(decoder.recoverFromFec(packets[lost + 1], frameSamples))
            },
        )
        assertEquals(frameSamples, plcFrame.size)
        assertEquals(frameSamples, fecFrame.size)
        // PLC fills the gap with plausible audio, not silence: comparable energy to the real frame.
        assertTrue("PLC must not be silence", rms(plcFrame) > 0.25 * rms(reference[lost]))
        // FEC reconstructs the lost frame much better than silence would.
        assertTrue("FEC must beat silence", errorRms(fecFrame, reference[lost]) < 0.7 * rms(reference[lost]))
    }
}
