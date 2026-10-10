package com.example.bluetoothtest

import android.media.MediaFormat

/**
 * Experimental temporal layering (off by default: DebugTunables.experimentalTemporalLayers; removed
 * 2026-10-07 under the no-frame-loss rule, restored 2026-10-08 as an Experimental option). Nothing
 * constructs this while the experiment is off.
 *
 * Proves from the compressed bitstream that an encoded frame can be dropped by any hop without
 * corrupting later frames (root-cause workstream A). An encoder setting is only a request: libaom's
 * real-time reference structure silently overwrote per-frame "update nothing" flags, and dropping
 * three such frames broke the M31's AV1 decoder (limits-new-ccaa9c9f). Nothing is marked
 * droppable on trust.
 *
 * - AV1: every frame in the access unit has `refresh_frame_flags == 0` (no reference slot, saved
 *   CDFs or motion fields change). Needs the sequence header, kept from the last keyframe.
 * - VP9: `refresh_frame_flags == 0` and every inter frame of the session is error resilient;
 *   otherwise the next frame inherits motion vectors, segmentation and loop-filter deltas from the
 *   previous decoded frame. Superframes are never droppable.
 * - Anything else or unparsable data is never droppable.
 */
internal class BitstreamDroppability(private val mimeType: String) {
    private var vp9AllErrorResilient = true
    private var av1Sequence: Av1SequenceHeader? = null

    var droppableFrames = 0L
        private set
    var examinedFrames = 0L
        private set

    /**
     * Codec configuration (csd) from the encoder: an AV1 sequence header as raw OBUs or inside an
     * AV1CodecConfigurationRecord (av1C, first byte 0x81). Without it an encoder that sends its
     * sequence header only as configuration left every later frame unverifiable (never droppable).
     */
    fun observeCodecConfig(bytes: ByteArray) {
        if (mimeType != MediaFormat.MIMETYPE_VIDEO_AV1 || bytes.isEmpty()) return
        // AV1 OBU headers have the forbidden bit clear, so 0x81 can only be the av1C marker/version.
        val obus = if (bytes[0].toInt() and 0xff == AV1C_MARKER_VERSION_1) {
            if (bytes.size <= AV1C_HEADER_BYTES) return
            bytes.copyOfRange(AV1C_HEADER_BYTES, bytes.size)
        } else {
            bytes
        }
        runCatching {
            forEachObu(obus) { type, _, _, payload ->
                if (type == OBU_SEQUENCE_HEADER) av1Sequence = parseAv1SequenceHeader(payload)
            }
        }
    }

    /** [bytes] is one non-config access unit. Keyframes are parsed (sequence header) but never droppable. */
    fun isDroppable(bytes: ByteArray, keyFrame: Boolean): Boolean {
        val droppable = when (mimeType) {
            MediaFormat.MIMETYPE_VIDEO_AV1 -> av1Droppable(bytes, keyFrame)
            MediaFormat.MIMETYPE_VIDEO_VP9 -> !keyFrame && vp9Droppable(bytes)
            else -> false
        }
        if (!keyFrame) examinedFrames++
        if (droppable) droppableFrames++
        return droppable
    }

    private fun vp9Droppable(bytes: ByteArray): Boolean {
        val header = vp9Header(bytes)
        if (header == null || !header.errorResilient) vp9AllErrorResilient = false
        return vp9AllErrorResilient && header != null && !header.keyFrame &&
            !header.intraOnly && !header.superframe && header.refreshFrameFlags == 0
    }

    private fun av1Droppable(bytes: ByteArray, keyFrame: Boolean): Boolean {
        val refreshes = mutableListOf<Int>()
        val parsed = runCatching {
            forEachObu(bytes) { type, temporalId, spatialId, payload ->
                when (type) {
                    OBU_SEQUENCE_HEADER -> av1Sequence = parseAv1SequenceHeader(payload)
                    OBU_FRAME_HEADER, OBU_FRAME -> {
                        val sequence = av1Sequence ?: error("No AV1 sequence header yet")
                        refreshes += av1RefreshFrameFlags(sequence, payload, temporalId, spatialId)
                    }
                }
            }
        }.isSuccess
        return parsed && !keyFrame && refreshes.isNotEmpty() && refreshes.all { it == 0 }
    }

    internal data class Vp9Header(
        val keyFrame: Boolean,
        val errorResilient: Boolean,
        val intraOnly: Boolean,
        val refreshFrameFlags: Int,
        val superframe: Boolean,
    )

    internal data class Av1SequenceHeader(
        val reducedStillPictureHeader: Boolean,
        val decoderModelInfoPresent: Boolean,
        val equalPictureInterval: Boolean,
        val bufferRemovalTimeLength: Int,
        val framePresentationTimeLength: Int,
        val operatingPointIdc: IntArray,
        val decoderModelPresentForOp: BooleanArray,
        val frameIdNumbersPresent: Boolean,
        val frameIdLength: Int,
        val forceScreenContentTools: Int,
        val forceIntegerMv: Int,
        val orderHintBits: Int,
    )

    companion object {
        const val OBU_SEQUENCE_HEADER = 1
        private const val AV1C_MARKER_VERSION_1 = 0x81
        private const val AV1C_HEADER_BYTES = 4
        const val OBU_FRAME_HEADER = 3
        const val OBU_FRAME = 6
        private const val SELECT = 2
        private const val KEY_FRAME = 0
        private const val INTRA_ONLY_FRAME = 2
        private const val SWITCH_FRAME = 3

        /** Parses the VP9 uncompressed header far enough to read refresh_frame_flags. */
        internal fun vp9Header(bytes: ByteArray): Vp9Header? {
            if (bytes.isEmpty()) return null
            val superframe = hasVp9SuperframeIndex(bytes)
            val reader = BitReader(bytes)
            return runCatching {
                if (reader.bits(2) != 2) return null // frame_marker
                val profile = reader.bits(1) or (reader.bits(1) shl 1)
                if (profile == 3) reader.bits(1)
                if (reader.bits(1) == 1) return null // show_existing_frame
                val keyFrame = reader.bits(1) == 0
                val showFrame = reader.bits(1) == 1
                val errorResilient = reader.bits(1) == 1
                if (keyFrame) return Vp9Header(true, errorResilient, false, 0xff, superframe)
                val intraOnly = if (showFrame) false else reader.bits(1) == 1
                if (!errorResilient) reader.bits(2) // reset_frame_context
                if (intraOnly) return Vp9Header(false, errorResilient, true, 0xff, superframe)
                Vp9Header(false, errorResilient, false, reader.bits(8), superframe)
            }.getOrNull()
        }

        private fun hasVp9SuperframeIndex(bytes: ByteArray): Boolean {
            val marker = bytes.last().toInt() and 0xff
            if (marker and 0xe0 != 0xc0) return false
            val frames = (marker and 0x7) + 1
            val magnitude = (marker shr 3 and 0x3) + 1
            val indexSize = 2 + magnitude * frames
            return bytes.size >= indexSize && (bytes[bytes.size - indexSize].toInt() and 0xff) == marker
        }

        /** Walks low-overhead OBUs (each with obu_has_size_field, as MediaCodec and libaom emit). */
        internal fun forEachObu(bytes: ByteArray, onObu: (type: Int, temporalId: Int, spatialId: Int, payload: ByteArray) -> Unit) {
            var position = 0
            while (position < bytes.size) {
                val header = bytes[position].toInt() and 0xff
                require(header and 0x80 == 0) { "AV1 forbidden bit" }
                val type = header shr 3 and 0xf
                val extension = header and 0x4 != 0
                val hasSize = header and 0x2 != 0
                position++
                var temporalId = 0
                var spatialId = 0
                if (extension) {
                    val ext = bytes[position].toInt() and 0xff
                    temporalId = ext shr 5
                    spatialId = ext shr 3 and 0x3
                    position++
                }
                val size: Int
                if (hasSize) {
                    var value = 0L
                    var shift = 0
                    while (true) {
                        val byte = bytes[position++].toInt() and 0xff
                        value = value or ((byte and 0x7f).toLong() shl shift)
                        if (byte and 0x80 == 0) break
                        shift += 7
                        require(shift < 56) { "AV1 leb128 too long" }
                    }
                    size = value.toInt()
                } else {
                    size = bytes.size - position
                }
                require(size >= 0 && position + size <= bytes.size) { "AV1 OBU overruns access unit" }
                onObu(type, temporalId, spatialId, bytes.copyOfRange(position, position + size))
                position += size
            }
        }

        internal fun parseAv1SequenceHeader(payload: ByteArray): Av1SequenceHeader {
            val r = BitReader(payload)
            r.bits(3) // seq_profile
            r.bits(1) // still_picture
            val reduced = r.bits(1) == 1
            var decoderModelInfoPresent = false
            var equalPictureInterval = false
            var bufferDelayLength = 0
            var bufferRemovalTimeLength = 0
            var framePresentationTimeLength = 0
            val idc: IntArray
            val decoderModelPresent: BooleanArray
            if (reduced) {
                r.bits(5) // seq_level_idx[0]
                idc = intArrayOf(0)
                decoderModelPresent = booleanArrayOf(false)
            } else {
                val timingInfoPresent = r.bits(1) == 1
                if (timingInfoPresent) {
                    r.bits(32) // num_units_in_display_tick
                    r.bits(32) // time_scale
                    equalPictureInterval = r.bits(1) == 1
                    if (equalPictureInterval) r.uvlc()
                    decoderModelInfoPresent = r.bits(1) == 1
                    if (decoderModelInfoPresent) {
                        bufferDelayLength = r.bits(5) + 1
                        r.bits(32) // num_units_in_decoding_tick
                        bufferRemovalTimeLength = r.bits(5) + 1
                        framePresentationTimeLength = r.bits(5) + 1
                    }
                }
                val initialDisplayDelayPresent = r.bits(1) == 1
                val count = r.bits(5) + 1
                idc = IntArray(count)
                decoderModelPresent = BooleanArray(count)
                for (i in 0 until count) {
                    idc[i] = r.bits(12)
                    val level = r.bits(5)
                    if (level > 7) r.bits(1) // seq_tier
                    if (decoderModelInfoPresent) {
                        decoderModelPresent[i] = r.bits(1) == 1
                        if (decoderModelPresent[i]) {
                            r.bits(bufferDelayLength) // decoder_buffer_delay
                            r.bits(bufferDelayLength) // encoder_buffer_delay
                            r.bits(1) // low_delay_mode_flag
                        }
                    }
                    if (initialDisplayDelayPresent && r.bits(1) == 1) r.bits(4)
                }
            }
            val widthBits = r.bits(4) + 1
            val heightBits = r.bits(4) + 1
            r.bits(widthBits)
            r.bits(heightBits)
            val frameIdNumbersPresent = !reduced && r.bits(1) == 1
            var frameIdLength = 0
            if (frameIdNumbersPresent) {
                val delta = r.bits(4) + 2
                val additional = r.bits(3) + 1
                frameIdLength = delta + additional
            }
            r.bits(1) // use_128x128_superblock
            r.bits(1) // enable_filter_intra
            r.bits(1) // enable_intra_edge_filter
            var forceScreenContentTools = SELECT
            var forceIntegerMv = SELECT
            var orderHintBits = 0
            if (!reduced) {
                r.bits(1) // enable_interintra_compound
                r.bits(1) // enable_masked_compound
                r.bits(1) // enable_warped_motion
                r.bits(1) // enable_dual_filter
                val enableOrderHint = r.bits(1) == 1
                if (enableOrderHint) {
                    r.bits(1) // enable_jnt_comp
                    r.bits(1) // enable_ref_frame_mvs
                }
                forceScreenContentTools = if (r.bits(1) == 1) SELECT else r.bits(1)
                forceIntegerMv = if (forceScreenContentTools > 0) {
                    if (r.bits(1) == 1) SELECT else r.bits(1)
                } else {
                    SELECT
                }
                if (enableOrderHint) orderHintBits = r.bits(3) + 1
            }
            return Av1SequenceHeader(
                reduced,
                decoderModelInfoPresent,
                equalPictureInterval,
                bufferRemovalTimeLength,
                framePresentationTimeLength,
                idc,
                decoderModelPresent,
                frameIdNumbersPresent,
                frameIdLength,
                forceScreenContentTools,
                forceIntegerMv,
                orderHintBits,
            )
        }

        /** refresh_frame_flags of one frame header (AV1 spec 5.9.2); 0xff for shown key/switch frames. */
        internal fun av1RefreshFrameFlags(
            sequence: Av1SequenceHeader,
            payload: ByteArray,
            temporalId: Int,
            spatialId: Int,
        ): Int {
            if (sequence.reducedStillPictureHeader) return 0xff
            val r = BitReader(payload)
            require(r.bits(1) == 0) { "show_existing_frame is not a droppable frame" }
            val frameType = r.bits(2)
            val frameIsIntra = frameType == INTRA_ONLY_FRAME || frameType == KEY_FRAME
            val showFrame = r.bits(1) == 1
            if (showFrame && sequence.decoderModelInfoPresent && !sequence.equalPictureInterval) {
                r.bits(sequence.framePresentationTimeLength)
            }
            if (!showFrame) r.bits(1) // showable_frame
            if (frameType == SWITCH_FRAME || frameType == KEY_FRAME && showFrame) return 0xff
            val errorResilient = r.bits(1) == 1
            r.bits(1) // disable_cdf_update
            val allowScreenContentTools = if (sequence.forceScreenContentTools == SELECT) {
                r.bits(1)
            } else {
                sequence.forceScreenContentTools
            }
            if (allowScreenContentTools == 1 && sequence.forceIntegerMv == SELECT) {
                r.bits(1) // force_integer_mv (read even for intra frames, then overridden)
            }
            if (sequence.frameIdNumbersPresent) r.bits(sequence.frameIdLength)
            r.bits(1) // frame_size_override_flag (not a switch frame here)
            r.bits(sequence.orderHintBits)
            if (!frameIsIntra && !errorResilient) r.bits(3) // primary_ref_frame
            if (sequence.decoderModelInfoPresent && r.bits(1) == 1) {
                for (op in sequence.operatingPointIdc.indices) {
                    if (!sequence.decoderModelPresentForOp[op]) continue
                    val idc = sequence.operatingPointIdc[op]
                    val inTemporal = idc shr temporalId and 1 == 1
                    val inSpatial = idc shr (spatialId + 8) and 1 == 1
                    if (idc == 0 || inTemporal && inSpatial) r.bits(sequence.bufferRemovalTimeLength)
                }
            }
            return r.bits(8)
        }
    }

    private class BitReader(private val bytes: ByteArray) {
        private var position = 0

        fun bits(count: Int): Int {
            var value = 0L
            repeat(count) {
                val byte = bytes[position shr 3].toInt() and 0xff
                value = value shl 1 or (byte shr (7 - (position and 7)) and 1).toLong()
                position++
            }
            return value.toInt()
        }

        fun uvlc() {
            var leadingZeros = 0
            while (bits(1) == 0) {
                leadingZeros++
                require(leadingZeros < 32) { "AV1 uvlc too long" }
            }
            if (leadingZeros > 0) bits(leadingZeros)
        }
    }
}

/**
 * Stream flags for one encoder output buffer. [droppability] (null unless an experiment verifies
 * droppable frames) sees configuration and keyframes too, so it holds the AV1 sequence header before
 * judging inter frames; only an inter frame its bitstream proves droppable is marked discardable.
 */
internal fun encodedBufferFlags(
    droppability: BitstreamDroppability?,
    codecConfig: Boolean,
    keyFrame: Boolean,
    bytes: ByteArray,
): Int = when {
    codecConfig -> {
        droppability?.observeCodecConfig(bytes)
        StreamProtocol.FLAG_CODEC_CONFIG
    }
    keyFrame -> {
        droppability?.isDroppable(bytes, keyFrame = true)
        StreamProtocol.FLAG_KEY_FRAME
    }
    droppability?.isDroppable(bytes, keyFrame = false) == true -> StreamProtocol.FLAG_DISCARDABLE
    else -> 0
}
