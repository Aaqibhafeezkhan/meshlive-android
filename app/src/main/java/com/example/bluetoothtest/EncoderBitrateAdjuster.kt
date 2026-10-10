package com.example.bluetoothtest

import kotlin.math.pow

/**
 * Holds a MediaCodec encoder's measured output to the bitrate the rate controller wants (default on
 * since 2026-10-09; DEBUG off switch `test_encoder_bitrate_adjuster=false`). Sits below the call and
 * stream rate control: LinkRateController and the start-rate guard choose the target, this only
 * changes the number handed to MediaCodec so the encoder actually produces that target.
 *
 * Port of WebRTC's Android DynamicBitrateAdjuster (sdk/android/src/java/org/webrtc/
 * DynamicBitrateAdjuster.java on BaseBitrateAdjuster.java, BSD licence; reference-repos/webrtc at
 * 42995c38). WebRTC selects it only for Exynos VP8 hardware encoders in HardwareVideoEncoderFactory;
 * here it runs for every encoder without model matching, because an encoder that tracks its target
 * never accumulates a deviation and keeps scale 1.0. Measured reason: in a GATT call the M31's
 * OMX.Exynos.VP9.Encoder produced 720 kbps for a 401-515 kbps target (1.5-2.7x per 5 s window,
 * 1.21-1.29x over whole sessions) while c2.android.vp9 (ASUS) stayed at 0.99-1.02x
 * (xpair-s24m31-431d40dc/0002, mvp-smoke-m31asus-*).
 *
 * Same as WebRTC: the deviation (actual minus expected bytes) is judged at most every
 * [ADJUSTMENT_SECONDS]; it must exceed one second of target bytes; it is capped at
 * [ADJUSTMENT_SECONDS] seconds of target bytes (at most three steps per judgement); the scale is
 * [MAX_SCALE]^(exponent/[STEPS]); a lower target rescales the accumulated deviation. Keyframes and
 * scene cuts get WebRTC's handling: no special case, but one burst below a second of target bytes
 * never moves the scale, a larger one moves it a step or two, and the encoder's own compensation
 * afterwards pays it back.
 *
 * Different from WebRTC: (1) the exponent range is -[STEPS]..0 (scale 0.25x-1.0x instead of
 * 0.25x-4x), so the bitrate requested from MediaCodec never exceeds the rate controller's target;
 * an undershooting encoder only gets back an earlier reduction. (2) Expected bytes use the frame's
 * presentation-time interval (clamped to [MAX_FRAME_US]) instead of 1/configured fps, because the
 * camera frame-rate limit changes the real cadence without telling MediaCodec. (3) While the scale
 * is already 1.0, undershoot credit is held to one second of target bytes: unused link time is not
 * banked, so a quiet scene cannot pre-pay a later overshoot that would fill the send queue.
 * (4) No reduction while most frames of the judged window were at the encoder's maximum quantizer
 * (when the bitstream exposes it, VP9 today): a lower request cannot lower that output, and winding
 * the scale down there (at 150 kbps 640x480 the M31 sat at max q_idx for 99.5% of frames, ~193
 * kbps) would leave the request far too low once the rate controller raises the target.
 *
 * Not thread-safe: the encoder's worker thread owns it.
 */
internal class EncoderBitrateAdjuster {
    /** The rate controller's bitrate for the encoder (before this correction), in bps. */
    var targetBps = 0
        private set
    var scaleExponent = 0
        private set
    var adjustments = 0
        private set
    /** Bytes produced above (positive) or below the target since the accumulator started. */
    var deviationBytes = 0.0
        private set
    /** Judgements that held the scale because the encoder was at its maximum quantizer. */
    var ceilingHolds = 0
        private set
    private var sinceAdjustmentUs = 0L
    private var lastPtsUs = Long.MIN_VALUE
    private var windowFrames = 0
    private var windowCeilingFrames = 0

    val scale: Double get() = MAX_SCALE.pow(scaleExponent.toDouble() / STEPS)

    /** The bitrate to request from MediaCodec for [target]: never above it, never below 1 bps. */
    fun adjustedBps(target: Int = targetBps): Int =
        (target * scale).toInt().coerceIn(1, target.coerceAtLeast(1))

    fun setTarget(bps: Int) {
        if (bps <= 0 || bps == targetBps) return
        // WebRTC: rescale the accumulator when its range (one second of target bytes) shrinks.
        if (targetBps > 0 && bps < targetBps) deviationBytes = deviationBytes * bps / targetBps
        targetBps = bps
    }

    /**
     * Records one encoded frame (not codec configuration). [nominalFps] is used for the first frame
     * and for timestamps that do not advance. [atQuantizerCeiling] is true when the frame's parsed
     * quantizer is at the encoder's maximum (false when unknown). Returns true when [scale] changed
     * and the codec bitrate should be re-applied.
     */
    fun onEncodedFrame(ptsUs: Long, bytes: Int, nominalFps: Int, atQuantizerCeiling: Boolean = false): Boolean {
        if (targetBps <= 0 || nominalFps <= 0 || bytes < 0) return false
        windowFrames++
        if (atQuantizerCeiling) windowCeilingFrames++
        val previousPtsUs = lastPtsUs
        lastPtsUs = ptsUs
        val frameUs = if (previousPtsUs == Long.MIN_VALUE || ptsUs <= previousPtsUs) {
            1_000_000L / nominalFps
        } else {
            (ptsUs - previousPtsUs).coerceAtMost(MAX_FRAME_US)
        }
        val bytesPerSecond = targetBps / BITS_PER_BYTE
        deviationBytes += bytes - bytesPerSecond * frameUs / 1_000_000.0
        sinceAdjustmentUs += frameUs
        // Adjust when the encoder is one second's worth of data over or under the target.
        val threshold = bytesPerSecond
        val cap = ADJUSTMENT_SECONDS * threshold
        val floor = if (scaleExponent >= 0) -threshold else -cap
        deviationBytes = deviationBytes.coerceIn(floor, cap)
        if (sinceAdjustmentUs <= ADJUSTMENT_SECONDS * 1_000_000L) return false
        sinceAdjustmentUs = 0
        val atCeiling = windowCeilingFrames * 2 > windowFrames
        windowFrames = 0
        windowCeilingFrames = 0
        val before = scaleExponent
        if (deviationBytes > threshold && atCeiling) {
            ceilingHolds++
            deviationBytes = threshold
        } else if (deviationBytes > threshold) {
            val steps = (deviationBytes / threshold + 0.5).toInt()
            scaleExponent = (scaleExponent - steps).coerceAtLeast(-STEPS)
            deviationBytes = threshold
        } else if (deviationBytes < -threshold) {
            val steps = (-deviationBytes / threshold + 0.5).toInt()
            scaleExponent = (scaleExponent + steps).coerceAtMost(0)
            deviationBytes = -threshold
        }
        if (scaleExponent == before) return false
        adjustments++
        return true
    }

    companion object {
        /** WebRTC BITRATE_ADJUSTMENT_SEC: judge at most once every three seconds. */
        const val ADJUSTMENT_SECONDS = 3.0
        /** WebRTC BITRATE_ADJUSTMENT_MAX_SCALE: the lowest scale is 1/4. */
        const val MAX_SCALE = 4.0
        /** WebRTC BITRATE_ADJUSTMENT_STEPS: steps from 1.0 to the lowest scale. */
        const val STEPS = 20
        /** A longer gap between frames is a stall, not encoder undershoot. */
        const val MAX_FRAME_US = 500_000L
        private const val BITS_PER_BYTE = 8.0
    }
}
