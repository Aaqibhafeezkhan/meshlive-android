package com.example.bluetoothtest

/**
 * What the viewer's decoder was configured with, and when a new keyframe needs a different decoder
 * (Android MediaCodec has no portable mid-stream format change: only some adaptive-playback decoders
 * accept one, and AV1 is not in that list, so a decoder is re-created like libwebrtc's
 * AndroidVideoDecoder.reinitDecode does). Pure state, no Android types, so the decision is unit-tested.
 *
 * A keyframe needs a rebuild when its coded size differs from the configured one, or when the codec
 * configuration (csd) that arrived with it differs from what the decoder holds. Configuration that
 * equals the current one passes through unchanged, so a stream without any size change behaves exactly
 * as before. Different configuration is held back (never queued as a separate codec-config buffer, which
 * MediaCodec accepts only together with a keyframe) and becomes the decoder's csd at the rebuild.
 *
 * [currentCodecSpecificData] is what recovery re-queues: always the configuration of the newest
 * encoder, never the stream-start one.
 */
internal class DecoderFormatTracker(
    private val codecId: Int,
    initialCodecSpecificData: List<ByteArray>,
    initialWidth: Int,
    initialHeight: Int,
) {
    /** The decoder to create for a keyframe: its size (unchanged when the bitstream did not say), csd and why. */
    class Rebuild(
        val width: Int,
        val height: Int,
        val codecSpecificData: List<ByteArray>,
        val sizeChanged: Boolean,
        val configChanged: Boolean,
        /** Set by [recoveryRebuild]: the decoder is re-created for the unchanged picture (not a size or csd change). */
        private val recoveryReason: String? = null,
    ) {
        val reason: String
            get() = recoveryReason
                ?: listOfNotNull("size".takeIf { sizeChanged }, "csd".takeIf { configChanged }).joinToString("+")
    }

    /**
     * What to do with one packet before it reaches the decode queue. [queue] false: hold it (new codec
     * configuration, applied with its keyframe). [resetQueue]: drop everything queued before it (a
     * sender-marked discontinuity, or a keyframe that needs another decoder: the older frames belong to
     * the old picture). [rebuild]: the decoder this keyframe needs.
     */
    class PacketPlan(val queue: Boolean, val resetQueue: Boolean, val rebuild: Rebuild?)

    /** [plan] for a video packet; audio never comes here. */
    fun plan(packet: StreamProtocol.Packet): PacketPlan {
        val isConfig = packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0
        val isKeyFrame = packet.flags and StreamProtocol.FLAG_KEY_FRAME != 0
        if (isConfig) return PacketPlan(queue = onCodecConfig(packet.payload), resetQueue = false, rebuild = null)
        val discontinuity = isKeyFrame && packet.flags and StreamProtocol.FLAG_DISCONTINUITY != 0
        val rebuild = if (isKeyFrame) onKeyFrame(packet.payload) else null
        return PacketPlan(queue = true, resetQueue = discontinuity || rebuild != null, rebuild = rebuild)
    }

    private var width = initialWidth
    private var height = initialHeight
    private var current: List<ByteArray> = initialCodecSpecificData.map(ByteArray::copyOf)
    /** Configuration packets since the previous keyframe: the configuration of the encoder this keyframe starts. */
    private val sinceKeyFrame = mutableListOf<ByteArray>()

    @Synchronized
    fun codedSize(): Pair<Int, Int> = width to height

    @Synchronized
    fun currentCodecSpecificData(): List<ByteArray> = current

    /**
     * A codec-config packet arrived. True: it equals what the decoder already holds, queue it as before.
     * False: it is new configuration, held for the next keyframe.
     */
    @Synchronized
    fun onCodecConfig(payload: ByteArray): Boolean {
        if (sinceKeyFrame.none { it.contentEquals(payload) }) sinceKeyFrame += payload.copyOf()
        return current.any { it.contentEquals(payload) }
    }

    /** The decoder was just created from the first keyframe, whose size was read before it was configured. */
    @Synchronized
    fun adopt(width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    private fun sameBuffers(a: List<ByteArray>, b: List<ByteArray>): Boolean =
        a.size == b.size && a.indices.all { a[it].contentEquals(b[it]) }

    /** The size [accessUnit] (a keyframe) carries, using the newest configuration when it has no in-band header. */
    @Synchronized
    fun sizeOf(accessUnit: ByteArray): Pair<Int, Int>? =
        CodedVideoSize.ofKeyFrame(codecId, accessUnit, sinceKeyFrame.ifEmpty { current })

    /**
     * The decoder to create when recovery must release and re-create the running decoder instead of flushing it
     * (MediaTek Codec2 flush bug, a stalled decoder): the same picture size and the newest encoder's
     * configuration, so nothing is reported as a size change.
     */
    @Synchronized
    fun recoveryRebuild(reason: String): Rebuild =
        Rebuild(width, height, current.map(ByteArray::copyOf), sizeChanged = false, configChanged = false, recoveryReason = reason)

    /** Design HC (codecs 4 and 5): the configuration is the keyframe's own in-band parameter sets, never config packets. */
    private val inBandSets = VideoCodecMap.isInBandParameterSetCodec(codecId)

    /** A keyframe arrived: null when the configured decoder can take it, else the decoder it needs. */
    @Synchronized
    fun onKeyFrame(accessUnit: ByteArray): Rebuild? {
        val held = if (inBandSets) {
            CodedVideoSize.inBandCodecSpecificData(codecId, accessUnit)
        } else {
            sinceKeyFrame.toList()
        }
        sinceKeyFrame.clear()
        val configChanged = if (inBandSets) {
            held.isNotEmpty() && !sameBuffers(held, current)
        } else {
            held.any { packet -> current.none { it.contentEquals(packet) } }
        }
        val parsed = CodedVideoSize.ofKeyFrame(codecId, accessUnit, held.ifEmpty { current })
        val sizeChanged = parsed != null && parsed != (width to height)
        if (!configChanged && !sizeChanged) return null
        // No new configuration but a new size: the keyframe carries the whole format in-band for VP9 and
        // AV1, and the previous csd would describe the old picture, so the new decoder gets none.
        val nextConfig = if (held.isEmpty()) emptyList() else decoderCodecSpecificData(codecId, held)
        val (nextWidth, nextHeight) = parsed ?: (width to height)
        width = nextWidth
        height = nextHeight
        current = nextConfig.map(ByteArray::copyOf)
        return Rebuild(nextWidth, nextHeight, current, sizeChanged, configChanged)
    }
}

/**
 * A keyframe the sender marked FLAG_DISCONTINUITY ([RestartDiscontinuityMarker]) is a planned size change
 * (an Auto ladder rung): the viewer keeps showing the last frame until the first frame of the new size
 * renders instead of covering the picture with "Recovering video" (the second Camera2 A/B showed that
 * cover for about 1.7 s per change). A recovery already in progress keeps its cover.
 */
internal fun isPlannedSizeChange(discontinuityKeyFrame: Boolean, alreadyRecovering: Boolean): Boolean =
    discontinuityKeyFrame && !alreadyRecovering

/**
 * Marks the first video keyframe of a restarted encoder FLAG_DISCONTINUITY when the restart changes the
 * coded size (Auto ladder rung, call resolution ladder): every frame before it belongs to the old
 * picture size and must not be decoded after it. The flag already exists on the wire (PROTOCOL
 * "Packet flags"), receivers that predate size-aware decoding treat the keyframe as a recovery point,
 * and the mesh envelope never carries it (meshPacketFlags). Not marked: the first encoder of a session
 * and restarts that keep the size (camera flip, camera off/on).
 */
internal class RestartDiscontinuityMarker(private val sizeChanged: Boolean) {
    private var pending = sizeChanged

    fun flagsFor(flags: Int): Int {
        if (!pending) return flags
        if (flags and (StreamProtocol.FLAG_CODEC_CONFIG or StreamProtocol.FLAG_AUDIO) != 0) return flags
        if (flags and StreamProtocol.FLAG_KEY_FRAME == 0) return flags
        pending = false
        return flags or StreamProtocol.FLAG_DISCONTINUITY
    }

    fun wrap(send: (Int, Long, ByteArray) -> Unit): (Int, Long, ByteArray) -> Unit =
        if (!sizeChanged) send else { flags, timeUs, bytes -> send(flagsFor(flags), timeUs, bytes) }
}

/**
 * Which cached codec-config packets a late joiner must get: those of the current encoder only. An
 * encoder's configuration arrives before its first frame, so a config packet that follows media starts
 * a new batch and the previous encoder's packets are stale (a restart after a rung change would
 * otherwise leave configuration of two picture sizes in the cache, and a joiner would be handed both).
 */
internal class VideoConfigBatch {
    private var mediaSinceConfig = false

    /** A video config packet arrived: true when the cached video config packets belong to an earlier encoder. */
    fun onConfig(): Boolean {
        val stale = mediaSinceConfig
        mediaSinceConfig = false
        return stale
    }

    /**
     * A video frame was sent. [restartKeyFrame] (a FLAG_DISCONTINUITY keyframe, the first of a restarted
     * encoder) with no new configuration since the previous frame means the encoder sends none: the cached
     * packets describe the old picture size, and this returns true so the caller drops them.
     */
    fun onMedia(restartKeyFrame: Boolean = false): Boolean {
        val stale = restartKeyFrame && mediaSinceConfig
        mediaSinceConfig = true
        return stale
    }
}
