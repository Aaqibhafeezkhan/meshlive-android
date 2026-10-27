package com.example.bluetoothtest

/*
 * Registry entries added by batch T (thermal, memory, native). They join the registry through
 * MemoryThermalPolicy.entries (no root hook needed). The corrected forecast and battery-fallback entries
 * live with the older MEMORY_THERMAL entries in MemoryThermalPolicy.kt.
 */

/** Always-on: log the memory tier at startup (diagnostics only; changes no behaviour). */
internal data object MemoryTierLogging : DevicePolicyEffect {
    override fun describe(): String = "log the MemTotal tier and the Android 17 Memory Limiter cap the tier implies"
}

/** Experimental: tier-based sender queue and lag-frame caps. */
internal data object MemoryTierSizing : DevicePolicyEffect {
    override fun describe(): String = "4 MiB sender queue and lag cap 8 on the 4 GB limiter tier and on low-RAM phones below 3200 MiB"
}

/** Experimental: AOM_SIMD_CAPS_MASK after a native crash at encoder start. */
internal data object NativeSimdSafeModeEffect : DevicePolicyEffect {
    override fun describe(): String = "relaunch with AOM_SIMD_CAPS_MASK=0x3 (NEON, CRC32) after a native crash within 10 s of encoder init"
}

/** Experimental: 32-bit process thread cap and MediaCodec preference. */
internal data object Userspace32TierEffect : DevicePolicyEffect {
    override fun describe(): String = "at most 2 libaom threads and MediaCodec preferred when the process is 32-bit"
}

internal object ThermalFamilyEntries {
    private const val REVIEWED = "2026-10-14"
    private const val AOM = "https://aomedia.googlesource.com/aom/+/0006e96cdb595ff88950a2c664115335ca5517bf"

    private val MEMORY_LIMITER = PolicySource(
        PolicySourceKind.AOSP_SOURCE,
        "https://source.android.com/docs/core/perf/memory-limiter",
        "Android 17 per-app limit by MemTotal tier [3200,4800) 4 GB: visible 2048 / not visible 1024 MiB, swap 1024; " +
            "[4800,6800) 6 GB 4096/2048; [6800,9216) 8 GB 5120/3072; [9216,14336) 12 GB 8192/4096; [14336,18432) 16 GB " +
            "10240/5120; below 3200 and from 18432 MiB no limit. FOREGROUND_SERVICE and BOUND_FOREGROUND_SERVICE are " +
            "'not visible'. Anon memory plus swap counts; growth after swap is exhausted ends in allocation failure and " +
            "termination (fetched 2026-10-14)",
    )
    private val MEMORY_BLOG = PolicySource(
        PolicySourceKind.VENDOR_DOC,
        "https://android-developers.googleblog.com/2026/08/app-broader-memory-limits.html",
        "Android developers blog 2026-08: limits arrive on Pixel first and spread to other makers over the coming year; the " +
            "exit is reported as REASON_OTHER with a MemoryLimiter description (cited through the research catalogue)",
    )
    private val MEMORY_INFO = PolicySource(
        PolicySourceKind.API_REFERENCE,
        "https://developer.android.com/reference/android/app/ActivityManager.MemoryInfo#totalMem",
        "totalMem: the total memory accessible by the kernel; it is below the advertised RAM",
    )
    private val EXIT_INFO = PolicySource(
        PolicySourceKind.API_REFERENCE,
        "https://developer.android.com/reference/android/app/ApplicationExitInfo",
        "getHistoricalProcessExitReasons (API 30): reason, status (signal number for a native crash), timestamp and " +
            "description of recent process exits; REASON_CRASH_NATIVE, REASON_LOW_MEMORY, REASON_OTHER",
    )
    private val LMKD = PolicySource(
        PolicySourceKind.AOSP_SOURCE,
        "https://source.android.com/docs/core/perf/lmkd",
        "lmkd kills by pressure-stall information since Android 10; low-RAM configurations use stricter stall and swap " +
            "thresholds (the page does not state kill rules for foreground-service processes)",
    )
    private val AOM_ARM64_DETECT = PolicySource(
        PolicySourceKind.GOOGLE_OPEN_SOURCE,
        "$AOM/aom_ports/aarch64_cpudetect.c",
        "aom_arm_cpu_caps(): flags = arm_get_cpu_caps() & arm_cpu_env_mask() unless AOM_SIMD_CAPS forces flags; the mask is " +
            "parsed by strtol from AOM_SIMD_CAPS_MASK (arm_cpudetect.h); HAS_NEON 1, CRC32 2, DOTPROD 4, I8MM 8, SVE 16, SVE2 32 (arm.h)",
    )
    private val NDK_HWCAP = PolicySource(
        PolicySourceKind.PLATFORM_DOC,
        "https://developer.android.com/ndk/guides/cpu-features",
        "AT_HWCAP via getauxval is the supported way to read CPU features on Android; the NDK notes the value can be wrong " +
            "on some old devices",
    )
    private val ABI_GUIDE = PolicySource(
        PolicySourceKind.PLATFORM_DOC,
        "https://developer.android.com/ndk/guides/abis",
        "armeabi-v7a is the 32-bit ARM ABI; Process.is64Bit() tells which one the process runs",
    )
    private val AOM_ARM32_DETECT = PolicySource(
        PolicySourceKind.GOOGLE_OPEN_SOURCE,
        "$AOM/aom_ports/aarch32_cpudetect.c",
        "Armv7 NEON detected from getauxval(AT_HWCAP) & HWCAP_NEON; no dotprod, i8mm or SVE kernels exist for Armv7",
    )

    val entries: List<DevicePolicyEntry> = listOf(
        DevicePolicyEntry(
            id = "memory-tier-log",
            title = "Log the memory tier and the Android 17 Memory Limiter cap it implies",
            matcher = DeviceMatcher(),
            effects = listOf(MemoryTierLogging),
            behaviour = "At startup the app logs BENCHMARK memory_tier: MemTotal, the tier ([3200,4800) 4 GB and so on), the " +
                "heap class, the isLowRamDevice flag, the expected not-visible (foreground service) cap on Android 17 and the " +
                "native budget (70% of that cap). Diagnostics only; no buffer changes. Phones below 3200 MiB are not limited " +
                "by the Memory Limiter (lmkd pressure applies instead).",
            sources = listOf(MEMORY_LIMITER, MEMORY_INFO),
            sourceStatus = PolicySourceStatus.OFFICIAL,
            evidence = listOf("none: MemoryTierTest covers the tier table; the log line is not yet captured from a phone"),
            confidence = PolicyConfidence.MEDIUM,
            reviewed = REVIEWED,
            origin = "new (T-03); the app logged isLowRamDevice only",
            confidenceNote = "The tier table is the AOSP page; the limiter config can be overridden by the vendor " +
                "(/vendor/etc/memory-limiter-config.xml) and apps cannot read their limit, so the cap is an expectation.",
            area = PolicyArea.MEMORY_THERMAL,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            fallback = "No tier line; buffers unchanged",
        ),
        DevicePolicyEntry(
            id = "memory-tier-sizing",
            title = "Tier-based sender queue and lag-frame caps",
            matcher = DeviceMatcher(),
            effects = listOf(MemoryTierSizing),
            behaviour = "Proposed (Experimental, test_memory_tier=true): on Android 17 the 4 GB tier gives a foreground-service " +
                "process 1 GiB of anon memory plus swap, so a long relay or software-AV1 session budgets 70% of it: the stream " +
                "sender queue is 4 MiB instead of 8 MiB and the libaom lag-in-frames request (quality mode only) is capped at 8. " +
                "A phone below 3200 MiB MemTotal that reports isLowRamDevice takes the same tight values (lmkd pressure). " +
                "Larger tiers keep the defaults. Only ever lowers the original constants. The exit classifier " +
                "MemoryTierPolicy.classifyExit(reason, description) tells a MemoryLimiter kill from an lmkd kill for the " +
                "process-exit report (B-03).",
            sources = listOf(MEMORY_LIMITER, MEMORY_BLOG, MEMORY_INFO, EXIT_INFO, LMKD),
            sourceStatus = PolicySourceStatus.PARTIAL,
            evidence = listOf("none: not run under a limiter; `adb shell am memory-limiter manual <pid> <MB>` is the intended test"),
            confidence = PolicyConfidence.LOW,
            reviewed = REVIEWED,
            origin = "new experiment (T-03); extends low-ram-sender-queue",
            confidenceNote = "The caps are official; whether libaom's lag or the sender queue is what reaches them is unmeasured, " +
                "and the 4 MiB / 8-frame values are ours.",
            area = PolicyArea.MEMORY_THERMAL,
            proposed = true,
            validation = PolicyValidation.EXPERIMENTAL,
            fallback = "The original 8 MiB sender queue, or 4 MiB on isLowRamDevice (low-ram-sender-queue)",
            legacySwitches = listOf("DEBUG test_memory_tier=true enables the sizing (default off); the tier log is always on"),
        ),
        DevicePolicyEntry(
            id = "native-simd-safe-mode",
            title = "Mask libaom SIMD kernels after a native crash at encoder start",
            matcher = DeviceMatcher(sdkMin = 30),
            effects = listOf(NativeSimdSafeModeEffect),
            behaviour = "Proposed (Experimental, test_native_simd_safe_mode=true; arm64 only): the app writes a marker when the " +
                "software AV1 encoder starts initialising and clears it after 10 s of running. If the next start finds the " +
                "marker and ApplicationExitInfo says the previous process ended with a native crash (SIGILL, SIGSEGV or SIGBUS) " +
                "within 10 s of it, AOM_SIMD_CAPS_MASK=0x3 (NEON and CRC32 only) is set before the native library is used and " +
                "kept for later starts. A crash while safe mode is already on clears it and does not re-enter (SIMD is then " +
                "not the cause). Never selected by SoC or model; API 29 has no exit info, so the net does not act there.",
            sources = listOf(AOM_ARM64_DETECT, NDK_HWCAP, EXIT_INFO),
            sourceStatus = PolicySourceStatus.PARTIAL,
            evidence = listOf("none: no libaom SIMD fault has been observed on any phone; the risk is theoretical (Go issue 28431 is the precedent)"),
            confidence = PolicyConfidence.LOW,
            reviewed = REVIEWED,
            origin = "new experiment (T-05)",
            confidenceNote = "The environment variable and mask bits are read from the pinned libaom source (high). Whether any " +
                "phone needs it is unknown; the exoneration rule bounds the cost of a wrong attribution.",
            area = PolicyArea.NATIVE,
            proposed = true,
            validation = PolicyValidation.EXPERIMENTAL,
            fallback = "libaom runtime detection unchanged (native-simd-runtime-detection)",
            legacySwitches = listOf("DEBUG test_native_simd_safe_mode=true enables the code path (default off)"),
        ),
        DevicePolicyEntry(
            id = "userspace32-tier",
            title = "32-bit userspace tier for software AV1",
            matcher = DeviceMatcher(sdkMin = 29),
            effects = listOf(Userspace32TierEffect),
            behaviour = "Proposed (Experimental, test_userspace32_tier=true): when Process.is64Bit() is false (only possible with " +
                "the optional armeabi-v7a build, release decision Q-N9) the software AV1 encoder uses at most 2 threads and a " +
                "platform MediaCodec encoder is preferred over libaom when one exists. The process ABI is always logged. " +
                "Matches on the process ABI, never on an SoC family.",
            sources = listOf(ABI_GUIDE, AOM_ARM32_DETECT),
            sourceStatus = PolicySourceStatus.PARTIAL,
            evidence = listOf("none: no owned phone runs a 32-bit process"),
            confidence = PolicyConfidence.LOW,
            reviewed = REVIEWED,
            origin = "new experiment (T-08)",
            confidenceNote = "The ABI fact is official; the 2-thread cap and the MediaCodec preference are our expectation for weak " +
                "32-bit cores and are unmeasured.",
            area = PolicyArea.NATIVE,
            proposed = true,
            validation = PolicyValidation.EXPERIMENTAL,
            fallback = "The existing thread count and codec ladder",
            legacySwitches = listOf("DEBUG test_userspace32_tier=true enables the code path (default off)"),
        ),
    )
}
