package com.example.bluetoothtest

import kotlin.math.ceil

/**
 * DEBUG-only radio-free encoder bench (`test_encoder_bench`, default off, Experimental; docs/SWITCHES.md).
 *
 * The bench feeds the declared native raw-motion fixture (the same `test_raw_yuv` input the benchmark suite
 * uses for streams and calls) through the phone's real selected encoder ([CameraVideoEncoder] in its raw-input
 * mode, so MediaCodec configuration, rate control and libaom setup are the app's own), with no Bluetooth,
 * advertising or scanning, and logs what the encoder produced. This file is the pure part: extra parsing, the
 * requested-versus-effective mapping, the per-second meter and the log lines. Nothing here touches Android.
 *
 * Rule: a setting that cannot be applied is never changed to something else without saying so. Statically known
 * impossibilities refuse the run (`result=refused`, with `requested` and the encoder's `effective` default);
 * settings the encoder may accept or ignore (intra refresh) are passed through and the read-back from the
 * encoder's output format becomes the `effective` value.
 */
internal enum class BenchBitrateMode(val token: String, val codecMode: Int?) {
    AUTO("auto", null),
    CQ("cq", 0),
    VBR("vbr", 1),
    CBR("cbr", 2),
    CBR_FD("cbr_fd", 3),
    ;

    companion object {
        fun fromToken(token: String): BenchBitrateMode? =
            entries.firstOrNull { it.token == token.trim().lowercase().replace('-', '_') }

        fun fromCodecMode(mode: Int): BenchBitrateMode? = entries.firstOrNull { it.codecMode == mode }
    }
}

internal data class EncoderBenchRequest(
    /** Null or "auto": the app's own first (best-ranked) encoder. Else av1, vp9, libaom-native, bench-only hevc/avc, a mime, an exact encoder name. */
    val codec: String?,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val bitrateMode: BenchBitrateMode,
    /** MediaFormat intra-refresh-period in frames; null leaves periodic keyframes. */
    val intraRefreshFrames: Int?,
    /** Null keeps the app's own interval ([APP_KEY_FRAME_INTERVAL_SECONDS]). */
    val keyFrameIntervalSeconds: Int?,
    val durationSeconds: Int,
    val libaomCpuUsed: Int?,
    val libaomThreads: Int?,
    val rawPath: String,
    val rawWidth: Int?,
    val rawHeight: Int?,
)

/**
 * The per-run encoder overrides [CameraVideoEncoder] accepts from the bench (null everywhere else, so product
 * behaviour is unchanged). A pinned libaom cpu-used is not adapted by the speed governor during the run.
 */
data class EncoderBenchOverrides(
    val keyFrameIntervalSeconds: Int?,
    val libaomCpuUsed: Int?,
    val libaomThreads: Int?,
)

internal data class EncoderBenchParse(val request: EncoderBenchRequest?, val issues: List<String>)

/** What a candidate encoder offers, read from the platform by the Activity and passed in as plain data. */
internal data class EncoderBenchCaps(
    val encoderName: String,
    val mime: String,
    val hardware: Boolean,
    val nativeLibaom: Boolean,
    /** Platform-reported bitrate modes (BITRATE_MODE_CQ 0, VBR 1, CBR 2, CBR_FD 3). */
    val supportedModes: Set<Int>,
    /** The mode Automatic resolves to on this encoder (the app's own default), null when none. */
    val defaultMode: Int?,
    /** VideoCapabilities.areSizeAndRateSupported; null when unknown (native libaom). */
    val sizeAndRateSupported: Boolean?,
    /** FEATURE_IntraRefresh; null when unknown. */
    val intraRefreshFeature: Boolean?,
)

internal data class EncoderBenchPlan(
    val refusals: List<String>,
    val requested: List<Pair<String, String>>,
    /** What the encoder will use, decided before it starts; replaced by [EncoderBenchSpec.effectiveAfterStart]. */
    val effective: List<Pair<String, String>>,
    /** Value for [DebugTunables.bitrateModeOverride] (MediaCodec only); null leaves the encoder's resolved mode. */
    val bitrateModeOverride: Int?,
    val rateControl: EncoderRateControl,
)

/** Read-back after the encoder started: its output format and the live libaom state. */
internal data class EncoderBenchReadback(
    val outputIntraRefreshPeriod: Int?,
    val outputBitrateMode: Int?,
    val outputBitrateBps: Int?,
    val outputFrameRate: Int?,
    val outputIFrameInterval: Int?,
    val libaomCpuUsed: Int?,
    val libaomThreads: Int?,
    val rawInputDrops: Long,
)

/**
 * A candidate for codec resolution, in the app's own priority order. [benchOnly] marks HEVC/AVC encoders that exist
 * for radio-free measurement only (they are not product codecs): "auto" never picks them.
 */
internal data class BenchCodecCandidate(
    val encoderName: String,
    val mime: String,
    val nativeLibaom: Boolean,
    val benchOnly: Boolean = false,
)

internal object EncoderBenchSpec {
    const val EXTRA = "test_encoder_bench"
    const val MIME_AV1 = "video/av01"
    const val MIME_VP9 = "video/x-vnd.on2.vp9"
    /** HEVC and AVC are bench-only measurement codecs (Q39 ladder preparation); no product path uses them. */
    const val MIME_HEVC = "video/hevc"
    const val MIME_AVC = "video/avc"

    /** True for the bench-only HEVC/AVC mimes. */
    fun isBenchOnlyMime(mime: String): Boolean = mime == MIME_HEVC || mime == MIME_AVC
    /** The app's own key-frame interval (VideoSettings.keyFrameIntervalSeconds), reported when none is requested. */
    const val APP_KEY_FRAME_INTERVAL_SECONDS = 60
    const val MAX_DURATION_SECONDS = 600

    /** The bench runs only in a DEBUG build and only for an explicit true. */
    fun requested(debug: Boolean, extras: Map<String, Any?>): Boolean =
        debug && toBoolean(extras[EXTRA]) == true

    /**
     * Parses the launch extras (`adb shell am start ... --ez/--ei/--es`). Reuses the benchmark suite's extras
     * (`test_width`, `test_height`, `test_fps`, `test_bitrate_kbps`, `test_bitrate_mode`, `test_intra_refresh_frames`,
     * `test_raw_yuv`, `test_raw_width`, `test_raw_height`) and adds `test_bench_codec`, `test_bench_bitrate_mode`
     * (auto|cq|vbr|cbr|cbr_fd), `test_bench_keyframe_s`, `test_bench_duration_s`, `test_bench_libaom_cpu_used`
     * and `test_bench_libaom_threads`. An invalid or out-of-range value is an issue, never a silent default.
     */
    fun parse(extras: Map<String, Any?>): EncoderBenchParse {
        val issues = mutableListOf<String>()

        fun intOf(key: String): Int? {
            val value = extras[key] ?: return null
            return toInt(value) ?: run {
                issues += "$key is not an integer: $value"
                null
            }
        }

        fun ranged(key: String, range: IntRange, default: Int? = null): Int? {
            val value = intOf(key) ?: return default
            if (value !in range) {
                issues += "$key=$value is outside ${range.first}..${range.last}"
                return null
            }
            return value
        }

        val rawPath = (extras["test_raw_yuv"] as? String)?.trim().orEmpty()
        if (rawPath.isEmpty()) {
            issues += "test_raw_yuv is required: the bench encodes the declared raw fixture and never uses a camera"
        }
        val rawWidth = intOf("test_raw_width")?.takeIf { it > 0 }
        val rawHeight = intOf("test_raw_height")?.takeIf { it > 0 }
        val width = ranged("test_width", 2..8192) ?: rawWidth
        val height = ranged("test_height", 2..8192) ?: rawHeight
        if (width == null || height == null) {
            issues += "no geometry: pass test_width/test_height or test_raw_width/test_raw_height"
        } else if (width % 2 != 0 || height % 2 != 0) {
            issues += "${width}x$height is not an even I420 geometry"
        }
        val fps = ranged("test_fps", 1..120, default = 30)
        val bitrateKbps = ranged("test_bitrate_kbps", 1..100_000, default = 400)

        val mode = parseMode(extras, issues)
        val intraRefresh = ranged("test_intra_refresh_frames", 2..600)
        val keyFrame = ranged("test_bench_keyframe_s", 1..3600)
        val duration = ranged("test_bench_duration_s", 1..MAX_DURATION_SECONDS, default = 20)
        val cpuUsed = ranged("test_bench_libaom_cpu_used", 0..10)
        val threads = ranged("test_bench_libaom_threads", 1..16)
        val codec = (extras["test_bench_codec"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        if (issues.isNotEmpty() || width == null || height == null || fps == null || bitrateKbps == null ||
            duration == null
        ) {
            return EncoderBenchParse(null, issues)
        }
        return EncoderBenchParse(
            EncoderBenchRequest(
                codec = codec,
                width = width,
                height = height,
                fps = fps,
                bitrateKbps = bitrateKbps,
                bitrateMode = mode,
                intraRefreshFrames = intraRefresh,
                keyFrameIntervalSeconds = keyFrame,
                durationSeconds = duration,
                libaomCpuUsed = cpuUsed,
                libaomThreads = threads,
                rawPath = rawPath,
                rawWidth = rawWidth,
                rawHeight = rawHeight,
            ),
            emptyList(),
        )
    }

    private fun parseMode(extras: Map<String, Any?>, issues: MutableList<String>): BenchBitrateMode {
        val token = extras["test_bench_bitrate_mode"]
        if (token != null) {
            return (token as? String)?.let(BenchBitrateMode::fromToken) ?: run {
                issues += "test_bench_bitrate_mode=$token is not auto|cq|vbr|cbr|cbr_fd"
                BenchBitrateMode.AUTO
            }
        }
        val legacy = extras["test_bitrate_mode"] ?: return BenchBitrateMode.AUTO
        val code = toInt(legacy)
        return code?.let(BenchBitrateMode::fromCodecMode)?.takeIf { it != BenchBitrateMode.AUTO } ?: run {
            issues += "test_bitrate_mode=$legacy is not 0 (CQ), 1 (VBR), 2 (CBR) or 3 (CBR_FD)"
            BenchBitrateMode.AUTO
        }
    }

    /**
     * Picks the encoder for the requested codec from [candidates] (already in the app's priority order).
     * Returns the candidate, or a reason when none matches; "svt-av1" is recognised and reported as not built.
     */
    fun resolveCodec(token: String?, candidates: List<BenchCodecCandidate>): Pair<BenchCodecCandidate?, String?> {
        val key = token?.trim()?.lowercase().orEmpty()
        val match: BenchCodecCandidate? = when (key) {
            "", "auto" -> candidates.firstOrNull { !it.benchOnly }
            "av1" -> candidates.firstOrNull { it.mime == MIME_AV1 }
            "vp9" -> candidates.firstOrNull { it.mime == MIME_VP9 }
            "hevc", "h265", "h.265" -> candidates.firstOrNull { it.mime == MIME_HEVC }
            "avc", "h264", "h.264" -> candidates.firstOrNull { it.mime == MIME_AVC }
            MIME_AV1, "video/av1" -> candidates.firstOrNull { it.mime == MIME_AV1 }
            MIME_VP9 -> candidates.firstOrNull { it.mime == MIME_VP9 }
            MIME_HEVC -> candidates.firstOrNull { it.mime == MIME_HEVC }
            MIME_AVC -> candidates.firstOrNull { it.mime == MIME_AVC }
            "libaom", "libaom-native" -> candidates.firstOrNull { it.nativeLibaom }
            "svt-av1", "svtav1", "svt_av1" ->
                return null to "svt_av1_not_built: SVT-AV1 is not in this build; add it before comparing"
            else -> candidates.firstOrNull { it.encoderName.equals(token?.trim(), ignoreCase = true) }
        }
        return if (match != null) {
            match to null
        } else {
            null to "codec_unavailable: no encoder on this phone matches '${token ?: "auto"}'"
        }
    }

    /** Requested settings as the log prints them. */
    fun requestedPairs(request: EncoderBenchRequest): List<Pair<String, String>> = listOf(
        "codec" to (request.codec ?: "auto"),
        "width" to request.width.toString(),
        "height" to request.height.toString(),
        "fps" to request.fps.toString(),
        "bitrate_kbps" to request.bitrateKbps.toString(),
        "mode" to request.bitrateMode.token,
        "intra_refresh_frames" to (request.intraRefreshFrames?.toString() ?: "none"),
        "keyframe_s" to (request.keyFrameIntervalSeconds?.toString() ?: "app_default"),
        "duration_s" to request.durationSeconds.toString(),
        "libaom_cpu_used" to (request.libaomCpuUsed?.toString() ?: "app_default"),
        "libaom_threads" to (request.libaomThreads?.toString() ?: "app_default"),
    )

    /**
     * Maps a request onto a resolved encoder: what will be applied, and why a run is refused. Nothing is changed
     * silently: a setting this encoder cannot honour refuses the run and the refusal names it.
     */
    fun plan(request: EncoderBenchRequest, caps: EncoderBenchCaps): EncoderBenchPlan {
        val refusals = mutableListOf<String>()
        // HEVC/AVC: the bitrate mode is always set explicitly. Android 12+ raises an unset or VBR mode silently, so
        // Automatic means CBR here, never the encoder's own default. A CBR the encoder does not report is refused.
        val explicitCbrDefault = !caps.nativeLibaom && isBenchOnlyMime(caps.mime) &&
            request.bitrateMode == BenchBitrateMode.AUTO
        val mode = if (explicitCbrDefault) BenchBitrateMode.CBR else request.bitrateMode
        val modeName = mode.token
        if (caps.nativeLibaom) {
            if (mode == BenchBitrateMode.CQ) refusals += "mode_unsupported: libaom-native has no CQ mode here"
            if (request.intraRefreshFrames != null) {
                refusals += "intra_refresh_unsupported: libaom-native has no MediaFormat intra-refresh"
            }
        } else {
            if (request.libaomCpuUsed != null) refusals += "libaom_cpu_used_requires_libaom_native"
            if (request.libaomThreads != null) refusals += "libaom_threads_requires_libaom_native"
            val codecMode = mode.codecMode
            if (codecMode != null && codecMode !in caps.supportedModes) {
                refusals += "mode_unsupported: ${caps.encoderName} does not report bitrate mode $modeName " +
                    "(supports ${caps.supportedModes.sorted().joinToString(",") { modeToken(it) }})" +
                    if (explicitCbrDefault) "; hevc/avc default to an explicit cbr, request another mode to override" else ""
            }
        }
        if (caps.sizeAndRateSupported == false) {
            refusals += "size_unsupported: ${caps.encoderName} reports ${request.width}x${request.height} at " +
                "${request.fps} fps as unsupported"
        }
        val rawGeometry = if (request.rawWidth != null && request.rawHeight != null) {
            request.rawWidth to request.rawHeight
        } else {
            null
        }
        if (rawGeometry != null && (rawGeometry.first != request.width || rawGeometry.second != request.height)) {
            refusals += "geometry_mismatch: fixture is ${rawGeometry.first}x${rawGeometry.second} but " +
                "${request.width}x${request.height} was requested; the bench never rescales"
        }
        val override = if (!caps.nativeLibaom) mode.codecMode else null
        val rateControl = when (mode) {
            BenchBitrateMode.AUTO -> EncoderRateControl.AUTOMATIC
            BenchBitrateMode.VBR -> EncoderRateControl.VBR
            BenchBitrateMode.CBR_FD -> EncoderRateControl.CBR_FD
            // CQ has no EncoderRateControl; CBR is the neutral non-adjusting choice and the override sets CQ.
            BenchBitrateMode.CBR, BenchBitrateMode.CQ -> EncoderRateControl.CBR
        }
        val effectiveMode = when {
            explicitCbrDefault -> "cbr(bench_default)"
            mode == BenchBitrateMode.AUTO -> "auto(" + (caps.defaultMode?.let(::modeToken) ?: "encoder_default") + ")"
            else -> modeName
        }
        val effective = listOf(
            "codec" to caps.encoderName,
            "mime" to caps.mime,
            "hardware" to caps.hardware.toString(),
            "width" to request.width.toString(),
            "height" to request.height.toString(),
            "fps" to request.fps.toString(),
            "bitrate_kbps" to request.bitrateKbps.toString(),
            "mode" to effectiveMode,
            "rate_adjuster" to if (mode == BenchBitrateMode.AUTO && !caps.nativeLibaom) "app_default" else "off",
            "intra_refresh_frames" to when {
                request.intraRefreshFrames == null -> "none"
                caps.intraRefreshFeature == false -> "requested_feature_unsupported"
                else -> "pending_readback"
            },
            "keyframe_s" to (request.keyFrameIntervalSeconds ?: APP_KEY_FRAME_INTERVAL_SECONDS).toString(),
            "libaom_cpu_used" to if (caps.nativeLibaom) (request.libaomCpuUsed?.toString() ?: "app_governor") else "n/a",
            "libaom_threads" to if (caps.nativeLibaom) (request.libaomThreads?.toString() ?: "app_default") else "n/a",
        )
        return EncoderBenchPlan(refusals, requestedPairs(request), effective, override, rateControl)
    }

    /** The effective settings once the encoder runs: read-back values replace the pre-start guesses. */
    fun effectiveAfterStart(
        plan: EncoderBenchPlan,
        request: EncoderBenchRequest,
        readback: EncoderBenchReadback,
    ): List<Pair<String, String>> = plan.effective.map { (key, value) ->
        key to when (key) {
            "intra_refresh_frames" -> if (request.intraRefreshFrames == null) {
                value
            } else {
                readback.outputIntraRefreshPeriod?.let { "applied:$it" } ?: "not_reported(requested ${request.intraRefreshFrames})"
            }
            "mode" -> readback.outputBitrateMode?.let { "$value|reported:${modeToken(it)}" } ?: value
            "bitrate_kbps" -> readback.outputBitrateBps?.let { "$value|reported:${it / 1_000}" } ?: value
            "libaom_cpu_used" -> readback.libaomCpuUsed?.toString() ?: value
            "libaom_threads" -> readback.libaomThreads?.toString() ?: value
            else -> value
        }
    }

    fun modeToken(codecMode: Int): String = BenchBitrateMode.fromCodecMode(codecMode)?.token ?: "mode$codecMode"

    private fun toInt(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun toBoolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
        else -> null
    }

    /** `[key=value;key=value]` so the host helper can read it with one regular expression. */
    fun bracket(pairs: List<Pair<String, String>>): String =
        pairs.joinToString(";", "[", "]") { (key, value) -> "$key=${value.replace(';', ',').replace(']', ')')}" }
}

/**
 * Per-second and whole-run counters for the bench. Latency is the time from a raw frame's presentation time
 * to its encoded packet arriving: a proxy for the encoder pipeline, not a screen or radio latency.
 */
internal class EncoderBenchMeter {
    private var windowFrames = 0
    private var windowBytes = 0L
    private var windowKeyFrames = 0
    private val windowLatenciesMs = ArrayList<Double>()
    private var totalFrames = 0L
    private var totalBytes = 0L
    private var totalKeyFrames = 0L
    private val totalLatenciesMs = ArrayList<Double>()
    private var firstPacketAtMs: Long? = null

    data class Window(
        val fpsOut: Double,
        val kbpsOut: Double,
        val latencyP50Ms: Double?,
        val latencyP95Ms: Double?,
        val keyFrames: Int,
        val frames: Int,
    )

    fun onPacket(atMs: Long, bytes: Int, keyFrame: Boolean, codecConfig: Boolean, latencyMs: Double?) {
        if (codecConfig) return
        if (firstPacketAtMs == null) firstPacketAtMs = atMs
        windowFrames++
        windowBytes += bytes
        totalFrames++
        totalBytes += bytes
        if (keyFrame) {
            windowKeyFrames++
            totalKeyFrames++
        }
        if (latencyMs != null) {
            windowLatenciesMs += latencyMs
            totalLatenciesMs += latencyMs
        }
    }

    /** Closes the current window of [windowMs] and starts the next one. */
    fun sample(windowMs: Long): Window {
        val seconds = windowMs.coerceAtLeast(1L) / 1_000.0
        val window = Window(
            fpsOut = windowFrames / seconds,
            kbpsOut = windowBytes * 8.0 / 1_000.0 / seconds,
            latencyP50Ms = percentile(windowLatenciesMs, 0.50),
            latencyP95Ms = percentile(windowLatenciesMs, 0.95),
            keyFrames = windowKeyFrames,
            frames = windowFrames,
        )
        windowFrames = 0
        windowBytes = 0L
        windowKeyFrames = 0
        windowLatenciesMs.clear()
        return window
    }

    /** Whole-run figures over [runMs] (rates are frames and bits over the whole run, start-up included). */
    fun total(runMs: Long): Window {
        val seconds = runMs.coerceAtLeast(1L) / 1_000.0
        return Window(
            fpsOut = totalFrames / seconds,
            kbpsOut = totalBytes * 8.0 / 1_000.0 / seconds,
            latencyP50Ms = percentile(totalLatenciesMs, 0.50),
            latencyP95Ms = percentile(totalLatenciesMs, 0.95),
            keyFrames = totalKeyFrames.toInt(),
            frames = totalFrames.toInt(),
        )
    }

    fun firstPacketAtMs(): Long? = firstPacketAtMs

    companion object {
        /** Nearest-rank percentile, null with no samples. */
        fun percentile(values: List<Double>, fraction: Double): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val rank = ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}

internal object EncoderBenchLines {
    private const val PREFIX = "BENCHMARK encoder_bench: "

    private fun ms(value: Double?): String = value?.let { "%.1f".format(java.util.Locale.US, it) } ?: "n/a"

    private fun core(
        event: String,
        codec: String,
        requested: List<Pair<String, String>>,
        effective: List<Pair<String, String>>,
        window: EncoderBenchMeter.Window?,
        dropped: Long,
        thermal: String,
        power: String?,
    ): String = PREFIX + "event=$event | codec=$codec | requested=${EncoderBenchSpec.bracket(requested)} | " +
        "effective=${EncoderBenchSpec.bracket(effective)} | " +
        "fps_out=${window?.let { "%.2f".format(java.util.Locale.US, it.fpsOut) } ?: "n/a"} | " +
        "kbps_out=${window?.let { "%.1f".format(java.util.Locale.US, it.kbpsOut) } ?: "n/a"} | " +
        "enc_latency_ms_p50/p95=${ms(window?.latencyP50Ms)}/${ms(window?.latencyP95Ms)} | " +
        "keyframes=${window?.keyFrames ?: 0} | dropped=$dropped | thermal=$thermal" +
        (power?.let { " | power=$it" } ?: "")

    fun sample(
        second: Int,
        codec: String,
        requested: List<Pair<String, String>>,
        effective: List<Pair<String, String>>,
        window: EncoderBenchMeter.Window,
        dropped: Long,
        thermal: String,
        power: PowerSample? = null,
    ): String = core("sample", codec, requested, effective, window, dropped, thermal, power?.let(BenchPower::sampleField)) +
        " | t_s=$second"

    fun summary(
        result: String,
        reason: String?,
        codec: String,
        requested: List<Pair<String, String>>,
        effective: List<Pair<String, String>>,
        window: EncoderBenchMeter.Window?,
        dropped: Long,
        thermal: String,
        runSeconds: Double,
        bleViolations: Int,
        power: PowerSummary? = null,
    ): String = core("summary", codec, requested, effective, window, dropped, thermal, power?.let(BenchPower::summaryField)) +
        " | result=$result | reason=${reason ?: "none"} | frames=${window?.frames ?: 0} | " +
        "run_s=${"%.1f".format(java.util.Locale.US, runSeconds)} | ble_violations=$bleViolations"
}

/**
 * One reading of the battery as the platform reported it, before any interpretation. Null means the platform did
 * not report it (the property is unsupported or the sticky broadcast lacked the extra).
 */
internal data class BatteryReading(
    /** BATTERY_PROPERTY_CURRENT_NOW as returned (nominally microamperes; Long.MIN_VALUE already mapped to null). */
    val currentNowRaw: Long?,
    /** BATTERY_PROPERTY_CURRENT_AVERAGE as returned. */
    val currentAverageRaw: Long?,
    /** EXTRA_VOLTAGE of the sticky ACTION_BATTERY_CHANGED intent, millivolts. */
    val voltageMv: Int?,
    /** EXTRA_PLUGGED bit mask (AC 1, USB 2, wireless 4, dock 8); 0 is unplugged. */
    val pluggedMask: Int?,
    /** EXTRA_STATUS (BATTERY_STATUS_*). */
    val status: Int?,
    /** EXTRA_TEMPERATURE, tenths of a degree Celsius. */
    val temperatureTenthsC: Int?,
)

/**
 * Which way the current property's sign points on this phone. AOSP documents positive as current into the battery
 * (charging) and negative as out of it (discharging); some OEMs report the opposite. Detected from the plug and
 * charge state, never assumed.
 */
internal enum class CurrentSign(val token: String) {
    AOSP("aosp"),
    INVERTED("inverted"),
    UNKNOWN("unknown"),
}

/** One interpreted battery sample. [currentNowMa]/[currentAverageMa] are in the AOSP sign (negative discharging). */
internal data class PowerSample(
    val currentNowRaw: Long?,
    val currentAverageRaw: Long?,
    val currentNowMa: Double?,
    val currentAverageMa: Double?,
    /** "ua" when the raw value was read as microamperes, "ma" when as milliamperes, "unknown". */
    val currentNowUnits: String,
    val currentAverageUnits: String,
    val sign: CurrentSign,
    val voltageMv: Int?,
    /** |mA| x V; null when the current or the voltage is unknown. */
    val powerMw: Double?,
    val plugged: String,
    val status: String,
    val temperatureC: Double?,
    /** False while plugged (USB/AC/wireless power invalidates the discharge figure) or when a term is unknown. */
    val valid: Boolean,
    val reason: String,
)

/** Whole-run figures over the per-second samples. Energy and the means use only the valid samples. */
internal data class PowerSummary(
    val samples: Int,
    val validSamples: Int,
    val pluggedSamples: Int,
    val meanMw: Double?,
    val p95Mw: Double?,
    val energyMj: Double?,
    val mjPerFrame: Double?,
    val powerValid: Boolean,
    val reason: String,
    val sign: String,
    val units: String,
    val voltageMeanMv: Double?,
    val tempStartC: Double?,
    val tempEndC: Double?,
    val tempMaxC: Double?,
)

/**
 * Battery power estimate for the bench (`power=[...]` on every sample and the summary). It is a coarse, whole-phone
 * discharge estimate: it includes the screen, CPU and everything else, the current property updates slowly on some
 * phones, and any USB/AC power makes it meaningless, so [PowerSample.valid] is false while plugged.
 */
internal object BenchPower {
    const val UNKNOWN = "unknown"

    /**
     * A raw magnitude below this is read as milliamperes, at or above as microamperes. A phone running the encoder
     * with the screen on draws well over 10 mA (10 000 uA), and a phone drawing 10 A is not a phone, so the two
     * readings cannot collide.
     */
    const val MICRO_AMP_FLOOR = 10_000L
    const val STATUS_CHARGING = 2
    const val STATUS_DISCHARGING = 3
    const val STATUS_NOT_CHARGING = 4
    const val STATUS_FULL = 5
    const val VOLTAGE_MIN_MV = 2_000
    const val VOLTAGE_MAX_MV = 6_000

    /** Magnitude in mA and the unit the raw value was read as; null for unreported or zero (many phones answer 0 when unsupported). */
    fun magnitudeMa(raw: Long?): Pair<Double, String>? {
        if (raw == null || raw == 0L || raw == Long.MIN_VALUE) return null
        val magnitude = Math.abs(raw)
        return if (magnitude >= MICRO_AMP_FLOOR) magnitude / 1_000.0 to "ua" else magnitude.toDouble() to "ma"
    }

    /** The sign convention implied by [raw] and the plug/charge state, UNKNOWN when the state does not say. */
    fun detectSign(raw: Long?, pluggedMask: Int?, status: Int?): CurrentSign {
        if (raw == null || raw == 0L || raw == Long.MIN_VALUE) return CurrentSign.UNKNOWN
        val unplugged = pluggedMask == 0 || (pluggedMask == null && status == STATUS_DISCHARGING)
        val charging = pluggedMask != null && pluggedMask != 0 && status == STATUS_CHARGING
        return when {
            unplugged && status != STATUS_CHARGING -> if (raw < 0) CurrentSign.AOSP else CurrentSign.INVERTED
            charging -> if (raw > 0) CurrentSign.AOSP else CurrentSign.INVERTED
            else -> CurrentSign.UNKNOWN
        }
    }

    fun pluggedToken(mask: Int?): String {
        if (mask == null) return UNKNOWN
        if (mask == 0) return "none"
        val names = mutableListOf<String>()
        if (mask and 1 != 0) names += "ac"
        if (mask and 2 != 0) names += "usb"
        if (mask and 4 != 0) names += "wireless"
        if (mask and 8 != 0) names += "dock"
        if (names.isEmpty()) names += "mask$mask"
        return names.joinToString("+")
    }

    fun statusToken(status: Int?): String = when (status) {
        null -> UNKNOWN
        1 -> "unknown_status"
        STATUS_CHARGING -> "charging"
        STATUS_DISCHARGING -> "discharging"
        STATUS_NOT_CHARGING -> "not_charging"
        STATUS_FULL -> "full"
        else -> "status$status"
    }

    fun sample(reading: BatteryReading): PowerSample {
        val nowMagnitude = magnitudeMa(reading.currentNowRaw)
        val avgMagnitude = magnitudeMa(reading.currentAverageRaw)
        val sign = detectSign(reading.currentNowRaw, reading.pluggedMask, reading.status)
        // The average has no state of its own: it follows the instantaneous sign, else its own detection.
        val avgSign = if (sign != CurrentSign.UNKNOWN) {
            sign
        } else {
            detectSign(reading.currentAverageRaw, reading.pluggedMask, reading.status)
        }

        fun signed(raw: Long?, magnitude: Pair<Double, String>?, convention: CurrentSign): Double? {
            if (raw == null || magnitude == null) return null
            val value = if (raw < 0) -magnitude.first else magnitude.first
            return if (convention == CurrentSign.INVERTED) -value else value
        }

        val nowMa = signed(reading.currentNowRaw, nowMagnitude, sign)
        val avgMa = signed(reading.currentAverageRaw, avgMagnitude, avgSign)
        val voltage = reading.voltageMv?.takeIf { it in VOLTAGE_MIN_MV..VOLTAGE_MAX_MV }
        val power = if (nowMa != null && voltage != null) Math.abs(nowMa) * voltage / 1_000.0 else null
        val reason = when {
            reading.pluggedMask == null -> "plugged_unknown"
            reading.pluggedMask != 0 -> "plugged"
            nowMa == null -> "current_unknown"
            voltage == null -> "voltage_unknown"
            else -> "ok"
        }
        return PowerSample(
            currentNowRaw = reading.currentNowRaw,
            currentAverageRaw = reading.currentAverageRaw,
            currentNowMa = nowMa,
            currentAverageMa = avgMa,
            currentNowUnits = nowMagnitude?.second ?: UNKNOWN,
            currentAverageUnits = avgMagnitude?.second ?: UNKNOWN,
            sign = sign,
            voltageMv = voltage,
            powerMw = power,
            plugged = pluggedToken(reading.pluggedMask),
            status = statusToken(reading.status),
            temperatureC = reading.temperatureTenthsC?.let { it / 10.0 },
            valid = reason == "ok",
            reason = reason,
        )
    }

    private fun fixed(value: Double?, digits: Int): String =
        value?.let { "%.${digits}f".format(java.util.Locale.US, it) } ?: UNKNOWN

    fun sampleField(sample: PowerSample): String = EncoderBenchSpec.bracket(
        listOf(
            "current_now_raw" to (sample.currentNowRaw?.toString() ?: UNKNOWN),
            "current_now_ma" to fixed(sample.currentNowMa, 1),
            "current_now_units" to sample.currentNowUnits,
            "current_avg_raw" to (sample.currentAverageRaw?.toString() ?: UNKNOWN),
            "current_avg_ma" to fixed(sample.currentAverageMa, 1),
            "current_avg_units" to sample.currentAverageUnits,
            "sign" to sample.sign.token,
            "voltage_mv" to (sample.voltageMv?.toString() ?: UNKNOWN),
            "power_mw" to fixed(sample.powerMw, 0),
            "plugged" to sample.plugged,
            "status" to sample.status,
            "temp_c" to fixed(sample.temperatureC, 1),
            "power_valid" to sample.valid.toString(),
            "power_reason" to sample.reason,
        ),
    )

    fun summaryField(summary: PowerSummary): String = EncoderBenchSpec.bracket(
        listOf(
            "power_valid" to summary.powerValid.toString(),
            "power_reason" to summary.reason,
            "samples" to summary.samples.toString(),
            "valid_samples" to summary.validSamples.toString(),
            "plugged_samples" to summary.pluggedSamples.toString(),
            "power_mw_mean" to fixed(summary.meanMw, 0),
            "power_mw_p95" to fixed(summary.p95Mw, 0),
            "energy_mj" to fixed(summary.energyMj, 0),
            "mj_per_frame" to fixed(summary.mjPerFrame, 2),
            "sign" to summary.sign,
            "current_now_units" to summary.units,
            "voltage_mv_mean" to fixed(summary.voltageMeanMv, 0),
            "temp_c_start" to fixed(summary.tempStartC, 1),
            "temp_c_end" to fixed(summary.tempEndC, 1),
            "temp_c_max" to fixed(summary.tempMaxC, 1),
        ),
    )
}

/**
 * Collects the per-second [PowerSample]s with the length and frame count of the window each one closed. Energy,
 * mean and p95 use only valid samples (unplugged, current and voltage known); [PowerSummary.powerValid] needs every
 * sample valid, so a run that was ever plugged is flagged even though its unplugged seconds still report figures.
 */
internal class EncoderBenchPowerMeter {
    private class Entry(val sample: PowerSample, val windowMs: Long, val frames: Int)

    private val entries = ArrayList<Entry>()

    fun add(sample: PowerSample, windowMs: Long, frames: Int) {
        entries += Entry(sample, windowMs.coerceAtLeast(0L), frames)
    }

    fun summary(): PowerSummary {
        val valid = entries.filter { it.sample.valid && it.sample.powerMw != null }
        val energyMj = if (valid.isEmpty()) null else valid.sumOf { it.sample.powerMw!! * it.windowMs / 1_000.0 }
        val validMs = valid.sumOf { it.windowMs }
        val validFrames = valid.sumOf { it.frames }
        val rank = listOf("plugged", "plugged_unknown", "current_unknown", "voltage_unknown")
        val reason = when {
            entries.isEmpty() -> "no_samples"
            else -> entries.map { it.sample.reason }.filter { it != "ok" }.minByOrNull { rank.indexOf(it) } ?: "ok"
        }

        fun agreed(values: List<String>): String {
            val known = values.filter { it != BenchPower.UNKNOWN }.toSet()
            return when (known.size) {
                0 -> BenchPower.UNKNOWN
                1 -> known.first()
                else -> "mixed"
            }
        }

        val temps = entries.mapNotNull { it.sample.temperatureC }
        return PowerSummary(
            samples = entries.size,
            validSamples = valid.size,
            pluggedSamples = entries.count { it.sample.reason == "plugged" },
            meanMw = if (energyMj != null && validMs > 0) energyMj / (validMs / 1_000.0) else null,
            p95Mw = EncoderBenchMeter.percentile(valid.map { it.sample.powerMw!! }, 0.95),
            energyMj = energyMj,
            mjPerFrame = if (energyMj != null && validFrames > 0) energyMj / validFrames else null,
            powerValid = entries.isNotEmpty() && valid.size == entries.size,
            reason = reason,
            sign = agreed(entries.map { it.sample.sign.token }),
            units = agreed(entries.map { it.sample.currentNowUnits }),
            voltageMeanMv = entries.mapNotNull { it.sample.voltageMv }.takeIf { it.isNotEmpty() }?.average(),
            tempStartC = temps.firstOrNull(),
            tempEndC = temps.lastOrNull(),
            tempMaxC = temps.maxOrNull(),
        )
    }
}

/**
 * Process-wide guard: while the encoder bench is active no Bluetooth start path may run. The bench Activity
 * [activate]s it before anything else; the radio entry points (scan, advertise, GATT, L2CAP, RFCOMM, discovery)
 * call [check] first. A violation throws, is counted, and the bench summary reports it (`ble_violations`) even
 * when a caller swallowed the exception (several entry points sit inside runCatching).
 */
object EncoderBenchGuard {
    @Volatile var active: Boolean = false
        private set
    private val violationSites = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** Replaced in tests; the default writes to the log. */
    @Volatile internal var report: (String) -> Unit = { message -> android.util.Log.e("BluetoothVideo", message) }

    fun activate() {
        violationSites.clear()
        active = true
    }

    fun deactivate() {
        active = false
    }

    val violations: Int get() = violationSites.size

    fun violationSites(): List<String> = synchronized(violationSites) { violationSites.toList() }

    /** Call at the top of every Bluetooth start path. Free when the bench is not active. */
    @JvmStatic
    fun check(site: String) {
        if (!active) return
        violationSites += site
        val message = "BENCHMARK encoder_bench: event=violation | site=$site | reason=bluetooth_start_during_encoder_bench"
        report(message)
        throw IllegalStateException("Bluetooth start path '$site' ran during test_encoder_bench")
    }
}
