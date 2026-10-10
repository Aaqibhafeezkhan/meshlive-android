package com.example.bluetoothtest

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Av1DroppabilityTest {
    private class BitWriter {
        private val bits = mutableListOf<Int>()
        fun put(value: Long, count: Int) = apply {
            for (i in count - 1 downTo 0) bits += ((value shr i) and 1).toInt()
        }
        fun bytes(): ByteArray {
            while (bits.size % 8 != 0) bits += 0
            return ByteArray(bits.size / 8) { index ->
                (0 until 8).fold(0) { acc, bit -> acc shl 1 or bits[index * 8 + bit] }.toByte()
            }
        }
    }

    private fun obu(type: Int, payload: ByteArray): ByteArray {
        require(payload.size < 128)
        return byteArrayOf(((type shl 3) or 0x2).toByte(), payload.size.toByte()) + payload
    }

    // libaom real-time shape: no timing info, one operating point, 7-bit order hints,
    // screen-content tools chosen per frame, integer MV chosen per frame.
    private fun sequenceHeader() = BitWriter()
        .put(0, 3).put(0, 1).put(0, 1) // profile, still_picture, reduced
        .put(0, 1) // timing_info_present
        .put(0, 1) // initial_display_delay_present
        .put(0, 5) // operating_points_cnt_minus_1
        .put(0, 12).put(8, 5).put(0, 1) // idc, seq_level_idx 8, seq_tier
        .put(10, 4).put(9, 4).put(639, 11).put(479, 10) // frame size bits and maxima
        .put(0, 1) // frame_id_numbers_present
        .put(0, 1).put(1, 1).put(1, 1) // 128x128, filter intra, intra edge
        .put(0, 4) // interintra, masked, warped, dual filter
        .put(1, 1).put(0, 1).put(1, 1) // order hint, jnt_comp, ref_frame_mvs
        .put(1, 1) // seq_choose_screen_content_tools -> SELECT
        .put(1, 1) // seq_choose_integer_mv -> SELECT
        .put(6, 3) // order_hint_bits_minus_1
        .bytes()

    private fun interFrameHeader(refresh: Int, screenContent: Int = 0) = BitWriter()
        .put(0, 1) // show_existing_frame
        .put(1, 2) // INTER_FRAME
        .put(1, 1) // show_frame
        .put(0, 1) // error_resilient_mode
        .put(0, 1) // disable_cdf_update
        .put(screenContent.toLong(), 1).also { if (screenContent == 1) it.put(0, 1) }
        .put(0, 1) // frame_size_override_flag
        .put(37, 7) // order_hint
        .put(0, 3) // primary_ref_frame
        .put(refresh.toLong(), 8)
        .put(0x5a, 8) // rest of the frame (ignored)
        .bytes()

    private fun keyFrameHeader() = BitWriter().put(0, 1).put(0, 2).put(1, 1).put(0x3f, 8).bytes()

    @Test
    fun interFramesThatRefreshNothingAreDroppableAfterTheSequenceHeader() {
        val analyzer = BitstreamDroppability(MediaFormat.MIMETYPE_VIDEO_AV1)
        val temporalDelimiter = obu(2, ByteArray(0))
        assertFalse(analyzer.isDroppable(temporalDelimiter + obu(6, interFrameHeader(0)), keyFrame = false))
        assertFalse(
            analyzer.isDroppable(temporalDelimiter + obu(1, sequenceHeader()) + obu(6, keyFrameHeader()), keyFrame = true),
        )
        assertTrue(analyzer.isDroppable(temporalDelimiter + obu(6, interFrameHeader(0)), keyFrame = false))
        // The libaom override that broke the M31 decoder: a "droppable" frame refreshing LAST.
        assertFalse(analyzer.isDroppable(temporalDelimiter + obu(6, interFrameHeader(0x01)), keyFrame = false))
        assertTrue(analyzer.isDroppable(temporalDelimiter + obu(6, interFrameHeader(0, screenContent = 1)), keyFrame = false))
        assertEquals(2L, analyzer.droppableFrames)
    }

    @Test
    fun refreshFlagsParseFromTheFrameHeader() {
        val sequence = BitstreamDroppability.parseAv1SequenceHeader(sequenceHeader())
        assertEquals(7, sequence.orderHintBits)
        assertEquals(0x81, BitstreamDroppability.av1RefreshFrameFlags(sequence, interFrameHeader(0x81), 0, 0))
        assertEquals(0xff, BitstreamDroppability.av1RefreshFrameFlags(sequence, keyFrameHeader(), 0, 0))
    }

    @Test
    fun truncatedDataIsNeverDroppable() {
        val analyzer = BitstreamDroppability(MediaFormat.MIMETYPE_VIDEO_AV1)
        analyzer.isDroppable(obu(1, sequenceHeader()) + obu(6, keyFrameHeader()), keyFrame = true)
        val frame = obu(6, interFrameHeader(0))
        assertFalse(analyzer.isDroppable(frame.copyOf(frame.size - 3), keyFrame = false))
    }
}
