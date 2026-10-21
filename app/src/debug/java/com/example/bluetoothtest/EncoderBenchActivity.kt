package com.example.bluetoothtest

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import androidx.camera.viewfinder.view.ViewfinderView
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * DEBUG encoder bench (`test_encoder_bench`; docs/dev/switches.md, default off, Experimental). Encodes the declared
 * raw fixture through the phone's real selected encoder for a fixed time with no Bluetooth, advertising or
 * scanning, logs one `BENCHMARK encoder_bench:` line per second and a summary, then finishes. [MainActivity]
 * hands the launch over before it creates any view, service or radio object; [EncoderBenchGuard] is active for
 * this whole process so any Bluetooth start path that does run is refused, counted and reported.
 */
class EncoderBenchActivity : Activity() {
    private lateinit var status: TextView
    private var worker: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EncoderBenchGuard.activate()
        if (savedInstanceState != null || !BuildConfig.DEBUG) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply {
            text = "Encoder bench (no Bluetooth)"
            textSize = 14f
            setPadding(32, 64, 32, 32)
        }
        setContentView(status)
        // The viewfinder is only a constructor argument: a raw fixture never opens a camera.
        val viewfinder = ViewfinderView(this)
        val extras = EncoderBenchLaunch.extras(intent)
        worker = thread(name = "EncoderBench") { run(extras, viewfinder) }
    }

    override fun onDestroy() {
        worker?.interrupt()
        EncoderBenchGuard.deactivate()
        super.onDestroy()
    }

    private fun show(text: String) = runOnUiThread { if (!isDestroyed) status.text = text }

    private fun emit(line: String) {
        Log.i("BluetoothVideo", line)
        show(line.removePrefix("BENCHMARK encoder_bench: ").take(400))
    }

    private fun thermalName(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unavailable"
        val status = getSystemService(PowerManager::class.java)?.currentThermalStatus ?: return "unavailable"
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "status$status"
        }
    }

    /**
     * One battery reading for the power estimate: the BatteryManager current properties and the sticky battery
     * intent. A property the platform does not offer comes back as Long.MIN_VALUE (or throws) and is logged
     * `unknown`; nothing here can fail the bench.
     */
    private fun readBattery(): BatteryReading {
        fun property(id: Int): Long? {
            val value = runCatching { getSystemService(BatteryManager::class.java)?.getLongProperty(id) }.getOrNull()
            return value?.takeIf { it != Long.MIN_VALUE }
        }

        val sticky: Intent? = runCatching { registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()

        fun extra(name: String): Int? = sticky?.takeIf { it.hasExtra(name) }?.getIntExtra(name, 0)

        return BatteryReading(
            currentNowRaw = property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            currentAverageRaw = property(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            voltageMv = extra(BatteryManager.EXTRA_VOLTAGE),
            pluggedMask = extra(BatteryManager.EXTRA_PLUGGED),
            status = extra(BatteryManager.EXTRA_STATUS),
            temperatureTenthsC = extra(BatteryManager.EXTRA_TEMPERATURE),
        )
    }

    private fun refuse(
        reason: String,
        codec: String,
        requested: List<Pair<String, String>>,
        effective: List<Pair<String, String>>,
    ) {
        emit(
            EncoderBenchLines.summary(
                "refused", reason, codec, requested, effective, null, 0L, thermalName(), 0.0,
                EncoderBenchGuard.violations,
            ),
        )
        finishBench()
    }

    private fun finishBench() = runOnUiThread { if (!isDestroyed) finishAndRemoveTask() }

    private fun run(extras: Map<String, Any?>, viewfinder: ViewfinderView) {
        val parsed = EncoderBenchSpec.parse(extras)
        val request = parsed.request
        if (request == null) {
            refuse(parsed.issues.joinToString("; "), "n/a", emptyList(), emptyList())
            return
        }
        val requested = EncoderBenchSpec.requestedPairs(request)
        // HEVC/AVC encoders are appended after the product candidates and flagged bench-only: "auto" never picks them.
        val candidates = VideoCodec.allMediaCodecEncoders() +
            VideoCodec.availableEncoders().filter { it.backend == VideoEncoderBackend.NATIVE_LIBAOM } +
            VideoCodec.benchOnlyMediaCodecEncoders()
        val (match, codecReason) = EncoderBenchSpec.resolveCodec(
            request.codec,
            candidates.map {
                BenchCodecCandidate(
                    it.encoderName, it.mimeType, it.backend == VideoEncoderBackend.NATIVE_LIBAOM,
                    benchOnly = it.protocolId == VideoCodec.BENCH_ONLY_PROTOCOL_ID,
                )
            },
        )
        val videoCodec = match?.let { chosen -> candidates.first { it.encoderName == chosen.encoderName } }
        if (videoCodec == null) {
            refuse(codecReason ?: "codec_unavailable", request.codec ?: "auto", requested, emptyList())
            return
        }
        val plan = EncoderBenchSpec.plan(request, capsFor(videoCodec, request))
        if (plan.refusals.isNotEmpty()) {
            refuse(plan.refusals.joinToString("; "), videoCodec.encoderName, plan.requested, plan.effective)
            return
        }
        val file = File(request.rawPath)
        val declared = (request.rawWidth ?: request.width) to (request.rawHeight ?: request.height)
        val fixture = RawFixtureGeometry.check(
            if (file.isFile) file.length() else 0L, declared.first, declared.second, request.width, request.height,
        )
        Log.i("BluetoothVideo", RawFixtureGeometry.line(file.name, file.length(), declared, request.width, request.height, fixture))
        if (!file.isFile || !fixture.ok) {
            refuse(
                if (file.isFile) "raw_fixture_refused: ${fixture.reason}" else "raw_fixture_missing: ${request.rawPath}",
                videoCodec.encoderName, plan.requested, plan.effective,
            )
            return
        }

        DebugTunables.bitrateModeOverride = plan.bitrateModeOverride
        DebugTunables.intraRefreshFrames = request.intraRefreshFrames
        val settings = VideoSettings(
            VideoResolution(request.width, request.height, "${request.height}p"),
            fps = request.fps,
            bitRate = request.bitrateKbps * 1_000,
            latencyMs = 500,
        )
        val meter = EncoderBenchMeter()
        val powerMeter = EncoderBenchPowerMeter()
        val failure = AtomicReference<String?>(null)
        val encoder = CameraVideoEncoder(
            this, viewfinder, CameraLens.BACK, videoCodec, settings,
            referenceFrameLimit = 0,
            debugTraceSessionName = null,
            onPacket = { flags, timeUs, bytes ->
                val nowUs = SystemClock.elapsedRealtimeNanos() / 1_000L
                val latencyMs = ((nowUs - timeUs) / 1_000.0).takeIf { it > -5.0 && it < 60_000.0 }?.coerceAtLeast(0.0)
                meter.onPacket(
                    SystemClock.elapsedRealtime(), bytes.size,
                    keyFrame = flags and StreamProtocol.FLAG_KEY_FRAME != 0,
                    codecConfig = flags and StreamProtocol.FLAG_CODEC_CONFIG != 0,
                    latencyMs = latencyMs,
                )
            },
            onStatus = { message -> Log.i("BluetoothVideo", "Encoder bench: $message") },
            onFailure = { error -> failure.set("encoder_failure: ${error.message ?: error.javaClass.simpleName}") },
            rateControl = plan.rateControl,
            scaleMode = VideoScaleMode.FILL,
            debugRawInputFile = file,
            rawGeometry = declared,
            bench = EncoderBenchOverrides(request.keyFrameIntervalSeconds, request.libaomCpuUsed, request.libaomThreads),
        )
        val startedMs = SystemClock.elapsedRealtime()
        try {
            encoder.start()
        } catch (error: Exception) {
            runCatching { encoder.close() }
            refuse(
                "encoder_start_failed: ${error.message ?: error.javaClass.simpleName}",
                videoCodec.encoderName, plan.requested, plan.effective,
            )
            return
        }

        var lastTickMs = startedMs
        var second = 0
        var interrupted = false
        while (second < request.durationSeconds && failure.get() == null) {
            val dueMs = startedMs + (second + 1) * 1_000L
            val sleepMs = dueMs - SystemClock.elapsedRealtime()
            try {
                if (sleepMs > 0) Thread.sleep(sleepMs)
            } catch (_: InterruptedException) {
                interrupted = true
                break
            }
            val nowMs = SystemClock.elapsedRealtime()
            second++
            val windowMs = nowMs - lastTickMs
            val window = meter.sample(windowMs)
            lastTickMs = nowMs
            val readback = encoder.benchReadback()
            val power = BenchPower.sample(readBattery())
            powerMeter.add(power, windowMs, window.frames)
            emit(
                EncoderBenchLines.sample(
                    second, videoCodec.encoderName, plan.requested,
                    EncoderBenchSpec.effectiveAfterStart(plan, request, readback),
                    window, readback.rawInputDrops, thermalName(), power,
                ),
            )
        }
        val runMs = SystemClock.elapsedRealtime() - startedMs
        val readback = encoder.benchReadback()
        runCatching { encoder.close() }
        val total = meter.total(runMs)
        val result: String
        val reason: String?
        when {
            EncoderBenchGuard.violations > 0 -> {
                result = "failed"
                reason = "bluetooth_start_during_bench: ${EncoderBenchGuard.violationSites().joinToString(",")}"
            }
            failure.get() != null -> {
                result = "failed"
                reason = failure.get()
            }
            interrupted -> {
                result = "failed"
                reason = "interrupted"
            }
            total.frames == 0 -> {
                result = "failed"
                reason = "no_frames_encoded"
            }
            else -> {
                result = "ok"
                reason = null
            }
        }
        emit(
            EncoderBenchLines.summary(
                result, reason, videoCodec.encoderName, plan.requested,
                EncoderBenchSpec.effectiveAfterStart(plan, request, readback),
                total, readback.rawInputDrops, thermalName(), runMs / 1_000.0, EncoderBenchGuard.violations,
                powerMeter.summary(),
            ),
        )
        finishBench()
    }

    /** What the platform reports for [codec], as plain data for the pure planner. */
    private fun capsFor(codec: VideoCodec, request: EncoderBenchRequest): EncoderBenchCaps {
        val native = codec.backend == VideoEncoderBackend.NATIVE_LIBAOM
        if (native) {
            return EncoderBenchCaps(
                codec.encoderName, codec.mimeType, hardware = false, nativeLibaom = true,
                supportedModes = codec.supportedBitrateModes,
                defaultMode = codec.resolveBitrateMode(EncoderRateControl.AUTOMATIC),
                sizeAndRateSupported = null, intraRefreshFeature = null,
            )
        }
        val info = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { it.name == codec.encoderName }
        val capabilities = info?.let { runCatching { it.getCapabilitiesForType(codec.mimeType) }.getOrNull() }
        val modes = (0..3).filterTo(mutableSetOf()) { mode ->
            runCatching { capabilities?.encoderCapabilities?.isBitrateModeSupported(mode) }.getOrNull() == true
        }
        return EncoderBenchCaps(
            codec.encoderName, codec.mimeType, codec.hardwareAccelerated, nativeLibaom = false,
            supportedModes = modes,
            defaultMode = codec.resolveBitrateMode(EncoderRateControl.AUTOMATIC),
            sizeAndRateSupported = runCatching {
                capabilities?.videoCapabilities?.areSizeAndRateSupported(request.width, request.height, request.fps.toDouble())
            }.getOrNull(),
            intraRefreshFeature = runCatching {
                capabilities?.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_IntraRefresh)
            }.getOrNull(),
        )
    }
}
