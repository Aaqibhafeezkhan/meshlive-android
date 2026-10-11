package com.example.bluetoothtest

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Native libopus call codec (promoted to Settings > Features on 2026-10-11 as "Call audio with loss
 * recovery"; default [ConnectivityFeatureDefaults.NATIVE_OPUS] until the phone A/B T10; DEBUG
 * `test_call_native_opus` is the benchmark switch; README register).
 *
 * c2.android.opus hard-codes general-audio mode, no in-band FEC and no packet-loss concealment
 * (AOSP frameworks/av C2SoftOpusEnc.cpp / C2SoftOpusDec.cpp). WebRTC drives libopus with
 * OPUS_APPLICATION_VOIP, in-band FEC with a loss hint, decoder PLC on a gap and FEC recovery from the
 * next packet (webrtc modules/audio_coding/codecs/opus/opus_interface.cc). The packets stay standard
 * Opus, so MediaCodec and iOS libopus peers decode them; no wire field changes. DTX stays off by
 * default because it changes the packet cadence the playout clock and scorers assume.
 */
internal data class NativeOpusSettings(
    val fec: Boolean = true,
    val lossPercent: Int = DEFAULT_LOSS_PERCENT,
    val dtx: Boolean = false,
) {
    init {
        require(lossPercent in 0..100) { "Opus loss hint must be 0-100%" }
    }

    /** The JNI argument: negative leaves FEC off. */
    val fecLossPercent: Int
        get() = if (fec) lossPercent else -1

    companion object {
        const val EXTRA_ENABLED = "test_call_native_opus"
        const val PREF_ENABLED = "feature_native_opus"

        /**
         * The shared feature rule ([FeatureCatalog.resolve]) for the main switch. FEC, the loss hint and
         * DTX are not user settings: DTX is DEBUG-only and stays off (it changes the packet cadence).
         */
        fun resolveEnabled(
            benchmarkLaunch: Boolean,
            extra: (String) -> Boolean,
            stored: (String) -> Boolean?,
        ): Boolean = FeatureCatalog.resolve(ConnectivityFeatures.nativeOpus, benchmarkLaunch, extra, stored)

        /**
         * Expected-loss hint while FEC is on. libopus spends LBRR bits only for a non-zero hint; there
         * is no loss feedback on the wire yet, so this is a fixed DEBUG-tunable value.
         */
        const val DEFAULT_LOSS_PERCENT = 10
    }
}

internal class NativeOpusEncoder(
    sampleRate: Int,
    private val channels: Int,
    bitRate: Int,
    complexity: Int,
    val settings: NativeOpusSettings,
) : Closeable {
    private var handle: Long = NativeOpusBridge.nativeEncoderCreate(
        sampleRate, channels, bitRate, complexity, settings.fecLossPercent, settings.dtx, true,
    )
    private val output = ByteArray(MAX_PACKET_BYTES)

    /** Encoder delay in 48 kHz samples: the OpusHead pre-skip a decoder must discard. */
    val lookaheadSamples: Int = NativeOpusBridge.nativeEncoderLookahead(handle)

    /** Encodes one frame of PCM16 little-endian; DTX may return a 1-2 byte "no transmission" packet. */
    @Synchronized
    fun encode(pcm: ByteArray, frameSamples: Int): ByteArray {
        check(handle != 0L) { "Native Opus encoder is closed" }
        val length = NativeOpusBridge.nativeEncode(handle, pcm, frameSamples, output)
        return output.copyOf(length)
    }

    @Synchronized
    override fun close() {
        if (handle == 0L) return
        NativeOpusBridge.nativeEncoderDestroy(handle)
        handle = 0L
    }

    companion object {
        const val MAX_PACKET_BYTES = 4_000
    }
}

internal class NativeOpusDecoder(
    private val sampleRate: Int,
    private val channels: Int,
) : Closeable {
    private var handle: Long = NativeOpusBridge.nativeDecoderCreate(sampleRate, channels)
    private val output = ByteArray(MAX_FRAME_SAMPLES * channels * 2)

    @Synchronized
    fun decode(packet: ByteArray): ByteArray = run(packet, MAX_FRAME_SAMPLES, fec = false)

    /** libopus packet-loss concealment for [samples] per channel (a multiple of 2.5 ms). */
    @Synchronized
    fun conceal(samples: Int): ByteArray = run(null, samples, fec = false)

    /** The frame lost just before [nextPacket], from its in-band FEC data (PLC when it has none). */
    @Synchronized
    fun recoverFromFec(nextPacket: ByteArray, samples: Int): ByteArray = run(nextPacket, samples, fec = true)

    fun packetSamples(packet: ByteArray): Int =
        NativeOpusBridge.nativePacketSamples(packet, packet.size, sampleRate)

    fun packetHasFec(packet: ByteArray): Boolean =
        NativeOpusBridge.nativePacketHasFec(packet, packet.size) == 1

    private fun run(packet: ByteArray?, samples: Int, fec: Boolean): ByteArray {
        check(handle != 0L) { "Native Opus decoder is closed" }
        val decoded = NativeOpusBridge.nativeDecode(handle, packet, packet?.size ?: 0, samples, fec, output)
        return output.copyOf(decoded * channels * 2)
    }

    @Synchronized
    override fun close() {
        if (handle == 0L) return
        NativeOpusBridge.nativeDecoderDestroy(handle)
        handle = 0L
    }

    companion object {
        /** 120 ms at 48 kHz, the largest Opus frame. */
        const val MAX_FRAME_SAMPLES = 5_760
    }
}

/**
 * The codec configuration a native sender publishes: the same "AOPUSHDR/AOPUSDLY/AOPUSPRL" block
 * Codec2 Opus encoders emit (AOSP OpusHeader.cpp WriteOpusHeaders), which [OpusCodecConfig.normalize]
 * and Android's Opus decoders already accept.
 */
internal object NativeOpusConfig {
    private const val OPUS_SAMPLE_RATE = 48_000L

    fun opusHead(channels: Int, preSkip: Int, inputSampleRate: Int): ByteArray =
        ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray(Charsets.US_ASCII))
            put(1)
            put(channels.toByte())
            putShort(preSkip.toShort())
            putInt(inputSampleRate)
            putShort(0)
            put(0)
        }.array()

    fun codec2Block(channels: Int, preSkip: Int, inputSampleRate: Int): ByteArray {
        val head = opusHead(channels, preSkip, inputSampleRate)
        val buffer = ByteBuffer.allocate(16 + head.size + 2 * (16 + 8)).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("AOPUSHDR".toByteArray(Charsets.US_ASCII)).putLong(head.size.toLong()).put(head)
        buffer.put("AOPUSDLY".toByteArray(Charsets.US_ASCII)).putLong(8)
            .putLong(preSkip * 1_000_000_000L / OPUS_SAMPLE_RATE)
        buffer.put("AOPUSPRL".toByteArray(Charsets.US_ASCII)).putLong(8)
            .putLong(OpusCodecConfig.DEFAULT_PREROLL_NS)
        return buffer.array()
    }

    /** The OpusHead pre-skip (samples at 48 kHz) from a normalized csd-0. */
    fun preSkip(opusHead: ByteArray): Int =
        if (opusHead.size >= 12) (opusHead[10].toInt() and 0xff) or ((opusHead[11].toInt() and 0xff) shl 8) else 0
}

/**
 * Which samples a native decoder synthesizes before decoding a packet that arrived after a gap.
 * Up to [maxConcealUs] of missing audio is concealed (libopus PLC fades it out naturally); the frame
 * just before the packet comes from that packet's in-band FEC when it carries some and the whole gap
 * is covered. A longer gap (mute, push-to-talk release, sender stall) keeps its remainder for the
 * playout's sender-gap alignment, exactly as without the native codec. Concealed audio is counted
 * separately and never as received media.
 */
internal data class OpusGapPlan(
    val concealFrames: Int,
    val fecForLast: Boolean,
    val firstConcealPtsUs: Long,
    val frameUs: Long,
    val uncoveredUs: Long,
) {
    val plcFrames: Int
        get() = concealFrames - if (fecForLast) 1 else 0

    companion object {
        val NONE = OpusGapPlan(0, false, 0L, 0L, 0L)
    }
}

internal object OpusGapConcealment {
    const val DEFAULT_MAX_CONCEAL_US = 100_000L

    fun plan(
        expectedPtsUs: Long?,
        packetPtsUs: Long,
        frameUs: Long,
        packetHasFec: Boolean,
        maxConcealUs: Long = DEFAULT_MAX_CONCEAL_US,
    ): OpusGapPlan {
        if (expectedPtsUs == null || frameUs <= 0L) return OpusGapPlan.NONE
        val gapUs = packetPtsUs - expectedPtsUs
        if (gapUs < frameUs / 2) return OpusGapPlan.NONE
        val lostFrames = ((gapUs + frameUs / 2) / frameUs).toInt()
        val maxFrames = (maxConcealUs / frameUs).toInt().coerceAtLeast(0)
        val conceal = minOf(lostFrames, maxFrames)
        if (conceal == 0) return OpusGapPlan(0, false, expectedPtsUs, frameUs, gapUs)
        val covered = conceal == lostFrames
        return OpusGapPlan(
            concealFrames = conceal,
            fecForLast = covered && packetHasFec,
            firstConcealPtsUs = expectedPtsUs,
            frameUs = frameUs,
            uncoveredUs = (gapUs - conceal * frameUs).coerceAtLeast(0L),
        )
    }
}
