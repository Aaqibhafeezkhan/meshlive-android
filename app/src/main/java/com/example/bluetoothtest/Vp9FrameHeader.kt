package com.example.bluetoothtest

/**
 * One VP9 frame's uncompressed header (VP9 bitstream spec 6.2), read far enough to reach
 * base_q_idx. Diagnostic for workstream C (burst-free encoding): hardware VP9 encoders expose no
 * per-frame quantizer through MediaCodec before API 33 encoding statistics, yet the burst mechanism
 * is a quantizer one. The M31 OMX.Exynos.VP9 coded its scene cut as an inter frame at the stale
 * pre-cut base_q_idx (12 at 600 kbps -> 135 KB), then climbed about +51 per frame to 255 and stayed
 * there for seconds (lane-b-burst-*-529f36cc); the S24 c2.exynos.vp9 rose only +48..+56 on the cut
 * frame (38 -> 94, 83 KB). Later cuts at an already high quantizer cost 5-11 KB.
 *
 * [baseQIdx] is 0-255 (0 = lossless when every delta is 0); -1 for show_existing_frame, which
 * carries no new picture.
 */
internal data class Vp9FrameInfo(
    val keyFrame: Boolean,
    val intraOnly: Boolean,
    val showFrame: Boolean,
    val showExistingFrame: Boolean,
    val errorResilient: Boolean,
    val refreshFrameFlags: Int,
    val baseQIdx: Int,
    /** Coded size, only from keyframes and intra-only frames (0 otherwise). */
    val width: Int = 0,
    val height: Int = 0,
) {
    val label: String
        get() = when {
            showExistingFrame -> "show_existing"
            keyFrame -> "key"
            intraOnly -> "intra_only"
            else -> "inter"
        }
}

internal object Vp9FrameHeader {
    /** Highest quantizer index; the M31 sat here for seconds after each scene-cut burst. */
    const val MAX_Q_IDX = 255

    /**
     * Parses the frame at [offset] (length [length]) of [bytes]. Null when the data is not a VP9
     * frame header (wrong frame marker, bad sync code, or truncated before base_q_idx).
     */
    fun parse(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Vp9FrameInfo? {
        if (offset < 0 || length <= 0 || offset + length > bytes.size) return null
        val reader = Reader(bytes, offset, length)
        return runCatching { parseHeader(reader) }.getOrNull()
    }

    /**
     * The frames of one encoder access unit: a superframe (several frames, usually a hidden
     * reference frame and a shown one, with an index at the end) or the whole buffer.
     * Pairs are (offset, size).
     */
    fun frames(bytes: ByteArray): List<Pair<Int, Int>> {
        superframeSizes(bytes)?.let { sizes ->
            val frames = ArrayList<Pair<Int, Int>>(sizes.size)
            var position = 0
            sizes.forEach { size ->
                frames += position to size
                position += size
            }
            return frames
        }
        return if (bytes.isEmpty()) emptyList() else listOf(0 to bytes.size)
    }

    /**
     * The quantizer of an access unit: the shown frame's base_q_idx (the frame the viewer sees),
     * falling back to the highest one when no frame is shown. Null when nothing parses.
     */
    fun accessUnit(bytes: ByteArray): Vp9FrameInfo? {
        val parsed = frames(bytes).mapNotNull { (offset, size) -> parse(bytes, offset, size) }
            .filter { !it.showExistingFrame }
        return parsed.lastOrNull { it.showFrame } ?: parsed.maxByOrNull { it.baseQIdx }
    }

    private fun superframeSizes(bytes: ByteArray): List<Int>? {
        if (bytes.isEmpty()) return null
        val marker = bytes.last().toInt() and 0xff
        if (marker and 0xe0 != 0xc0) return null
        val frames = (marker and 0x7) + 1
        val magnitude = (marker shr 3 and 0x3) + 1
        val indexSize = 2 + magnitude * frames
        if (bytes.size < indexSize) return null
        val indexStart = bytes.size - indexSize
        if (bytes[indexStart].toInt() and 0xff != marker) return null
        val sizes = ArrayList<Int>(frames)
        var position = indexStart + 1
        repeat(frames) {
            var size = 0L
            for (byte in 0 until magnitude) {
                size = size or ((bytes[position + byte].toLong() and 0xff) shl (8 * byte))
            }
            position += magnitude
            sizes += size.toInt()
        }
        val total = sizes.fold(0L) { sum, size -> sum + size }
        // A frame whose last byte happens to look like a marker is not a superframe: the indexed
        // frames must exactly fill the bytes before the index.
        if (sizes.any { it <= 0 } || total != indexStart.toLong()) return null
        return sizes
    }

    private fun parseHeader(r: Reader): Vp9FrameInfo? {
        if (r.bits(2) != FRAME_MARKER) return null
        val profileLow = r.bits(1)
        val profileHigh = r.bits(1)
        val profile = (profileHigh shl 1) or profileLow
        if (profile == 3) r.bits(1) // reserved_zero
        if (r.bits(1) == 1) {
            r.bits(3) // frame_to_show_map_idx
            return Vp9FrameInfo(
                keyFrame = false,
                intraOnly = false,
                showFrame = true,
                showExistingFrame = true,
                errorResilient = false,
                refreshFrameFlags = 0,
                baseQIdx = -1,
            )
        }
        val keyFrame = r.bits(1) == 0
        val showFrame = r.bits(1) == 1
        val errorResilient = r.bits(1) == 1
        var intraOnly = false
        var refresh = ALL_REFERENCES
        var width = 0
        var height = 0
        if (keyFrame) {
            if (!syncCode(r)) return null
            colorConfig(r, profile)
            width = r.bits(16) + 1
            height = r.bits(16) + 1
            renderSize(r)
        } else {
            intraOnly = if (showFrame) false else r.bits(1) == 1
            if (!errorResilient) r.bits(2) // reset_frame_context
            if (intraOnly) {
                if (!syncCode(r)) return null
                if (profile > 0) colorConfig(r, profile)
                refresh = r.bits(8)
                width = r.bits(16) + 1
                height = r.bits(16) + 1
                renderSize(r)
            } else {
                refresh = r.bits(8)
                repeat(REFS_PER_FRAME) {
                    r.bits(3) // ref_frame_idx
                    r.bits(1) // ref_frame_sign_bias
                }
                var foundRef = false
                for (i in 0 until REFS_PER_FRAME) {
                    if (r.bits(1) == 1) {
                        foundRef = true
                        break
                    }
                }
                if (!foundRef) {
                    r.bits(16) // frame_width_minus_1
                    r.bits(16) // frame_height_minus_1
                }
                renderSize(r)
                r.bits(1) // allow_high_precision_mv
                if (r.bits(1) == 0) r.bits(2) // is_filter_switchable / raw_interpolation_filter
            }
        }
        if (!errorResilient) {
            r.bits(1) // refresh_frame_context
            r.bits(1) // frame_parallel_decoding_mode
        }
        r.bits(2) // frame_context_idx
        // loop_filter_params()
        r.bits(6) // filter_level
        r.bits(3) // sharpness_level
        if (r.bits(1) == 1) { // loop_filter_delta_enabled
            if (r.bits(1) == 1) { // loop_filter_delta_update
                repeat(4) { if (r.bits(1) == 1) r.bits(7) } // update_ref_delta + su(6)
                repeat(2) { if (r.bits(1) == 1) r.bits(7) } // update_mode_delta + su(6)
            }
        }
        val baseQIdx = r.bits(8)
        return Vp9FrameInfo(
            keyFrame = keyFrame,
            intraOnly = intraOnly,
            showFrame = showFrame,
            showExistingFrame = false,
            errorResilient = errorResilient,
            refreshFrameFlags = refresh,
            baseQIdx = baseQIdx,
            width = width,
            height = height,
        )
    }

    private fun syncCode(r: Reader): Boolean =
        r.bits(8) == 0x49 && r.bits(8) == 0x83 && r.bits(8) == 0x42

    private fun colorConfig(r: Reader, profile: Int) {
        if (profile >= 2) r.bits(1) // ten_or_twelve_bit
        val colorSpace = r.bits(3)
        if (colorSpace != CS_RGB) {
            r.bits(1) // color_range
            if (profile == 1 || profile == 3) {
                r.bits(1) // subsampling_x
                r.bits(1) // subsampling_y
                r.bits(1) // reserved_zero
            }
        } else if (profile == 1 || profile == 3) {
            r.bits(1) // reserved_zero
        }
    }

    private fun renderSize(r: Reader) {
        if (r.bits(1) == 1) { // render_and_frame_size_different
            r.bits(16)
            r.bits(16)
        }
    }

    private class Reader(private val bytes: ByteArray, private val start: Int, private val length: Int) {
        private var position = 0

        fun bits(count: Int): Int {
            var value = 0
            repeat(count) {
                require(position < length * 8) { "VP9 header truncated" }
                val byte = bytes[start + (position shr 3)].toInt() and 0xff
                value = (value shl 1) or (byte shr (7 - (position and 7)) and 1)
                position++
            }
            return value
        }
    }

    private const val FRAME_MARKER = 2
    private const val CS_RGB = 7
    private const val REFS_PER_FRAME = 3
    private const val ALL_REFERENCES = 0xff
}

/**
 * Per-window quantizer summary for the encoder's 5 s output line: share of frames at the maximum
 * quantizer is the "encoder floor" signal (at 150 kbps 640x480 the M31 sat at q_idx >= 250 for 99.5%
 * of frames, so trimming its target changed nothing; lane-b F6).
 */
internal class QuantizerWindow {
    private val values = ArrayList<Int>()

    fun record(qIdx: Int) {
        if (qIdx >= 0) values += qIdx
    }

    val samples: Int get() = values.size

    /** "q p50/max/min a/b/c, at max n%" or null when nothing was parsed. */
    fun summaryAndReset(): String? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val atMax = sorted.count { it >= Vp9FrameHeader.MAX_Q_IDX - 5 }
        values.clear()
        return "VP9 q_idx p50/max/min %d/%d/%d, at max %.0f%%".format(
            sorted[sorted.size / 2],
            sorted.last(),
            sorted.first(),
            atMax * 100.0 / sorted.size,
        )
    }
}
