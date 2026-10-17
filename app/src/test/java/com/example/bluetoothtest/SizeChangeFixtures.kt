package com.example.bluetoothtest

/** Synthetic VP9 and AV1 keyframes of a chosen size, for the decoder size-change tests. */
internal object SizeChangeFixtures {
    private class BitWriter {
        private val bits = ArrayList<Int>()

        fun put(value: Long, count: Int) = apply {
            for (i in count - 1 downTo 0) bits += ((value shr i) and 1).toInt()
        }

        fun put(value: Int, count: Int) = put(value.toLong(), count)

        fun bytes(minimumBits: Int = 0): ByteArray {
            while (bits.size % 8 != 0 || bits.size < minimumBits) bits += 0
            return ByteArray(bits.size / 8) { index ->
                (0 until 8).fold(0) { acc, bit -> acc shl 1 or bits[index * 8 + bit] }.toByte()
            }
        }
    }

    /** A profile 0 VP9 keyframe header (VP9 bitstream spec 6.2) followed by filler for the compressed part. */
    fun vp9KeyFrame(width: Int, height: Int): ByteArray {
        val header = BitWriter()
            .put(2, 2).put(0, 1).put(0, 1) // frame_marker, profile 0
            .put(0, 1) // show_existing_frame
            .put(0, 1) // frame_type: key
            .put(1, 1) // show_frame
            .put(0, 1) // error_resilient_mode
            .put(0x49, 8).put(0x83, 8).put(0x42, 8) // sync code
            .put(2, 3).put(0, 1) // color_space, color_range
            .put(width - 1, 16).put(height - 1, 16)
            .put(0, 1) // render_and_frame_size_different
            .put(1, 1).put(1, 1) // refresh_frame_context, frame_parallel_decoding_mode
            .put(0, 2) // frame_context_idx
            .put(36, 6).put(2, 3).put(0, 1) // loop filter level, sharpness, no deltas
            .put(60, 8) // base_q_idx
            .bytes(minimumBits = 64)
        return header + ByteArray(200) { (it * 7).toByte() }
    }

    /** A VP9 inter frame header, to show that only keyframes carry the size. */
    fun vp9InterFrame(): ByteArray {
        val header = BitWriter()
            .put(2, 2).put(0, 1).put(0, 1).put(0, 1) // marker, profile 0, show_existing_frame
            .put(1, 1).put(1, 1).put(0, 1) // non-key, show_frame, error_resilient
            .put(0, 2) // reset_frame_context
            .put(0x01, 8) // refresh_frame_flags
            .also { writer -> repeat(3) { writer.put(it, 3).put(0, 1) } }
            .put(1, 1) // found_ref
            .put(0, 1).put(1, 1).put(1, 1) // render size, high precision mv, switchable filter
            .put(1, 1).put(0, 1).put(1, 2) // contexts
            .put(36, 6).put(2, 3).put(0, 1).put(80, 8)
            .bytes(minimumBits = 64)
        return header + ByteArray(50) { 1 }
    }

    private fun obu(type: Int, payload: ByteArray): ByteArray {
        require(payload.size < 128)
        return byteArrayOf(((type shl 3) or 0x2).toByte(), payload.size.toByte()) + payload
    }

    /** The libaom real-time shaped sequence header (no timing info, one operating point) for a size. */
    fun av1SequenceHeaderObu(width: Int, height: Int): ByteArray = obu(
        1,
        BitWriter()
            .put(0, 3).put(0, 1).put(0, 1) // profile, still_picture, reduced
            .put(0, 1).put(0, 1).put(0, 5) // timing_info, initial_display_delay, operating points - 1
            .put(0, 12).put(8, 5).put(0, 1) // idc, seq_level_idx, seq_tier
            .put(10, 4).put(9, 4).put(width - 1, 11).put(height - 1, 10)
            .put(0, 1) // frame_id_numbers_present
            .put(0, 1).put(1, 1).put(1, 1)
            .put(0, 4)
            .put(1, 1).put(0, 1).put(1, 1)
            .put(1, 1).put(1, 1)
            .put(6, 3)
            .bytes(),
    )

    /** A temporal unit holding a keyframe, with the sequence header in-band or left to the codec configuration. */
    fun av1KeyFrame(width: Int, height: Int, sequenceHeaderInBand: Boolean = true): ByteArray {
        val frame = obu(6, BitWriter().put(0, 1).put(0, 2).put(1, 1).put(0x3f, 8).bytes())
        return obu(2, ByteArray(0)) + (if (sequenceHeaderInBand) av1SequenceHeaderObu(width, height) else ByteArray(0)) + frame
    }

    fun av1InterFrame(): ByteArray = obu(2, ByteArray(0)) + obu(6, ByteArray(10) { 3 })

    /** Codec-config bytes an encoder reports for AV1: the sequence header OBU. */
    fun av1Config(width: Int, height: Int): ByteArray = av1SequenceHeaderObu(width, height)

    /** The same inside an AV1CodecConfigurationRecord (av1C). */
    fun av1cConfig(width: Int, height: Int): ByteArray =
        byteArrayOf(0x81.toByte(), 0x08, 0x0c, 0x00) + av1SequenceHeaderObu(width, height)
}
