package com.example.bluetoothtest

/**
 * The coded picture size a keyframe carries, read from the compressed bitstream itself so no wire change
 * is needed (PROTOCOL "Mid-stream size changes"):
 *
 * - VP9: every keyframe's uncompressed header has frame_width/height_minus_1.
 * - AV1: the sequence header OBU (max_frame_width/height_minus_1), in the keyframe's temporal unit or,
 *   for an encoder that sends it only as configuration, in the codec-config bytes (raw OBUs or an av1C
 *   record). The encoder here never overrides the frame size, so the maximum is the coded size.
 *
 * Null means "could not tell" and callers keep their current size: nothing here guesses.
 */
internal object CodedVideoSize {
    private const val AV1C_MARKER_VERSION_1 = 0x81
    private const val AV1C_HEADER_BYTES = 4

    /** Size of the keyframe access unit [accessUnit] of [codecId], using [codecConfig] when it has no in-band header. */
    fun ofKeyFrame(codecId: Int, accessUnit: ByteArray, codecConfig: List<ByteArray> = emptyList()): Pair<Int, Int>? =
        when (codecId) {
            StreamProtocol.CODEC_VP9 -> vp9(accessUnit)
            StreamProtocol.CODEC_AV1 -> av1(accessUnit) ?: codecConfig.asReversed().firstNotNullOfOrNull(::av1Config)
            // Design HC: the in-band SPS of an IDR that passes the structural gate (cropped coded size).
            StreamProtocol.CODEC_H264, StreamProtocol.CODEC_HEVC -> AnnexBCodec.of(codecId)?.let { codec ->
                AccessUnitGate.keyFrameSets(codec, accessUnit, MAX_HEADER_SIDE, MAX_HEADER_SIDE)
            }?.let { it.codedWidth to it.codedHeight }
            else -> null
        }

    /** The largest `BTV3` header side: the envelope used when only the size of an HC keyframe is wanted. */
    private const val MAX_HEADER_SIDE = 4096

    /**
     * Design HC: the decoder csd of an HC keyframe, from its own in-band parameter sets once the whole AU passes the
     * structural gate (empty when it does not).
     */
    fun inBandCodecSpecificData(codecId: Int, accessUnit: ByteArray): List<ByteArray> =
        AnnexBCodec.of(codecId)?.let { codec -> AccessUnitGate.keyFrameSets(codec, accessUnit, MAX_HEADER_SIDE, MAX_HEADER_SIDE) }
            ?.csd().orEmpty()

    /** Size named by AV1 codec-config bytes (null for VP9, whose configuration carries none). */
    fun ofCodecConfig(codecId: Int, codecConfig: List<ByteArray>): Pair<Int, Int>? =
        if (codecId == StreamProtocol.CODEC_AV1) codecConfig.asReversed().firstNotNullOfOrNull(::av1Config) else null

    private fun vp9(accessUnit: ByteArray): Pair<Int, Int>? {
        if (accessUnit.isEmpty()) return null
        for ((offset, size) in Vp9FrameHeader.frames(accessUnit)) {
            val frame = Vp9FrameHeader.parse(accessUnit, offset, size) ?: continue
            if (frame.keyFrame && frame.width > 0 && frame.height > 0) return frame.width to frame.height
        }
        return null
    }

    private fun av1(obus: ByteArray): Pair<Int, Int>? {
        if (obus.isEmpty()) return null
        var found: Pair<Int, Int>? = null
        runCatching {
            BitstreamDroppability.forEachObu(obus) { type, _, _, payload ->
                if (found == null && type == BitstreamDroppability.OBU_SEQUENCE_HEADER) {
                    val sequence = BitstreamDroppability.parseAv1SequenceHeader(payload)
                    if (sequence.maxFrameWidth > 0 && sequence.maxFrameHeight > 0) {
                        found = sequence.maxFrameWidth to sequence.maxFrameHeight
                    }
                }
            }
        }
        return found
    }

    private fun av1Config(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.isEmpty()) return null
        // AV1 OBU headers have the forbidden bit clear, so 0x81 can only be the av1C marker/version.
        val obus = if (bytes[0].toInt() and 0xff == AV1C_MARKER_VERSION_1) {
            if (bytes.size <= AV1C_HEADER_BYTES) return null
            bytes.copyOfRange(AV1C_HEADER_BYTES, bytes.size)
        } else {
            bytes
        }
        return av1(obus)
    }
}
