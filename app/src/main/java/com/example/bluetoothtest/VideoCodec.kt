package com.example.bluetoothtest

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

enum class VideoEncoderBackend {
    MEDIA_CODEC,
    NATIVE_LIBAOM,
    /** DECISIONS "Design VX": the bundled libvpx VP9 encoder (standard VP9, codec 3 on the wire). */
    NATIVE_LIBVPX,
    ;

    /** A bundled software encoder fed I420 by the app (libaom or libvpx). */
    val isNative: Boolean get() = this != MEDIA_CODEC
}

enum class EncoderRateControl(val label: String, val description: String, val codecMode: Int?) {
    AUTOMATIC(
        "Automatic (recommended)",
        "Uses this encoder's preferred supported mode; usually steady bitrate for Bluetooth.",
        null,
    ),
    CBR(
        "Steady bitrate (CBR)",
        "Aims for predictable radio load; quality can vary when motion or detail changes.",
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
    ),
    VBR(
        "Scene-adaptive bitrate (VBR)",
        "May improve difficult scenes on a direct link; bursts can overload Bluetooth relay paths and cause stalls.",
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
    ),
    CBR_FD(
        "Steady bitrate + frame dropping (CBR-FD)",
        "Keeps the rate and queue bounded by allowing encoder-side frame drops; motion may look less smooth.",
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD,
    ),
}

data class VideoCodec(
    val displayName: String,
    val mimeType: String,
    val protocolId: Int,
    val encoderName: String,
    val hardwareAccelerated: Boolean,
    val usesSurfaceInput: Boolean,
    val bitrateMode: Int?,
    val supportedBitrateModes: Set<Int>,
    val complexity: Int?,
    val backend: VideoEncoderBackend = VideoEncoderBackend.MEDIA_CODEC,
    /** Design HC, codecs 4 and 5 only: `KEY_PROFILE` / `KEY_LEVEL` to request (null: not set, as for VP9/AV1). */
    val profile: Int? = null,
    val level: Int? = null,
    /**
     * Design HC, codecs 4 and 5 only: why Automatic never picks this component whatever the session (`software`);
     * null when it is eligible. Components that are CQ-only or lack the profile are not offered at all
     * ([hcStaticIneligibility]).
     */
    val automaticBlock: String? = null,
    /** Design HC, codecs 4 and 5 only: the declared size and frame-rate envelope for the per-session checks. */
    val envelope: EncoderEnvelope? = null,
) {
    /**
     * Static order (lower first). Design VX: the bundled libvpx VP9 encoder ranks right after the hardware encoders
     * while `prefer_libvpx` is on ([VideoCodecSwitches.preferLibvpx], shipped on since 0.4.2), and after everything else while it
     * is off, so the existing order is unchanged and libvpx is only a manual choice.
     */
    val selectionPriority: Int
        get() = when {
            hardwareAccelerated && protocolId == StreamProtocol.CODEC_AV1 -> 0
            hardwareAccelerated -> 1
            backend == VideoEncoderBackend.NATIVE_LIBVPX -> if (VideoCodecSwitches.preferLibvpx) 2 else 6
            backend == VideoEncoderBackend.NATIVE_LIBAOM -> 3
            protocolId == StreamProtocol.CODEC_AV1 -> 4
            else -> 5
        }

    fun availableRateControls(): List<EncoderRateControl> = EncoderRateControl.entries.filter { control ->
        control == EncoderRateControl.AUTOMATIC || control.codecMode in supportedBitrateModes
    }

    fun resolveBitrateMode(control: EncoderRateControl): Int? =
        control.codecMode?.takeIf { it in supportedBitrateModes } ?: bitrateMode

    fun realtimeComplexity(settings: VideoSettings): Int? = complexity?.let { preferred ->
        val pixels = settings.resolution.width.toLong() * settings.resolution.height
        when {
            settings.fps >= 30 && pixels <= 720L * 480L -> minOf(preferred, 1)
            settings.fps >= 30 -> 0
            settings.fps >= 24 -> minOf(preferred, 2)
            else -> preferred
        }
    }

    fun nativeCpuUsed(settings: VideoSettings): Int? {
        if (backend == VideoEncoderBackend.NATIVE_LIBVPX) {
            return nativeVpxCpuUsed(settings.resolution.width, settings.resolution.height, settings.fps)
        }
        if (backend != VideoEncoderBackend.NATIVE_LIBAOM) return null
        val pixels = settings.resolution.width.toLong() * settings.resolution.height
        return when {
            pixels <= 640L * 360L && settings.fps <= 24 -> 7
            pixels <= 640L * 360L -> 8
            pixels <= 720L * 480L && settings.fps <= 20 -> 7
            pixels <= 720L * 480L && settings.fps <= 24 -> 8
            pixels <= 720L * 480L -> 9
            settings.fps <= 15 -> 9
            else -> 10
        }
    }

    companion object {
        private val desiredTypes = listOf(
            MediaFormat.MIMETYPE_VIDEO_AV1 to StreamProtocol.CODEC_AV1,
            MediaFormat.MIMETYPE_VIDEO_VP9 to StreamProtocol.CODEC_VP9,
        )

        /** Design HC product types, each only while its master is on (both off: exactly [desiredTypes]). */
        private fun productTypes(): List<Pair<String, Int>> = desiredTypes + listOfNotNull(
            (MediaFormat.MIMETYPE_VIDEO_HEVC to StreamProtocol.CODEC_HEVC).takeIf { VideoCodecSwitches.hevc },
            (MediaFormat.MIMETYPE_VIDEO_AVC to StreamProtocol.CODEC_H264).takeIf { VideoCodecSwitches.h264 },
        )

        /**
         * Design HC: the reason the hardware components of codec 4 or 5 were not offered at all (`cq_only`,
         * `profile`), from the last enumeration; read by the `encoder_choice` reason when no eligible one exists.
         */
        @Volatile var hcStaticIneligibility: Map<Int, String> = emptyMap()
            private set

        /** Protocol id of the bench-only HEVC/AVC entries: not a stream codec, never negotiated or sent. */
        const val BENCH_ONLY_PROTOCOL_ID = -1

        private val benchOnlyTypes = listOf(
            MediaFormat.MIMETYPE_VIDEO_HEVC to BENCH_ONLY_PROTOCOL_ID,
            MediaFormat.MIMETYPE_VIDEO_AVC to BENCH_ONLY_PROTOCOL_ID,
        )

        /**
         * Every MediaCodec AV1 and VP9 encoder this phone offers, in the app's own order (hardware first).
         * The app uses the best one per codec ([availableEncoders]); the DEBUG encoder bench may name any.
         */
        fun allMediaCodecEncoders(): List<VideoCodec> = mediaCodecEncoders(productTypes())

        /**
         * HEVC and AVC MediaCodec encoders for the DEBUG radio-free encoder bench only (Q39 ladder preparation).
         * They carry [BENCH_ONLY_PROTOCOL_ID] and appear in no product list, negotiation or stream header.
         */
        fun benchOnlyMediaCodecEncoders(): List<VideoCodec> {
            val product = productTypes().map { it.first }
            return mediaCodecEncoders(benchOnlyTypes.filter { it.first !in product })
        }

        private fun mediaCodecEncoders(types: List<Pair<String, Int>>): List<VideoCodec> {
            val codecInfos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            return types.flatMap { (mime, protocolId) ->
                codecInfos.mapNotNull { info ->
                    if (!info.isEncoder || info.isAlias ||
                        info.supportedTypes.none { it.equals(mime, ignoreCase = true) }
                    ) return@mapNotNull null

                    val capabilities = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
                        ?: return@mapNotNull null
                    val supportsSurface = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in
                        capabilities.colorFormats
                    val supportsYuv = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in
                        capabilities.colorFormats
                    if (!supportsSurface && !supportsYuv) return@mapNotNull null

                    val encoderCapabilities = capabilities.encoderCapabilities ?: return@mapNotNull null
                    val supportedBitrateModes = setOf(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD,
                    ).filterTo(mutableSetOf()) { mode ->
                        encoderCapabilities.isBitrateModeSupported(mode)
                    }
                    val complexity = 4.takeIf {
                        mime == MediaFormat.MIMETYPE_VIDEO_AV1 &&
                            encoderCapabilities.complexityRange.contains(it)
                    }
                    val bitrateMode = when {
                        encoderCapabilities.isBitrateModeSupported(
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                        ) -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                        encoderCapabilities.isBitrateModeSupported(
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                        ) -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                        else -> null
                    }
                    val codecLabel = when (mime) {
                        MediaFormat.MIMETYPE_VIDEO_AV1 -> "AV1"
                        MediaFormat.MIMETYPE_VIDEO_HEVC -> "HEVC"
                        MediaFormat.MIMETYPE_VIDEO_AVC -> "AVC"
                        else -> "VP9"
                    }
                    val encoderType = if (info.isHardwareAccelerated) {
                        "hardware"
                    } else {
                        "Android compatibility"
                    }
                    val usesSurface = preferSurfaceInput(
                        info.isHardwareAccelerated,
                        supportsSurface,
                        supportsYuv,
                    )
                    if (protocolId == StreamProtocol.CODEC_H264 || protocolId == StreamProtocol.CODEC_HEVC) {
                        return@mapNotNull hcEncoder(info, mime, protocolId, capabilities, usesSurface)
                    }
                    VideoCodec(
                        displayName = "$codecLabel - $encoderType",
                        mimeType = mime,
                        protocolId = protocolId,
                        encoderName = info.name,
                        hardwareAccelerated = info.isHardwareAccelerated,
                        usesSurfaceInput = usesSurface,
                        bitrateMode = bitrateMode,
                        supportedBitrateModes = supportedBitrateModes,
                        complexity = complexity,
                    )
                }
            }.sortedWith(
                compareBy<VideoCodec> { if (it.hardwareAccelerated) 0 else 1 }
                    .thenBy { if (it.protocolId == StreamProtocol.CODEC_AV1) 0 else 1 }
                    .thenBy { if (it.usesSurfaceInput) 0 else 1 }
                    .thenBy { encoderTieBreakRank(it.encoderName) },
            ).let(::hcComponentOrder)
        }

        /** [all] with the HC components that can never be used (CQ-only, missing profile) removed and recorded. */
        private fun hcComponentOrder(all: List<VideoCodec>): List<VideoCodec> {
            if (all.none { it.isHcCodec }) return all
            val blocked = all.filter { it.isHcCodec && it.automaticBlock in HcEncoderEligibility.STATIC_BLOCKS }
            hcStaticIneligibility = blocked.filter { it.hardwareAccelerated }
                .groupBy(VideoCodec::protocolId)
                .mapValues { (_, codecs) -> codecs.first().automaticBlock.orEmpty() }
            return HcEncoderEligibility.componentOrder(all - blocked.toSet())
        }

        private fun hcEncoder(
            info: MediaCodecInfo,
            mime: String,
            protocolId: Int,
            capabilities: MediaCodecInfo.CodecCapabilities,
            usesSurface: Boolean,
        ): VideoCodec {
            val encoderCapabilities = capabilities.encoderCapabilities
            val modes = HcBitrateModes(
                cbr = encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR),
                cbrFd = encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD),
                vbr = encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR),
                cq = encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ),
            )
            val profile = HcEncoderEligibility.profileFor(protocolId, capabilities.profileLevels.map { it.profile to it.level })
            val label = if (protocolId == StreamProtocol.CODEC_HEVC) "HEVC" else "H.264"
            return VideoCodec(
                displayName = "$label - ${if (info.isHardwareAccelerated) "hardware" else "software"}",
                mimeType = mime,
                protocolId = protocolId,
                encoderName = info.name,
                hardwareAccelerated = info.isHardwareAccelerated,
                usesSurfaceInput = usesSurface,
                bitrateMode = modes.automaticMode(),
                supportedBitrateModes = modes.declared(),
                complexity = null,
                profile = profile?.first,
                level = profile?.second,
                automaticBlock = HcEncoderEligibility.staticBlock(info.isHardwareAccelerated, modes, profile != null),
                envelope = runCatching { EncoderEnvelope.measure(capabilities.videoCapabilities) }.getOrNull(),
            )
        }

        fun availableEncoders(): List<VideoCodec> {
            val compatible = allMediaCodecEncoders()
            val bestMediaCodecEncoders = compatible
                .groupBy(VideoCodec::protocolId)
                .values
                .flatMap(HcEncoderEligibility::bestPerCodec)
            val nativeAom = NativeAomBridge.versionOrNull()?.let {
                VideoCodec(
                    displayName = "AV1 - optimized software",
                    mimeType = MediaFormat.MIMETYPE_VIDEO_AV1,
                    protocolId = StreamProtocol.CODEC_AV1,
                    encoderName = "libaom-native",
                    hardwareAccelerated = false,
                    usesSurfaceInput = false,
                    bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                    supportedBitrateModes = setOf(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD,
                    ),
                    complexity = null,
                    backend = VideoEncoderBackend.NATIVE_LIBAOM,
                )
            }
            return (bestMediaCodecEncoders + listOfNotNull(nativeAom, nativeLibvpx()))
                .sortedBy(VideoCodec::selectionPriority)
        }

        /** Encoder name of the bundled libvpx VP9 encoder (logs, bench arms, `test_codec`). */
        const val LIBVPX_ENCODER_NAME = "libvpx-vp9"

        /**
         * DECISIONS "Design VX": the bundled libvpx VP9 encoder, offered whenever the native library loaded. Always
         * selectable by hand; Automatic uses it only with `prefer_libvpx` on ([VideoCodecChoice]).
         */
        fun nativeLibvpx(): VideoCodec? = NativeVpxBridge.versionOrNull()?.let {
            VideoCodec(
                displayName = "VP9 - optimized software",
                mimeType = MediaFormat.MIMETYPE_VIDEO_VP9,
                protocolId = StreamProtocol.CODEC_VP9,
                encoderName = LIBVPX_ENCODER_NAME,
                hardwareAccelerated = false,
                usesSurfaceInput = false,
                bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                supportedBitrateModes = setOf(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD,
                ),
                complexity = null,
                backend = VideoEncoderBackend.NATIVE_LIBVPX,
            )
        }

        fun availableDecoders(): List<VideoDecoderSupport> {
            val codecInfos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            return productTypes().mapNotNull { (mime, protocolId) ->
                val candidates = codecInfos.filter { info ->
                    !info.isEncoder && !info.isAlias && !info.name.endsWith(".secure") &&
                        info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
                }.let { found ->
                    // Design HC: a decode bit means the whole wire envelope (every H.264 profile we send, HEVC Main)
                    // at the largest session size and FPS; a decoder that lacks part of it is not advertised.
                    if (protocolId == StreamProtocol.CODEC_H264 || protocolId == StreamProtocol.CODEC_HEVC) {
                        found.filter { info -> hcDecoderCoversEnvelope(info, mime, protocolId) }
                    } else {
                        found
                    }
                }
                val decoder = candidates.firstOrNull { it.isHardwareAccelerated }
                    ?: candidates.firstOrNull()
                    ?: return@mapNotNull null
                VideoDecoderSupport(
                    codecName = VideoCodecMap.label(protocolId),
                    protocolId = protocolId,
                    decoderName = decoder.name,
                    hardwareAccelerated = decoder.isHardwareAccelerated,
                )
            }.sortedBy(VideoDecoderSupport::protocolId)
        }

        private fun hcDecoderCoversEnvelope(info: MediaCodecInfo, mime: String, protocolId: Int): Boolean = runCatching {
            val capabilities = info.getCapabilitiesForType(mime)
            val profiles = capabilities.profileLevels.map { it.profile }.toSet()
            val (width, height, fps) = HcDecoderEnvelope.MAX_SESSION
            HcDecoderEnvelope.profilesCovered(protocolId, profiles) &&
                capabilities.videoCapabilities.areSizeAndRateSupported(width, height, fps.toDouble())
        }.getOrDefault(false)
    }

    /** Design HC: codec 4 (H.264) or 5 (HEVC). */
    val isHcCodec: Boolean
        get() = protocolId == StreamProtocol.CODEC_H264 || protocolId == StreamProtocol.CODEC_HEVC
}

/** 0 for components a `PreferredEncoderTieBreak` policy entry names (Codec2), else 1. */
internal fun encoderTieBreakRank(encoderName: String): Int =
    if (DeviceFamilyPolicy.componentEffects<PreferredEncoderTieBreak>(encoderName).isNotEmpty()) 0 else 1

internal fun preferSurfaceInput(
    hardwareAccelerated: Boolean,
    supportsSurface: Boolean,
    supportsYuv: Boolean,
): Boolean = supportsSurface && (hardwareAccelerated || !supportsYuv)

internal fun privateCallCodec(
    selected: VideoCodec,
    available: List<VideoCodec>,
    fingerprint: BluetoothStackFingerprint,
    transport: BluetoothTransportOption,
    preferLibvpx: Boolean = VideoCodecSwitches.preferLibvpx,
): VideoCodec {
    // Design HC: the family VP9 call policy covers codecs 3, 4 and 5 (4 and 5 exist only with their masters on).
    val needsVp9Compatibility = selected.protocolId in CALL_VP9_COMPATIBILITY_CODECS &&
        DeviceFamilyPolicy.resolve(fingerprint).callVp9Compatibility(transport)
    if (!needsVp9Compatibility) return selected
    return automaticCandidates(available, preferLibvpx)
        .filter { it.protocolId == StreamProtocol.CODEC_VP9 }
        .minByOrNull(VideoCodec::selectionPriority)
        ?: selected
}

/**
 * Design VX: the encoders Automatic may pick. With `prefer_libvpx` off (shipped on since 0.4.2) the bundled libvpx encoder is a manual
 * choice only, so no Automatic or fallback path picks it.
 */
internal fun automaticCandidates(available: List<VideoCodec>, preferLibvpx: Boolean): List<VideoCodec> =
    if (preferLibvpx) available else available.filter { it.backend != VideoEncoderBackend.NATIVE_LIBVPX }

/** The selected codecs a `CallVp9Compatibility` family policy swaps to VP9 (Design HC: AV1, H.264, HEVC). */
internal val CALL_VP9_COMPATIBILITY_CODECS = setOf(StreamProtocol.CODEC_AV1, StreamProtocol.CODEC_H264, StreamProtocol.CODEC_HEVC)

internal fun privateCallCodecForPeer(
    selected: VideoCodec,
    allAvailable: List<VideoCodec>,
    peerDecodeCodecMask: Int,
    preferLibvpx: Boolean = VideoCodecSwitches.preferLibvpx,
): VideoCodec? {
    // Design VX: a hand-picked libvpx stays the selection; only Automatic fallbacks skip it while prefer_libvpx is off.
    val available = automaticCandidates(allAvailable, preferLibvpx)
    // A legacy peer prefers VP9 (privateCallEndpointDecodeMask): honoured whenever this phone can
    // encode VP9, exactly as when that peer advertised VP9 only.
    if (peerDecodeCodecMask and NodeCapabilities.DECODE_PREFERS_VP9 != 0) {
        if (selected.protocolId == StreamProtocol.CODEC_VP9) return selected
        available.filter { it.protocolId == StreamProtocol.CODEC_VP9 }
            .minByOrNull(VideoCodec::selectionPriority)
            ?.let { return it }
    }
    if (peerDecodeCodecMask and selected.capabilityMask != 0) return selected
    return available
        .filter { peerDecodeCodecMask and it.capabilityMask != 0 }
        .minByOrNull(VideoCodec::selectionPriority)
}

data class VideoDecoderSupport(
    val codecName: String,
    val protocolId: Int,
    val decoderName: String,
    val hardwareAccelerated: Boolean,
)
