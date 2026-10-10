package com.example.bluetoothtest

/**
 * Holds an encoder to the bitrate the app asked for, using its measured output (root-cause
 * program, 2026-10-07). Encoder rate control is vendor firmware: with two temporal layers
 * requested, c2.exynos.vp9.encoder on the S24 produced 415-483 kbps for a 300 kbps target for a
 * whole session (without layering the same input settled at ~300), which made a two-viewer fan-out
 * look full. Instead of a per-model correction table, each session measures its own encoder.
 *
 * The target given to the encoder is scaled by request/measured when the encoder's average over
 * [MIN_WINDOWS]-[HORIZON_WINDOWS] report windows is above [OVER_RATIO] of the request; an undershoot
 * gives back the trim. The encoder target never exceeds the request, so the requested bitrate is
 * never raised. An average, not consecutive over-target windows: the M31 OMX VP9 averaged ~1.7x a
 * 120 kbps request (248/174/247/86/247 kbps windows from content bursts) and the consecutive rule
 * never fired, so its fan-out viewer stayed 2.5-3.6 s behind (kfshape-a99f1ef7).
 */
internal class EncoderRateTrim(private val minimumFactor: Double = MINIMUM_FACTOR) {
    var factor = 1.0
        private set
    private val windows = ArrayDeque<Double>()
    private var settlingWindows = 0

    /**
     * A new request (link or thermal adaptation) takes VBR encoders a window to reach; judging the
     * transition compounded a 600 -> 240 kbps cut into an encoder target far below the request.
     */
    @Synchronized
    fun onRequestChanged() {
        windows.clear()
        settlingWindows = SETTLING_WINDOWS
    }

    /** Returns the new encoder target in bps when it should change, otherwise null. */
    @Synchronized
    fun onWindow(requestedBps: Int, measuredBps: Double): Int? {
        if (requestedBps <= 0 || measuredBps <= 0.0) return null
        if (settlingWindows > 0) {
            settlingWindows--
            return null
        }
        windows.addLast(measuredBps)
        while (windows.size > HORIZON_WINDOWS) windows.removeFirst()
        if (windows.size < MIN_WINDOWS) return null
        val ratio = windows.average() / requestedBps
        val next = when {
            ratio > OVER_RATIO -> (factor / ratio).coerceAtLeast(minimumFactor)
            ratio < UNDER_RATIO && factor < 1.0 -> (factor / ratio).coerceAtMost(1.0)
            else -> factor
        }
        if (kotlin.math.abs(next - factor) < CHANGE_EPSILON) return null
        factor = next
        onRequestChanged()
        return encoderTargetBps(requestedBps)
    }

    @Synchronized
    fun encoderTargetBps(requestedBps: Int): Int = (requestedBps * factor).toInt().coerceAtLeast(1)

    companion object {
        const val OVER_RATIO = 1.10
        const val UNDER_RATIO = 0.90
        /** 15-20 s of 5 s windows: one keyframe or scene cut cannot trim on its own. */
        const val MIN_WINDOWS = 3
        const val HORIZON_WINDOWS = 4
        const val MINIMUM_FACTOR = 0.5
        const val SETTLING_WINDOWS = 1
        private const val CHANGE_EPSILON = 0.02
    }
}
