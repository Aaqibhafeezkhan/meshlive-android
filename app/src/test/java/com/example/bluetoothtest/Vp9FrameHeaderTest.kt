package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Vp9FrameHeaderTest {
    /**
     * First 32 bytes of real encoder output (generated coverage input, 640x480 at 15 FPS) from the
     * media traces of regress-57484598: case 0028 (M31 OMX.Exynos.VP9, 600 kbps), 0026 (S24
     * c2.exynos.vp9, 600 kbps) and 0034 (ASUS c2.android.vp9, error resilient). Expected values were
     * read independently by the lane-b scratch parser (vp9_qp.py) from the full frames.
     */
    private data class Fixture(val name: String, val hex: String, val key: Boolean, val qIdx: Int, val bytes: Int)

    private val fixtures = listOf(
        Fixture("m31 keyframe", "824983420027f01df41438241c18dc3bfdfeff7fbfdfefea3187221872118320", true, 110, 8_956),
        Fixture("m31 frame 74", "863fc002181460068faf0040201c180282c41620b100084208882088c2109021", false, 13, 5_170),
        Fixture("m31 scene cut 75", "863fc002181460060ff30040200802824412209100084208882088c210902100", false, 12, 134_918),
        Fixture("m31 frame 80", "863fc00218fc607f8e1d008840239c99d28c4442211100080040020010000001", false, 255, 3_862),
        Fixture("s24 keyframe", "824983426027f01df414301b877fbfdfeff7fbfdfd4630e4430e423064002108", true, 110, 10_269),
        Fixture("s24 scene cut 75", "863fc002182c602f0fab02a2389b6f228c6148860c8444002108422086230882", false, 94, 82_650),
        Fixture("asus keyframe", "834983420027f01df068e0907063f00016205f7effc2fff0fe2fba3f7f47eff9", true, 126, 5_011),
        Fixture("asus scene cut 75", "8701024b081c120e0c320005fc7fbc5ffabfbbe6fce7af673da7f5074ede9f03", false, 50, 106_253),
        Fixture("asus frame 77", "870102493f1c120e0cfd0001787f45ffd1fa3ee3797faefdbf43ebf167b0e2af", false, 253, 2_619),
    )

    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun realHardwareAndSoftwareEncoderHeadersGiveTheirQuantizer() {
        fixtures.forEach { fixture ->
            val frame = Vp9FrameHeader.parse(hex(fixture.hex))
            assertNotNull(fixture.name, frame)
            frame!!
            assertEquals(fixture.name, fixture.key, frame.keyFrame)
            assertEquals(fixture.name, fixture.qIdx, frame.baseQIdx)
            assertTrue(fixture.name, frame.showFrame)
            if (fixture.key) {
                assertEquals(fixture.name, 640, frame.width)
                assertEquals(fixture.name, 480, frame.height)
            }
        }
    }

    @Test
    fun errorResilienceIsReadFromTheHeader() {
        assertTrue(Vp9FrameHeader.parse(hex(fixtures.first { it.name == "asus keyframe" }.hex))!!.errorResilient)
        assertFalse(Vp9FrameHeader.parse(hex(fixtures.first { it.name == "m31 keyframe" }.hex))!!.errorResilient)
    }

    @Test
    fun theM31SceneCutIsAnInterFrameAtTheStaleQuantizer() {
        // lane-b F1: the cut is not a keyframe; it keeps the pre-cut quantizer (13 -> 12).
        val before = Vp9FrameHeader.parse(hex(fixtures[1].hex))!!
        val cut = Vp9FrameHeader.parse(hex(fixtures[2].hex))!!
        assertFalse(cut.keyFrame)
        assertTrue(cut.baseQIdx <= before.baseQIdx)
        assertEquals("inter", cut.label)
    }

    @Test
    fun truncatedOrForeignDataIsRejected() {
        val full = hex(fixtures[1].hex)
        assertNull(Vp9FrameHeader.parse(full.copyOf(6)))
        assertNull(Vp9FrameHeader.parse(byteArrayOf()))
        // AV1 temporal delimiter / anything without the 0b10 frame marker.
        assertNull(Vp9FrameHeader.parse(byteArrayOf(0x12, 0x00, 0x0a, 0x0b)))
        // A keyframe with a broken sync code.
        val badSync = hex(fixtures[0].hex).also { it[1] = 0x48 }
        assertNull(Vp9FrameHeader.parse(badSync))
    }

    @Test
    fun syntheticHeadersCoverLoopFilterDeltasHiddenFramesAndProfiles() {
        val inter = BitWriter().interFrame(show = true, qIdx = 77, withDeltas = true).bytes()
        val parsedInter = Vp9FrameHeader.parse(inter)!!
        assertEquals(77, parsedInter.baseQIdx)
        assertEquals(0x05, parsedInter.refreshFrameFlags)

        val hidden = BitWriter().interFrame(show = false, qIdx = 30, withDeltas = false).bytes()
        val parsedHidden = Vp9FrameHeader.parse(hidden)!!
        assertFalse(parsedHidden.showFrame)
        assertEquals(30, parsedHidden.baseQIdx)

        val profile1Key = BitWriter().keyFrame(profile = 1, width = 352, height = 288, qIdx = 200).bytes()
        val parsedKey = Vp9FrameHeader.parse(profile1Key)!!
        assertTrue(parsedKey.keyFrame)
        assertEquals(352, parsedKey.width)
        assertEquals(288, parsedKey.height)
        assertEquals(200, parsedKey.baseQIdx)

        val existing = Vp9FrameHeader.parse(BitWriter().bits(2, 2).bits(0, 2).bits(1, 1).bits(3, 3).bytes())!!
        assertTrue(existing.showExistingFrame)
        assertEquals(-1, existing.baseQIdx)
    }

    @Test
    fun superframesReportTheShownFrame() {
        val hidden = BitWriter().interFrame(show = false, qIdx = 30, withDeltas = false).bytes()
        val shown = BitWriter().interFrame(show = true, qIdx = 140, withDeltas = false).bytes()
        val marker = (0xc0 or 1).toByte() // one-byte sizes, two frames
        val superframe = hidden + shown + byteArrayOf(marker, hidden.size.toByte(), shown.size.toByte(), marker)
        assertEquals(listOf(0 to hidden.size, hidden.size to shown.size), Vp9FrameHeader.frames(superframe))
        assertEquals(140, Vp9FrameHeader.accessUnit(superframe)!!.baseQIdx)
        // Without a valid index the whole buffer is one frame.
        assertEquals(listOf(0 to shown.size), Vp9FrameHeader.frames(shown))
        // Sizes that do not fill the data are not a superframe index.
        val bogus = shown + byteArrayOf(marker, 1, 1, marker)
        assertEquals(1, Vp9FrameHeader.frames(bogus).size)
    }

    @Test
    fun quantizerWindowSummarizesAndFlagsTheEncoderFloor() {
        val window = QuantizerWindow()
        assertNull(window.summaryAndReset())
        listOf(12, 63, 114, 165, 216, 255, 255, 255).forEach(window::record)
        window.record(-1) // show_existing frames carry no quantizer
        val summary = window.summaryAndReset()!!
        assertTrue(summary, summary.startsWith("VP9 q_idx p50/max/min 216/255/12"))
        assertTrue(summary, summary.contains("at max 38%"))
        assertEquals(0, window.samples)
    }

    /** Writes VP9 uncompressed-header fields MSB first. */
    private class BitWriter {
        private val bits = ArrayList<Int>()

        fun bits(value: Int, count: Int): BitWriter {
            for (i in count - 1 downTo 0) bits += value shr i and 1
            return this
        }

        fun bytes(): ByteArray {
            while (bits.size % 8 != 0 || bits.size < 64) bits += 0
            return ByteArray(bits.size / 8) { index ->
                (0 until 8).fold(0) { acc, bit -> acc shl 1 or bits[index * 8 + bit] }.toByte()
            }
        }

        fun interFrame(show: Boolean, qIdx: Int, withDeltas: Boolean): BitWriter {
            bits(2, 2).bits(0, 1).bits(0, 1) // marker, profile 0
            bits(0, 1) // show_existing_frame
            bits(1, 1) // frame_type: non-key
            bits(if (show) 1 else 0, 1)
            bits(0, 1) // error_resilient_mode
            if (!show) bits(0, 1) // intra_only
            bits(0, 2) // reset_frame_context
            bits(0x05, 8) // refresh_frame_flags
            repeat(3) { bits(it, 3).bits(0, 1) } // ref_frame_idx, sign bias
            bits(1, 1) // found_ref on the first reference
            bits(0, 1) // render_and_frame_size_different
            bits(1, 1) // allow_high_precision_mv
            bits(1, 1) // is_filter_switchable
            bits(1, 1).bits(0, 1) // refresh_frame_context, frame_parallel_decoding_mode
            bits(1, 2) // frame_context_idx
            loopFilter(withDeltas)
            return bits(qIdx, 8)
        }

        fun keyFrame(profile: Int, width: Int, height: Int, qIdx: Int): BitWriter {
            bits(2, 2).bits(profile and 1, 1).bits(profile shr 1, 1)
            bits(0, 1) // show_existing_frame
            bits(0, 1) // frame_type: key
            bits(1, 1) // show_frame
            bits(0, 1) // error_resilient_mode
            bits(0x49, 8).bits(0x83, 8).bits(0x42, 8)
            bits(2, 3) // color_space BT.709
            bits(0, 1) // color_range
            if (profile == 1 || profile == 3) bits(1, 1).bits(0, 1).bits(0, 1) // subsampling, reserved
            bits(width - 1, 16).bits(height - 1, 16)
            bits(0, 1) // render_and_frame_size_different
            bits(1, 1).bits(1, 1) // refresh_frame_context, frame_parallel_decoding_mode
            bits(0, 2) // frame_context_idx
            loopFilter(withDeltas = true)
            return bits(qIdx, 8)
        }

        private fun loopFilter(withDeltas: Boolean) {
            bits(36, 6) // filter_level
            bits(2, 3) // sharpness_level
            bits(if (withDeltas) 1 else 0, 1) // loop_filter_delta_enabled
            if (!withDeltas) return
            bits(1, 1) // loop_filter_delta_update
            listOf(1, 0, -1, -1).forEach { delta ->
                bits(1, 1) // update_ref_delta
                bits(kotlin.math.abs(delta), 6).bits(if (delta < 0) 1 else 0, 1)
            }
            bits(0, 1).bits(1, 1).bits(5, 6).bits(0, 1) // mode deltas: skip one, update one
        }
    }
}
