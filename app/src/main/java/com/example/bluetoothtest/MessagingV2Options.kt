package com.example.bluetoothtest

/*
 * Messaging v2 phase 1b: the attachment switches (docs/SWITCHES.md "Messaging v2") and the pure size arithmetic of the
 * photo path. Everything is inert unless the master switch `test_messaging_v2` is on. A DEBUG benchmark launch reads only
 * its own extras, so a stored choice never leaks into a measured case; the Settings rows are phase 1c.
 */

/** Who may decode a picture and where (Q-M12). */
enum class DecodeMode {
    /** A service with `android:isolatedProcess`: no permissions, no files of ours, killed after the decode. */
    ISOLATED,

    /** The control arm: the same bounds in the app process. Used when the isolated service cannot be bound. */
    IN_PROCESS,
}

data class MessagingV2AttachOptions(
    /** `test_mv2_autodownload_mb`: largest segmented file fetched without a tap; 0 never, 64 everything. */
    val autoDownloadMb: Int = 8,
    /** `test_mv2_max_file_mb`: largest file this phone sends. */
    val maxFileMb: Int = 64,
    /** `test_mv2_photo_edge`: longest edge of a re-encoded photo. */
    val photoEdge: Int = 1600,
    /** `test_mv2_photo_quality`: JPEG quality of a re-encoded photo. */
    val photoQuality: Int = 80,
    /** `test_mv2_auto_thumbnails` (Experimental, off): decode manifest thumbnails without a tap. Nothing reads it yet. */
    val autoThumbnails: Boolean = false,
    /** `test_mv2_decode_isolated`: tap-to-decode in the isolated process (the safer arm); false is the in-process control arm. */
    val isolatedDecode: Boolean = true,
) {
    val autoDownloadBytes: Long get() = autoDownloadMb * 1_000_000L
    val maxFileBytes: Long get() = maxFileMb * 1_000_000L
    val decodeMode: DecodeMode get() = if (isolatedDecode) DecodeMode.ISOLATED else DecodeMode.IN_PROCESS

    /** The limits object of the engine with the automatic-download size from this switch. */
    fun applyTo(limits: MessagingV2Limits): MessagingV2Limits = limits.copy(autoDownloadMaxBytes = autoDownloadBytes)

    companion object {
        const val EXTRA_AUTODOWNLOAD_MB = "test_mv2_autodownload_mb"
        const val EXTRA_MAX_FILE_MB = "test_mv2_max_file_mb"
        const val EXTRA_PHOTO_EDGE = "test_mv2_photo_edge"
        const val EXTRA_PHOTO_QUALITY = "test_mv2_photo_quality"
        const val EXTRA_AUTO_THUMBNAILS = "test_mv2_auto_thumbnails"
        const val EXTRA_DECODE_ISOLATED = "test_mv2_decode_isolated"

        /**
         * Values from launch extras, each clamped to what the design allows; an absent or unparsable extra keeps the default.
         * [int] and [bool] return null when the extra is absent.
         */
        fun fromExtras(int: (String) -> Int?, bool: (String) -> Boolean?): MessagingV2AttachOptions {
            val d = MessagingV2AttachOptions()
            return MessagingV2AttachOptions(
                autoDownloadMb = (int(EXTRA_AUTODOWNLOAD_MB) ?: d.autoDownloadMb).coerceIn(0, 64),
                maxFileMb = (int(EXTRA_MAX_FILE_MB) ?: d.maxFileMb).coerceIn(1, 64),
                photoEdge = (int(EXTRA_PHOTO_EDGE) ?: d.photoEdge).coerceIn(320, 4096),
                photoQuality = (int(EXTRA_PHOTO_QUALITY) ?: d.photoQuality).coerceIn(30, 95),
                autoThumbnails = bool(EXTRA_AUTO_THUMBNAILS) ?: d.autoThumbnails,
                isolatedDecode = bool(EXTRA_DECODE_ISOLATED) ?: d.isolatedDecode,
            )
        }
    }
}

/** Process-wide attachment options, set once at launch (like [MessagingV2Feature]). */
object MessagingV2Options {
    const val EXTRA_READ_RECEIPTS = "test_mv2_read_receipts"

    @Volatile var current: MessagingV2AttachOptions = MessagingV2AttachOptions()

    /** The global read-receipt switch (Settings > Privacy; a DEBUG benchmark launch reads `test_mv2_read_receipts`). Off by default. */
    @Volatile var readReceipts: Boolean = false

    @Volatile private var benchmark = false

    /**
     * A benchmark launch reads its extras only; any other launch uses the user's stored Settings choices ([stored]; the defaults
     * when nothing was stored: files up to 8 MB without a tap, read receipts off).
     */
    fun configure(
        benchmarkLaunch: Boolean,
        int: (String) -> Int?,
        bool: (String) -> Boolean?,
        stored: MessagingV2UserChoices = MessagingV2UserChoices(),
    ) {
        benchmark = benchmarkLaunch
        if (benchmarkLaunch) {
            current = MessagingV2AttachOptions.fromExtras(int, bool)
            readReceipts = bool(EXTRA_READ_RECEIPTS) ?: false
        } else {
            current = MessagingV2AttachOptions(autoDownloadMb = stored.autoDownloadMb)
            readReceipts = stored.readReceipts
        }
    }

    /** The user changed a Settings > Privacy row: it applies to the running engine at once. A benchmark launch ignores it. */
    fun applyChoices(choices: MessagingV2UserChoices) {
        if (benchmark) return
        current = current.copy(autoDownloadMb = choices.autoDownloadMb)
        readReceipts = choices.readReceipts
        StoreForwardRuntime.nodeIfOpen()?.v2?.let {
            it.readReceipts = readReceipts
            it.autoDownloadMaxBytes = current.autoDownloadBytes
        }
    }
}

/** Why a file will not be sent (shown in the sheet before sending). */
sealed class AttachRefusal(val message: String) {
    class Empty : AttachRefusal("The file is empty")
    class TooLarge(val size: Long, val limit: Long) : AttachRefusal("The file is larger than the limit")
    class NotReadable(detail: String) : AttachRefusal("The file cannot be read: $detail")
}

object AttachmentPolicy {
    /** Null when [size] may be sent under [options]; the engine's own 64 MB ceiling still applies on top. */
    fun check(size: Long, options: MessagingV2AttachOptions): AttachRefusal? = when {
        size <= 0 -> AttachRefusal.Empty()
        size > minOf(options.maxFileBytes, MessagingV2Format.MAX_FILE_BYTES) -> AttachRefusal.TooLarge(size, minOf(options.maxFileBytes, MessagingV2Format.MAX_FILE_BYTES))
        else -> null
    }

    /** Estimated seconds to move [size] bytes over a link of [kbps] (the send sheet shows this from the learned rate). */
    fun estimateSeconds(size: Long, kbps: Int): Long =
        if (kbps <= 0) -1 else ((size * 8.0 * 1.04) / (kbps * 1000.0)).toLong().coerceAtLeast(1)
}

/** Photo path arithmetic: orientation, scaling and the sample size, with no Android classes so it is tested on the JVM. */
object PhotoGeometry {
    /** Pictures larger than this are not decoded on the sender either (the picker is the user's own, but a bomb is still a bomb). */
    const val SENDER_MAX_PIXELS = 150_000_000L

    class Target(val width: Int, val height: Int, val scaled: Boolean)

    /**
     * Size of the sent photo: the upright size (width and height swapped for orientations 5 to 8) scaled so the longest
     * edge is at most [edge], never enlarged.
     */
    fun target(width: Int, height: Int, orientation: Int, edge: Int): Target {
        require(width > 0 && height > 0 && edge > 0)
        val upright = if (ExifOrientation.swapsAxes(orientation)) height to width else width to height
        val longest = maxOf(upright.first, upright.second)
        if (longest <= edge) return Target(upright.first, upright.second, false)
        val scale = edge.toDouble() / longest
        return Target(
            maxOf(1, Math.round(upright.first * scale).toInt()),
            maxOf(1, Math.round(upright.second * scale).toInt()),
            true,
        )
    }

    /** Largest power-of-two `inSampleSize` that keeps the decoded longest edge at or above [edge]. */
    fun sampleSize(width: Int, height: Int, edge: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / (sample * 2) >= edge) sample *= 2
        return sample
    }

    /** True when the picture is already within [edge] and a JPEG with no orientation fix: it still goes through the re-encode (metadata). */
    fun withinBounds(width: Int, height: Int, edge: Int): Boolean = maxOf(width, height) <= edge

    /** Sample size for a tap-to-decode: the decoded edge stays within two times [displayEdge] (then scaled down to it). */
    fun displaySampleSize(width: Int, height: Int, displayEdge: Int): Int = sampleSize(width, height, displayEdge)

    /** JPEG thumbnail edge for the manifest (at most 320, M3). */
    fun thumbnailTarget(width: Int, height: Int, orientation: Int): Target = target(width, height, orientation, MessagingV2Format.THUMBNAIL_MAX_EDGE)
}
