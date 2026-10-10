package com.example.bluetoothtest

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Experimental native libopus call codec (`test_call_native_opus`): pure-JVM logic. */
class NativeOpusCodecTest {
    private val frameUs = 20_000L

    @Test
    fun noGapNeedsNoConcealment() {
        assertEquals(OpusGapPlan.NONE, OpusGapConcealment.plan(null, 1_000_000L, frameUs, packetHasFec = true))
        assertEquals(OpusGapPlan.NONE, OpusGapConcealment.plan(1_000_000L, 1_000_000L, frameUs, packetHasFec = true))
        // Sub-frame jitter in the sender's clock is not a lost packet.
        assertEquals(OpusGapPlan.NONE, OpusGapConcealment.plan(1_000_000L, 1_009_000L, frameUs, packetHasFec = true))
        // A late duplicate or reordered packet is decoded as it is, never concealed.
        assertEquals(OpusGapPlan.NONE, OpusGapConcealment.plan(1_000_000L, 960_000L, frameUs, packetHasFec = true))
    }

    @Test
    fun oneLostPacketIsRecoveredFromTheNextPacketsFec() {
        val plan = OpusGapConcealment.plan(1_000_000L, 1_020_000L, frameUs, packetHasFec = true)
        assertEquals(1, plan.concealFrames)
        assertTrue(plan.fecForLast)
        assertEquals(0, plan.plcFrames)
        assertEquals(1_000_000L, plan.firstConcealPtsUs)
        assertEquals(0L, plan.uncoveredUs)
    }

    @Test
    fun aBurstUsesPlcThenFecForTheFrameJustBeforeThePacket() {
        val withFec = OpusGapConcealment.plan(1_000_000L, 1_060_000L, frameUs, packetHasFec = true)
        assertEquals(3, withFec.concealFrames)
        assertEquals(2, withFec.plcFrames)
        assertTrue(withFec.fecForLast)
        val withoutFec = OpusGapConcealment.plan(1_000_000L, 1_060_000L, frameUs, packetHasFec = false)
        assertEquals(3, withoutFec.plcFrames)
        assertFalse(withoutFec.fecForLast)
    }

    @Test
    fun aLongSenderGapIsConcealedOnlyUpToTheBoundAndKeepsItsRemainder() {
        // 200 ms missing (mute, push-to-talk release): 100 ms of PLC fade, the rest stays a sender gap
        // for the playout's existing silence alignment. FEC would not be contiguous, so it is unused.
        val plan = OpusGapConcealment.plan(1_000_000L, 1_200_000L, frameUs, packetHasFec = true)
        assertEquals(5, plan.concealFrames)
        assertEquals(5, plan.plcFrames)
        assertFalse(plan.fecForLast)
        assertEquals(100_000L, plan.uncoveredUs)
        assertTrue(plan.uncoveredUs >= SENDER_GAP_MIN_MS * 1_000L)
    }

    @Test
    fun theNativeCodecConfigurationIsTheCodec2BlockEveryReceiverAlreadyParses() {
        val block = NativeOpusConfig.codec2Block(channels = 1, preSkip = 312, inputSampleRate = 48_000)
        val normalized = OpusCodecConfig.normalize(listOf(block))
        assertEquals(3, normalized.size)
        assertArrayEquals(NativeOpusConfig.opusHead(1, 312, 48_000), normalized[0])
        assertEquals(312, NativeOpusConfig.preSkip(normalized[0]))
        assertEquals(1, normalized[0][8].toInt())
        assertEquals(1, normalized[0][9].toInt())
        assertEquals(6_500_000L, ByteBuffer.wrap(normalized[1]).order(ByteOrder.LITTLE_ENDIAN).long)
        assertEquals(OpusCodecConfig.DEFAULT_PREROLL_NS, ByteBuffer.wrap(normalized[2]).order(ByteOrder.LITTLE_ENDIAN).long)
    }

    @Test
    fun fecOffIsPassedToTheBridgeAsANegativeLossHint() {
        assertEquals(10, NativeOpusSettings().fecLossPercent)
        assertEquals(-1, NativeOpusSettings(fec = false).fecLossPercent)
        assertFalse(NativeOpusSettings().dtx)
    }
}
