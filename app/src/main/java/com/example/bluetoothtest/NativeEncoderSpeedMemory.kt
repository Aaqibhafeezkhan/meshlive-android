package com.example.bluetoothtest

import android.content.Context
import android.content.SharedPreferences

/** What a previous session on this phone settled on for one geometry and frame rate. */
internal data class NativeEncoderSpeedRecord(
    val cpuUsed: Int,
    /** The full tool set overran the budget at the fastest preset, so the fast profile was needed. */
    val fastTools: Boolean,
)

internal interface NativeEncoderSpeedStore {
    fun load(key: String): NativeEncoderSpeedRecord?
    fun save(key: String, record: NativeEncoderSpeedRecord)
}

/** Per phone (the preferences are local), per encoded geometry and frame rate. */
internal fun nativeEncoderSpeedKey(width: Int, height: Int, fps: Int): String = "${width}x$height@$fps"

/**
 * Remembers where [NativeEncoderSpeedGovernor] settles and escalates to the real-time fast tool
 * profile when even the fastest preset overruns the frame budget.
 *
 * Measured on the Oppo A15s at 640x480/15 (lane-a 2026-10-08): the governor started at the table
 * preset 7 and climbed 7 -> 8 -> 9 -> 10 in 8-10 s every run, the first keyframe took 110-153 ms,
 * and at cpu-used 10 encode p95 was still 52-64 ms of a 66.7 ms budget with the full tool set.
 *
 * Phones within budget are unchanged: a remembered preset is used only when it is *faster* than
 * the table default (ASUS settles at 6-7 against a default of 7, so it still starts at 7), and the
 * fast profile is only added after [ESCALATE_AFTER_WINDOWS] consecutive overloaded windows at the
 * fastest preset. The governor still trials slower presets from the remembered start.
 */
internal class NativeEncoderSpeedLearner(
    defaultCpuUsed: Int,
    private val defaultFastTools: Boolean,
    remembered: NativeEncoderSpeedRecord?,
    private val maxCpuUsed: Int = NativeEncoderSpeedGovernor.MAX_REALTIME_CPU_USED,
) {
    val startCpuUsed: Int = maxOf(defaultCpuUsed, remembered?.cpuUsed ?: defaultCpuUsed)
        .coerceAtMost(maxCpuUsed)

    /** True when the encoder runs (or must be re-created) with the fast tool profile. */
    var fastTools: Boolean = defaultFastTools || remembered?.fastTools == true
        private set

    /** The fast profile was added for this phone (remembered or escalated), not by the mode table. */
    val fastToolsLearned: Boolean get() = fastTools && !defaultFastTools

    private var saved: NativeEncoderSpeedRecord? = remembered

    /**
     * True once, when the governor has been pinned at its fastest preset and still overloaded for
     * [ESCALATE_AFTER_WINDOWS] windows: the caller re-creates the encoder with the fast profile
     * (sequence-level tools cannot change on a live libaom encoder; this costs one keyframe).
     */
    fun shouldEscalateToFastTools(governor: NativeEncoderSpeedGovernor): Boolean {
        if (fastTools || governor.overloadedAtFastestWindows < ESCALATE_AFTER_WINDOWS) return false
        fastTools = true
        return true
    }

    /** A record to persist once the governor has held one preset for [SETTLE_WINDOWS], or null. */
    fun settledRecord(governor: NativeEncoderSpeedGovernor): NativeEncoderSpeedRecord? {
        if (governor.trialInProgress || governor.stableWindows < SETTLE_WINDOWS) return null
        val record = NativeEncoderSpeedRecord(
            governor.cpuUsed,
            // Keep the learned fast profile only while it still needs (nearly) the fastest preset;
            // if it settles lower, the next session tries the full tool set again.
            fastTools = fastToolsLearned && governor.cpuUsed >= maxCpuUsed - 1,
        )
        if (record == saved) return null
        saved = record
        return record
    }

    companion object {
        const val ESCALATE_AFTER_WINDOWS = 2
        const val SETTLE_WINDOWS = 3
    }
}

internal class NativeEncoderSpeedPreferences(context: Context) : NativeEncoderSpeedStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(key: String): NativeEncoderSpeedRecord? {
        val cpuUsed = prefs.getInt("$key.cpuUsed", -1).takeIf { it >= 0 } ?: return null
        return NativeEncoderSpeedRecord(cpuUsed, prefs.getBoolean("$key.fastTools", false))
    }

    override fun save(key: String, record: NativeEncoderSpeedRecord) {
        prefs.edit()
            .putInt("$key.cpuUsed", record.cpuUsed)
            .putBoolean("$key.fastTools", record.fastTools)
            .apply()
    }

    private companion object {
        const val PREFS = "native_encoder_speed"
    }
}
