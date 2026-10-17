package com.example.bluetoothtest

import android.os.Build

/**
 * 32-bit userspace tier (batch T, item T-08; Experimental, `test_userspace32_tier=true`, DEBUG only,
 * default off). Applies only to a process that is not 64-bit (`Process.is64Bit()` false), which exists only
 * when the optional armeabi-v7a build (`-PenableArmeabiV7a=true`, release decision Q-N9) is installed.
 * The fact is the process ABI, never the SoC family. Sources: Android NDK ABI guide
 * (https://developer.android.com/ndk/guides/abis); libaom aarch32_cpudetect.c (NEON detected from
 * getauxval, no dotprod/i8mm/SVE kernels on Armv7).
 *
 * Effects when on (all lower the load; none raises it):
 *  - the software AV1 encoder uses at most [MAX_NATIVE_THREADS] threads (a 32-bit process has a 3 GB
 *    address-space budget and typically weaker cores; the speed governor remains the real driver);
 *  - a platform MediaCodec encoder is preferred over the software AV1 encoder when one exists.
 * The process ABI is always logged ([logLine]).
 */
internal object Userspace32Tier {
    const val MAX_NATIVE_THREADS = 2

    fun is32BitProcess(process64: Boolean): Boolean = !process64

    fun active(switchOn: Boolean, process64: Boolean): Boolean = switchOn && is32BitProcess(process64)

    /** The libaom thread count to use for a [requested] count. */
    fun nativeThreadCap(requested: Int, switchOn: Boolean, process64: Boolean): Int =
        if (active(switchOn, process64)) requested.coerceIn(1, MAX_NATIVE_THREADS) else requested

    /** True when the app should choose a MediaCodec encoder over libaom if one is available. */
    fun preferMediaCodecOverNativeAv1(switchOn: Boolean, process64: Boolean): Boolean = active(switchOn, process64)

    fun logLine(switchOn: Boolean, process64: Boolean = android.os.Process.is64Bit(), abis: List<String> = Build.SUPPORTED_ABIS.toList()): String =
        "BENCHMARK userspace: process64=$process64 | supportedAbis=${abis.joinToString(",")} | tierSwitch=$switchOn | " +
            "tierActive=${active(switchOn, process64)} | nativeThreadCap=" +
            (if (active(switchOn, process64)) MAX_NATIVE_THREADS.toString() else "none")
}
