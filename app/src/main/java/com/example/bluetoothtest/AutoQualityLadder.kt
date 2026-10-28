package com.example.bluetoothtest

import kotlin.math.floor
import kotlin.math.pow

/**
 * Measured "Auto" video quality ladder (adopted 2026-10-12; EXPERIMENTAL, off by default;
 * switch [AutoQualityFeature], DEBUG launch extra `test_auto_ladder`).
 *
 * Problem: Auto used to start from the fixed 240p / 15 FPS / 200 kbps default whatever the link
 * measured. Display-referenced VMAF puts 360p15 8-9 points above 240p15 at the same rate from about
 * 150 kbps, the call ladder never went above the start geometry (so a 240p start disabled every
 * upward step), and 200 kbps video needs a 324 kbps link. This policy maps the measured link goodput
 * to a start rung; the existing adaptation (LinkRateController, [CallResolutionLadder],
 * [FrameRateLadder]) then moves along the same rungs. Everything here is a pure function.
 *
 * Evidence (DECISIONS "Measured Auto ladder"; offline analysis of 1,077 scored phone runs and
 * 139 offline encodes): equal-quality bitrate scales with pixels^0.3-0.5 and fps^0.5-0.6 (not 0.85);
 * dropping 30 -> 15 FPS gains more than dropping 480p -> 360p; 360p beats 240p from ~150 kbps; 480p
 * overtakes 360p near 330 kbps (VP9) / 430-490 kbps (AV1). Confidence is low below 200 kbps (no video
 * below 100 kbps was ever measured) and low-medium above 700 kbps.
 *
 * Wire model (two clean HCI-snoop points, +-8%): wire = 1.076 x (video + 0.64 x fps) + audio wire,
 * with the audio wire 50 kbps for Opus 32 and 34 kbps for Opus 16. Video budgets use 85% of the
 * link goodput.
 */
internal enum class AutoEncoderClass {
    /** Encode margin of at least 1.5x at 720p15 (S24-class libaom): AV1 may use 720p15. */
    STRONG,

    /** The default: no margin evidence, so no 720p. */
    STANDARD,

    /** Slow AV1 CPUs (M31/A15s lose 3.5-5.6 VMAF at 500 kbps): the VP9 thresholds apply to AV1. */
    WEAK,
}

/** One rung: a camera geometry and frame rate, entered at [minVideoKbps] of video budget or more. */
internal data class AutoRung(val width: Int, val height: Int, val fps: Int, val minVideoKbps: Int) {
    val label: String get() = "${height}p@$fps"
    val pixels: Long get() = width.toLong() * height
    val resolution: VideoResolution get() = VideoResolution(width, height, "${height}p")
}

/**
 * The start choice for one link. [rung] is null for audio only. [videoKbps] is the video budget at
 * that rung (never above the caller's maximum), [wireKbps] the modelled link load of video, record
 * overhead and audio.
 */
internal data class AutoPlan(
    val rung: AutoRung?,
    val videoKbps: Int,
    val linkKbps: Int,
    val linkAssumed: Boolean,
    val audioWireKbps: Int,
    val wireKbps: Double,
    /** What the start rests on: assumed, probe_only, phy_bound or delivered ([AutoQualityLadder.evidencePlan]); empty for [AutoQualityLadder.plan]. */
    val evidence: String = "",
) {
    val audioOnly: Boolean get() = rung == null

    fun log(): String =
        "rung=${rung?.label ?: "audio_only"} | videoKbps=$videoKbps | linkKbps=$linkKbps | " +
            "linkAssumed=$linkAssumed | wireKbps=${"%.0f".format(wireKbps)}" +
            (if (evidence.isNotEmpty()) " | evidence=$evidence" else "")
}

/** How starved a manual combination is, from its bits per pixel per frame against the fitted floor. */
internal enum class StarvationLevel { OK, WARN, STARVED }

internal data class StarvationCheck(
    val level: StarvationLevel,
    /** Bits per pixel per frame at 640x480 equivalent (alpha 0.4); see [AutoQualityLadder.bppfEq]. */
    val bppfEq: Double,
    /** The fitted "acceptable" floor for the codec family. */
    val floorBppfEq: Double,
    /** Video kbps that would reach the floor at the same geometry and frame rate. */
    val minimumKbps: Int,
)

internal object AutoQualityLadder {
    /** Share of the link goodput planned for media (the learned-ceiling work kept GATT layered at 93%). */
    const val UTILISATION = 0.85
    const val FRAMING = 1.076
    /** 80 bytes per record (header and AEAD tag) per frame, in kbps per frame per second. */
    const val RECORD_KBPS_PER_FPS = 0.64
    const val OPUS32_WIRE_KBPS = 50
    const val OPUS16_WIRE_KBPS = 34

    /** The lowest video target Auto plans; below this the plan is audio only. */
    const val MIN_VIDEO_KBPS = 40

    /**
     * Link goodput planned when nothing is measured yet: a typical Bluetooth pair, chosen so the
     * unmeasured start is 360p15 (V = 181 kbps) rather than the old 240p/200.
     */
    const val ASSUMED_LINK_KBPS = 300

    /** A call direction plans half the measured link: both directions share it (utilisation applies on top). */
    const val CALL_DIRECTION_SHARE = 0.5

    const val STRONG_MIN_ENCODE_MARGIN = 1.5

    /**
     * Auto steps a rung down after the rate controller's (already debounced) target stays under the
     * rung's limit for this long; the WebRTC-style ladder used 3 s. Steps up keep the ladder's 1.25x
     * headroom held for 10 s and 15 s between changes, one rung at a time.
     */
    const val AUTO_DOWN_HOLD_MS = 1_500L

    /**
     * Streams change the picture size far less readily than calls: a size change restarts the sender's
     * camera encoder and makes every viewer rebuild its decoder (second Camera2 A/B: 480p to 240p to 480p
     * to 240p inside 40 s on a link probed at 1488 kbps). A step down needs the target under the rung's
     * limit for [STREAM_DOWN_HOLD_MS] and congestion evidence (queue age or lateness) that has lasted
     * [STREAM_CONGESTION_MS]; a step up needs [STREAM_UP_CLEAN_MS] without a queue over bounds, the target
     * at 1.25x the larger rung for [STREAM_UP_HOLD_MS], and [STREAM_UP_MIN_INTERVAL_MS] since the last change.
     */
    const val STREAM_DOWN_HOLD_MS = 3_000L
    const val STREAM_DOWN_MIN_INTERVAL_MS = 15_000L
    const val STREAM_CONGESTION_MS = 3_000L
    const val STREAM_UP_CLEAN_MS = 30_000L
    const val STREAM_UP_HOLD_MS = 30_000L
    const val STREAM_UP_MIN_INTERVAL_MS = 60_000L

    /** A frame-rate step on congestion needs severe congestion lasting this long (queue age alone is the rate controller's). */
    const val FPS_SEVERE_CONGESTION_MS = 4_000L

    /**
     * After a PHY change the controller restarts at this fraction of the evidence start (the link is a
     * different one: the 2M link carried 85 kbps, the 1M link 130-180). Not the full start: the new link's
     * capacity is unmeasured, and a restart that is too high costs a queue.
     */
    const val LINK_CHANGE_RESTART_FRACTION = 0.6

    fun linkChangeRestartKbps(startKbps: Int): Int =
        (startKbps * LINK_CHANGE_RESTART_FRACTION).toInt().coerceAtLeast(MIN_VIDEO_KBPS)

    /** Frame rate exponent of the equal-quality bitrate (measured 0.48 AV1, 0.59 VP9). */
    private const val FPS_EXPONENT = 0.55

    /** Pixel exponent of the equal-quality bitrate (alpha 0.2-0.4 per family; 0.4 pooled). */
    private const val PIXEL_ALPHA = 0.4
    private const val REFERENCE_PIXELS = 307_200.0

    /** Bits per pixel per frame (640x480 equivalent) for "acceptable" (VMAF about 70 on the hardest content). */
    const val ACCEPTABLE_BPPF_AV1 = 0.050
    const val ACCEPTABLE_BPPF_VP9 = 0.065

    /**
     * Design HC rows, stated assumptions until the quality-per-bit measurement (DECISIONS "Design HC", design decision
     * 2026-10-27): HEVC uses the VP9 rows; H.264 the VP9 rows times [H264_BITS_FACTOR] bits per pixel.
     */
    const val H264_BITS_FACTOR = 1.3
    const val ACCEPTABLE_BPPF_H264 = ACCEPTABLE_BPPF_VP9 * H264_BITS_FACTOR

    /** A manual combination below this fraction of the floor is [StarvationLevel.STARVED]. */
    const val STARVED_FRACTION = 0.5

    // ---- rung tables (ascending quality; minVideoKbps strictly ascending) -------------------

    private val AV1_RUNGS = listOf(
        AutoRung(426, 240, 10, 40), AutoRung(426, 240, 12, 60), AutoRung(426, 240, 15, 95),
        AutoRung(640, 360, 15, 150), AutoRung(854, 480, 15, 430), AutoRung(854, 480, 24, 700),
        AutoRung(854, 480, 30, 930),
    )

    /** Strong encoders keep 15 FPS and take 720p instead of 24/30 FPS at 480p (table "or 720p @15-20"). */
    private val AV1_STRONG_RUNGS = AV1_RUNGS.take(5) + AutoRung(1280, 720, 15, 480)

    private val VP9_RUNGS = listOf(
        AutoRung(426, 240, 10, 40), AutoRung(426, 240, 12, 60), AutoRung(426, 240, 15, 95),
        AutoRung(640, 360, 15, 175), AutoRung(854, 480, 15, 330), AutoRung(854, 480, 24, 850),
        AutoRung(854, 480, 30, 1_190),
    )

    /** Design HC: the VP9 rungs at [H264_BITS_FACTOR] times the bits (stated assumption until measured). */
    private val H264_RUNGS = VP9_RUNGS.map { it.copy(minVideoKbps = (it.minVideoKbps * H264_BITS_FACTOR).toInt()) }

    /** Rungs from the lowest quality to the highest for [codecId] on an encoder of class [encoderClass]. */
    fun rungs(codecId: Int, encoderClass: AutoEncoderClass): List<AutoRung> = when {
        codecId == StreamProtocol.CODEC_H264 -> H264_RUNGS
        codecId != StreamProtocol.CODEC_AV1 -> VP9_RUNGS
        encoderClass == AutoEncoderClass.WEAK -> VP9_RUNGS
        encoderClass == AutoEncoderClass.STRONG -> AV1_STRONG_RUNGS
        else -> AV1_RUNGS
    }

    /** Software encode p95 must leave this much of the frame interval (same bound as [EncoderBurstRanking.MAX_ENCODE_LOAD]). */
    const val MAX_ENCODE_LOAD = 0.8

    /**
     * Predicted p95 encode time as a fraction of the frame interval for a software encoder that took
     * [msPerMpx] p95 milliseconds per megapixel of frame (encode time scales with pixels; first phone A/B:
     * the A15s libaom took 100 ms for a 720x480 frame, 291 ms per megapixel, so 15 FPS at 640x360 is
     * already a load of 1.0 and only 240p-class sizes fit).
     */
    fun encodeLoad(msPerMpx: Double, width: Int, height: Int, fps: Int): Double =
        msPerMpx * (width.toLong() * height / 1_000_000.0) * fps / 1_000.0

    /**
     * The class a measured software encoder belongs to: strong with at least 1.5x margin at 720p15, weak
     * when 360p15 is already over [MAX_ENCODE_LOAD], else standard (also when nothing is measured yet,
     * and for a hardware encoder, which has no measurement). Weak AV1 uses the VP9 thresholds.
     */
    fun encoderClassFromLoad(msPerMpx: Double?): AutoEncoderClass? {
        if (msPerMpx == null || msPerMpx <= 0.0) return null
        return when {
            encodeLoad(msPerMpx, 1280, 720, 15) <= 1.0 / STRONG_MIN_ENCODE_MARGIN -> AutoEncoderClass.STRONG
            encodeLoad(msPerMpx, 640, 360, 15) > MAX_ENCODE_LOAD -> AutoEncoderClass.WEAK
            else -> AutoEncoderClass.STANDARD
        }
    }

    /**
     * [rungs] without the rungs this encoder cannot sustain ([MAX_ENCODE_LOAD] at that size and frame
     * rate). Nothing measured keeps every rung; if even the lowest is over, the lowest stays: the
     * lowest sensible rung rather than none.
     */
    fun usableRungs(codecId: Int, encoderClass: AutoEncoderClass, encodeMsPerMpx: Double?): List<AutoRung> {
        val all = rungs(codecId, encoderClass)
        if (encodeMsPerMpx == null) return all
        val fits = all.filter { encodeLoad(encodeMsPerMpx, it.width, it.height, it.fps) <= MAX_ENCODE_LOAD }
        return fits.ifEmpty { all.take(1) }
    }

    fun encoderClass(encodeMarginAt720p15: Double?, slowAv1Cpu: Boolean = false): AutoEncoderClass = when {
        slowAv1Cpu -> AutoEncoderClass.WEAK
        encodeMarginAt720p15 != null && encodeMarginAt720p15 >= STRONG_MIN_ENCODE_MARGIN -> AutoEncoderClass.STRONG
        else -> AutoEncoderClass.STANDARD
    }

    // ---- wire model --------------------------------------------------------------------------

    fun wireKbps(videoKbps: Int, fps: Int, audioWireKbps: Int): Double =
        FRAMING * (videoKbps + fps * RECORD_KBPS_PER_FPS) + audioWireKbps

    /** Audio wire kbps for an Opus bitrate in bps (16 kbps -> 34, 32 kbps -> 50, linear between). */
    fun audioWireKbps(audioBitRateBps: Int): Int =
        (OPUS16_WIRE_KBPS + (OPUS32_WIRE_KBPS - OPUS16_WIRE_KBPS) * (audioBitRateBps - 16_000) / 16_000.0)
            .coerceIn(OPUS16_WIRE_KBPS.toDouble(), OPUS32_WIRE_KBPS.toDouble()).toInt()

    /** Video kbps a link of [linkKbps] goodput carries at [fps] alongside audio, at [UTILISATION]; 0 when none. */
    fun videoBudgetKbps(linkKbps: Int, fps: Int, audioWireKbps: Int = OPUS32_WIRE_KBPS): Int =
        floor((UTILISATION * linkKbps - audioWireKbps) / FRAMING - fps * RECORD_KBPS_PER_FPS)
            .toInt().coerceAtLeast(0)

    // ---- start plan --------------------------------------------------------------------------

    /**
     * The best rung the link carries. [linkKbps] null plans [ASSUMED_LINK_KBPS]. [maxVideoKbps] caps the
     * planned video (transport maximum). Below the lowest rung the plan is audio only.
     */
    fun plan(
        linkKbps: Int?,
        codecId: Int,
        encoderClass: AutoEncoderClass = AutoEncoderClass.STANDARD,
        audioWireKbps: Int = OPUS32_WIRE_KBPS,
        maxVideoKbps: Int = VideoSettings.MAX_BIT_RATE_KBPS,
        encodeMsPerMpx: Double? = null,
    ): AutoPlan {
        val link = linkKbps?.takeIf { it > 0 } ?: ASSUMED_LINK_KBPS
        val assumed = linkKbps == null || linkKbps <= 0
        val rung = usableRungs(codecId, encoderClass, encodeMsPerMpx).lastOrNull {
            it.minVideoKbps <= maxVideoKbps && videoBudgetKbps(link, it.fps, audioWireKbps) >= it.minVideoKbps
        }
        if (rung == null) {
            return AutoPlan(null, 0, link, assumed, audioWireKbps, audioWireKbps.toDouble())
        }
        val video = minOf(videoBudgetKbps(link, rung.fps, audioWireKbps), maxVideoKbps)
            .let { it - it % VIDEO_STEP_KBPS }
        return AutoPlan(rung, video, link, assumed, audioWireKbps, wireKbps(video, rung.fps, audioWireKbps))
    }

    private const val VIDEO_STEP_KBPS = 5

    /**
     * LE data-length PDUs one connection event carries on a 1M PHY. Measured: ASUS to S24 GATT on 1M at a
     * 15 ms interval carried about 245 kbps in total (1.9 x the one-PDU bound of 131 kbps) while the
     * 1074 kbps "probe" of the same pair was a burst arrival rate seen at 2M.
     */
    const val PDUS_PER_EVENT_1M = 2

    /**
     * Without delivered evidence a start (and the header it sets) never exceeds this frame rate: the first
     * Camera2 A/B planned 480p24 at 1100 kbps from a 1488 kbps probe, kept 24 FPS in the header, and the
     * viewer froze 12 times; the same pair with a delivered 124 kbps and 240p15 had none.
     */
    const val MAX_UNCONFIRMED_FPS = 15

    /** The plan with its frame rate capped at [MAX_UNCONFIRMED_FPS] (the last rung at or under it, not above the plan's). */
    private fun unconfirmedFps(base: AutoPlan, table: List<AutoRung>, evidence: String): AutoPlan {
        val rung = base.rung ?: return base.copy(evidence = evidence)
        if (rung.fps <= MAX_UNCONFIRMED_FPS) return base.copy(evidence = evidence)
        val capped = table.lastOrNull { it.fps <= MAX_UNCONFIRMED_FPS && table.indexOf(it) < table.indexOf(rung) } ?: table.first()
        return base.copy(
            rung = capped,
            wireKbps = wireKbps(base.videoKbps, capped.fps, base.audioWireKbps),
            evidence = evidence,
        )
    }

    /** A probe-only (cold) start takes this fraction of the plain plan's video ... */
    const val PROBE_ONLY_VIDEO_FRACTION = 0.3

    /**
     * ... at most this many kbps: just under the 360p entry of both codecs (AV1 150, VP9 175), so a ladder
     * started at this rate begins at 240p ...
     */
    const val PROBE_ONLY_MAX_VIDEO_KBPS = 140

    /** ... and with pictures of at most this many pixels (240p class). */
    const val PROBE_ONLY_MAX_PIXELS = 426L * 240

    /**
     * The link-level ceiling of an LE connection the phone knows to run on 1M (a remembered bad-2M peer):
     * [PDUS_PER_EVENT_1M] payloads of one data-length PDU per [intervalMs] (Core v5.4 Vol 6 Part B 4.5.1
     * connection events; DLE payload 247 bytes after the L2CAP header). Null for a link not known to be on 1M.
     */
    fun phyCeilingKbps(intervalMs: Double): Int =
        (PDUS_PER_EVENT_1M * LeConnectionParameters.DLE_PDU_L2CAP_PAYLOAD_BYTES * 8 / intervalMs).toInt()

    /**
     * The start plan from the evidence a phone has before the first report of this session.
     * [probeKbps] is the phone's best single-link probe, which is the peer's burst arrival rate, not
     * sustained goodput; [phyCeilingKbps] bounds a link known to be on 1M ([phyCeilingKbps]);
     * [sustainedVideoKbps] is the video rate this peer's link last carried cleanly (the weakest peer's).
     *
     * - The link is the lowest of probe and PHY ceiling (unknown: [ASSUMED_LINK_KBPS]).
     * - Delivered evidence caps the video at that rate, and the rung drops until it fits.
     * - A probe alone (no PHY bound, nothing delivered) is a cold start: 30% of the plan's video, at most
     *   [PROBE_ONLY_MAX_VIDEO_KBPS] kbps, 240p class, 15 FPS at most; the climb then follows delivered evidence.
     * - A plan on the assumed link keeps the approved start (360p15): it is already a conservative guess.
     */
    fun evidencePlan(
        probeKbps: Int?,
        phyCeilingKbps: Int?,
        sustainedVideoKbps: Int?,
        codecId: Int,
        encoderClass: AutoEncoderClass = AutoEncoderClass.STANDARD,
        audioWireKbps: Int = OPUS32_WIRE_KBPS,
        maxVideoKbps: Int = VideoSettings.MAX_BIT_RATE_KBPS,
        encodeMsPerMpx: Double? = null,
    ): AutoPlan {
        val probe = probeKbps?.takeIf { it > 0 }
        val ceiling = phyCeilingKbps?.takeIf { it > 0 }
        val sustained = sustainedVideoKbps?.takeIf { it > 0 }
        val link = listOfNotNull(probe, ceiling).minOrNull()
        val base = plan(link, codecId, encoderClass, audioWireKbps, maxVideoKbps, encodeMsPerMpx)
        val rung = base.rung ?: return base.copy(evidence = if (probe == null && ceiling == null) "assumed" else "probe_only")
        val table = usableRungs(codecId, encoderClass, encodeMsPerMpx)
        return when {
            sustained != null -> {
                val video = minOf(base.videoKbps, sustained).let { it - it % VIDEO_STEP_KBPS }
                val fitted = table.lastOrNull { it.minVideoKbps <= video && it.pixels <= rung.pixels && it.fps <= rung.fps }
                if (fitted == null) {
                    base.copy(rung = null, videoKbps = 0, wireKbps = audioWireKbps.toDouble(), evidence = "delivered")
                } else {
                    base.copy(rung = fitted, videoKbps = video, wireKbps = wireKbps(video, fitted.fps, audioWireKbps), evidence = "delivered")
                }
            }
            ceiling != null -> unconfirmedFps(base, table, "phy_bound")
            probe != null -> {
                // The probe is a burst arrival rate and overstated weak links 10-20x (a 1488 kbps probe on a
                // link that carried 44-90 kbps froze the viewer for 62% of 17 s): a cold start takes a fraction
                // of the plan, a hard ceiling, 240p-class pictures and 15 FPS at most. Delivered evidence
                // (the controller's climb, a remembered rate) takes it from there.
                val cold = (base.videoKbps * PROBE_ONLY_VIDEO_FRACTION).toInt()
                    .coerceAtMost(PROBE_ONLY_MAX_VIDEO_KBPS).coerceAtLeast(MIN_VIDEO_KBPS)
                val coldRung = table.lastOrNull {
                    it.pixels <= PROBE_ONLY_MAX_PIXELS && it.fps <= MAX_UNCONFIRMED_FPS && it.minVideoKbps <= cold &&
                        table.indexOf(it) <= table.indexOf(rung)
                } ?: table.first()
                val video = minOf(base.videoKbps, cold).let { it - it % VIDEO_STEP_KBPS }
                base.copy(rung = coldRung, videoKbps = video, wireKbps = wireKbps(video, coldRung.fps, audioWireKbps), evidence = "probe_only")
            }
            else -> unconfirmedFps(base, table, "assumed")
        }
    }

    /**
     * A call direction's plan from the measured link of the call transport (both directions share it).
     * [audioBitRateBps] is the call's Opus rate.
     */
    fun callPlan(
        measuredLinkKbps: Int?,
        codecId: Int,
        encoderClass: AutoEncoderClass,
        audioBitRateBps: Int,
        maxVideoKbps: Int,
        encodeMsPerMpx: Double? = null,
    ): AutoPlan = plan(
        measuredLinkKbps?.takeIf { it > 0 }?.let { (it * CALL_DIRECTION_SHARE).toInt() },
        codecId,
        encoderClass,
        audioWireKbps(audioBitRateBps),
        maxVideoKbps,
        encodeMsPerMpx,
    )

    // ---- adaptation thresholds ---------------------------------------------------------------

    private fun bucketPixels(pixels: Long): Int = when {
        pixels <= 320L * 180 -> 0
        pixels <= 480L * 270 -> 1
        pixels <= 640L * 360 -> 2
        pixels <= 960L * 540 -> 3
        else -> 4
    }

    private fun bucketOf(rung: AutoRung): Int = bucketPixels(rung.pixels)

    /** The size each pixel bucket's table entries are for (240p, 360p, 480p, 720p). */
    private val BUCKET_REFERENCE_PIXELS = longArrayOf(0L, 426L * 240, 640L * 360, 854L * 480, 1280L * 720)

    /**
     * Minimum video kbps for [width]x[height] at [fps]: the table entry of its pixel bucket (else the
     * 15 FPS entry scaled by fps^0.55), scaled by (pixels / the bucket's table size)^0.4 for a size that
     * is not the table size, so an off-grid camera mode (640x480, 720x480, 480x360) needs what its
     * pixel count needs and not the whole next bucket.
     */
    fun minVideoKbps(codecId: Int, encoderClass: AutoEncoderClass, width: Int, height: Int, fps: Int): Int {
        val pixels = width.toLong() * height
        val bucket = bucketPixels(pixels)
        if (bucket == 0) return 0
        val table = rungs(codecId, encoderClass)
        val exact = table.firstOrNull { bucketOf(it) == bucket && it.fps == fps }?.minVideoKbps
        val base: Double = exact?.toDouble()
            ?: ((table.firstOrNull { bucketOf(it) == bucket && it.fps == 15 }?.minVideoKbps
                ?: beyondTableKbps(codecId, bucket)) * (fps / 15.0).pow(FPS_EXPONENT))
        val pixelScale = (pixels.toDouble() / BUCKET_REFERENCE_PIXELS[bucket]).pow(PIXEL_ALPHA)
        return (base * pixelScale).toInt()
    }

    /**
     * The per-size start limit [CallResolutionLadder] uses under the measured ladder: [minVideoKbps] in
     * bps, 0 at 240p and below (the floor; the frame rate falls instead).
     */
    fun callStartLimit(codecId: Int, encoderClass: AutoEncoderClass, fps: Int): (VideoResolution) -> Int = { size ->
        if (size.width.toLong() * size.height <= 480L * 270) {
            0
        } else {
            minVideoKbps(codecId, encoderClass, size.width, size.height, fps) * 1_000
        }
    }

    /**
     * Maps the Auto plan's header (maximum) geometry and frame rate to what the camera offers. A rung
     * needs the same aspect ratio as the header, so the mode comes from the aspect-ratio group that
     * has at least two ladder sizes at or below the target (preferring the requested aspect, then more
     * sizes, then more pixels): a phone with no 16:9 mode but 640x480 and 320x240 gets that pair, not a
     * lone 720x480 whose ladder could never step. An exact match is kept as requested.
     */
    fun fitSettings(
        requested: VideoSettings,
        modes: List<DeviceVideoMode>,
        encodeMsPerMpx: Double? = null,
    ): VideoSettings {
        val candidates = modes.filter { mode -> mode.fpsOptions.any { it <= requested.fps } }
        require(candidates.isNotEmpty()) { "No compatible camera/encoder mode at ${requested.fps} FPS" }
        val exact = candidates.firstOrNull {
            it.resolution.width == requested.resolution.width && it.resolution.height == requested.resolution.height
        }
        val exactFits = exact != null && (encodeMsPerMpx == null ||
            encodeLoad(encodeMsPerMpx, requested.resolution.width, requested.resolution.height, requested.fps) <= MAX_ENCODE_LOAD)
        if (exactFits && requested.fps in exact!!.fpsOptions) return requested
        val targetPixels = requested.resolution.width.toLong() * requested.resolution.height
        val sized = candidates.filter { pixelsOf(it) <= targetPixels && pixelsOf(it) >= CallResolutionLadder.FLOOR_PIXELS }
        // A software encoder is only offered the sizes it can encode in time at the request's frame rate.
        val usable = encodeMsPerMpx?.let { load ->
            sized.filter { encodeLoad(load, it.resolution.width, it.resolution.height, requested.fps) <= MAX_ENCODE_LOAD }
        } ?: sized
        val chosen = when {
            exactFits -> exact!!
            usable.isEmpty() -> candidates.minBy(::pixelsOf)
            else -> {
                val requestedAspect = requested.resolution.width.toDouble() / requested.resolution.height
                val groups = mutableListOf<MutableList<DeviceVideoMode>>()
                for (mode in usable.sortedByDescending(::pixelsOf)) {
                    val aspect = mode.resolution.width.toDouble() / mode.resolution.height
                    val group = groups.firstOrNull {
                        val a = it[0].resolution.width.toDouble() / it[0].resolution.height
                        kotlin.math.abs(a - aspect) / a <= CallResolutionLadder.ASPECT_TOLERANCE
                    }
                    if (group == null) groups += mutableListOf(mode) else group += mode
                }
                fun sizes(group: List<DeviceVideoMode>) = group.map { bucketPixels(pixelsOf(it)) }.distinct().size
                fun aspectMatches(group: List<DeviceVideoMode>): Boolean {
                    val a = group[0].resolution.width.toDouble() / group[0].resolution.height
                    return kotlin.math.abs(a - requestedAspect) / requestedAspect <= CallResolutionLadder.ASPECT_TOLERANCE
                }
                groups.maxWith(
                    compareBy<List<DeviceVideoMode>>(
                        { minOf(sizes(it), 2) }, { aspectMatches(it) }, { sizes(it) }, { pixelsOf(it[0]) },
                    ),
                )[0]
            }
        }
        return requested.copy(
            resolution = chosen.resolution,
            fps = chosen.fpsOptions.filter { it <= requested.fps }.max(),
        )
    }

    private fun pixelsOf(mode: DeviceVideoMode): Long = mode.resolution.width.toLong() * mode.resolution.height

    /** Sizes above the rungs (720p without margin evidence, 1080p) keep the WebRTC start limits. */
    private fun beyondTableKbps(codecId: Int, bucket: Int): Int = when {
        bucket == 4 && codecId == StreamProtocol.CODEC_AV1 -> 576
        bucket == 4 && codecId == StreamProtocol.CODEC_H264 -> (480 * H264_BITS_FACTOR).toInt()
        bucket == 4 -> 480
        else -> 0
    }

    /**
     * Resolution thresholds in the shape [CallResolutionLadder] uses: {largest pixel count, minimum
     * video bps}, ascending; a size takes the first entry at least as large. 426x240 shares the
     * 480x270 entry and is the floor (0): below the 240p entry the frame rate falls (12, then 10 FPS,
     * [fpsForVideoKbps]) instead of the picture shrinking further, as in the measured ladder.
     */
    fun callLimits(codecId: Int, encoderClass: AutoEncoderClass, fps: Int): List<Pair<Long, Int>> = listOf(
        320L * 180 to 0,
        480L * 270 to 0,
        640L * 360 to minVideoKbps(codecId, encoderClass, 640, 360, fps) * 1_000,
        960L * 540 to minVideoKbps(codecId, encoderClass, 854, 480, fps) * 1_000,
        1280L * 720 to minVideoKbps(codecId, encoderClass, 1280, 720, fps) * 1_000,
        1920L * 1080 to 1_000_000,
    )

    /**
     * Frame rate for a call video target: [headerFps] while the target meets the entry of the
     * current top rung at that rate (24/30 FPS need their own rung), else 15; below the 240p15 entry
     * the lower rungs apply (12, then 10). Frame rate is cut before resolution above 15 FPS; at
     * 15 FPS and below resolution goes first (the resolution ladder reaches 240p before this curve
     * cuts below 15). Never above [headerFps].
     */
    fun fpsForVideoKbps(
        codecId: Int,
        encoderClass: AutoEncoderClass,
        headerWidth: Int,
        headerHeight: Int,
        headerFps: Int,
        videoKbps: Int,
    ): Int {
        if (headerFps > 15 && videoKbps >= minVideoKbps(codecId, encoderClass, headerWidth, headerHeight, headerFps)) {
            return headerFps
        }
        val fps = when {
            videoKbps >= minVideoKbps(codecId, encoderClass, 426, 240, 15) -> 15
            videoKbps >= minVideoKbps(codecId, encoderClass, 426, 240, 12) -> 12
            else -> 10
        }
        return minOf(fps, headerFps)
    }

    /**
     * The header (maximum) geometry and frame rate for a call: the highest rung the class may ever
     * use, at least 15 FPS and the start rung's FPS. The sender's resolution ladder starts at the rung
     * for the link and moves up and down inside this header; the receiver decoder is configured for it.
     */
    fun callHeader(
        codecId: Int,
        encoderClass: AutoEncoderClass,
        start: AutoPlan,
        encodeMsPerMpx: Double? = null,
    ): Pair<VideoResolution, Int> {
        val usable = usableRungs(codecId, encoderClass, encodeMsPerMpx)
        val top = usable.last()
        val topAt15 = usable.lastOrNull { it.fps == 15 } ?: top
        val fps = maxOf(minOf(15, top.fps), start.rung?.fps ?: 0)
        // The header fps may only exceed 15 if its geometry rung exists (24/30 are 480p rungs).
        val resolution = if (fps > 15) top.resolution else topAt15.resolution
        return resolution to fps
    }

    // ---- picker guard -------------------------------------------------------------------------

    /**
     * Bits per pixel per frame at the 640x480 reference size, with the measured pixel exponent: equals
     * plain bits per pixel per frame at 640x480 and rewards larger frames less than linearly.
     */
    fun bppfEq(width: Int, height: Int, fps: Int, videoKbps: Int): Double {
        if (fps <= 0) return 0.0
        val pixels = width.toDouble() * height
        return videoKbps * 1_000.0 / (fps * REFERENCE_PIXELS * (pixels / REFERENCE_PIXELS).pow(PIXEL_ALPHA))
    }

    fun acceptableBppf(codecId: Int): Double =
        when (codecId) {
            StreamProtocol.CODEC_AV1 -> ACCEPTABLE_BPPF_AV1
            StreamProtocol.CODEC_H264 -> ACCEPTABLE_BPPF_H264
            else -> ACCEPTABLE_BPPF_VP9
        }

    /**
     * Whether a manual request is starved. This only classifies: the request is never changed, the
     * picker shows the warning and, as today, requested versus effective during the session.
     */
    fun starvation(codecId: Int, width: Int, height: Int, fps: Int, videoKbps: Int): StarvationCheck {
        val floor = acceptableBppf(codecId)
        val eq = bppfEq(width, height, fps, videoKbps)
        val pixels = width.toDouble() * height
        val minimum = floor * fps * REFERENCE_PIXELS * (pixels / REFERENCE_PIXELS).pow(PIXEL_ALPHA) / 1_000.0
        val level = when {
            eq >= floor -> StarvationLevel.OK
            eq >= floor * STARVED_FRACTION -> StarvationLevel.WARN
            else -> StarvationLevel.STARVED
        }
        return StarvationCheck(level, eq, floor, kotlin.math.ceil(minimum).toInt())
    }

    fun starvationWarning(check: StarvationCheck, width: Int, height: Int, fps: Int): String? = when (check.level) {
        StarvationLevel.OK -> null
        StarvationLevel.WARN ->
            "Low for ${width}x$height at $fps FPS: expect a soft picture. About ${check.minimumKbps} kbps or more keeps it clear."
        StarvationLevel.STARVED ->
            "Too low for ${width}x$height at $fps FPS: expect a very blocky picture. Try at least ${check.minimumKbps} kbps, a lower frame rate or a smaller size."
    }
}

/**
 * Frame-rate hysteresis for the Auto ladder. The curve ([AutoQualityLadder.fpsForVideoKbps]) is a pure
 * function of the video target, so a target that jumps between 40 and 150 kbps flipped a weak call
 * between 10 and 15 FPS 6-11 times a minute (first weak-pair A/B). A lower rate is taken once the curve
 * has asked for it for [downHoldMs]; a higher one only after [upHoldMs] of asking, one level at a time,
 * and not within [minUpIntervalMs] of the last change. The first value passes through.
 */
internal class FpsHysteresis(
    private val downHoldMs: Long = DOWN_HOLD_MS,
    private val upHoldMs: Long = UP_HOLD_MS,
    private val minUpIntervalMs: Long = MIN_UP_INTERVAL_MS,
    private val levels: List<Int> = LEVELS,
) {
    var current = -1
        private set
    private var downSinceMs = -1L
    private var upSinceMs = -1L
    private var lastChangeMs = Long.MIN_VALUE / 2
    private var upBlockedUntilMs = Long.MIN_VALUE

    /**
     * [allowUp] false (the latency guard: queues are not within their bounds) holds every rise;
     * [congested] true lowers the rate one level at once (not within [MIN_CONGESTION_STEP_MS] of the last
     * change) and holds rises for [CONGESTION_UP_BLOCK_MS].
     */
    fun update(rawFps: Int, nowMs: Long, allowUp: Boolean = true, congested: Boolean = false): Int {
        if (current < 0) {
            current = rawFps
            lastChangeMs = nowMs
            return current
        }
        if (congested) {
            upBlockedUntilMs = nowMs + CONGESTION_UP_BLOCK_MS
            val lower = levels.lastOrNull { it < current }
            if (lower != null && nowMs - lastChangeMs >= MIN_CONGESTION_STEP_MS) change(lower, nowMs)
            return current
        }
        if (!allowUp || nowMs < upBlockedUntilMs) upSinceMs = -1L
        when {
            rawFps < current -> {
                upSinceMs = -1L
                if (downSinceMs < 0L) downSinceMs = nowMs
                if (nowMs - downSinceMs >= downHoldMs) change(rawFps, nowMs)
            }
            rawFps > current -> {
                downSinceMs = -1L
                if (!allowUp || nowMs < upBlockedUntilMs) return current
                if (upSinceMs < 0L) upSinceMs = nowMs
                if (nowMs - upSinceMs >= upHoldMs && nowMs - lastChangeMs >= minUpIntervalMs) {
                    change(levels.firstOrNull { it > current }?.coerceAtMost(rawFps) ?: rawFps, nowMs)
                }
            }
            else -> {
                downSinceMs = -1L
                upSinceMs = -1L
            }
        }
        return current
    }

    private fun change(to: Int, nowMs: Long) {
        current = to
        lastChangeMs = nowMs
        downSinceMs = -1L
        upSinceMs = -1L
    }

    companion object {
        const val DOWN_HOLD_MS = 2_000L
        const val UP_HOLD_MS = 10_000L
        const val MIN_UP_INTERVAL_MS = 15_000L
        const val MIN_CONGESTION_STEP_MS = 5_000L
        const val CONGESTION_UP_BLOCK_MS = 30_000L
        val LEVELS = listOf(10, 12, 15, 24, 30)
    }
}

/**
 * The Auto ladder never raises latency (first lane-2 A/B: an M31 stream source started at 24 FPS and 870
 * kbps on a 1185 kbps link, the frame rate rose to about 23, the sender queue reached 1.86 s and the
 * receiver's playout delay its 2000 ms cap, 1.2 s above the control). A rise of the frame rate or of the
 * picture size is allowed only while the sender queue and the media queued at the peer (their sum is the
 * age of the newest media in flight) stayed within [QUEUE_BOUND_MS] and no child reported its playout
 * delay rising ("late") for [HOLD_MS]; the rate controller's own cuts and the rung and frame-rate steps down
 * are unaffected, so growth backs off as before. Every sample from every child feeds one guard, so the
 * tightest child decides.
 */
internal class ClimbGuard(
    private val holdMs: Long = HOLD_MS,
    private val queueBoundMs: Int = QUEUE_BOUND_MS,
    private val congestedMs: Int = CONGESTED_MS,
) {
    private var lastUncleanMs = Long.MIN_VALUE
    private var lastCongestedMs = Long.MIN_VALUE
    private var congestedSinceMs = -1L
    private var severeSinceMs = -1L
    private var samples = 0

    fun onSample(backlogAgeMs: Int, peerQueueMs: Int, childLate: Boolean, nowMs: Long) {
        samples++
        val queueMs = backlogAgeMs.coerceAtLeast(0) + peerQueueMs.coerceAtLeast(0)
        if (queueMs > queueBoundMs || childLate) lastUncleanMs = nowMs
        val congested = queueMs >= congestedMs || childLate && queueMs > queueBoundMs
        if (congested) lastCongestedMs = nowMs
        // Continuous stretches: the first sample that is not congested (or not severe) ends the stretch.
        if (congested) {
            if (congestedSinceMs < 0L) congestedSinceMs = nowMs
        } else {
            congestedSinceMs = -1L
        }
        val severe = queueMs >= SEVERE_MS || childLate && queueMs >= congestedMs
        if (severe) {
            if (severeSinceMs < 0L) severeSinceMs = nowMs
        } else {
            severeSinceMs = -1L
        }
    }

    /** Congested without a clean sample in between for at least [minMs] (queue age or lateness still growing). */
    fun sustainedCongestion(nowMs: Long, minMs: Long): Boolean =
        congestedSinceMs >= 0L && nowMs - congestedSinceMs >= minMs

    /** A queue of [SEVERE_MS] or a late child with [congestedMs] queued, continuously for at least [minMs]. */
    fun severeCongestion(nowMs: Long, minMs: Long): Boolean =
        severeSinceMs >= 0L && nowMs - severeSinceMs >= minMs

    /** No sample in the last [holdMs] was over the bound or late, and the guard has seen traffic. */
    fun climbAllowed(nowMs: Long, requiredCleanMs: Long = holdMs): Boolean =
        samples > 0 && (lastUncleanMs == Long.MIN_VALUE || nowMs - lastUncleanMs >= requiredCleanMs)

    /** The newest sample was congested: queued media of [congestedMs] or more, or late with a queue present. */
    fun congested(nowMs: Long): Boolean = lastCongestedMs != Long.MIN_VALUE && nowMs - lastCongestedMs < CONGESTED_WINDOW_MS

    companion object {
        const val HOLD_MS = 5_000L
        /** A queue this old (ms) is severe on its own. */
        const val SEVERE_MS = 1_000
        /** The rate controller's own "clean window" bound ([LinkRateController.CLEAN_BACKLOG_MS]). */
        const val QUEUE_BOUND_MS = 150
        /** [LinkRateController.CONGESTED_BACKLOG_MS]. */
        const val CONGESTED_MS = 400
        private const val CONGESTED_WINDOW_MS = 1_500L
    }
}

/**
 * Audio first on a link that cannot carry the lowest video rung (Auto calls). The rate controller at its
 * floor while the link is still congested means audio plus the smallest video overruns the link: the
 * A15s to M31 uplink delivered about 63 kbps against 72 kbps of audio payload plus the 40 kbps video
 * minimum, so the call video queue overflowed (34-136 frames dropped in three of six runs) and the
 * receiver's playout delay was pushed to 600 ms. After [pauseAfterMs] of continuous floor congestion the
 * gate asks to pause the call video (the camera-off path); it asks to resume after [firstResumeMs],
 * doubling the wait (up to [maxResumeMs]) while a resumed video is paused again within [failureWindowMs].
 */
internal class AudioFirstGate(
    private val pauseAfterMs: Long = PAUSE_AFTER_MS,
    private val firstResumeMs: Long = FIRST_RESUME_MS,
    private val maxResumeMs: Long = MAX_RESUME_MS,
    private val failureWindowMs: Long = FAILURE_WINDOW_MS,
) {
    enum class Action { NONE, PAUSE, RESUME }

    var paused = false
        private set
    private var congestedSinceMs = -1L
    private var pausedAtMs = 0L
    private var resumedAtMs = Long.MIN_VALUE / 2
    private var failures = 0

    fun onSample(atFloorCongested: Boolean, nowMs: Long): Action {
        if (paused) {
            if (nowMs - pausedAtMs < waitMs()) return Action.NONE
            paused = false
            resumedAtMs = nowMs
            congestedSinceMs = -1L
            return Action.RESUME
        }
        if (!atFloorCongested) {
            congestedSinceMs = -1L
            return Action.NONE
        }
        if (congestedSinceMs < 0L) congestedSinceMs = nowMs
        if (nowMs - congestedSinceMs < pauseAfterMs) return Action.NONE
        failures = if (nowMs - resumedAtMs < failureWindowMs) minOf(failures + 1, MAX_FAILURES) else 0
        paused = true
        pausedAtMs = nowMs
        congestedSinceMs = -1L
        return Action.PAUSE
    }

    /** The wait before the next resume attempt. */
    fun waitMs(): Long = minOf(maxResumeMs, firstResumeMs shl failures)

    companion object {
        const val PAUSE_AFTER_MS = 6_000L
        const val FIRST_RESUME_MS = 30_000L
        const val MAX_RESUME_MS = 120_000L
        const val FAILURE_WINDOW_MS = 20_000L
        private const val MAX_FAILURES = 3
    }
}

/**
 * Switch for the measured Auto ladder. Default off; only a DEBUG launch with `test_auto_ladder=true`
 * turns it on (control arm: the extra absent or false). It applies only to Automatic video quality
 * (never to a manual request) and not to raw benchmark inputs.
 */
object AutoQualityFeature {
    const val EXTRA = "test_auto_ladder"

    /** DEBUG-only encoder class override for the control matrix: strong, standard or weak. */
    const val CLASS_EXTRA = "test_auto_ladder_class"

    /** A constant, default off, with no Settings row; the extra decides in any DEBUG launch ([FeatureCatalog.resolve]). */
    val spec = FeatureSpec(
        id = "auto_ladder",
        prefKey = null,
        defaultEnabled = false,
        benchmarkExtra = EXTRA,
        group = ViewerFeatures.GROUP,
        extraInAnyDebugLaunch = true,
    )

    @Volatile var enabled = false

    @Volatile internal var encoderClassOverride: AutoEncoderClass? = null

    internal fun parseClass(value: String?): AutoEncoderClass? =
        AutoEncoderClass.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
}
