package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class AudioConcealmentTest {
    private val rate = 48_000

    private fun sine(hz: Double, samples: Int, amplitude: Double = 8_000.0, offset: Int = 0) =
        ShortArray(samples) { (amplitude * sin(2 * PI * hz * (it + offset) / rate)).roundToInt().toShort() }

    @Test
    fun pcm16RoundTrips() {
        val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 12345, -12345)
        assertArrayEquals(samples, pcm16ToShorts(shortsToPcm16(samples)))
        assertEquals(byteArrayOf(0x39, 0x30).toList(), shortsToPcm16(shortArrayOf(12345)).toList())
    }

    @Test
    fun findsThePitchPeriodOfVoicedAudio() {
        val concealer = AudioConcealer(rate)
        concealer.observe(sine(200.0, 1_920)) // 40 ms of a 200 Hz tone: period 240 samples
        val (period, voicing) = concealer.pitch()
        assertEquals(240.0, period.toDouble(), 2.0)
        assertTrue("voicing $voicing", voicing > 0.95)
    }

    @Test
    fun continuesVoicedAudioSmoothlyThenFadesToComfortNoise() {
        val concealer = AudioConcealer(rate)
        val history = sine(200.0, 1_920)
        concealer.observe(history)
        val out = concealer.conceal(480) // 10 ms
        // The first concealed sample continues the waveform (no click at the junction).
        val expectedNext = (8_000.0 * sin(2 * PI * 200.0 * 1_920 / rate))
        assertEquals(expectedNext, out[0].toDouble(), 400.0)
        assertTrue(concealer.concealing)
        assertEquals(1L, concealer.events)
        // After the 60 ms fade only comfort noise remains, capped near -50 dBFS.
        concealer.conceal(48 * 60)
        val tail = concealer.conceal(480)
        assertTrue("tail rms ${rmsOf(tail)}", rmsOf(tail) <= AudioConcealer.COMFORT_MAX_RMS * 1.2)
        assertTrue("early rms ${rmsOf(out)}", rmsOf(out) > 3_000.0)
        assertEquals(480L + 48 * 60 + 480, concealer.concealedSamples)
        assertEquals(48L * 60, concealer.expandSamples)
        assertEquals(960L, concealer.comfortSamples)
    }

    @Test
    fun digitalSilenceConcealsAsSilence() {
        val concealer = AudioConcealer(rate)
        concealer.observe(ShortArray(1_920))
        val out = concealer.conceal(4_800)
        assertTrue(out.all { it.toInt() == 0 })
    }

    @Test
    fun mergeCrossFadesIntoRealAudioAndEndsTheEvent() {
        val concealer = AudioConcealer(rate)
        concealer.observe(sine(200.0, 1_920))
        val concealed = concealer.conceal(480)
        val real = sine(200.0, 960, offset = 1_920 + 480)
        val original = real.copyOf()
        assertEquals(480, concealer.merge(real))
        assertFalse(concealer.concealing)
        // The join between the last concealed sample and the merged start has no jump.
        assertTrue(abs(real[0] - concealed.last()) < 1_500)
        // Beyond the 5 ms merge window the real audio is untouched.
        val merge = rate * AudioConcealer.MERGE_MS / 1_000
        for (i in merge until real.size) assertEquals(original[i], real[i])
        // Nothing to merge when not concealing.
        assertEquals(0, concealer.merge(real))
    }

    @Test
    fun comfortNoiseFollowsTheBackgroundLevel() {
        val concealer = AudioConcealer(rate)
        val random = java.util.Random(1)
        val background = ShortArray(4_800) { ((random.nextDouble() * 2 - 1) * 50).toInt().toShort() }
        concealer.observe(background)
        assertTrue("noise ${concealer.noiseRms}", concealer.noiseRms in 15.0..45.0)
        concealer.observe(sine(200.0, 960)) // speech raises the floor only slowly
        assertTrue("noise ${concealer.noiseRms}", concealer.noiseRms < 200.0)
    }

    @Test
    fun scheduleConcealsOnlyWhenTheTrackIsNearlyDry() {
        assertTrue(ConcealmentSchedule.shouldConceal(audioStarted = true, primed = true, queuedMs = 10))
        assertFalse(ConcealmentSchedule.shouldConceal(audioStarted = true, primed = true, queuedMs = 60))
        assertFalse(ConcealmentSchedule.shouldConceal(audioStarted = false, primed = true, queuedMs = 0))
        assertFalse(ConcealmentSchedule.shouldConceal(audioStarted = true, primed = false, queuedMs = 0))
    }

    @Test
    fun catchUpAcceleratesOnlyInSilenceUnlessTheBacklogIsLarge() {
        val g = SilenceGatedCatchUp
        assertEquals(1.04f, g.speed(1.04f, chunkRms = 100.0, excessLagMs = 120), 0f)
        assertEquals(1.0f, g.speed(1.04f, chunkRms = 3_000.0, excessLagMs = 120), 0f)
        assertEquals(1.04f, g.speed(1.04f, chunkRms = 3_000.0, excessLagMs = 500), 0f)
        // Slowing down to rebuild the delay is unchanged.
        assertEquals(0.97f, g.speed(0.97f, chunkRms = 3_000.0, excessLagMs = -100), 0f)
        assertEquals(1.0f, g.speed(1.0f, chunkRms = 3_000.0, excessLagMs = 0), 0f)
    }
}
