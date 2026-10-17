package com.example.bluetoothtest

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

enum class VideoEncoderBackend {
    MEDIA_CODEC,
    NATIVE_LIBAOM,
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
) {
    val selectionPriority: Int
        get() = when {
            hardwareAccelerated && protocolId == StreamProtocol.CODEC_AV1 -> 0
            hardwareAccelerated -> 1
            backend == VideoEncoderBackend.NATIVE_LIBAOM -> 2
            protocolId == StreamProtocol.CODEC_AV1 -> 3
            else -> 4
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
        fun allMediaCodecEncoders(): List<VideoCodec> = mediaCodecEncoders(desiredTypes)

        /**
         * HEVC and AVC MediaCodec encoders for the DEBUG radio-free encoder bench only (Q39 ladder preparation).
         * They carry [BENCH_ONLY_PROTOCOL_ID] and appear in no product list, negotiation or stream header.
         */
        fun benchOnlyMediaCodecEncoders(): List<VideoCodec> = mediaCodecEncoders(benchOnlyTypes)

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
            )
        }

        fun availableEncoders(): List<VideoCodec> {
            val compatible = allMediaCodecEncoders()
            val bestMediaCodecEncoders = compatible
                .groupBy(VideoCodec::protocolId)
                .values
                .map(List<VideoCodec>::first)
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
            return (bestMediaCodecEncoders + listOfNotNull(nativeAom))
                .sortedBy(VideoCodec::selectionPriority)
        }

        fun availableDecoders(): List<VideoDecoderSupport> {
            val codecInfos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            return desiredTypes.mapNotNull { (mime, protocolId) ->
                val candidates = codecInfos.filter { info ->
                    !info.isEncoder && !info.isAlias && !info.name.endsWith(".secure") &&
                        info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
                }
                val decoder = candidates.firstOrNull { it.isHardwareAccelerated }
                    ?: candidates.firstOrNull()
                    ?: return@mapNotNull null
                VideoDecoderSupport(
                    codecName = if (mime == MediaFormat.MIMETYPE_VIDEO_AV1) "AV1" else "VP9",
                    protocolId = protocolId,
                    decoderName = decoder.name,
                    hardwareAccelerated = decoder.isHardwareAccelerated,
                )
            }.sortedBy(VideoDecoderSupport::protocolId)
        }
    }
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
): VideoCodec {
    val needsVp9Compatibility = selected.protocolId == StreamProtocol.CODEC_AV1 &&
        DeviceFamilyPolicy.resolve(fingerprint).callVp9Compatibility(transport)
    if (!needsVp9Compatibility) return selected
    return available
        .filter { it.protocolId == StreamProtocol.CODEC_VP9 }
        .minByOrNull(VideoCodec::selectionPriority)
        ?: selected
}

internal fun privateCallCodecForPeer(
    selected: VideoCodec,
    available: List<VideoCodec>,
    peerDecodeCodecMask: Int,
): VideoCodec? {
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
