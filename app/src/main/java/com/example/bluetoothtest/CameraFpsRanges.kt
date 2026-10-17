package com.example.bluetoothtest

import android.util.Log

/** An AE target fps range without Android types, so the chooser runs on the JVM. */
internal data class FpsRange(val lower: Int, val upper: Int) {
    override fun toString(): String = "$lower-$upper"
}

/**
 * AE target fps range choice (V-03) and the effective frame-rate ceiling of a configured stream set.
 *
 * The default rule is the one the encoder always used: the highest `upper` not above the limit, then
 * the highest `lower` (a fixed range such as [30,30]); with no range at or under the limit, the
 * lowest ceiling (a [7,30] range containing a 7 FPS limit delivered up to 30 FPS). AOSP
 * `CONTROL_AE_TARGET_FPS_RANGE` and CameraX `AeFpsRangeLegacyQuirk` (b/167425305): a LEGACY HAL
 * defaults to [30,30] and under-exposes dim scenes, so CameraX picks the smallest lower bound. That
 * rule changes exposure behaviour, so it is the Experimental `test_camera_legacy_ae_range` arm.
 */
internal object CameraFpsRanges {
    fun select(ranges: List<FpsRange>, maximumFps: Int, legacyLowestLower: Boolean = false): FpsRange? {
        val atOrUnder = ranges.filter { it.upper <= maximumFps }
        val best = atOrUnder.maxOfOrNull { it.upper }
        if (best != null) {
            val sameCeiling = atOrUnder.filter { it.upper == best }
            return if (legacyLowestLower) sameCeiling.minBy { it.lower } else sameCeiling.maxBy { it.lower }
        }
        // No range reaches down to the limit: the lowest-ceiling range, never one that lets the
        // camera run faster.
        val lowestCeiling = ranges.minOfOrNull { it.upper } ?: return null
        return ranges.filter { it.upper == lowestCeiling }.maxBy { it.lower }
    }

    /**
     * The frame rate the camera can really deliver: the range ceiling, capped by the slowest
     * configured stream (`StreamConfigurationMap.getOutputMinFrameDuration`: a big extra stream
     * silently caps a 60 FPS request). Zero or negative durations are unknown and ignored.
     */
    fun effectiveMaxFps(selected: FpsRange?, minFrameDurationsNs: List<Long>): Int? {
        val ceiling = selected?.upper
        val slowest = minFrameDurationsNs.filter { it > 0L }.maxOrNull()
        val streamCap = slowest?.let { (1_000_000_000L / it).toInt().coerceAtLeast(1) }
        return when {
            ceiling != null && streamCap != null -> minOf(ceiling, streamCap)
            else -> ceiling ?: streamCap
        }
    }
}

/**
 * Raw `INFO_SUPPORTED_HARDWARE_LEVEL` ints (the named enum is [CameraHardwareLevel] in DeviceTraitsProbe.kt). They are never compared numerically: the constants are LIMITED=0, FULL=1,
 * LEGACY=2, LEVEL_3=3, EXTERNAL=4 (Signal's CameraXUtil keeps an explicit order array for this).
 */
internal object CameraLevelCodes {
    const val LIMITED = 0
    const val FULL = 1
    const val LEGACY = 2
    const val LEVEL_3 = 3
    const val EXTERNAL = 4

    fun name(level: Int?): String = when (level) {
        LIMITED -> "LIMITED"
        FULL -> "FULL"
        LEGACY -> "LEGACY"
        LEVEL_3 -> "LEVEL_3"
        EXTERNAL -> "EXTERNAL"
        null -> "unknown"
        else -> "level_$level"
    }

    fun isLegacy(level: Int?): Boolean = level == LEGACY
}

/**
 * Camera characteristic reads can throw outside the documented exceptions on some HALs:
 * AssertionError (CONTROL_ZOOM_RATIO_RANGE on Jio/A02s/Vivo 2039, CameraX b/231701345),
 * BufferUnderflowException (FLASH_INFO_AVAILABLE on Spreadtrum, b/216667482) and NPE
 * (INFO_SUPPORTED_HARDWARE_LEVEL on some Redmi, Signal CameraXUtil). A failed read is "unknown".
 */
internal inline fun <T> safeCameraRead(what: String, read: () -> T?): T? = try {
    read()
} catch (error: VirtualMachineError) {
    throw error
} catch (error: Throwable) {
    // AssertionError is an Error, not an Exception: catch Throwable on purpose.
    runCatching { Log.w("BluetoothVideo", "Camera characteristic $what unreadable: ${error.javaClass.simpleName}") }
    null
}

/** The first camera id whose facing matches; ids whose characteristics cannot be read are skipped. */
internal fun pickCameraId(ids: Array<String>, facing: Int, facingOf: (String) -> Int?): String? =
    ids.firstOrNull { id -> safeCameraRead("LENS_FACING($id)") { facingOf(id) } == facing }
