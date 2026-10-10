package com.example.bluetoothtest

import android.content.Context
import android.content.SharedPreferences

/**
 * Experimental call encoder fit (default off; DEBUG `test_call_encoder_fit`, benchmark tunable
 * `call_encoder_fit`). Capability-based, not family-based: it uses only this phone's own measured
 * encode time.
 *
 * Problem: in a call a phone encodes and decodes at once. The Oppo A15s (Helio P35) streams native
 * AV1 640x480/15 within budget (encode p95 52-64 ms of 66.7 ms at cpu-used 10, lane-a 2026-10-08),
 * but in calls, while it also decodes the peer's 640x480 video, encode p95 was 101-103 ms at
 * cpu-used 10 with the fast tool profile and it sent 6.9-7.2 FPS (calls-noloss-s24a15s-51cc8028/0002;
 * median 7.7 FPS over the S24/A15s call runs). Nothing changes the geometry, so every call repeats it.
 *
 * On: when the native encoder stays over its frame budget at its fastest preset with the fast tool
 * profile for [OVERLOAD_WINDOWS] windows during a call, that call geometry is remembered for
 * [MEMORY_MS]. A later call that would use a remembered geometry starts at the largest smaller mode
 * the camera/encoder offers at the same frame rate that is not remembered as overloaded. The change
 * is visible (status line and `BENCHMARK call_encoder_fit`). The geometry never changes mid-call:
 * the call header fixes it. Off: nothing is recorded or changed.
 */
internal object CallEncoderFit {
    const val OVERLOAD_WINDOWS = 3
    const val MEMORY_MS = 7L * 24 * 60 * 60 * 1_000

    /**
     * The largest offered mode at most [requested]'s size, at [requested]'s frame rate, whose
     * geometry is not remembered as overloaded; [requested] itself when it is not overloaded or no
     * smaller mode exists.
     */
    fun fitted(
        requested: VideoSettings,
        supportedModes: List<DeviceVideoMode>,
        overloaded: (width: Int, height: Int, fps: Int) -> Boolean,
    ): VideoSettings {
        if (!overloaded(requested.resolution.width, requested.resolution.height, requested.fps)) return requested
        val requestedPixels = requested.resolution.width.toLong() * requested.resolution.height
        val candidate = supportedModes
            .filter { mode -> requested.fps in mode.fpsOptions }
            .filter { mode -> mode.resolution.width.toLong() * mode.resolution.height < requestedPixels }
            .filter { mode -> !overloaded(mode.resolution.width, mode.resolution.height, requested.fps) }
            .maxByOrNull { mode -> mode.resolution.width.toLong() * mode.resolution.height }
            ?: return requested
        return requested.copy(resolution = candidate.resolution)
    }
}

internal class CallEncoderFitPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOverloaded(key: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val recordedAtMs = prefs.getLong(key, -1L)
        return recordedAtMs >= 0L && nowMs - recordedAtMs in 0..CallEncoderFit.MEMORY_MS
    }

    fun recordOverload(key: String, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(key, nowMs).apply()
    }

    private companion object {
        const val PREFS = "call_encoder_fit"
    }
}
