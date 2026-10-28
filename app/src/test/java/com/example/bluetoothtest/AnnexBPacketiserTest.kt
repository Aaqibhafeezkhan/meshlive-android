package com.example.bluetoothtest

import com.example.bluetoothtest.AnnexBTestStreams.annexB
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sender-side packetiser: cache, strip, prepend, key frame from the NAL type, ordering, round trip through the gate. */
class AnnexBPacketiserTest {
    private val key = StreamProtocol.FLAG_KEY_FRAME

    private fun eachCodec(block: (AnnexBCodec) -> Unit) = AnnexBCodec.values().forEach(block)

    private fun packet(r: PacketiserResult): PacketiserResult.Packet {
        if (r is PacketiserResult.Error) throw AssertionError("error ${r.reason} ${r.detail}")
        return r as PacketiserResult.Packet
    }

    private fun error(r: PacketiserResult): String {
        if (r is PacketiserResult.Packet) throw AssertionError("unexpected packet")
        return (r as PacketiserResult.Error).reason
    }

    private fun aud(c: AnnexBCodec) = if (c == AnnexBCodec.HEVC) AnnexBTestStreams.hevcAud() else AnnexBTestStreams.avcAud()
    private fun sei(c: AnnexBCodec) = if (c == AnnexBCodec.HEVC) AnnexBTestStreams.hevcPrefixSei() else AnnexBTestStreams.avcSei()
    private fun filler(c: AnnexBCodec) = if (c == AnnexBCodec.HEVC) AnnexBTestStreams.hevcFiller() else AnnexBTestStreams.avcFiller()

    @Test
    fun configThenIdrAndPRoundTripThroughTheGate() = eachCodec { codec ->
        val s = AnnexBTestStreams.setsFor(codec)
        val p = AnnexBPacketiser(codec)
        assertNull(p.cachedSets)
        p.onCodecConfig(s.annexB())
        assertNotNull(p.cachedSets)
        val gate = AccessUnitGate(codec, 640, 480) { 0L }
        // encoder IDR with AUD and SEI but no sets; encoder P with filler
        val idr = packet(p.onAccessUnit(annexB(aud(codec), sei(codec), AnnexBTestStreams.slice(codec, true)), 0))
        assertTrue(idr.keyFrame)
        assertEquals(640 to 480, idr.codedSize)
        val v = gate.check(idr.payload, key) as AuVerdict.Accept
        assertNotNull(v.activated)
        assertArrayEquals(idr.payload, v.accessUnit)
        for (i in 1..3) {
            val pf = packet(p.onAccessUnit(annexB(AnnexBTestStreams.slice(codec, false), filler(codec)), i * 33_000L))
            assertFalse(pf.keyFrame)
            assertNull(pf.codedSize)
            val pv = gate.check(pf.payload, 0) as AuVerdict.Accept
            assertFalse(pv.keyFrame)
        }
        val idr2 = packet(p.onAccessUnit(annexB(AnnexBTestStreams.slice(codec, true)), 200_000))
        assertNull((gate.check(idr2.payload, key) as AuVerdict.Accept).activated)
    }

    @Test
    fun idrGetsTheCachedSetsInOrderAndPGetsNone() = eachCodec { codec ->
        val s = AnnexBTestStreams.setsFor(codec)
        val p = AnnexBPacketiser(codec)
        p.onCodecConfig(s.annexB())
        val slice = AnnexBTestStreams.slice(codec, true)
        assertArrayEquals(annexB(s.nals() + slice), packet(p.onAccessUnit(annexB(slice), 0)).payload)
        val ps = AnnexBTestStreams.slice(codec, false)
        assertArrayEquals(annexB(ps), packet(p.onAccessUnit(annexB(ps), 1)).payload)
    }

    @Test
    fun inBandSetsAreStrippedFromNonKeyFramesAndUpdateTheCache() = eachCodec { codec ->
        val first = AnnexBTestStreams.setsFor(codec, 640, 480)
        val second = AnnexBTestStreams.setsFor(codec, 320, 240)
        val p = AnnexBPacketiser(codec)
        p.onCodecConfig(first.annexB())
        packet(p.onAccessUnit(annexB(AnnexBTestStreams.slice(codec, true)), 0))
        // a P access unit that drags new sets along: the sets are removed from it, the cache takes them
        val pSlice = AnnexBTestStreams.slice(codec, false)
        val out = packet(p.onAccessUnit(annexB(second.nals() + pSlice), 1)).payload
        assertArrayEquals(annexB(pSlice), out)
        val idr = packet(p.onAccessUnit(annexB(AnnexBTestStreams.slice(codec, true)), 2))
        assertEquals(320 to 240, idr.codedSize)
        assertArrayEquals(annexB(second.nals() + AnnexBTestStreams.slice(codec, true)), idr.payload)
    }

    @Test
    fun inBandSetsOnAKeyFrameWinOverTheCache() = eachCodec { codec ->
        val old = AnnexBTestStreams.setsFor(codec, 640, 480)
        val fresh = AnnexBTestStreams.setsFor(codec, 480, 360)
        val p = AnnexBPacketiser(codec)
        p.onCodecConfig(old.annexB())
        val slice = AnnexBTestStreams.slice(codec, true)
        val r = packet(p.onAccessUnit(annexB(fresh.nals() + slice), 0))
        assertEquals(480 to 360, r.codedSize)
        assertArrayEquals(annexB(fresh.nals() + slice), r.payload)
    }

    @Test
    fun consecutiveConfigBuffersMergeAndConfigAfterMediaReplaces() = eachCodec { codec ->
        val a = AnnexBTestStreams.setsFor(codec, 640, 480)
        val b = AnnexBTestStreams.setsFor(codec, 320, 240)
        val p = AnnexBPacketiser(codec)
        // one set arriving as separate buffers (and csd-0 / csd-1 repeated afterwards)
        for (nal in a.nals()) p.onCodecConfig(annexB(nal))
        assertEquals(640, p.cachedSets!!.codedWidth)
        p.onCodecConfig(annexB(a.sps))
        p.onCodecConfig(annexB(a.pps))
        assertTrue(p.cachedSets!!.sameAs(AccessUnitGate.keyFrameSets(codec, annexB(a.nals() + AnnexBTestStreams.slice(codec, true)), 640, 480)))
        packet(p.onAccessUnit(annexB(AnnexBTestStreams.slice(codec, true)), 0))
        // media started: a new config buffer starts a fresh cache; until it is complete nothing is cached
        p.onCodecConfig(annexB(b.sps))
        assertNull("a partial new set is not a complete cache", p.cachedSets)
        p.onCodecConfig(annexB(listOfNotNull(b.vps, b.pps)))
        assertEquals(320, p.cachedSets!!.codedWidth)
        assertEquals(320 to 240, packet(p.onAccessUnit(annexB(AnnexBTestStreams.slice(codec, true)), 1)).codedSize)
    }

    @Test
    fun keyFrameIsJudgedFromTheNalTypeNotFromAnyFlag() = eachCodec { codec ->
        val p = AnnexBPacketiser(codec)
        p.onCodecConfig(AnnexBTestStreams.setsFor(codec).annexB())
        // a non-IDR slice is never a key frame (no way to tell the packetiser otherwise)
        assertFalse(packet(p.onAccessUnit(annexB(AnnexBTestStreams.slice(codec, false)), 0)).keyFrame)
        assertTrue(packet(p.onAccessUnit(annexB(AnnexBTestStreams.slice(codec, true)), 1)).keyFrame)
    }

    @Test
    fun hevcIdrNLpIsAKeyFrame() {
        val p = AnnexBPacketiser(AnnexBCodec.HEVC)
        p.onCodecConfig(AnnexBTestStreams.setsFor(AnnexBCodec.HEVC).annexB())
        val r = packet(p.onAccessUnit(annexB(AnnexBTestStreams.hevcSlice(true, nalType = 20)), 0))
        assertTrue(r.keyFrame)
    }

    @Test
    fun keyFrameWithoutACompleteCachedSetIsAnError() = eachCodec { codec ->
        val s = AnnexBTestStreams.setsFor(codec)
        val p = AnnexBPacketiser(codec)
        val slice = AnnexBTestStreams.slice(codec, true)
        assertEquals("no_parameter_sets", error(p.onAccessUnit(annexB(slice), 0)))
        p.onCodecConfig(annexB(s.sps)) // incomplete: no PPS (and no VPS for HEVC)
        assertEquals("no_parameter_sets", error(p.onAccessUnit(annexB(slice), 1)))
        assertNull(p.cachedSets)
        // a P frame does not need them
        assertFalse(packet(p.onAccessUnit(annexB(AnnexBTestStreams.slice(codec, false)), 2)).keyFrame)
        // a complete config (after media: a fresh cache) makes the next IDR work; the failed ones consumed no timestamp
        p.onCodecConfig(s.annexB())
        assertTrue(packet(p.onAccessUnit(annexB(slice), 3)).keyFrame)
    }

    @Test
    fun unsupportedParameterSetsAreNotCached() {
        val p = AnnexBPacketiser(AnnexBCodec.HEVC)
        // Main 10 would be rejected by every receiver
        val s = AnnexBTestStreams.hevcSets(AnnexBTestStreams.HevcSps(bitDepthLuma = 10, bitDepthChroma = 10, profileIdc = 2, compat = 0x20000000L))
        p.onCodecConfig(s.annexB())
        assertNull(p.cachedSets)
        assertNotNull(p.configProblem)
        assertEquals("no_parameter_sets", error(p.onAccessUnit(annexB(AnnexBTestStreams.hevcSlice(true)), 0)))
    }

    @Test
    fun presentationTimeMustStrictlyIncrease() = eachCodec { codec ->
        val p = AnnexBPacketiser(codec)
        p.onCodecConfig(AnnexBTestStreams.setsFor(codec).annexB())
        val pf = annexB(AnnexBTestStreams.slice(codec, false))
        packet(p.onAccessUnit(annexB(AnnexBTestStreams.slice(codec, true)), 1_000))
        packet(p.onAccessUnit(pf, 2_000))
        assertEquals("reorder", error(p.onAccessUnit(pf, 2_000)))
        assertEquals("reorder", error(p.onAccessUnit(pf, 1_500)))
        // a rejected unit does not move the clock
        packet(p.onAccessUnit(pf, 2_001))
        assertEquals("reorder", error(p.onAccessUnit(annexB(AnnexBTestStreams.slice(codec, true)), 2_001)))
    }

    @Test
    fun malformedInputAndMissingSlicesAreErrors() = eachCodec { codec ->
        val p = AnnexBPacketiser(codec)
        p.onCodecConfig(AnnexBTestStreams.setsFor(codec).annexB())
        assertEquals("malformed", error(p.onAccessUnit(byteArrayOf(1, 2, 3, 4), 0)))
        assertEquals("malformed", error(p.onAccessUnit(ByteArray(0), 0)))
        assertEquals("no_slice", error(p.onAccessUnit(annexB(aud(codec), sei(codec)), 0)))
        val slice = AnnexBTestStreams.slice(codec, false)
        assertEquals("multi_slice", error(p.onAccessUnit(annexB(slice, slice), 0)))
        // none of those consumed the first timestamp
        assertFalse(packet(p.onAccessUnit(annexB(slice), 0)).keyFrame)
    }

    @Test
    fun unsupportedNalTypesAreAnError() {
        val h = AnnexBPacketiser(AnnexBCodec.HEVC)
        h.onCodecConfig(AnnexBTestStreams.setsFor(AnnexBCodec.HEVC).annexB())
        assertEquals("unsupported_nal", error(h.onAccessUnit(annexB(AnnexBTestStreams.hevcSlice(true, nalType = 21)), 0))) // CRA
        val a = AnnexBPacketiser(AnnexBCodec.H264)
        a.onCodecConfig(AnnexBTestStreams.setsFor(AnnexBCodec.H264).annexB())
        assertEquals("unsupported_nal", error(a.onAccessUnit(annexB(AnnexBTestStreams.avcNal(2, byteArrayOf(1, 2)), AnnexBTestStreams.avcSlice(false)), 0)))
    }

    @Test
    fun threeByteStartCodesAndSeveralNalsInOneConfigBuffer() = eachCodec { codec ->
        val s = AnnexBTestStreams.setsFor(codec)
        val p = AnnexBPacketiser(codec)
        p.onCodecConfig(annexB(s.nals(), fourByte = false))
        assertNotNull(p.cachedSets)
        val slice = AnnexBTestStreams.slice(codec, true)
        val r = packet(p.onAccessUnit(annexB(listOf(slice), fourByte = false), 0))
        assertArrayEquals(annexB(s.nals() + slice), r.payload)
    }

    @Test
    fun croppedSizesComeFromTheSps() = eachCodec { codec ->
        val p = AnnexBPacketiser(codec)
        p.onCodecConfig(AnnexBTestStreams.setsFor(codec, 1920, 1080).annexB())
        assertEquals(1920 to 1080, packet(p.onAccessUnit(annexB(AnnexBTestStreams.slice(codec, true)), 0)).codedSize)
        val q = AnnexBPacketiser(codec)
        q.onCodecConfig(AnnexBTestStreams.setsFor(codec, 426, 240).annexB())
        assertEquals(426 to 240, packet(q.onAccessUnit(annexB(AnnexBTestStreams.slice(codec, true)), 0)).codedSize)
    }
}
