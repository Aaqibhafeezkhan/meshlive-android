package com.example.bluetoothtest

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.system.Os
import android.system.OsConstants

/*
 * Runtime device traits (BACKLOG R-03): facts the phone reports about itself that device-family
 * matchers can use instead of Build.MODEL. Everything is read once, every read is individually
 * guarded (a hidden API, a vendor service or a missing package must never stop the app), and a
 * failed read is "unknown", which never satisfies a matcher constraint.
 *
 * Camera hardware levels are NOT read at start (opening the camera service costs time and can
 * trip the privacy indicator on some builds): they are read on first request through
 * [DeviceTraitsProbe.readCameraLevels].
 */

/** Camera2 INFO_SUPPORTED_HARDWARE_LEVEL as a name. The raw ints are not ordered (LEGACY is 2); never compare them. */
enum class CameraHardwareLevel {
    LEGACY, LIMITED, FULL, LEVEL_3, EXTERNAL, UNKNOWN;

    companion object {
        fun fromCamera2(value: Int?): CameraHardwareLevel = when (value) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> LEGACY
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> LIMITED
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> FULL
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> LEVEL_3
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> EXTERNAL
            else -> UNKNOWN
        }
    }
}

/** What [DeviceTraitsProbe] learned; null / empty means the read failed or the fact does not exist on this phone. */
internal data class DeviceRuntimeTraits(
    /** Vendor ROM version properties that returned a value (name -> value). The names are UNVERIFIED, see [DeviceTraitsProbe.ROM_PROPERTIES]. */
    val romProperties: Map<String, String> = emptyMap(),
    val supportedAbis: List<String> = emptyList(),
    val pageSizeBytes: Int? = null,
    val totalMemBytes: Long? = null,
    val lowRamDevice: Boolean? = null,
    val memoryClassMb: Int? = null,
    /** Build.VERSION.MEDIA_PERFORMANCE_CLASS (API 31+); 0 = the phone declares no class. */
    val mediaPerformanceClass: Int? = null,
    /** Bluetooth package versionName (package -> version); a package that is hidden or absent is not listed. */
    val bluetoothPackageVersions: Map<String, String> = emptyMap(),
    val audioLowLatency: Boolean? = null,
    val audioPro: Boolean? = null,
    val outputSampleRateHz: Int? = null,
    val aecAvailable: Boolean? = null,
    val nsAvailable: Boolean? = null,
    val fingerprint: String = "",
    /** Camera id -> level, read on first request only; null = not read yet. */
    val cameraHardwareLevels: Map<String, CameraHardwareLevel>? = null,
) {
    fun describe(): String = listOf(
        "rom=" + if (romProperties.isEmpty()) "none" else
            "unverified{" + romProperties.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value.clean()}" } + "}",
        "abis=${supportedAbis.joinToString("+").ifEmpty { "?" }}",
        "pageSize=${pageSizeBytes ?: "?"}",
        "totalMemMb=${totalMemBytes?.let { it / (1024 * 1024) } ?: "?"}",
        "lowRam=${lowRamDevice.flag()}",
        "memClassMb=${memoryClassMb ?: "?"}",
        "mpc=${mediaPerformanceClass ?: "?"}",
        "btPkg=" + if (bluetoothPackageVersions.isEmpty()) "?" else
            bluetoothPackageVersions.toSortedMap().entries.joinToString("+") { "${it.key}:${it.value.clean()}" },
        "lowLatencyAudio=${audioLowLatency.flag()}",
        "proAudio=${audioPro.flag()}",
        "outRateHz=${outputSampleRateHz ?: "?"}",
        "aec=${aecAvailable.flag()}",
        "ns=${nsAvailable.flag()}",
        "camLevels=" + (cameraHardwareLevels?.let { levels ->
            if (levels.isEmpty()) "none" else levels.toSortedMap().entries.joinToString("+") { "${it.key}:${it.value.name}" }
        } ?: "unread"),
        "fingerprint=${fingerprint.clean().ifEmpty { "?" }}",
    ).joinToString(";")

    private fun Boolean?.flag(): String = when (this) {
        true -> "1"
        false -> "0"
        null -> "?"
    }

    /** Keeps the device_policy line parseable: ';' separates traits and '|' separates sections. */
    private fun String.clean(): String = replace(';', '_').replace('|', '_').replace(' ', '_')
}

/**
 * Constraints on [DeviceRuntimeTraits]; every set field must hold. A phone whose runtime traits are
 * not read, or whose needed fact is unknown, never satisfies a constrained field.
 */
internal data class RuntimeMatcher(
    val lowRamDevice: Boolean? = null,
    /** True when the declared class is <= this value; 0 (no class declared) counts as 0. */
    val mediaPerformanceClassAtMost: Int? = null,
    val pageSizeAtLeastBytes: Int? = null,
    val audioLowLatency: Boolean? = null,
    val aecAvailable: Boolean? = null,
    /** At least one of these ROM properties returned a value (names UNVERIFIED, see [DeviceTraitsProbe.ROM_PROPERTIES]). */
    val romPropertiesAny: Set<String> = emptySet(),
    /** At least one camera reports one of these levels (needs the lazy camera read). */
    val anyCameraLevelIn: Set<CameraHardwareLevel> = emptySet(),
) {
    fun matches(runtime: DeviceRuntimeTraits?): Boolean {
        if (runtime == null) return false
        if (lowRamDevice != null && runtime.lowRamDevice != lowRamDevice) return false
        if (mediaPerformanceClassAtMost != null &&
            (runtime.mediaPerformanceClass == null || runtime.mediaPerformanceClass > mediaPerformanceClassAtMost)
        ) return false
        if (pageSizeAtLeastBytes != null &&
            (runtime.pageSizeBytes == null || runtime.pageSizeBytes < pageSizeAtLeastBytes)
        ) return false
        if (audioLowLatency != null && runtime.audioLowLatency != audioLowLatency) return false
        if (aecAvailable != null && runtime.aecAvailable != aecAvailable) return false
        if (romPropertiesAny.isNotEmpty() && romPropertiesAny.none { it in runtime.romProperties }) return false
        if (anyCameraLevelIn.isNotEmpty() &&
            (runtime.cameraHardwareLevels == null || runtime.cameraHardwareLevels.values.none { it in anyCameraLevelIn })
        ) return false
        return true
    }

    fun describe(): String = listOfNotNull(
        lowRamDevice?.let { "lowRam=$it" },
        mediaPerformanceClassAtMost?.let { "mediaPerformanceClass<=$it" },
        pageSizeAtLeastBytes?.let { "pageSize>=$it" },
        audioLowLatency?.let { "lowLatencyAudio=$it" },
        aecAvailable?.let { "aec=$it" },
        romPropertiesAny.takeIf { it.isNotEmpty() }?.let { "ROM property in ${it.sorted()} (unverified names)" },
        anyCameraLevelIn.takeIf { it.isNotEmpty() }?.let { "camera level in ${it.map(CameraHardwareLevel::name).sorted()}" },
    ).joinToString(",")
}

/** Where the probe reads from; a JVM test injects a fake. Any method may throw: the probe guards each call. */
internal interface DeviceTraitSource {
    fun systemProperty(name: String): String?
    fun supportedAbis(): List<String>
    fun pageSizeBytes(): Int?
    fun totalMemBytes(): Long?
    fun isLowRamDevice(): Boolean?
    fun memoryClassMb(): Int?
    fun mediaPerformanceClass(): Int?
    fun packageVersionName(packageName: String): String?
    fun hasSystemFeature(name: String): Boolean?
    fun outputSampleRateHz(): Int?
    fun aecAvailable(): Boolean?
    fun nsAvailable(): Boolean?
    fun fingerprint(): String?
    fun cameraIds(): List<String>
    fun cameraHardwareLevel(id: String): Int?
}

internal object DeviceTraitsProbe {
    /**
     * Vendor ROM version properties. UNVERIFIED: the names come from secondary reports (BACKLOG R-03:
     * oem-bt.md F1/F2/S2-09) and have not been checked with getprop on the six benchmark phones, so
     * they are logged as `rom=unverified{...}` and no entry may rely on one until a phone owner has
     * compared a real getprop dump.
     */
    val ROM_PROPERTIES: List<String> = listOf(
        "ro.build.version.oneui",
        "ro.miui.ui.version.name",
        "ro.mi.os.version.name",
        "ro.build.version.opporom",
        "ro.build.version.oplusrom",
        "ro.build.version.emui",
        "ro.vivo.os.version",
        "ro.tranos.version",
    )

    val BLUETOOTH_PACKAGES: List<String> = listOf("com.android.bluetooth", "com.google.android.bluetooth")

    private const val FEATURE_AUDIO_LOW_LATENCY = "android.hardware.audio.low_latency"
    private const val FEATURE_AUDIO_PRO = "android.hardware.audio.pro"

    @Volatile private var cached: DeviceRuntimeTraits? = null

    /** The last probe result, or null before [attach]. */
    fun current(): DeviceRuntimeTraits? = cached

    /** `device_policy` line fragment: the probe result, or `unread`. */
    fun describeCurrent(): String = cached?.describe() ?: "unread"

    /**
     * Reads the phone once (idempotent) and attaches the result to [DeviceTraits] so matchers can
     * use it. Call from the main activity next to [DeviceTraits.attachBluetoothAdapter].
     */
    fun attach(context: Context) {
        if (cached != null) return
        val read = read(AndroidDeviceTraitSource(context.applicationContext))
        synchronized(this) {
            if (cached == null) cached = read
        }
        DeviceTraits.attachRuntime(cached)
    }

    /**
     * Reads camera hardware levels once on first request (V-02 diagnostics, camera entries) and
     * re-attaches the runtime traits. Returns the levels, empty when the camera service failed.
     */
    fun readCameraLevels(context: Context): Map<String, CameraHardwareLevel> {
        cached?.cameraHardwareLevels?.let { return it }
        attach(context)
        val levels = readCameraLevels(AndroidDeviceTraitSource(context.applicationContext))
        synchronized(this) {
            cached = cached?.copy(cameraHardwareLevels = levels) ?: DeviceRuntimeTraits(cameraHardwareLevels = levels)
        }
        DeviceTraits.attachRuntime(cached)
        return levels
    }

    /** The pure read: every field independent, every failure (including Errors from missing APIs) becomes "unknown". */
    fun read(source: DeviceTraitSource): DeviceRuntimeTraits = DeviceRuntimeTraits(
        romProperties = ROM_PROPERTIES.mapNotNull { name ->
            guarded { source.systemProperty(name) }?.takeIf { it.isNotBlank() }?.let { name to it.trim() }
        }.toMap(),
        supportedAbis = guarded { source.supportedAbis() }.orEmpty(),
        pageSizeBytes = guarded { source.pageSizeBytes() }?.takeIf { it > 0 },
        totalMemBytes = guarded { source.totalMemBytes() }?.takeIf { it > 0 },
        lowRamDevice = guarded { source.isLowRamDevice() },
        memoryClassMb = guarded { source.memoryClassMb() }?.takeIf { it > 0 },
        mediaPerformanceClass = guarded { source.mediaPerformanceClass() },
        bluetoothPackageVersions = BLUETOOTH_PACKAGES.mapNotNull { pkg ->
            guarded { source.packageVersionName(pkg) }?.takeIf { it.isNotBlank() }?.let { pkg to it }
        }.toMap(),
        audioLowLatency = guarded { source.hasSystemFeature(FEATURE_AUDIO_LOW_LATENCY) },
        audioPro = guarded { source.hasSystemFeature(FEATURE_AUDIO_PRO) },
        outputSampleRateHz = guarded { source.outputSampleRateHz() }?.takeIf { it > 0 },
        aecAvailable = guarded { source.aecAvailable() },
        nsAvailable = guarded { source.nsAvailable() },
        fingerprint = guarded { source.fingerprint() }.orEmpty(),
    )

    /** Camera levels for every id; a camera whose characteristics fail reads as UNKNOWN, a failed id list as empty. */
    fun readCameraLevels(source: DeviceTraitSource): Map<String, CameraHardwareLevel> =
        guarded { source.cameraIds() }.orEmpty().associateWith { id ->
            CameraHardwareLevel.fromCamera2(guarded { source.cameraHardwareLevel(id) })
        }

    /** Runs [block]; Throwable (AssertionError, NPE, NoSuchMethodError from a missing API) becomes null. Only VM errors propagate. */
    private inline fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (error: VirtualMachineError) {
        throw error
    } catch (_: Throwable) {
        null
    }
}

private class AndroidDeviceTraitSource(private val context: Context) : DeviceTraitSource {
    override fun systemProperty(name: String): String? {
        val type = Class.forName("android.os.SystemProperties")
        return type.getMethod("get", String::class.java, String::class.java).invoke(null, name, "") as? String
    }

    override fun supportedAbis(): List<String> = Build.SUPPORTED_ABIS.toList()

    override fun pageSizeBytes(): Int? = Os.sysconf(OsConstants._SC_PAGESIZE).toInt()

    override fun totalMemBytes(): Long? = ActivityManager.MemoryInfo().also { activityManager().getMemoryInfo(it) }.totalMem

    override fun isLowRamDevice(): Boolean? = activityManager().isLowRamDevice

    override fun memoryClassMb(): Int? = activityManager().memoryClass

    override fun mediaPerformanceClass(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.VERSION.MEDIA_PERFORMANCE_CLASS else null

    @Suppress("DEPRECATION")
    override fun packageVersionName(packageName: String): String? =
        context.packageManager.getPackageInfo(packageName, 0).versionName

    override fun hasSystemFeature(name: String): Boolean? = context.packageManager.hasSystemFeature(name)

    override fun outputSampleRateHz(): Int? =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()

    override fun aecAvailable(): Boolean? = AcousticEchoCanceler.isAvailable()

    override fun nsAvailable(): Boolean? = NoiseSuppressor.isAvailable()

    override fun fingerprint(): String? = Build.FINGERPRINT

    override fun cameraIds(): List<String> =
        (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList.toList()

    override fun cameraHardwareLevel(id: String): Int? =
        (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(id)
            .get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)

    private fun activityManager(): ActivityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
}
