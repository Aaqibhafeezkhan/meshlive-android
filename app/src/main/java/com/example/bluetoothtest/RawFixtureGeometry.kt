package com.example.bluetoothtest

/**
 * The geometry contract of a DEBUG raw I420 fixture (TESTING "The raw-media contract"): the file is tightly
 * packed 8-bit I420 whose frame size comes from the fixture's declared geometry, never from the encoder
 * the phone happened to open. First Auto-ladder dark-scene A/B: the ASUS compat VP9 path mapped a 640x360
 * request to its nearest camera mode 640x480, and a 640x360 fixture (300 frames, 103,680,000 bytes) read at
 * 640x480 is exactly 225 frames, so the size check passed and every frame was misaligned (green and pink
 * corruption on the viewer). A raw source is refused unless the file is a whole number of declared frames
 * and the encoder runs at the declared size; bytes are never reinterpreted and nothing is rescaled.
 */
internal data class RawFixtureCheck(val ok: Boolean, val frames: Long, val reason: String)

internal object RawFixtureGeometry {
    /** Bytes of one tightly packed I420 frame, or null for a geometry I420 cannot describe. */
    fun frameBytes(width: Int, height: Int): Long? =
        if (width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) width.toLong() * height * 3 / 2 else null

    /**
     * The fixture's declared geometry: the runner's `test_raw_width` and `test_raw_height`, else the launch's
     * requested `test_width` and `test_height` as they were before any camera-mode mapping; null when the
     * launch declared neither (the encoder size is then the only evidence, and is still validated).
     */
    fun declared(rawWidth: Int, rawHeight: Int, requestedWidth: Int, requestedHeight: Int): Pair<Int, Int>? = when {
        rawWidth > 0 && rawHeight > 0 -> rawWidth to rawHeight
        requestedWidth > 0 && requestedHeight > 0 -> requestedWidth to requestedHeight
        else -> null
    }

    /**
     * Whether the resolution and FPS pickers may change the session settings. They never may while a DEBUG raw
     * fixture is active: the fixture's declared geometry and rate are fixed, and a camera mode (the nearest to a
     * 640x360 request on a phone with no such mode is 640x480) must not replace them. Lane 11: the deferred
     * callback of `setSelection()` did exactly that and the declared 640x360 fixture was then refused.
     */
    fun pickersMayChangeSettings(rawInputActive: Boolean): Boolean = !rawInputActive

    /**
     * Whether the Start button's mode condition holds. The camera path needs the selected camera mode to carry the
     * requested resolution and rate. A raw fixture needs only the encoder: the pickers never move (see
     * [pickersMayChangeSettings]), so the spinner stays at the smallest camera mode, and requiring it to match made
     * every raw launch whose size is not the first camera mode a silent no-start (lane 11, ASUS). The raw size and
     * rate stay validated loudly by the setup-time encoder checks (setup_refused) and the encoder-start raw_source check.
     */
    fun startGateModeOk(rawInputActive: Boolean, selectedMode: DeviceVideoMode?, settings: VideoSettings): Boolean =
        rawInputActive || (selectedMode != null &&
            selectedMode.resolution == settings.resolution && settings.fps in selectedMode.fpsOptions)

    fun check(
        fileBytes: Long,
        declaredWidth: Int,
        declaredHeight: Int,
        encoderWidth: Int,
        encoderHeight: Int,
    ): RawFixtureCheck {
        val frameBytes = frameBytes(declaredWidth, declaredHeight)
            ?: return RawFixtureCheck(false, 0, "declared ${declaredWidth}x$declaredHeight is not an even I420 geometry")
        if (fileBytes < frameBytes || fileBytes % frameBytes != 0L) {
            return RawFixtureCheck(
                false, 0,
                "file is $fileBytes bytes, not a whole number of ${declaredWidth}x$declaredHeight I420 frames ($frameBytes bytes each)",
            )
        }
        val frames = fileBytes / frameBytes
        if (declaredWidth != encoderWidth || declaredHeight != encoderHeight) {
            return RawFixtureCheck(
                false, frames,
                "fixture is ${declaredWidth}x$declaredHeight but the encoder runs at ${encoderWidth}x$encoderHeight; " +
                    "no reinterpretation or rescaling",
            )
        }
        return RawFixtureCheck(true, frames, "exact")
    }

    /** One line of `BENCHMARK raw_source` evidence for a launch or an encoder start. */
    fun line(
        fileName: String,
        fileBytes: Long,
        declared: Pair<Int, Int>?,
        encoderWidth: Int,
        encoderHeight: Int,
        check: RawFixtureCheck,
    ): String =
        "BENCHMARK raw_source: status=${if (check.ok) "OK" else "REFUSED"} | file=$fileName | fileBytes=$fileBytes | " +
            "declared=${declared?.let { "${it.first}x${it.second}" } ?: "none"} | encoder=${encoderWidth}x$encoderHeight | " +
            "frames=${check.frames} | reason=${check.reason}"
}
