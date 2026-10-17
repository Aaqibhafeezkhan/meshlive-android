package com.example.bluetoothtest

import android.app.ActivityManager
import android.content.Context

/**
 * Memory tier of this phone (batch T, item T-03). Android 17 adds a per-app Memory Limiter whose caps are
 * chosen by MemTotal tier (https://source.android.com/docs/core/perf/memory-limiter, fetched 2026-10-14):
 *
 * | MemTotal MiB      | tier  | visible | not visible (FGS, bound FGS, services) | swap |
 * | [3200, 4800)      | 4 GB  | 2048    | 1024                                   | 1024 |
 * | [4800, 6800)      | 6 GB  | 4096    | 2048                                   | 2048 |
 * | [6800, 9216)      | 8 GB  | 5120    | 3072                                   | 3072 |
 * | [9216, 14336)     | 12 GB | 8192    | 4096                                   | 4096 |
 * | [14336, 18432)    | 16 GB | 10240   | 5120                                   | 5120 |
 *
 * Below 3200 MiB and at or above 18432 MiB there is no limit. A screen-off relay or call is a
 * FOREGROUND_SERVICE process, so it gets the "not visible" cap; anon memory plus swap counts. Apps cannot
 * query their limit, so the tier (from ActivityManager.MemoryInfo.totalMem, which is below the advertised
 * RAM) is the only handle. The limiter is Android 17 (API 37) and may be vendor-overridden
 * (/vendor/etc/memory-limiter-config.xml), so the cap is an expectation, never a measured fact.
 *
 * The tier is always logged ([logLine]). It changes buffer sizes only with the Experimental switch
 * `test_memory_tier=true` ([ThermalSwitches.memoryTierSizing], DEBUG only, default off), and then only
 * downwards from the defaults.
 */
internal enum class MemoryTier(
    val label: String,
    val minMiB: Long,
    /** Exclusive upper bound in MiB, or Long.MAX_VALUE. */
    val maxMiB: Long,
    /** Android 17 not-visible (foreground service) cap in MiB, or null when the limiter does not apply. */
    val limiterNotVisibleMiB: Int?,
    val limiterVisibleMiB: Int?,
) {
    BELOW_LIMITER("below_3200", 0, 3_200, null, null),
    TIER_4GB("4gb", 3_200, 4_800, 1_024, 2_048),
    TIER_6GB("6gb", 4_800, 6_800, 2_048, 4_096),
    TIER_8GB("8gb", 6_800, 9_216, 3_072, 5_120),
    TIER_12GB("12gb", 9_216, 14_336, 4_096, 8_192),
    TIER_16GB("16gb", 14_336, 18_432, 5_120, 10_240),
    ABOVE_LIMITER("above_18432", 18_432, Long.MAX_VALUE, null, null),
    ;

    companion object {
        const val BYTES_PER_MIB = 1024L * 1024L

        fun fromTotalMiB(totalMiB: Long): MemoryTier =
            entries.firstOrNull { totalMiB >= it.minMiB && totalMiB < it.maxMiB } ?: BELOW_LIMITER

        fun fromTotalBytes(totalBytes: Long): MemoryTier = fromTotalMiB(totalBytes / BYTES_PER_MIB)
    }
}

/** Buffer sizes chosen for a tier. Defaults match the original constants, so a tier never raises them. */
internal data class MemorySizing(
    val senderQueueBytes: Long,
    /** Upper bound for libaom `g_lag_in_frames` (quality mode only; real-time mode always uses 0). */
    val maxLagFrames: Int,
    val reason: String,
)

internal object MemoryTierPolicy {
    /** Native budget as a share of the Android 17 not-visible cap, per the research note CP-05 (leaves room for the Java heap, Opus and the OS). */
    const val NATIVE_BUDGET_PERCENT = 70
    const val DEFAULT_MAX_LAG_FRAMES = 32
    const val TIGHT_MAX_LAG_FRAMES = 8
    const val TIGHT_SENDER_QUEUE_BYTES = 4L * 1024 * 1024

    /** Native budget in bytes under the limiter, or null where no limiter applies (or before Android 17). */
    fun nativeBudgetBytes(tier: MemoryTier, sdkInt: Int): Long? {
        if (sdkInt < ANDROID_17_SDK) return null
        val cap = tier.limiterNotVisibleMiB ?: return null
        return cap * MemoryTier.BYTES_PER_MIB * NATIVE_BUDGET_PERCENT / 100
    }

    /** True when the Android 17 limiter is expected to bound this process. */
    fun limiterExpected(tier: MemoryTier, sdkInt: Int): Boolean = nativeBudgetBytes(tier, sdkInt) != null

    /**
     * Sizing for [tier]. With [sizingEnabled] false (the default), or an entry switched off, the original
     * constants apply. With it on: the 4 GB tier under the limiter gets the 4 MiB sender queue and a lag cap of
     * 8 frames (a 1 GiB not-visible cap leaves about 700 MiB of native budget); a phone below 3200 MiB MemTotal
     * is not limited by the Memory Limiter but is by lmkd pressure, so it takes the tight values too.
     * Larger tiers keep the defaults. The values are ours, not measured.
     */
    fun sizing(tier: MemoryTier, sdkInt: Int, isLowRamDevice: Boolean, sizingEnabled: Boolean): MemorySizing {
        val default = MemorySizing(
            MemoryThermalPolicy.DEFAULT_SENDER_QUEUE_BYTES,
            DEFAULT_MAX_LAG_FRAMES,
            "defaults",
        )
        if (!sizingEnabled) return default
        return when {
            tier == MemoryTier.TIER_4GB && limiterExpected(tier, sdkInt) ->
                MemorySizing(TIGHT_SENDER_QUEUE_BYTES, TIGHT_MAX_LAG_FRAMES, "limiter_4gb_not_visible_cap_1024mib")
            tier == MemoryTier.BELOW_LIMITER && isLowRamDevice ->
                MemorySizing(TIGHT_SENDER_QUEUE_BYTES, TIGHT_MAX_LAG_FRAMES, "below_3200_low_ram")
            else -> default
        }
    }

    /** Applies a lag-frame cap to a requested value. */
    fun boundedLagFrames(requested: Int, sizing: MemorySizing): Int = requested.coerceIn(0, sizing.maxLagFrames)

    /** Result of classifying a previous process exit (fed by ApplicationExitInfo, batch B-03). */
    enum class ExitKind(val label: String) {
        MEMORY_LIMITER("memory_limiter"),
        LOW_MEMORY("low_memory"),
        OTHER("other"),
    }

    /**
     * ApplicationExitInfo.REASON_OTHER (13) with a description containing "MemoryLimiter" (Android 17 release
     * notes: the description carries it, e.g. "MemoryLimiter:AnonSwap"); REASON_LOW_MEMORY (3) is the lmkd
     * kill. [reason] values are the ApplicationExitInfo constants.
     */
    fun classifyExit(reason: Int, description: String?): ExitKind = when {
        description?.contains("MemoryLimiter", ignoreCase = true) == true -> ExitKind.MEMORY_LIMITER
        reason == REASON_LOW_MEMORY -> ExitKind.LOW_MEMORY
        else -> ExitKind.OTHER
    }

    /** ApplicationExitInfo.REASON_LOW_MEMORY; a compile-time constant, so safe below API 30 and in host tests. */
    const val REASON_LOW_MEMORY = android.app.ApplicationExitInfo.REASON_LOW_MEMORY
    const val ANDROID_17_SDK = 37

    fun logLine(
        totalMiB: Long,
        tier: MemoryTier,
        sdkInt: Int,
        isLowRamDevice: Boolean,
        memoryClassMiB: Int,
        sizing: MemorySizing,
        sizingEnabled: Boolean,
    ): String =
        "BENCHMARK memory_tier: totalMiB=$totalMiB | tier=${tier.label} | sdk=$sdkInt | lowRam=$isLowRamDevice | " +
            "memoryClassMiB=$memoryClassMiB | limiterExpected=${limiterExpected(tier, sdkInt)} | " +
            "notVisibleCapMiB=${tier.limiterNotVisibleMiB ?: "none"} | nativeBudgetMiB=" +
            "${nativeBudgetBytes(tier, sdkInt)?.let { it / MemoryTier.BYTES_PER_MIB } ?: "none"} | " +
            "sizingEnabled=$sizingEnabled | senderQueueBytes=${sizing.senderQueueBytes} | maxLagFrames=${sizing.maxLagFrames} | " +
            "sizingReason=${sizing.reason}"

    /** Reads the tier of this phone (null when ActivityManager is unavailable). */
    fun readTotalMiB(context: Context): Long? {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val info = ActivityManager.MemoryInfo()
        return runCatching {
            manager.getMemoryInfo(info)
            info.totalMem / MemoryTier.BYTES_PER_MIB
        }.getOrNull()
    }
}
