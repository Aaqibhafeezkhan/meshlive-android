package com.example.bluetoothtest

import android.os.Build
import android.system.Os
import android.system.OsConstants

/**
 * Native ABI and page-size policy (area NATIVE; entries join DevicePolicyRegistry): 16 KB page
 * alignment, ABI coverage and SIMD selection of the shipped native library. The pure helpers are
 * host-tested. Nothing here changes runtime behaviour: the build changes live in app/build.gradle.kts
 * (-PenableArmeabiV7a) and app/src/main/cpp/CMakeLists.txt (linker flags).
 */
internal object NativeCompat {
    const val ABI_ARM64 = "arm64-v8a"
    const val ABI_ARM32 = "armeabi-v7a"

    /** True when [deviceAbis] (Build.SUPPORTED_ABIS, preferred first) includes one this build packages. */
    fun nativeAv1Available(deviceAbis: List<String>, packagedAbis: List<String>): Boolean =
        deviceAbis.any { it in packagedAbis }

    /** The ABI the dynamic loader picks: the first device ABI (preference order) that the APK ships. */
    fun loadedAbi(deviceAbis: List<String>, packagedAbis: List<String>): String? =
        deviceAbis.firstOrNull { it in packagedAbis }

    fun packagedAbis(): List<String> = BuildConfig.NATIVE_ABIS.split(',').filter { it.isNotBlank() }

    fun pageSizeBytes(): Int = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE).toInt() }.getOrDefault(4096)

    /** Pure formatter for the `BENCHMARK native_compat:` evidence line. */
    fun reportLine(
        deviceAbis: List<String>,
        packaged: List<String>,
        is64BitProcess: Boolean,
        pageSize: Int,
    ): String =
        "BENCHMARK native_compat: deviceAbis=${deviceAbis.joinToString(",")} | packagedAbis=${packaged.joinToString(",")} | " +
            "loadedAbi=${loadedAbi(deviceAbis, packaged) ?: "none"} | process64=$is64BitProcess | pageSizeBytes=$pageSize | " +
            "simd=libaom runtime CPU detection (NEON baseline; dotprod/i8mm/SVE only when getauxval AT_HWCAP reports them)"

    fun currentReportLine(): String = reportLine(
        Build.SUPPORTED_ABIS.toList(),
        packagedAbis(),
        android.os.Process.is64Bit(),
        pageSizeBytes(),
    )

    /** This area's registry entries (joined by DevicePolicyRegistry). */
    val policyEntries: List<DevicePolicyEntry> get() = NativeCompatEntries.entries
}

/** Build-time: libbluetooth_video_native.so is linked with 16 KB max and common page size. */
internal data object SixteenKbLinkLayout : DevicePolicyEffect {
    override fun describe(): String =
        "libbluetooth_video_native.so linked with -z max-page-size=16384 and -z common-page-size=16384 (build time)"
}

/** libaom selects its kernels at run time from the CPU's reported features. */
internal data object NativeSimdRuntimeDetection : DevicePolicyEffect {
    override fun describe(): String = "libaom: Armv8 NEON baseline, dotprod/i8mm/SVE only when AT_HWCAP reports them"
}

/** Proposed build option: ship armeabi-v7a as well (-PenableArmeabiV7a=true, default off). */
internal data object ArmeabiV7aOptionalBuild : DevicePolicyEffect {
    override fun describe(): String = "optional armeabi-v7a native library for 32-bit userspace phones (build time)"
}

internal object NativeCompatEntries {
    private const val REVIEWED = "2026-10-10"
    private const val AOM = "https://aomedia.googlesource.com/aom/+/0006e96cdb595ff88950a2c664115335ca5517bf"

    private val PAGE_SIZES = PolicySource(
        PolicySourceKind.PLATFORM_DOC,
        "https://developer.android.com/guide/practices/page-sizes",
        "NDK r28 and higher compile 16 KB-aligned by default; AGP 8.5.1+ with uncompressed libraries gives 16 KB zip " +
            "alignment; check zipalign -c -P 16 and that each LOAD segment is 2**14 or higher; the RELRO end must satisfy " +
            "(VirtAddr + MemSiz) % 0x4000 == 0 or the library crashes on a 16 KB device; Play requires 16 KB support for " +
            "apps targeting API 35+ from 2027-02-01",
    )
    private val AOM_ARM64_DETECT = PolicySource(
        PolicySourceKind.GOOGLE_OPEN_SOURCE,
        "$AOM/aom_ports/aarch64_cpudetect.c",
        "AArch64 NEON is a baseline (mandatory in Armv8.0-A); CRC32, dotprod, i8mm, SVE and SVE2 are enabled only when " +
            "getauxval(AT_HWCAP/AT_HWCAP2) reports them (the __linux__ branch)",
    )
    private val AOM_CPU_HEADER = PolicySource(
        PolicySourceKind.GOOGLE_OPEN_SOURCE,
        "$AOM/aom_ports/arm_cpudetect.h",
        "the NDK cpu-features library is used only when __ANDROID_API__ < 18; this app has minSdk 29, so getauxval is used",
    )
    private val AOM_ARM32_DETECT = PolicySource(
        PolicySourceKind.GOOGLE_OPEN_SOURCE,
        "$AOM/aom_ports/aarch32_cpudetect.c",
        "Armv7 NEON is detected at run time from getauxval(AT_HWCAP) & HWCAP_NEON; without it the C paths run",
    )
    private val AOM_RTCD_DEFAULT = PolicySource(
        PolicySourceKind.GOOGLE_OPEN_SOURCE,
        "$AOM/cmake/aom_config_defaults.cmake",
        "CONFIG_RUNTIME_CPU_DETECT defaults to 1 (runtime CPU detection)",
    )
    private val ABI_GUIDE = PolicySource(
        PolicySourceKind.PLATFORM_DOC,
        "https://developer.android.com/ndk/guides/abis",
        "armeabi-v7a is the 32-bit ARM ABI, arm64-v8a the 64-bit one; a device installs and loads native code only for an " +
            "ABI the APK ships, picked in the device's preference order",
    )

    val entries: List<DevicePolicyEntry> = listOf(
        DevicePolicyEntry(
            id = "native-16kb-page-alignment",
            title = "16 KB page-size compatible native library",
            matcher = DeviceMatcher(),
            effects = listOf(SixteenKbLinkLayout),
            behaviour = "libbluetooth_video_native.so is linked with 16 KB max and common page size so every LOAD segment " +
                "and the RELRO end are 16 KB aligned, and AGP stores it uncompressed and 16 KB zip-aligned. Build time; " +
                "the flags are harmless on 4 KB devices, so the entry is universal and listed so the fact is in the one register.",
            sources = listOf(PAGE_SIZES),
            sourceStatus = PolicySourceStatus.OFFICIAL,
            evidence = listOf(
                "tools/check_apk_16kb.py and zipalign -c -P 16 on the built debug and release APKs (2026-10-10, NDK " +
                    "28.2.13676358, AGP 8.13.0-rc01): LOAD align 0x4000 and RELRO end remainder 0 after the flag. The " +
                    "pre-fix debug library failed Google's documented RELRO check ((VirtAddr + MemSiz) % 0x4000 == 0, " +
                    "remainder 8192); release passed it. Whether a 16 KB device would have faulted on it was not tested",
            ),
            confidence = PolicyConfidence.HIGH,
            reviewed = REVIEWED,
            origin = "new: explicit target_link_options in app/src/main/cpp/CMakeLists.txt",
            confidenceNote = "Verified statically on the APKs; not run on a 16 KB device or emulator.",
            area = PolicyArea.NATIVE,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            fallback = "NDK r28 defaults (LOAD segments aligned, RELRO end layout-dependent)",
            legacySwitches = listOf("build time: remove the target_link_options block in CMakeLists.txt"),
        ),
        DevicePolicyEntry(
            id = "native-simd-runtime-detection",
            title = "libaom selects SIMD kernels at run time",
            matcher = DeviceMatcher(),
            effects = listOf(NativeSimdRuntimeDetection),
            behaviour = "libaom starts from the Armv8 NEON baseline and uses dotprod, i8mm, SVE or SVE2 kernels only when the " +
                "kernel reports the CPU feature, so an older arm64 core never runs an unsupported instruction. The app's JNI " +
                "file has no architecture-specific code.",
            sources = listOf(AOM_ARM64_DETECT, AOM_CPU_HEADER, AOM_RTCD_DEFAULT),
            sourceStatus = PolicySourceStatus.OFFICIAL,
            evidence = listOf(
                "generated aom_config.h of the arm64-v8a build (2026-10-10): CONFIG_RUNTIME_CPU_DETECT 1, HAVE_NEON 1, " +
                    "HAVE_NEON_DOTPROD/I8MM/SVE/SVE2 1 (compiled, hwcap-gated)",
            ),
            confidence = PolicyConfidence.HIGH,
            reviewed = REVIEWED,
            origin = "audit of the shipped native libraries; no code changed",
            confidenceNote = "From source and the generated config; no phone without dotprod/SVE ran the encoder.",
            area = PolicyArea.NATIVE,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            fallback = "AOM_SIMD_CAPS / AOM_SIMD_CAPS_MASK environment variables force a feature set (debugging only)",
            legacySwitches = listOf(
                "none at app level; AOM_SIMD_CAPS_MASK=0 in the process environment for debugging",
                "Experimental native-simd-safe-mode (test_native_simd_safe_mode=true) sets AOM_SIMD_CAPS_MASK=0x3 after a native crash at encoder start",
            ),
        ),
        DevicePolicyEntry(
            id = "native-armeabi-v7a-optional-build",
            title = "Optional armeabi-v7a native library for 32-bit phones",
            matcher = DeviceMatcher(socFamilies = setOf(SocFamily.UNISOC, SocFamily.MEDIATEK), sdkMin = 29),
            effects = listOf(ArmeabiV7aOptionalBuild),
            behaviour = "Proposed, build-time only: -PenableArmeabiV7a=true adds armeabi-v7a (libaom with NEON) so phones whose " +
                "ABI list lacks arm64-v8a (32-bit userspace, for example some Android Go phones on budget Unisoc/MediaTek " +
                "SoCs) can install. Reported on those SoC families as a prompt to decide; default off, shipped ABIs unchanged. " +
                "Measured cost: release APK 10.7 -> 17.6 MB (+6.9 MB stored, about +2.6 MB compressed); speed on 32-bit " +
                "devices unmeasured.",
            sources = listOf(ABI_GUIDE, AOM_ARM32_DETECT),
            sourceStatus = PolicySourceStatus.PARTIAL,
            evidence = listOf("none: no owned phone is 32-bit; size measured from an armeabi-v7a build on 2026-10-10"),
            confidence = PolicyConfidence.LOW,
            reviewed = REVIEWED,
            origin = "new proposal for devices the project does not own",
            confidenceNote = "Which budget phones are 32-bit is not derivable from SoC family alone (ABI list is the fact). " +
                "Software AV1 on 32-bit cores is expected to be much slower; the speed governor then settles at cpu-used 10.",
            area = PolicyArea.NATIVE,
            proposed = true,
            validation = PolicyValidation.EXPERIMENTAL,
            fallback = "arm64-v8a only: a device without arm64 cannot install the app",
            legacySwitches = listOf("Gradle -PenableArmeabiV7a=true (default off)"),
        ),
    )
}
