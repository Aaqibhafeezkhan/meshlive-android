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
) {
    /** Rungs from largest to smallest; the first is always [requested]. */
    val rungs: List<VideoResolution> = buildRungs(requested, rungs, codecId)

    var current: VideoResolution = requested
        private set

    private var lowSinceMs = -1L
    private var highSinceMs = -1L
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
        val currentLimit = minStartBps(codecId, current)
        if (targetBps < currentLimit && index < rungs.lastIndex) {
            highSinceMs = -1L
            if (lowSinceMs < 0L) lowSinceMs = nowMs
            if (nowMs - lowSinceMs >= DOWN_HOLD_MS && nowMs - lastChangeMs >= DOWN_MIN_INTERVAL_MS) {
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
        if (targetBps >= minStartBps(codecId, larger) * UP_HEADROOM) {
            if (highSinceMs < 0L) highSinceMs = nowMs
            if (nowMs - highSinceMs >= UP_HOLD_MS && nowMs - lastChangeMs >= UP_MIN_INTERVAL_MS) {
                return change(larger, "link_recovered", targetBps, nowMs)
            }
        } else {
            highSinceMs = -1L
        }
        return null
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
        "$why(targetKbps=${targetBps / 1_000},limitKbps=${minStartBps(codecId, to) / 1_000})"

    private fun largestRungFor(targetBps: Int): VideoResolution =
        rungs.firstOrNull { minStartBps(codecId, it) <= targetBps } ?: rungs.last()

    companion object {
        const val DOWN_HOLD_MS = 3_000L
        const val DOWN_MIN_INTERVAL_MS = 5_000L
        const val UP_HOLD_MS = 10_000L
        const val UP_MIN_INTERVAL_MS = 15_000L
        const val UP_HEADROOM = 1.25
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

        private fun limits(codecId: Int) = if (codecId == StreamProtocol.CODEC_AV1) AV1_LIMITS else VP9_LIMITS

        /** The table entry index for [pixels]: the smallest entry at least as large (last when larger). */
        private fun bucket(codecId: Int, pixels: Long): Int =
            limits(codecId).indexOfFirst { it.first >= pixels }.let { if (it < 0) limits(codecId).lastIndex else it }

        fun minStartBps(codecId: Int, resolution: VideoResolution): Int =
            limits(codecId)[bucket(codecId, resolution.width.toLong() * resolution.height)].second

        private fun pixels(resolution: VideoResolution) = resolution.width.toLong() * resolution.height

        internal fun buildRungs(
            requested: VideoResolution,
            offered: List<VideoResolution>,
            codecId: Int,
        ): List<VideoResolution> {
            val aspect = requested.width.toDouble() / requested.height
            val requestedBucket = bucket(codecId, pixels(requested))
            val smaller = offered
                .filter { kotlin.math.abs(it.width.toDouble() / it.height - aspect) / aspect <= ASPECT_TOLERANCE }
                .filter { pixels(it) < pixels(requested) && pixels(it) >= FLOOR_PIXELS }
                .filter { bucket(codecId, pixels(it)) < requestedBucket }
                .sortedByDescending(::pixels)
                .distinctBy { bucket(codecId, pixels(it)) }
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

    @Volatile var enabled = true

    fun resolve(benchmarkLaunch: Boolean, hasExtra: Boolean, extraValue: Boolean): Boolean = when {
        hasExtra -> extraValue
        benchmarkLaunch -> false
        else -> true
    }
}
