package com.example.bluetoothtest

/**
 * F16 (Experimental, off by default). Measured on ef96bbf0, longcall m31-a15s-video300: the A15s's camera, encoder and
 * send queue kept up, but its outgoing video waited up to 2063 ms in its send queue while the M31 sent it 200 kbps VP9
 * plus 32 kbps audio over one GATT link of about 254 kbps (`adaptive_phy ... kbps=254 | blocked=0.52`). Both directions
 * share the link's connection events, so the full direction left the other one little airtime, and the M31 froze
 * 19 times (9.66 s). The M31's own rate control saw nothing wrong: its own direction was not late.
 */

/**
 * Fix 1, reverse-direction link sharing (DEBUG `test_call_link_share`). A call sender also watches the direction it
 * RECEIVES: when the peer's video keeps arriving late (the receiver's playout lateness, its arrival relative to its PTS,
 * so a slow camera does not count) in [STALL_WINDOWS] consecutive one-second windows, this side caps its own video rate
 * at [DECREASE] of what it sends; [RECOVER_WINDOWS] calm windows in a row raise the cap by [INCREASE] until it reaches the
 * requested rate and is lifted. The cap never goes below [floorBps]. Automatic rate control only; a fixed rate the user
 * chose is never touched. No wire change: the signal is local.
 */
internal class CallLinkShare(private val floorBps: Int) {
    /** The current cap on this side's video rate, or null (no cap). */
    var capBps: Int? = null
        private set

    /** Why the cap last changed, for the log. */
    var lastReason: String = "none"
        private set

    private var stallWindows = 0
    private var calmWindows = 0

    /**
     * One window of the received direction: [reverseLateMaxMs] is the largest playout lateness of the peer's video in it,
     * or null when no video arrived (camera off, voice call): such a window neither stalls nor counts as calm.
     * [sendingBps] is this side's current video rate and [requestedBps] the rate the call asked for. True when the cap changed.
     */
    fun onWindow(reverseLateMaxMs: Long?, sendingBps: Int, requestedBps: Int): Boolean {
        if (reverseLateMaxMs == null) return false
        if (reverseLateMaxMs >= STALL_LATE_MS) {
            calmWindows = 0
            stallWindows++
            if (stallWindows < STALL_WINDOWS) return false
            stallWindows = 0
            val base = minOf(capBps ?: sendingBps, sendingBps)
            val next = maxOf(floorBps, kotlin.math.round(base * DECREASE).toInt())
            if (next == capBps) return false
            capBps = next
            lastReason = "reverse_late_${reverseLateMaxMs}ms"
            return true
        }
        stallWindows = 0
        val cap = capBps ?: return false
        calmWindows++
        if (calmWindows < RECOVER_WINDOWS) return false
        calmWindows = 0
        val next = kotlin.math.round(cap * INCREASE).toInt()
        capBps = if (next >= requestedBps) null else next
        lastReason = if (capBps == null) "recovered" else "reverse_calm"
        return true
    }

    /** The rate to send: the controller's [targetBps] under the cap. */
    fun apply(targetBps: Int): Int = capBps?.let { minOf(it, targetBps) } ?: targetBps

    /** `call_link_share: requested= | target= | effective= | cap= | reason=` (kbps). */
    fun line(requestedBps: Int, targetBps: Int): String =
        "call_link_share: requested=${requestedBps / 1_000} | target=${targetBps / 1_000} | " +
            "effective=${apply(targetBps) / 1_000} | cap=${capBps?.let { it / 1_000 } ?: "none"} | reason=$lastReason"

    companion object {
        /** Calm windows on the M31 showed 0-100 ms of lateness; stalled ones 300-1182 ms. */
        const val STALL_LATE_MS = 250L
        const val STALL_WINDOWS = 2
        const val RECOVER_WINDOWS = 5
        const val DECREASE = 0.75
        const val INCREASE = 1.15
    }
}

/**
 * Fix 2, follow the camera's real frame rate (DEBUG `test_call_fps_follow`). In low light the A15s's camera stretched its
 * sensor frame to 100.05 ms (exposure 70 ms) although the session asked for AE range 15-15, so it delivered 10 fps in
 * windows of 20-90 s and 15 fps between them: the encoder and the receiver expected 15, and the cadence was bursty. When
 * the sensor frame stays above [STRETCH] x the requested interval for [WINDOWS] consecutive encoder windows, the call asks
 * the camera and the encoder for the camera's real rate (an even 10 fps). Once following, the sensor frame matches the
 * lower rate by design, so the way back is the exposure: below [RECOVER_EXPOSURE] x the requested interval for [WINDOWS]
 * windows (there is light again) the requested rate returns. Automatic rate control only; a fixed frame rate the user
 * chose is never touched.
 */
internal class CallFpsFollow(private val requestedFps: Int) {
    /** The frame rate the encoder should use now; [requestedFps] when not following. */
    var effectiveFps: Int = requestedFps
        private set

    private var slowWindows = 0
    private var fastWindows = 0

    val following: Boolean get() = effectiveFps < requestedFps

    /**
     * One encoder window with the camera's measured [sensorFrameMs] and [exposureMs] (null = unknown). True when
     * [effectiveFps] changed.
     */
    fun onWindow(sensorFrameMs: Double?, exposureMs: Double?): Boolean {
        if (requestedFps <= 0) return false
        val requestedIntervalMs = 1_000.0 / requestedFps
        if (!following) {
            if (sensorFrameMs == null || sensorFrameMs <= 0.0) return false
            if (sensorFrameMs > requestedIntervalMs * STRETCH) {
                slowWindows++
                if (slowWindows >= WINDOWS) {
                    slowWindows = 0
                    val cameraFps = kotlin.math.round(1_000.0 / sensorFrameMs).toInt().coerceIn(1, requestedFps)
                    if (cameraFps < requestedFps) {
                        effectiveFps = cameraFps
                        return true
                    }
                }
            } else {
                slowWindows = 0
            }
            return false
        }
        if (exposureMs == null || exposureMs <= 0.0) return false
        if (exposureMs < requestedIntervalMs * RECOVER_EXPOSURE) {
            fastWindows++
            if (fastWindows >= WINDOWS) {
                fastWindows = 0
                effectiveFps = requestedFps
                return true
            }
        } else {
            fastWindows = 0
        }
        return false
    }

    /** `call_fps_follow: requested= | effective= | sensorFrameMs= | exposureMs=`. */
    fun line(sensorFrameMs: Double?, exposureMs: Double?): String =
        "call_fps_follow: requested=$requestedFps | effective=$effectiveFps | " +
            "sensorFrameMs=${sensorFrameMs?.let { "%.1f".format(java.util.Locale.US, it) } ?: "unknown"} | " +
            "exposureMs=${exposureMs?.let { "%.1f".format(java.util.Locale.US, it) } ?: "unknown"}"

    companion object {
        const val STRETCH = 1.2
        const val RECOVER_EXPOSURE = 0.8
        const val WINDOWS = 2
    }
}
