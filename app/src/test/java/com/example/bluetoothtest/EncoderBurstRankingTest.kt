package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderBurstRankingTest {
    private val omxVp9 = VideoCodec(
        displayName = "VP9 - hardware",
        mimeType = "video/x-vnd.on2.vp9",
        protocolId = StreamProtocol.CODEC_VP9,
        encoderName = "OMX.Exynos.VP9.Encoder",
        hardwareAccelerated = true,
        usesSurfaceInput = true,
        bitrateMode = null,
        supportedBitrateModes = emptySet(),
        complexity = null,
    )
    private val libaom = omxVp9.copy(
        displayName = "AV1 - optimized software",
        mimeType = "video/av01",
        protocolId = StreamProtocol.CODEC_AV1,
        encoderName = "libaom-native",
        hardwareAccelerated = false,
        usesSurfaceInput = false,
        backend = VideoEncoderBackend.NATIVE_LIBAOM,
    )
    private val staticOrder = listOf(omxVp9, libaom).sortedBy(VideoCodec::selectionPriority)

    @Test
    fun theStaticOrderPutsHardwareFirst() {
        assertEquals(listOf(omxVp9, libaom), staticOrder)
    }

    @Test
    fun anEncoderMeasuredToKeepCutsSmallRanksAboveOneMeasuredToBurst() {
        // M31 (lane-b): OMX VP9 135 KB at 600 kbps = 1.8 s; libaom 26 KB = 0.35 s, encode p95 40/66.7 ms.
        val records = mapOf(
            omxVp9.encoderName to EncoderBurstRecord(1.8, null, 3, 0),
            libaom.encoderName to EncoderBurstRecord(0.35, 0.6, 5, 0),
        )
        assertEquals(listOf(libaom, omxVp9), EncoderBurstRanking.order(staticOrder) { records[it] })
    }

    @Test
    fun withoutAFittingMeasurementTheStaticOrderStays() {
        assertEquals(staticOrder, EncoderBurstRanking.order(staticOrder) { null })
        // A software encoder that cannot keep up does not fit, however small its cuts.
        val overloaded = mapOf(libaom.encoderName to EncoderBurstRecord(0.3, 0.95, 2, 0))
        assertEquals(staticOrder, EncoderBurstRanking.order(staticOrder) { overloaded[it] })
    }

    @Test
    fun unmeasuredEncodersRankBetweenFittingAndBurstingOnes() {
        val hardwareAv1 = omxVp9.copy(encoderName = "c2.exynos.av1.encoder", protocolId = StreamProtocol.CODEC_AV1)
        val codecs = listOf(hardwareAv1, omxVp9, libaom)
        val records = mapOf(
            hardwareAv1.encoderName to EncoderBurstRecord(1.2, null, 1, 0),
            libaom.encoderName to EncoderBurstRecord(0.4, 0.5, 1, 0),
        )
        assertEquals(listOf(libaom, omxVp9, hardwareAv1), EncoderBurstRanking.order(codecs) { records[it] })
    }

    @Test
    fun aSessionRecordsItsLargestFrameInSecondsOfItsOwnStream() {
        val accumulator = EncoderBurstAccumulator()
        repeat(149) { accumulator.record(5_000, 600_000) }
        assertNull(accumulator.sessionBurstSeconds(minFrames = 150))
        accumulator.record(134_918, 600_000)
        assertEquals(134_918 * 8.0 / 600_000, accumulator.sessionBurstSeconds(minFrames = 150)!!, 1e-9)
        assertEquals(134_918, accumulator.largestBytes)
        accumulator.record(0, 600_000)
        accumulator.record(1_000, 0)
        assertEquals(150, accumulator.frames)
    }

    @Test
    fun aLargerBurstReplacesTheRecordAndCalmSessionsLowerItSlowly() {
        val first = EncoderBurstRanking.merge(null, 1.8, null, 1)
        assertEquals(1.8, first.burstSeconds, 1e-9)
        assertEquals(1, first.sessions)
        assertFalse(first.fits)
        // A session that never met a scene cut barely lowers it.
        val calm = EncoderBurstRanking.merge(first, 0.1, null, 2)
        assertEquals(1.8 * 0.8 + 0.1 * 0.2, calm.burstSeconds, 1e-9)
        assertEquals(2, calm.sessions)
        val worse = EncoderBurstRanking.merge(calm, 2.5, null, 3)
        assertEquals(2.5, worse.burstSeconds, 1e-9)
        // Encode load keeps the worse of the new session and the decayed record.
        val software = EncoderBurstRanking.merge(EncoderBurstRanking.merge(null, 0.3, 0.6, 1), 0.3, 0.5, 2)
        assertEquals(0.6 * 0.8 + 0.5 * 0.2, software.encodeLoad!!, 1e-9)
        assertTrue(software.fits)
    }

    @Test
    fun recordsSurviveTheirStoredForm() {
        val record = EncoderBurstRecord(0.35, 0.62, 5, 1_760_000_000_000)
        assertEquals(record, EncoderBurstRecord.decode(record.encode()))
        val hardware = EncoderBurstRecord(1.8, null, 2, 7)
        assertEquals(hardware, EncoderBurstRecord.decode(hardware.encode()))
        assertNull(EncoderBurstRecord.decode("v0,1,2,3,4"))
        assertNull(EncoderBurstRecord.decode("v1,x,-,3,4"))
        assertNull(EncoderBurstRecord.decode("garbage"))
    }
}
