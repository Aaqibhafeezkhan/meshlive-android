package com.example.bluetoothtest

/**
 * Call resolution ladder (design decision 2026-10-10; default on for user calls, off for every DEBUG
 * benchmark launch unless it carries `test_call_resolution_ladder=true`; see
 * [CallResolutionLadderFeature]).
 *
 * Problem: rate control lowers only the bitrate and FPS of call video; the geometry stays as
 * requested. The M31 VP9 encoder at 150 kbps and 640x480 sat at its maximum quantizer for 99.5% of
 * frames (EncoderBitrateAdjuster header): a blocky picture and oversized keyframes, which also
 * delay audio behind them on the link.
 *
 * WebRTC answers this by choosing the send resolution from the target rate. Its default singlecast
 * limits ([minStartBps], `rtc_base/experiments/encoder_info_settings.cc`
 * GetDefaultSinglecastBitrateLimits at webrtc a05204958f9d, BSD-3, see THIRD_PARTY_NOTICES) give
 * each frame size the minimum start bitrate; a frame size uses the limit of the smallest table
 * entry at least as large (VideoEncoder::EncoderInfo::GetEncoderBitrateLimitsForResolution).
 *
 * This ladder applies those limits to Automatic-rate user calls:
 * - Rungs are camera/encoder modes this phone offers on every lens, with the call's aspect ratio
 *   (within [ASPECT_TOLERANCE]) and its exact frame rate, no larger than the call's start geometry
 *   (the header geometry, already limited by DeviceFamilyPolicy call caps and the encoder fit) and
 *   no smaller than [FLOOR_PIXELS] (WebRTC's 320x180 floor). One rung per limit bucket: the largest.
 * - Down: the target stayed below the current rung's start limit for [DOWN_HOLD_MS]; the ladder
 *   jumps to the largest rung whose limit the target meets.
 * - Up (hysteresis): one rung at a time, only after the target stayed at least [UP_HEADROOM] times
 *   the larger rung's limit for [UP_HOLD_MS] and [UP_MIN_INTERVAL_MS] passed since the last change.
 *
 * The call header keeps the start geometry and is not re-sent: it is the maximum. The encoder
 * restarts at the new size and its first frame is a keyframe carrying the new frame size, which
 * VP9/AV1 decoders follow in-band (Android CDD 5.3: dynamic resolution switching within a stream).
 * The FPS, bitrate target and aspect ratio are unchanged by the ladder.
 */
internal class CallResolutionLadder(
    val requested: VideoResolution,
    rungs: List<VideoResolution>,
    private val codecId: Int,
    /**
     * Measured Auto ladder only (AutoQualityFeature): {largest pixel count, minimum start bps}
     * thresholds from [AutoQualityLadder.callLimits] replacing the WebRTC table. The WebRTC AV1
     * 640x360 limit (256 kbps) is 40% above the measured crossover (360p15 at 150 kbps already scores
     * above 240p15 at 300), and the app's former 350 kbps "WebRTC VP9 360p" rationale never matched
     * the table (640x360 is 190 kbps; 350 is the 960x540 entry). Null keeps the WebRTC table.
     */
    private val limits: List<Pair<Long, Int>>? = null,
    /** Measured Auto ladder: step down sooner (the rate controller's target is already debounced). */
    private val downHoldMs: Long = DOWN_HOLD_MS,
    private val upHoldMs: Long = UP_HOLD_MS,
    private val upMinIntervalMs: Long = UP_MIN_INTERVAL_MS,
    private val downMinIntervalMs: Long = DOWN_MIN_INTERVAL_MS,
    /**
     * Measured Auto ladder: a start limit per size (bps) instead of one per bucket, so an off-grid
     * camera mode such as 640x480 or 720x480 is judged by its own pixel count. [limits] still
     * partitions sizes into rungs. Null keeps the per-bucket limit.
     */
    private val startLimit: ((VideoResolution) -> Int)? = null,
) {
    /** Rungs from largest to smallest; the first is always [requested]. */
    val rungs: List<VideoResolution> = buildRungs(requested, rungs, codecId, limits)

    private fun minStart(resolution: VideoResolution): Int =
        startLimit?.invoke(resolution) ?: minStartBps(codecId, resolution, limits)

    var current: VideoResolution = requested
        private set

    private var lowSinceMs = -1L
    private var highSinceMs = -1L
    private var overloadSinceMs = -1L
    private var upBlockedUntilMs = Long.MIN_VALUE

    /**
     * Measured Auto ladder: false holds every step up (the latency guard, [ClimbGuard]); the hold timer
     * restarts when the gate closes. Null: always open.
     */
    var climbGate: ((nowMs: Long) -> Boolean)? = null

    /**
     * Measured Auto ladder, streams: a step down also needs this to be true (sustained congestion
     * evidence), so a target that fell on its own never changes the picture size. The low-target timer
     * keeps running while it is false. Null: always open.
     */
    var downGate: ((nowMs: Long) -> Boolean)? = null
    private var lastChangeMs = Long.MIN_VALUE / 2

    val active: Boolean get() = rungs.size > 1

    data class Decision(val resolution: VideoResolution, val reason: String)

    /** The start rung for [targetBps] (WebRTC chooses the start resolution from the start rate). */
    fun start(targetBps: Int, nowMs: Long): Decision {
        val chosen = largestRungFor(targetBps)
        current = chosen
        lastChangeMs = nowMs
        lowSinceMs = -1L
        highSinceMs = -1L
        return Decision(chosen, reason(if (chosen == requested) "start" else "start_low_rate", targetBps, chosen))
    }

    /**
     * One rate-control sample: the video target this call's encoder now has. Returns a change, or
     * null to keep the current rung.
     */
    fun onTarget(targetBps: Int, nowMs: Long): Decision? {
        if (!active) return null
        val index = rungs.indexOf(current)
        val currentLimit = minStart(current)
        if (targetBps < currentLimit && index < rungs.lastIndex) {
            highSinceMs = -1L
            if (lowSinceMs < 0L) lowSinceMs = nowMs
            if (nowMs - lowSinceMs >= downHoldMs && nowMs - lastChangeMs >= downMinIntervalMs &&
                downGate?.invoke(nowMs) != false
            ) {
                return change(largestRungFor(targetBps), "weak_link", targetBps, nowMs)
            }
            return null
        }
        lowSinceMs = -1L
        if (index <= 0) {
            highSinceMs = -1L
            return null
        }
        val larger = rungs[index - 1]
        if (targetBps >= minStart(larger) * UP_HEADROOM && climbGate?.invoke(nowMs) != false) {
            if (highSinceMs < 0L) highSinceMs = nowMs
            if (nowMs - highSinceMs >= upHoldMs && nowMs - lastChangeMs >= upMinIntervalMs && nowMs >= upBlockedUntilMs) {
                return change(larger, "link_recovered", targetBps, nowMs)
            }
        } else {
            highSinceMs = -1L
        }
        return null
    }

    /**
     * Measured Auto ladder: the software encoder's live p95 encode time as a fraction of the frame
     * interval ([load], null when unknown). Over [ENCODER_OVERLOAD] for [ENCODER_HOLD_MS] steps one rung
     * down whatever the link says (a frame rate that flips between 10 and 15 FPS cannot help an encoder
     * that does not fit the picture), and blocks steps up for [ENCODER_UP_BLOCK_MS].
     */
    fun onEncoderLoad(load: Double?, nowMs: Long): Decision? {
        if (!active || load == null || load <= ENCODER_OVERLOAD) {
            overloadSinceMs = -1L
            return null
        }
        if (overloadSinceMs < 0L) overloadSinceMs = nowMs
        val index = rungs.indexOf(current)
        if (nowMs - overloadSinceMs < ENCODER_HOLD_MS || nowMs - lastChangeMs < DOWN_MIN_INTERVAL_MS ||
            index >= rungs.lastIndex
        ) {
            return null
        }
        val to = rungs[index + 1]
        current = to
        lastChangeMs = nowMs
        lowSinceMs = -1L
        highSinceMs = -1L
        overloadSinceMs = -1L
        upBlockedUntilMs = nowMs + ENCODER_UP_BLOCK_MS
        return Decision(to, "encoder_load(load=${"%.2f".format(load)},limitKbps=${minStart(to) / 1_000})")
    }

    private fun change(to: VideoResolution, why: String, targetBps: Int, nowMs: Long): Decision? {
        if (to == current) return null
        current = to
        lastChangeMs = nowMs
        lowSinceMs = -1L
        highSinceMs = -1L
        return Decision(to, reason(why, targetBps, to))
    }

    private fun reason(why: String, targetBps: Int, to: VideoResolution): String =
        "$why(targetKbps=${targetBps / 1_000},limitKbps=${minStart(to) / 1_000})"

    private fun largestRungFor(targetBps: Int): VideoResolution =
        rungs.firstOrNull { minStart(it) <= targetBps } ?: rungs.last()

    companion object {
        const val DOWN_HOLD_MS = 3_000L
        const val DOWN_MIN_INTERVAL_MS = 5_000L
        const val UP_HOLD_MS = 10_000L
        const val UP_MIN_INTERVAL_MS = 15_000L
        const val UP_HEADROOM = 1.25
        const val ENCODER_OVERLOAD = 1.0
        const val ENCODER_HOLD_MS = 3_000L
        const val ENCODER_UP_BLOCK_MS = 60_000L
        const val ASPECT_TOLERANCE = 0.02
        const val FLOOR_PIXELS = 320L * 180

        /**
         * WebRTC default singlecast limits {frame pixels, min start bps} (encoder_info_settings.cc,
         * webrtc a05204958f9d). VP9 (and H.265) share one table; AV1 has its own.
         */
        private val VP9_LIMITS = listOf(
            320L * 180 to 0, 480L * 270 to 120_000, 640L * 360 to 190_000,
            960L * 540 to 350_000, 1280L * 720 to 480_000, 1920L * 1080 to 1_000_000,
        )
        private val AV1_LIMITS = listOf(
            320L * 180 to 0, 480L * 270 to 176_000, 640L * 360 to 256_000,
            960L * 540 to 384_000, 1280L * 720 to 576_000, 1920L * 1080 to 1_000_000,
        )

        private fun limits(codecId: Int, override: List<Pair<Long, Int>>? = null) =
            override ?: if (codecId == StreamProtocol.CODEC_AV1) AV1_LIMITS else VP9_LIMITS

        /** The table entry index for [pixels]: the smallest entry at least as large (last when larger). */
        private fun bucket(codecId: Int, pixels: Long, override: List<Pair<Long, Int>>? = null): Int =
            limits(codecId, override).indexOfFirst { it.first >= pixels }
                .let { if (it < 0) limits(codecId, override).lastIndex else it }

        fun minStartBps(codecId: Int, resolution: VideoResolution, override: List<Pair<Long, Int>>? = null): Int =
            limits(codecId, override)[bucket(codecId, resolution.width.toLong() * resolution.height, override)].second

        private fun pixels(resolution: VideoResolution) = resolution.width.toLong() * resolution.height

        internal fun buildRungs(
            requested: VideoResolution,
            offered: List<VideoResolution>,
            codecId: Int,
            override: List<Pair<Long, Int>>? = null,
        ): List<VideoResolution> {
            val aspect = requested.width.toDouble() / requested.height
            val requestedBucket = bucket(codecId, pixels(requested), override)
            val smaller = offered
                .filter { kotlin.math.abs(it.width.toDouble() / it.height - aspect) / aspect <= ASPECT_TOLERANCE }
                .filter { pixels(it) < pixels(requested) && pixels(it) >= FLOOR_PIXELS }
                .filter { bucket(codecId, pixels(it), override) < requestedBucket }
                .sortedByDescending(::pixels)
                .distinctBy { bucket(codecId, pixels(it), override) }
            return listOf(requested) + smaller
        }
    }
}

/**
 * Switch for [CallResolutionLadder]. Normal launches: on. A DEBUG benchmark launch (any `test_*`
 * extra) keeps its exact requested geometry, so it is on only with an explicit
 * `test_call_resolution_ladder=true`; the extra set to false turns it off in any DEBUG launch.
 */
object CallResolutionLadderFeature {
    const val EXTRA = "test_call_resolution_ladder"

    /** A constant (no Settings row); the extra decides in any DEBUG launch that carries it ([FeatureCatalog.resolve]). */
    val spec = FeatureSpec(
        id = "call_resolution_ladder",
        prefKey = null,
        defaultEnabled = true,
        benchmarkExtra = EXTRA,
        group = CallFeatureSpecs.GROUP,
        extraInAnyDebugLaunch = true,
    )

    /** The spec's value, or the measured Auto ladder's need for it (`applyCallResolutionLadder`). */
    @Volatile var enabled = true
}
