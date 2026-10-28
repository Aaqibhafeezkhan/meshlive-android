package com.example.bluetoothtest

import android.media.MediaCodecInfo

/**
 * Design HC encoder eligibility, from declared capabilities rather than component names (DECISIONS "Design HC",
 * "Encoder eligibility"). Pure except [EncoderEnvelope.measure], so the rules are JVM-tested.
 *
 * - Hardware only for Automatic (`isHardwareAccelerated`); software HEVC/H.264 appear only in the manual picker.
 * - Profile: HEVC Main; H.264 High, Main or Constrained Baseline (then Baseline), whichever the encoder lists first in
 *   that order. A component without one is not offered.
 * - Bitrate modes: CQ is queried explicitly, so a CQ-only encoder is not mistaken for "no modes" and is not offered.
 * - Per session: the size range covers the session's maximum size and the performance-point, achievable or (only when
 *   neither is published) declared frame rate reaches the session's FPS.
 */
internal object HcEncoderEligibility {
    const val BLOCK_SOFTWARE = "software"
    const val BLOCK_CQ_ONLY = "cq_only"
    const val BLOCK_PROFILE = "profile"
    const val BLOCK_SIZE = "size"
    const val BLOCK_FPS = "fps"

    /** Blocks that make a component unusable for any session: it is not offered at all. */
    val STATIC_BLOCKS = setOf(BLOCK_CQ_ONLY, BLOCK_PROFILE)

    // MediaCodecInfo.CodecProfileLevel values (compile-time constants).
    private const val HEVC_MAIN = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
    private const val AVC_HIGH = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
    private const val AVC_MAIN = MediaCodecInfo.CodecProfileLevel.AVCProfileMain
    private const val AVC_CONSTRAINED_BASELINE = MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline
    private const val AVC_BASELINE = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline

    /** The (profile, highest listed level) to request, or null when the encoder lists no acceptable profile. */
    fun profileFor(protocolId: Int, profileLevels: List<Pair<Int, Int>>): Pair<Int, Int>? {
        val order = when (protocolId) {
            StreamProtocol.CODEC_HEVC -> listOf(HEVC_MAIN)
            StreamProtocol.CODEC_H264 -> listOf(AVC_HIGH, AVC_MAIN, AVC_CONSTRAINED_BASELINE, AVC_BASELINE)
            else -> return null
        }
        for (profile in order) {
            val levels = profileLevels.filter { it.first == profile }.map { it.second }
            if (levels.isNotEmpty()) return profile to levels.max()
        }
        return null
    }

    /** One standard level: its MediaCodec constant and the limits a session must fit (ITU-T H.264 Table A-1, H.265 Table A.8). */
    private class LevelLimit(val constant: Int, val maxLumaSamples: Long, val maxLumaRate: Long, val maxKbps: Long, val idc: Int)

    /** HEVC Main tier: MaxLumaPs, MaxLumaSr, MaxBR (Main profile, kbit/s). */
    private val HEVC_LEVELS = listOf(
        LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel1, 36_864, 552_960, 128, 30),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel2, 122_880, 3_686_400, 1_500, 60),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel21, 245_760, 7_372_800, 3_000, 63),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel3, 552_960, 16_588_800, 6_000, 90),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31, 983_040, 33_177_600, 10_000, 93),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4, 2_228_224, 66_846_720, 12_000, 120),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41, 2_228_224, 133_693_440, 20_000, 123),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5, 8_912_896, 267_386_880, 25_000, 150),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51, 8_912_896, 534_773_760, 40_000, 153),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52, 8_912_896, 1_069_547_520, 60_000, 156),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6, 35_651_584, 1_069_547_520, 60_000, 180),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61, 35_651_584, 2_139_095_040, 120_000, 183),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62, 35_651_584, 4_278_190_080, 240_000, 186),
    )

    /** H.264 in macroblocks: MaxFS, MaxMBPS, MaxBR (Baseline/Main, kbit/s; High allows 1.25x, ignored to stay conservative). */
    private val AVC_LEVELS = listOf(
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel1, 99, 1_485, 64, 10),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel11, 396, 3_000, 192, 11),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel12, 396, 6_000, 384, 12),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel13, 396, 11_880, 768, 13),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel2, 396, 11_880, 2_000, 20),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel21, 792, 19_800, 4_000, 21),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel22, 1_620, 20_250, 4_000, 22),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel3, 1_620, 40_500, 10_000, 30),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel31, 3_600, 108_000, 14_000, 31),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel32, 5_120, 216_000, 20_000, 32),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel4, 8_192, 245_760, 20_000, 40),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel41, 8_192, 245_760, 50_000, 41),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel42, 8_704, 522_240, 50_000, 42),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel5, 22_080, 589_824, 135_000, 50),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel51, 36_864, 983_040, 240_000, 51),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel52, 36_864, 2_073_600, 240_000, 52),
    )

    /** Rank of a MediaCodec level constant (HEVC High tier ranks with its Main-tier level), or null when unknown. */
    private fun rank(table: List<LevelLimit>, protocolId: Int, constant: Int): Int? {
        val main = if (protocolId == StreamProtocol.CODEC_HEVC && Integer.bitCount(constant) == 1 &&
            Integer.numberOfTrailingZeros(constant) % 2 == 1
        ) {
            constant shr 1 // HEVCHighTierLevelN == HEVCMainTierLevelN shl 1
        } else {
            constant
        }
        return table.indexOfFirst { it.constant == main }.takeIf { it >= 0 }
    }

    /**
     * The `KEY_LEVEL` to request for a [width]x[height] session at [fps] and [bitrateBps]: the lowest standard level
     * (HEVC Main tier) whose limits cover it, never above [listedMax], the encoder's highest listed level. Requesting
     * [listedMax] itself (the first Design HC rule) made the Exynos and Qualcomm HEVC encoders signal level 6.0
     * (general_level_idc 180) for 640x480 streams, beyond the 5.x decoders (Android's c2.android.hevc.decoder: 5.2).
     * An unknown [listedMax] is returned unchanged.
     */
    fun levelFor(protocolId: Int, listedMax: Int, width: Int, height: Int, fps: Int, bitrateBps: Int): Int {
        val table = when (protocolId) {
            StreamProtocol.CODEC_HEVC -> HEVC_LEVELS
            StreamProtocol.CODEC_H264 -> AVC_LEVELS
            else -> return listedMax
        }
        val cap = rank(table, protocolId, listedMax) ?: return listedMax
        val samples = if (protocolId == StreamProtocol.CODEC_HEVC) {
            width.toLong() * height
        } else {
            ((width + 15) / 16).toLong() * ((height + 15) / 16)
        }
        val needed = table.indexOfFirst {
            samples <= it.maxLumaSamples && samples * fps <= it.maxLumaRate && bitrateBps / 1000L <= it.maxKbps
        }.takeIf { it >= 0 } ?: table.lastIndex
        return if (needed >= cap) listedMax else table[needed].constant
    }

    /** The bitstream level_idc (HEVC general_level_idc, H.264 level_idc) of a MediaCodec level constant, or null. */
    fun levelIdc(protocolId: Int, constant: Int): Int? {
        val table = when (protocolId) {
            StreamProtocol.CODEC_HEVC -> HEVC_LEVELS
            StreamProtocol.CODEC_H264 -> AVC_LEVELS
            else -> return null
        }
        return rank(table, protocolId, constant)?.let { table[it].idc }
    }

    /** The session-independent block of one HC component, or null when Automatic may use it. */
    fun staticBlock(hardware: Boolean, modes: HcBitrateModes, hasProfile: Boolean): String? = when {
        modes.cqOnly -> BLOCK_CQ_ONLY
        !hasProfile -> BLOCK_PROFILE
        !hardware -> BLOCK_SOFTWARE
        else -> null
    }

    /** Why Automatic cannot use [codec] for a [width]x[height] session at [fps]; null when it can (VP9/AV1: always null). */
    fun sessionBlock(codec: VideoCodec, width: Int, height: Int, fps: Int): String? {
        if (!codec.isHcCodec) return null
        codec.automaticBlock?.let { return it }
        val envelope = codec.envelope ?: return BLOCK_SIZE
        if (!envelope.sizeSupported(width, height)) return BLOCK_SIZE
        val achievable = envelope.fpsAt(width, height) ?: return BLOCK_FPS
        return if (achievable + 0.5 < fps) BLOCK_FPS else null
    }

    /**
     * HC components of one codec reordered among themselves: eligible hardware by the frame rate it reaches at the
     * reference size (the ASUS Codec2 H.264 encoder measured 26-56 FPS at 720p against about 200 for its OMX one), then
     * other hardware, then software. Every other entry keeps its position.
     */
    fun componentOrder(all: List<VideoCodec>): List<VideoCodec> {
        val result = all.toMutableList()
        all.filter { it.isHcCodec }.groupBy(VideoCodec::protocolId).values.forEach { group ->
            val indices = all.indices.filter { all[it] in group }
            val sorted = group.sortedWith(
                compareBy<VideoCodec> { if (it.hardwareAccelerated && it.automaticBlock == null) 0 else if (it.hardwareAccelerated) 1 else 2 }
                    .thenByDescending { it.envelope?.fpsAt(REFERENCE_WIDTH, REFERENCE_HEIGHT) ?: 0.0 },
            )
            indices.forEachIndexed { i, index -> result[index] = sorted[i] }
        }
        return result
    }

    /** The product entries of one codec's components ([componentOrder] order): VP9/AV1 the first; HC the best hardware plus the best software. */
    fun bestPerCodec(components: List<VideoCodec>): List<VideoCodec> {
        val first = components.firstOrNull() ?: return emptyList()
        if (!first.isHcCodec) return listOf(first)
        val hardware = components.firstOrNull { it.hardwareAccelerated && it.automaticBlock == null }
        val software = components.firstOrNull { !it.hardwareAccelerated }
        return listOfNotNull(hardware, software)
    }

    const val REFERENCE_WIDTH = 854
    const val REFERENCE_HEIGHT = 480
}

/** Which bitrate modes an HC encoder declares, CQ included (Design HC rate-mode table). */
internal data class HcBitrateModes(val cbr: Boolean, val cbrFd: Boolean, val vbr: Boolean, val cq: Boolean) {
    val cqOnly: Boolean get() = cq && !cbr && !cbrFd && !vbr

    /**
     * Automatic's request: CBR; else CBR_FD; else VBR (with `EncoderBitrateAdjuster`); with no declared mode, null (no
     * `KEY_BITRATE_MODE`, the encoder's default, as today's Exynos path).
     */
    fun automaticMode(): Int? = when {
        cbr -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        cbrFd -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD
        vbr -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        else -> null
    }

    /** The modes the rate-control picker may offer (CQ never). */
    fun declared(): Set<Int> = buildSet {
        if (cbr) add(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        if (vbr) add(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        if (cbrFd) add(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD)
    }

    companion object {
        /** Log label of a resolved mode (`encoder_default` for null). */
        fun label(mode: Int?): String = when (mode) {
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR -> "CBR"
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD -> "CBR_FD"
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR -> "VBR"
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ -> "CQ"
            null -> "encoder_default"
            else -> "mode$mode"
        }
    }
}

/** One sampled frame rate at one size, and where it came from. */
data class FpsPoint(val width: Int, val height: Int, val fps: Double, val source: String)

/** The declared size range and sampled frame rates of an HC encoder (pure; [measure] reads the platform). */
data class EncoderEnvelope(
    val minWidth: Int,
    val maxWidth: Int,
    val minHeight: Int,
    val maxHeight: Int,
    val widthAlignment: Int,
    val heightAlignment: Int,
    val fpsPoints: List<FpsPoint>,
) {
    /** The session size fits the declared ranges and alignment in either orientation. */
    fun sizeSupported(width: Int, height: Int): Boolean = fits(width, height) || fits(height, width)

    private fun fits(width: Int, height: Int): Boolean =
        width in minWidth..maxWidth && height in minHeight..maxHeight &&
            width % widthAlignment.coerceAtLeast(1) == 0 && height % heightAlignment.coerceAtLeast(1) == 0

    /** The frame rate sampled at the smallest sample size covering [width]x[height] pixels, or null when none does. */
    fun fpsAt(width: Int, height: Int): Double? {
        val pixels = width.toLong() * height
        return fpsPoints.filter { it.width.toLong() * it.height >= pixels }
            .minByOrNull { it.width.toLong() * it.height }
            ?.fps
    }

    /** `source:WxH@fps,...` for the encoder_choice log. */
    fun label(): String = fpsPoints.joinToString(",") { "${it.source}:${it.width}x${it.height}@${it.fps.toInt()}" }

    companion object {
        /** Sample sizes: the app's resolutions plus 640x480, 720p and 1080p. */
        val SAMPLE_SIZES = listOf(426 to 240, 640 to 360, 640 to 480, 854 to 480, 1280 to 720, 1920 to 1080)
        private val SAMPLE_FPS = listOf(15, 24, 30, 60)

        /**
         * Reads [video]: per sample size, the highest of 15/24/30/60 FPS a published performance point covers
         * (`getSupportedPerformancePoints`), else the upper achievable rate (`getAchievableFrameRatesFor`), else the
         * declared upper rate, labelled so the log says which.
         */
        fun measure(video: MediaCodecInfo.VideoCapabilities): EncoderEnvelope {
            val points = runCatching { video.supportedPerformancePoints }.getOrNull().orEmpty()
            val samples = SAMPLE_SIZES.mapNotNull { (width, height) ->
                if (!runCatching { video.isSizeSupported(width, height) }.getOrDefault(false)) return@mapNotNull null
                val covered = SAMPLE_FPS.filter { fps ->
                    points.any { it.covers(MediaCodecInfo.VideoCapabilities.PerformancePoint(width, height, fps)) }
                }.maxOrNull()
                if (covered != null) return@mapNotNull FpsPoint(width, height, covered.toDouble(), "pp")
                runCatching { video.getAchievableFrameRatesFor(width, height)?.upper }.getOrNull()?.let {
                    return@mapNotNull FpsPoint(width, height, it, "achievable")
                }
                runCatching { video.getSupportedFrameRatesFor(width, height).upper }.getOrNull()?.let {
                    FpsPoint(width, height, it, "declared")
                }
            }
            return EncoderEnvelope(
                minWidth = video.supportedWidths.lower,
                maxWidth = video.supportedWidths.upper,
                minHeight = video.supportedHeights.lower,
                maxHeight = video.supportedHeights.upper,
                widthAlignment = video.widthAlignment,
                heightAlignment = video.heightAlignment,
                fpsPoints = samples,
            )
        }
    }
}

/** What an HC decode bit promises (Design HC "Decode bits mean the whole wire envelope"). */
internal object HcDecoderEnvelope {
    /** The largest session the app offers: 854x480 at 30 FPS. */
    val MAX_SESSION = Triple(854, 480, 30)

    fun profilesCovered(protocolId: Int, profiles: Set<Int>): Boolean = when (protocolId) {
        StreamProtocol.CODEC_HEVC -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain in profiles
        StreamProtocol.CODEC_H264 ->
            (MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline in profiles ||
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline in profiles) &&
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain in profiles &&
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh in profiles
        else -> false
    }
}
