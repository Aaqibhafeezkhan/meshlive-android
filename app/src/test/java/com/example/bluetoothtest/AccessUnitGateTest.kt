package com.example.bluetoothtest

import com.example.bluetoothtest.AnnexBTestStreams.AvcSps
import com.example.bluetoothtest.AnnexBTestStreams.AvcVui
import com.example.bluetoothtest.AnnexBTestStreams.HevcSps
import com.example.bluetoothtest.AnnexBTestStreams.Sets
import com.example.bluetoothtest.AnnexBTestStreams.annexB
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fail-closed access-unit gate, rule by rule (PROTOCOL "Access-unit validation"). */
class AccessUnitGateTest {
    private val key = StreamProtocol.FLAG_KEY_FRAME
    private var now = 0L

    private fun gate(codec: AnnexBCodec, w: Int = 640, h: Int = 480) = AccessUnitGate(codec, w, h) { now }

    private fun eachCodec(block: (AnnexBCodec) -> Unit) = AnnexBCodec.values().forEach(block)

    private fun idr(codec: AnnexBCodec, sets: Sets = AnnexBTestStreams.setsFor(codec), slice: ByteArray? = null): ByteArray =
        annexB(sets.nals() + (slice ?: AnnexBTestStreams.slice(codec, true)))

    private fun accept(v: AuVerdict): AuVerdict.Accept {
        if (v is AuVerdict.Reject) throw AssertionError("rejected rule ${v.rule}: ${v.reason}")
        if (v is AuVerdict.Close) throw AssertionError("closed: ${v.reason}")
        return v as AuVerdict.Accept
    }

    private fun rejectRule(v: AuVerdict): Int {
        if (v is AuVerdict.Accept) throw AssertionError("unexpectedly accepted")
        if (v is AuVerdict.Close) throw AssertionError("unexpectedly closed: ${v.reason}")
        return (v as AuVerdict.Reject).rule
    }

    private fun assertRule(rule: Int, codec: AnnexBCodec, payload: ByteArray, flags: Int, g: AccessUnitGate = gate(codec)) {
        assertEquals("$codec rule", rule, rejectRule(g.check(payload, flags)))
    }

    // ------------------------------------------------------------------ acceptance and output

    @Test
    fun idrThenPFramesPassAndOnlyTheFirstActivates() = eachCodec { codec ->
        val g = gate(codec)
        val first = accept(g.check(idr(codec), key))
        assertTrue(first.keyFrame)
        val sets = first.activated!!
        assertEquals(640, sets.codedWidth)
        assertEquals(480, sets.codedHeight)
        assertSame(sets, g.active)
        assertEquals(if (codec == AnnexBCodec.HEVC) 1 else 2, sets.csd().size)
        val p = accept(g.check(AnnexBTestStreams.pAu(codec), 0))
        assertFalse(p.keyFrame)
        assertNull(p.activated)
        val again = accept(g.check(idr(codec), key))
        assertNull("identical sets do not re-activate", again.activated)
        assertSame(sets, g.active)
    }

    @Test
    fun csdUsesFourByteStartCodesInThePlatformOrder() = eachCodec { codec ->
        val s = AnnexBTestStreams.setsFor(codec)
        val sets = accept(gate(codec).check(idr(codec, s), key)).activated!!
        val csd = sets.csd()
        if (codec == AnnexBCodec.HEVC) {
            assertArrayEquals(annexB(s.vps!!, s.sps, s.pps), csd[0])
        } else {
            assertArrayEquals(annexB(s.sps), csd[0])
            assertArrayEquals(annexB(s.pps), csd[1])
        }
    }

    @Test
    fun seiAudAndFillerAreRemovedAndStartCodesNormalised() = eachCodec { codec ->
        val s = AnnexBTestStreams.setsFor(codec)
        val slice = AnnexBTestStreams.slice(codec, true)
        val au: ByteArray
        if (codec == AnnexBCodec.HEVC) {
            au = annexB(
                listOf(AnnexBTestStreams.hevcAud(), AnnexBTestStreams.hevcPrefixSei()) + s.nals() +
                    listOf(slice, AnnexBTestStreams.hevcFiller(), AnnexBTestStreams.hevcSuffixSei()),
                fourByte = false,
            )
        } else {
            au = annexB(
                listOf(AnnexBTestStreams.avcAud(), AnnexBTestStreams.avcSei()) + s.nals() + listOf(slice, AnnexBTestStreams.avcFiller()),
                fourByte = false,
            )
        }
        val out = accept(gate(codec).check(au, key)).accessUnit
        assertArrayEquals(annexB(s.nals() + slice), out)
    }

    @Test
    fun seiContentsAreNeverParsed() = eachCodec { codec ->
        val garbage = ByteArray(40) { (0x90 + it).toByte() }
        val sei = if (codec == AnnexBCodec.HEVC) AnnexBTestStreams.hevcNal(39, garbage) else AnnexBTestStreams.avcNal(6, garbage)
        val g = gate(codec)
        accept(g.check(idr(codec), key))
        val out = accept(g.check(annexB(sei, AnnexBTestStreams.slice(codec, false)), 0)).accessUnit
        assertArrayEquals(annexB(AnnexBTestStreams.slice(codec, false)), out)
    }

    @Test
    fun sliceBytesWithEscapesPassThroughUnchanged() = eachCodec { codec ->
        val body = byteArrayOf(0, 0, 1, 0x55, 0, 0, 0, 0, 2, 0x66, 0, 0, 3, 0x77)
        val slice = if (codec == AnnexBCodec.HEVC) AnnexBTestStreams.hevcSlice(true, body = body) else AnnexBTestStreams.avcSlice(true, body = body)
        val out = accept(gate(codec).check(idr(codec, slice = slice), key)).accessUnit
        assertArrayEquals(annexB(AnnexBTestStreams.setsFor(codec).nals() + slice), out)
    }

    @Test
    fun trailingEscapeIsAllowedAndAnEscapeAboveThreeRejects() = eachCodec { codec ->
        val slice = AnnexBTestStreams.slice(codec, true)
        val sets = AnnexBTestStreams.setsFor(codec).nals()
        // cabac_zero_words style ending
        val ok = annexB(sets + (slice + byteArrayOf(0, 0, 3)))
        accept(gate(codec).check(ok, key))
        val bad = annexB(sets + (slice + byteArrayOf(0, 0, 3, 4)))
        assertRule(1, codec, bad, key)
    }

    @Test
    fun largeSliceIsHandledLinearly() = eachCodec { codec ->
        val slice = if (codec == AnnexBCodec.HEVC) AnnexBTestStreams.hevcSlice(true, length = 1_000_000) else AnnexBTestStreams.avcSlice(true, length = 1_000_000)
        val v = accept(gate(codec).check(idr(codec, slice = slice), key))
        assertTrue(v.accessUnit.size > 1_000_000)
    }

    // ------------------------------------------------------------------ rule 1 framing

    @Test
    fun rule1Framing() = eachCodec { codec ->
        val slice = AnnexBTestStreams.slice(codec, false)
        assertRule(1, codec, ByteArray(0), 0)
        assertRule(1, codec, slice, 0) // no start code
        assertRule(1, codec, byteArrayOf(0, 0, 0, 0, 1) + slice, 0) // five-byte lead-in is not a start code
        assertRule(1, codec, annexB(slice, slice).copyOf(annexB(slice).size + 4), 0) // an empty NAL unit at the end
        assertRule(1, codec, ByteArray(StreamProtocol.MAX_PAYLOAD_BYTES + 1) { 1 }, 0)
        // nine NAL units
        val nine = annexB(List(8) { if (codec == AnnexBCodec.HEVC) AnnexBTestStreams.hevcAud() else AnnexBTestStreams.avcAud() } + slice)
        assertRule(1, codec, nine, 0)
        // eight are fine
        val eight = annexB(List(7) { if (codec == AnnexBCodec.HEVC) AnnexBTestStreams.hevcAud() else AnnexBTestStreams.avcAud() } + slice)
        val g = gate(codec)
        accept(g.check(idr(codec), key))
        accept(g.check(eight, 0))
        // a payload of many tiny NAL units is rejected after eight, not parsed to the end
        val many = annexB(List(100_000) { byteArrayOf(0x09, 0x10) })
        assertRule(1, codec, many, 0)
    }

    @Test
    fun rule1ForbiddenByteRunsInsideANal() = eachCodec { codec ->
        val slice = AnnexBTestStreams.slice(codec, false)
        assertRule(1, codec, annexB(slice + byteArrayOf(0, 0, 2, 9)), 0)
        assertRule(1, codec, annexB(slice + byteArrayOf(0, 0, 0, 9)), 0)
        // trailing zero bytes before the next start code are fine
        val g = gate(codec)
        accept(g.check(idr(codec), key))
        accept(g.check(annexB(slice) + byteArrayOf(0, 0, 0, 0), 0))
    }

    // ------------------------------------------------------------------ rules 2 and 3

    @Test
    fun rule2NalHeader() {
        val sets = AnnexBTestStreams.setsFor(AnnexBCodec.HEVC)
        fun withHeader(layer: Int, tid: Int, forbidden: Boolean = false): ByteArray {
            val slice = AnnexBTestStreams.hevcNal(19, byteArrayOf(0x80.toByte(), 0x55, 0x55), layerId = layer, tidPlus1 = tid)
            if (forbidden) slice[0] = (slice[0].toInt() or 0x80).toByte()
            return annexB(sets.nals() + slice)
        }
        assertRule(2, AnnexBCodec.HEVC, withHeader(1, 1), key)
        assertRule(2, AnnexBCodec.HEVC, withHeader(0, 2), key)
        assertRule(2, AnnexBCodec.HEVC, withHeader(0, 0), key)
        assertRule(2, AnnexBCodec.HEVC, withHeader(0, 1, forbidden = true), key)
        val avcSets = AnnexBTestStreams.setsFor(AnnexBCodec.H264)
        val slice = AnnexBTestStreams.avcSlice(true)
        slice[0] = (slice[0].toInt() or 0x80).toByte()
        assertRule(2, AnnexBCodec.H264, annexB(avcSets.nals() + slice), key)
    }

    @Test
    fun rule3Types() {
        val hevc = AnnexBTestStreams.setsFor(AnnexBCodec.HEVC).nals()
        // CRA is deferred
        assertRule(3, AnnexBCodec.HEVC, annexB(hevc + AnnexBTestStreams.hevcSlice(true, nalType = 21)), key)
        assertRule(3, AnnexBCodec.HEVC, annexB(hevc + AnnexBTestStreams.hevcSlice(false, nalType = 3)), 0) // TSA
        assertRule(3, AnnexBCodec.HEVC, annexB(AnnexBTestStreams.hevcNal(36, byteArrayOf(1, 2)), AnnexBTestStreams.hevcSlice(false)), 0)
        val avc = AnnexBTestStreams.setsFor(AnnexBCodec.H264).nals()
        assertRule(3, AnnexBCodec.H264, annexB(avc + AnnexBTestStreams.avcNal(2, byteArrayOf(1, 2)) + AnnexBTestStreams.avcSlice(true)), key)
        assertRule(3, AnnexBCodec.H264, annexB(AnnexBTestStreams.avcNal(20, byteArrayOf(1, 2)), AnnexBTestStreams.avcSlice(false)), 0)
        assertRule(3, AnnexBCodec.H264, annexB(AnnexBTestStreams.avcNal(10, byteArrayOf(1)), AnnexBTestStreams.avcSlice(false)), 0)
    }

    // ------------------------------------------------------------------ rule 4

    @Test
    fun rule4OneSliceInOrder() = eachCodec { codec ->
        val sets = AnnexBTestStreams.setsFor(codec).nals()
        val s = AnnexBTestStreams.slice(codec, true)
        assertRule(4, codec, annexB(sets + s + s), key) // two slices
        assertRule(4, codec, annexB(sets), key) // no slice
        val filler = if (codec == AnnexBCodec.HEVC) AnnexBTestStreams.hevcFiller() else AnnexBTestStreams.avcFiller()
        assertRule(4, codec, annexB(listOf(filler) + sets + s), key) // filler before the slice
        assertRule(4, codec, annexB(sets + s + sets.first()), key) // a parameter set after the slice
        if (codec == AnnexBCodec.HEVC) {
            assertRule(4, codec, annexB(listOf(AnnexBTestStreams.hevcSuffixSei()) + sets + s), key)
            assertRule(4, codec, annexB(sets + s + AnnexBTestStreams.hevcPrefixSei()), key)
        } else {
            assertRule(4, codec, annexB(sets + s + AnnexBTestStreams.avcSei()), key)
        }
    }

    @Test
    fun rule4SliceHeader() {
        val avc = AnnexBTestStreams.setsFor(AnnexBCodec.H264).nals()
        assertRule(4, AnnexBCodec.H264, annexB(avc + AnnexBTestStreams.avcSlice(true, firstMb = 1)), key)
        assertRule(4, AnnexBCodec.H264, annexB(avc + AnnexBTestStreams.avcSlice(true, sliceType = 0)), key)
        assertRule(4, AnnexBCodec.H264, annexB(avc + AnnexBTestStreams.avcSlice(true, sliceType = 1)), key)
        val g = gate(AnnexBCodec.H264)
        accept(g.check(annexB(avc + AnnexBTestStreams.avcSlice(true)), key))
        assertRule(4, AnnexBCodec.H264, annexB(AnnexBTestStreams.avcSlice(false, sliceType = 2)), 0, g)
        assertRule(4, AnnexBCodec.H264, annexB(AnnexBTestStreams.avcSlice(false, sliceType = 1)), 0, g)
        val hevc = AnnexBTestStreams.setsFor(AnnexBCodec.HEVC).nals()
        assertRule(4, AnnexBCodec.HEVC, annexB(hevc + AnnexBTestStreams.hevcSlice(true, firstSlice = false)), key)
        assertRule(4, AnnexBCodec.HEVC, annexB(hevc + AnnexBTestStreams.hevcSlice(true, sliceType = 1)), key)
        val h = gate(AnnexBCodec.HEVC)
        accept(h.check(annexB(hevc + AnnexBTestStreams.hevcSlice(true)), key))
        assertRule(4, AnnexBCodec.HEVC, annexB(AnnexBTestStreams.hevcSlice(false, sliceType = 0)), 0, h)
    }

    @Test
    fun hevcSliceUsesTheExtraHeaderBitsOfThePps() {
        val sets = AnnexBTestStreams.hevcSets(extraBits = 2)
        val g = gate(AnnexBCodec.HEVC)
        accept(g.check(annexB(sets.nals() + AnnexBTestStreams.hevcSlice(true, extraBits = 2)), key))
        accept(g.check(annexB(AnnexBTestStreams.hevcSlice(false, extraBits = 2)), 0))
        // an I slice written without the reserved bits reads as slice_type 0 (B) once the PPS says two bits
        assertRule(4, AnnexBCodec.HEVC, annexB(AnnexBTestStreams.hevcSlice(false, extraBits = 0, sliceType = 2)), 0, g)
    }

    // ------------------------------------------------------------------ rule 5

    @Test
    fun rule5KeyFrameFlagIffIdr() = eachCodec { codec ->
        val g = gate(codec)
        val r1 = g.check(idr(codec), 0)
        assertEquals(5, rejectRule(r1))
        assertTrue("an IDR NAL counts as a keyframe", (r1 as AuVerdict.Reject).keyFrame)
        val g2 = gate(codec)
        accept(g2.check(idr(codec), key))
        val r2 = g2.check(AnnexBTestStreams.pAu(codec), key)
        assertEquals(5, rejectRule(r2))
        assertTrue("the flag alone counts as a keyframe", (r2 as AuVerdict.Reject).keyFrame)
    }

    @Test
    fun rule5IdrCarriesExactlyTheSetsInOrder() = eachCodec { codec ->
        val s = AnnexBTestStreams.setsFor(codec)
        val slice = AnnexBTestStreams.slice(codec, true)
        assertRule(5, codec, annexB(listOfNotNull(s.vps, s.sps) + slice), key) // no PPS
        assertRule(5, codec, annexB(listOfNotNull(s.vps, s.pps) + slice), key) // no SPS
        assertRule(5, codec, annexB(s.nals() + s.sps + slice), key) // two SPS
        assertRule(5, codec, annexB(listOfNotNull(s.vps, s.pps, s.sps) + slice), key) // PPS before SPS
        assertRule(5, codec, annexB(listOf(slice)), key) // a bare IDR
        if (codec == AnnexBCodec.HEVC) {
            assertRule(5, codec, annexB(listOf(s.sps, s.vps!!, s.pps, slice)), key) // SPS before VPS
            assertRule(5, codec, annexB(listOf(s.sps, s.pps, slice)), key) // no VPS
        }
    }

    @Test
    fun rule5ChainMustResolveInsideTheAu() = eachCodec { codec ->
        // slice names a PPS id that the AU does not carry
        val s = AnnexBTestStreams.setsFor(codec)
        assertRule(5, codec, annexB(s.nals() + AnnexBTestStreams.slice(codec, true, ppsId = 1)), key)
        // PPS names another SPS
        val pps = if (codec == AnnexBCodec.HEVC) AnnexBTestStreams.hevcPps(spsId = 1) else AnnexBTestStreams.avcPps(spsId = 1)
        assertRule(5, codec, idr(codec, Sets(s.vps, s.sps, pps)), key)
        if (codec == AnnexBCodec.HEVC) {
            val sps = HevcSps(vpsId = 1).build()
            assertRule(5, codec, idr(codec, Sets(s.vps, sps, s.pps)), key) // SPS names VPS 1, AU has VPS 0
        }
    }

    @Test
    fun rule5NonIdrRules() = eachCodec { codec ->
        val s = AnnexBTestStreams.setsFor(codec)
        // before any IDR
        assertRule(5, codec, AnnexBTestStreams.pAu(codec), 0)
        val g = gate(codec)
        accept(g.check(idr(codec), key))
        // carries a parameter set
        assertRule(5, codec, annexB(s.pps, AnnexBTestStreams.slice(codec, false)), 0, g)
        // names a different PPS than the active one
        assertRule(5, codec, AnnexBTestStreams.pAu(codec, ppsId = 1), 0, g)
        // and the stream continues after those drops
        accept(g.check(AnnexBTestStreams.pAu(codec), 0))
    }

    @Test
    fun rule5ParameterSetsAreAtMostOneThousandTwentyFourBytes() = eachCodec { codec ->
        fun sets(pad: Int, which: Int): Sets {
            val b = AnnexBTestStreams
            return if (codec == AnnexBCodec.HEVC) {
                Sets(
                    b.hevcVps(pad = if (which == 0) pad else 0),
                    HevcSps(pad = if (which == 1) pad else 0).build(),
                    b.hevcPps(pad = if (which == 2) pad else 0),
                )
            } else {
                Sets(null, AvcSps(pad = if (which == 1) pad else 0).build(), b.avcPps(pad = if (which == 2) pad else 0))
            }
        }
        for (which in if (codec == AnnexBCodec.HEVC) 0..2 else 1..2) {
            val ok = accept(gate(codec).check(idr(codec, sets(1024, which)), key))
            assertNotNull(ok.activated)
            assertRule(5, codec, idr(codec, sets(1025, which)), key)
        }
    }

    // ------------------------------------------------------------------ rule 6 envelope

    @Test
    fun rule6EnvelopeBoundaries() = eachCodec { codec ->
        fun au(w: Int, h: Int) = idr(codec, AnnexBTestStreams.setsFor(codec, w, h))
        accept(gate(codec).check(au(640, 480), key)) // exactly equal
        assertRule(6, codec, au(656, 480), key) // +16 wide
        assertRule(6, codec, au(640, 496), key) // +16 high
        accept(gate(codec).check(au(480, 640), key)) // rotated
        assertRule(6, codec, au(496, 640), key)
        accept(gate(codec).check(au(320, 176), key))
        // 1080 coded as 1088 passes a 1080-high header (rounded up to 16)
        accept(gate(codec, 1920, 1080).check(au(1920, 1080), key))
        accept(gate(codec, 1080, 1920).check(au(1920, 1080), key))
        // 426 wide is coded as 432 and the header rounds 426 up to 432
        val sd = accept(gate(codec, 426, 240).check(au(426, 240), key)).activated!!
        assertEquals(426, sd.codedWidth)
        assertEquals(240, sd.codedHeight)
        assertRule(6, codec, au(448, 240), key, gate(codec, 426, 240))
    }

    @Test
    fun rule6CropLeavesAtLeastSixteen() {
        val g = gate(AnnexBCodec.H264, 640, 480)
        accept(g.check(idr(AnnexBCodec.H264, AnnexBTestStreams.avcSets(AvcSps(widthMbs = 2, heightMbs = 2, cropRight = 8))), key))
        assertRule(6, AnnexBCodec.H264, idr(AnnexBCodec.H264, AnnexBTestStreams.avcSets(AvcSps(widthMbs = 2, heightMbs = 2, cropRight = 9))), key)
        assertRule(6, AnnexBCodec.HEVC, idr(AnnexBCodec.HEVC, AnnexBTestStreams.hevcSets(HevcSps(width = 32, height = 32, cropBottom = 9))), key)
    }

    // ------------------------------------------------------------------ rules 7 to 10 through the gate

    @Test
    fun rule7HevcVps() {
        val sps = HevcSps().build()
        val pps = AnnexBTestStreams.hevcPps()
        assertRule(7, AnnexBCodec.HEVC, idr(AnnexBCodec.HEVC, Sets(AnnexBTestStreams.hevcVps(maxLayersMinus1 = 1), sps, pps)), key)
        assertRule(7, AnnexBCodec.HEVC, idr(AnnexBCodec.HEVC, Sets(AnnexBTestStreams.hevcVps(maxSubLayersMinus1 = 2), sps, pps)), key)
    }

    @Test
    fun rule8HevcSps() {
        val vps = AnnexBTestStreams.hevcVps()
        val pps = AnnexBTestStreams.hevcPps()
        fun rule8(sps: HevcSps) = assertRule(8, AnnexBCodec.HEVC, idr(AnnexBCodec.HEVC, Sets(vps, sps.build(), pps)), key)
        rule8(HevcSps(maxSubLayersMinus1 = 1))
        rule8(HevcSps(profileIdc = 2, compat = 0x20000000L))
        rule8(HevcSps(chroma = 2))
        rule8(HevcSps(bitDepthLuma = 10))
        rule8(HevcSps(reorder = 2, maxDecMinus1 = 3))
        // a truncated SPS reads past the end
        assertRule(8, AnnexBCodec.HEVC, idr(AnnexBCodec.HEVC, Sets(vps, HevcSps().build().copyOf(20), pps)), key)
    }

    @Test
    fun rule9H264Sps() {
        val pps = AnnexBTestStreams.avcPps()
        fun rule9(sps: AvcSps) = assertRule(9, AnnexBCodec.H264, idr(AnnexBCodec.H264, Sets(null, sps.build(), pps)), key)
        rule9(AvcSps(profile = 88))
        rule9(AvcSps(profile = 100, bitDepth = 10))
        rule9(AvcSps(pocType = 0))
        rule9(AvcSps(pocType = 0, vui = AvcVui(reorder = 1)))
        rule9(AvcSps(frameMbsOnly = false))
        rule9(AvcSps(level = 63))
        // an HRD with 33 entries
        rule9(AvcSps(pocType = 0, vui = AvcVui(nalHrdCpb = -1, nalHrdCpbCntMinus1Raw = 32)))
        // a POC cycle that runs past the 512-byte parse budget
        rule9(AvcSps(pocType = 1, pocCycle = 100, pocOffset = 0x7FFFFFFFL, vui = AvcVui())) // about 790 bytes: under 1024, fields past 512
    }

    @Test
    fun rule10Pps() {
        val hevcSps = HevcSps().build()
        val vps = AnnexBTestStreams.hevcVps()
        assertRule(10, AnnexBCodec.HEVC, idr(AnnexBCodec.HEVC, Sets(vps, hevcSps, AnnexBTestStreams.hevcPps(ppsId = 64))), key)
        val sps = AvcSps().build()
        assertRule(10, AnnexBCodec.H264, idr(AnnexBCodec.H264, Sets(null, sps, AnnexBTestStreams.avcPps(redundantPic = true))), key)
        assertRule(10, AnnexBCodec.H264, idr(AnnexBCodec.H264, Sets(null, sps, AnnexBTestStreams.avcPps(sliceGroupsMinus1 = 1))), key)
        // highest legal ids are fine
        accept(gate(AnnexBCodec.H264).check(idr(AnnexBCodec.H264, AnnexBTestStreams.avcSets(AvcSps(spsId = 31), 255), AnnexBTestStreams.avcSlice(true, ppsId = 255)), key))
    }

    @Test
    fun h264HighProfileAndPocZeroStreamsPass() {
        val high = AvcSps(profile = 100, level = 40, scalingLists = 8, scalingDelta = 1, pocType = 0, vui = AvcVui(nalHrdCpb = 1, vclHrdCpb = 1, timing = true))
        val g = gate(AnnexBCodec.H264)
        val v = accept(g.check(idr(AnnexBCodec.H264, AnnexBTestStreams.avcSets(high)), key))
        assertEquals(640, v.activated!!.codedWidth)
        accept(g.check(AnnexBTestStreams.pAu(AnnexBCodec.H264), 0))
    }

    @Test
    fun unusualValuesAreReportedWithTheActivation() {
        val v = accept(gate(AnnexBCodec.H264).check(idr(AnnexBCodec.H264, AnnexBTestStreams.avcSets(AvcSps(level = 50, maxRef = 8))), key))
        assertEquals(listOf("level_idc" to 50L, "max_num_ref_frames" to 8L), v.unusual)
        val h = accept(gate(AnnexBCodec.HEVC).check(idr(AnnexBCodec.HEVC, AnnexBTestStreams.hevcSets(HevcSps(level = 156, maxDecMinus1 = 7))), key))
        assertEquals(listOf("general_level_idc" to 156L, "sps_max_dec_pic_buffering_minus1" to 7L), h.unusual)
    }

    // ------------------------------------------------------------------ activation

    private fun sizedIdr(codec: AnnexBCodec, w: Int, h: Int) = idr(codec, AnnexBTestStreams.setsFor(codec, w, h))

    @Test
    fun sameIdParameterSetReplacementActivatesWithTheNewSize() = eachCodec { codec ->
        val g = gate(codec)
        val first = accept(g.check(sizedIdr(codec, 640, 480), key)).activated!!
        accept(g.check(AnnexBTestStreams.pAu(codec), 0))
        val second = accept(g.check(sizedIdr(codec, 320, 240), key)).activated
        assertNotNull(second)
        assertEquals(320, second!!.codedWidth)
        assertEquals(240, second.codedHeight)
        assertFalse(second.sameAs(first))
        assertSame(second, g.active)
        accept(g.check(AnnexBTestStreams.pAu(codec), 0))
    }

    @Test
    fun rejectedReplacementKeepsTheOldSetAndDropsPFramesUntilTheNextValidIdr() = eachCodec { codec ->
        val g = gate(codec)
        val first = accept(g.check(sizedIdr(codec, 640, 480), key)).activated!!
        accept(g.check(AnnexBTestStreams.pAu(codec), 0))
        // an IDR with an oversize SPS under the same ids
        val bad = g.check(sizedIdr(codec, 1280, 720), key)
        assertEquals(6, rejectRule(bad))
        assertTrue((bad as AuVerdict.Reject).keyFrame)
        assertSame("old configuration stays active", first, g.active)
        assertTrue(g.waitingForIdr)
        // P frames are dropped (rule 5) even though they name the active PPS
        assertEquals(5, rejectRule(g.check(AnnexBTestStreams.pAu(codec), 0)))
        assertEquals(5, rejectRule(g.check(AnnexBTestStreams.pAu(codec), 0)))
        // the next valid IDR recovers; re-sending the old sets does not re-activate
        val recovered = accept(g.check(sizedIdr(codec, 640, 480), key))
        assertNull(recovered.activated)
        assertFalse(g.waitingForIdr)
        accept(g.check(AnnexBTestStreams.pAu(codec), 0))
        // a valid replacement after the failure activates normally
        assertNotNull(accept(g.check(sizedIdr(codec, 480, 360), key)).activated)
    }

    @Test
    fun rejectedFirstIdrLeavesNothingActive() = eachCodec { codec ->
        val g = gate(codec)
        assertEquals(6, rejectRule(g.check(sizedIdr(codec, 1280, 720), key)))
        assertNull(g.active)
        assertEquals(5, rejectRule(g.check(AnnexBTestStreams.pAu(codec), 0)))
        assertNotNull(accept(g.check(sizedIdr(codec, 640, 480), key)).activated)
    }

    @Test
    fun keyFrameSetsIsStatelessAndEnvelopeChecked() = eachCodec { codec ->
        val sets = AccessUnitGate.keyFrameSets(codec, sizedIdr(codec, 426, 240), 640, 480)
        assertNotNull(sets)
        assertEquals(426, sets!!.codedWidth)
        assertEquals(240, sets.codedHeight)
        assertNull(AccessUnitGate.keyFrameSets(codec, sizedIdr(codec, 1280, 720), 640, 480))
        assertNull(AccessUnitGate.keyFrameSets(codec, AnnexBTestStreams.pAu(codec), 640, 480))
        assertNull(AccessUnitGate.keyFrameSets(codec, byteArrayOf(1, 2, 3), 640, 480))
    }

    // ------------------------------------------------------------------ close conditions

    @Test
    fun codecConfigAndDiscardableCloseTheSession() = eachCodec { codec ->
        val g = gate(codec)
        accept(g.check(idr(codec), key))
        val v = g.check(idr(codec), key or StreamProtocol.FLAG_CODEC_CONFIG)
        assertEquals("codec_config", (v as AuVerdict.Close).reason)
        assertEquals("closed stays closed", "codec_config", (g.check(AnnexBTestStreams.pAu(codec), 0) as AuVerdict.Close).reason)
        val d = gate(codec).check(AnnexBTestStreams.pAu(codec), StreamProtocol.FLAG_DISCARDABLE)
        assertEquals("discardable", (d as AuVerdict.Close).reason)
    }

    private val garbage = byteArrayOf(9, 9, 9, 9)

    @Test
    fun threeRejectedKeyframesWithinTenSecondsClose() = eachCodec { codec ->
        val g = gate(codec)
        now = 0
        assertTrue(g.check(garbage, key) is AuVerdict.Reject)
        now = 2_000
        assertTrue(g.check(garbage, key) is AuVerdict.Reject)
        now = 10_000 // exactly ten seconds after the first
        assertEquals("rejected_keyframes", (g.check(garbage, key) as AuVerdict.Close).reason)
    }

    @Test
    fun rejectedKeyframesSpreadOverMoreThanTenSecondsDoNotClose() = eachCodec { codec ->
        val g = gate(codec)
        now = 0
        g.check(garbage, key)
        now = 6_000
        g.check(garbage, key)
        now = 10_001
        assertTrue(g.check(garbage, key) is AuVerdict.Reject)
        // the window slides: the latest three (6.0 s, 10.001 s, 12 s) are within ten seconds
        now = 12_000
        assertTrue(g.check(garbage, key) is AuVerdict.Close)
    }

    @Test
    fun aValidKeyframeInterruptsTheRun() = eachCodec { codec ->
        val g = gate(codec)
        now = 0
        g.check(garbage, key)
        now = 100
        g.check(garbage, key)
        now = 200
        accept(g.check(idr(codec), key))
        now = 300
        assertTrue(g.check(garbage, key) is AuVerdict.Reject)
        now = 400
        assertTrue(g.check(garbage, key) is AuVerdict.Reject)
        now = 500
        assertTrue(g.check(garbage, key) is AuVerdict.Close)
    }

    @Test
    fun rejectedNonKeyframesNeverCloseTheSession() = eachCodec { codec ->
        val g = gate(codec)
        repeat(20) {
            now += 10
            assertTrue(g.check(garbage, 0) is AuVerdict.Reject)
        }
        assertNotNull(accept(g.check(idr(codec), key)).activated)
    }

    @Test
    fun anIdrHiddenByAFramingFailureStillCountsAsAKeyframe() = eachCodec { codec ->
        // framing breaks after the IDR NAL unit was seen (a forbidden 00 00 02 in the following NAL unit)
        val s = AnnexBTestStreams.setsFor(codec).nals()
        val broken = annexB(s + AnnexBTestStreams.slice(codec, true) + byteArrayOf(9, 0, 0, 2, 9))
        val v = gate(codec).check(broken, 0)
        assertEquals(1, rejectRule(v))
        assertTrue((v as AuVerdict.Reject).keyFrame)
    }

    @Test
    fun rejectCarriesTheOffendingTypeAndSize() {
        val payload = annexB(AnnexBTestStreams.setsFor(AnnexBCodec.HEVC).nals() + AnnexBTestStreams.hevcSlice(true, nalType = 21))
        val v = gate(AnnexBCodec.HEVC).check(payload, key) as AuVerdict.Reject
        assertEquals(3, v.rule)
        assertEquals(21, v.nalType)
        assertEquals(payload.size, v.bytes)
        assertTrue(v.reason.isNotEmpty())
    }
}
