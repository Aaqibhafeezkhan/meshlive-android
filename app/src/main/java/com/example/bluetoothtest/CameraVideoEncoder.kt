package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Range
import androidx.camera.viewfinder.view.ViewfinderView
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

class CameraVideoEncoder(
    private val context: Context,
    private val viewfinder: ViewfinderView,
    private val cameraLens: CameraLens,
    private val videoCodec: VideoCodec,
    private val settings: VideoSettings,
    referenceFrameLimit: Int,
    private val debugTraceSessionName: String?,
    private val onPacket: (flags: Int, timeUs: Long, bytes: ByteArray) -> Unit,
    private val onStatus: (String) -> Unit,
    private val reusableViewfinderSurface: Pair<Surface, CameraViewfinderSurface>? = null,
    private val rateControl: EncoderRateControl = EncoderRateControl.AUTOMATIC,
    private val scaleMode: VideoScaleMode = VideoScaleMode.FILL,
    private val onFirstVideoFrame: () -> Unit = {},
    private val onFailure: (Throwable) -> Unit = {},
    private val debugRawInputFile: File? = null,
    /**
     * Experimental call encoder fit (CallEncoderFit; null for streams and while off): told once,
     * with this geometry's speed key, when the native encoder stays over its frame budget at its
     * fastest preset with the fast tool profile for CallEncoderFit.OVERLOAD_WINDOWS windows.
     */
    private val onNativeOverloadAtFastest: ((key: String) -> Unit)? = null,
) : Closeable {
    private var cameraInputWidth = settings.resolution.width
    private var cameraInputHeight = settings.resolution.height
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val workerThread = HandlerThread("CameraEncoder").apply { start() }
    private val worker = Handler(workerThread.looper)
    private val closed = AtomicBoolean(false)
    private val usesNativeAom = videoCodec.backend == VideoEncoderBackend.NATIVE_LIBAOM
    private val nativeEncoderThread = if (usesNativeAom) {
        HandlerThread("NativeAomEncoder").apply { start() }
    } else {
        null
    }
    private val nativeWorker = nativeEncoderThread?.let { thread -> Handler(thread.looper) }

    private var codec: MediaCodec? = null
    private var nativeEncoder: NativeAomEncoder? = null
    private var nativeInputBuffers: ArrayBlockingQueue<ByteBuffer>? = null
    private var nativeInputDrops = 0
    private val nativeCopyLatency = RollingLatencyStats()
    private val nativeEncodeLatency = RollingLatencyStats()
    private var nativeSpeedGovernor: NativeEncoderSpeedGovernor? = null
    private var nativeSpeedLearner: NativeEncoderSpeedLearner? = null
    private var nativeSpeedStore: NativeEncoderSpeedStore? = null
    private var nativeOverloadReported = false
    private val nativeSpeedKey = nativeEncoderSpeedKey(settings.resolution.width, settings.resolution.height, settings.fps)
    /** Tool profile of the live native encoder: the mode table's, or learned on this phone. */
    private var nativeFastTools = nativeUsesFastToolProfile(settings)
    /**
     * Experimental temporal layering (off by default, DebugTunables.experimentalTemporalLayers):
     * every other frame refreshes nothing and is discardable. Fixed for this encoder's lifetime.
     */
    private val experimentalTemporalLayers = DebugTunables.experimentalTemporalLayers
    private var nativeTemporalLayers = false
    private var nativeLayerFrameIndex = 0L
    /** LayeringPolicy: produce droppable frames only while a link needs them. */
    @Volatile private var droppableFramesWanted = true
    /** Explicit libaom references are active; only a new encoder returns to libaom's own. */
    private var nativeExplicitReferences = false
    private var nativeLastInterQuantizer = -1
    private var nativeInterBitratePct = 0
    /** Native frames are marked droppable only after their AV1 headers prove it (experiment only). */
    private val nativeDroppability by lazy { BitstreamDroppability(MediaFormat.MIMETYPE_VIDEO_AV1) }
    private var nativeUnverifiedDroppable = 0L
    private val nativeKeyFrameRequested = AtomicBoolean(true)
    private var lastNativeKeyFramePtsUs = Long.MIN_VALUE
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var viewfinderSurface: CameraViewfinderSurface? = null
    private var encoderSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var referenceImageReader: ImageReader? = null
    private val referenceRecorder = if (referenceFrameLimit > 0) {
        ReferenceRecorder(
            File(context.getExternalFilesDir(null), "benchmark"),
            settings.resolution.width,
            settings.resolution.height,
            referenceFrameLimit,
        )
    } else {
        null
    }
    private var pendingImage: Image? = null
    private val pendingInputBuffers = ArrayDeque<Int>()
    private var availableFpsRanges = emptyList<Range<Int>>()
    private var targetFpsRange: Range<Int>? = null
    private var captureTargets = emptyList<Surface>()
    private var captureSessionGeneration = 0
    private var continuousAutoFocusAvailable = false
    @Volatile private var activeBitrate = settings.bitRate
    /** Bitrate last handed to the hardware encoder (worker thread). */
    private var encoderTargetBps = settings.bitRate
    /** A shaped keyframe was requested; the full target returns once it is encoded. */
    @Volatile private var shapedKeyFramePending = false
    private val restoreShapedTarget = Runnable {
        if (!shapedKeyFramePending) return@Runnable
        shapedKeyFramePending = false
        if (sceneCutShaper.pending) return@Runnable
        val activeCodec = codec ?: return@Runnable
        runCatching {
            activeCodec.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, codecTargetBps()) },
            )
        }
    }

    /**
     * The rate controller's target for the MediaCodec encoder: the trimmed target, lowered further
     * only by the experimental quantizer headroom (factor 1 while it is off).
     */
    private fun baseCodecTargetBps(): Int =
        (encoderTargetBps * (quantizerHeadroom?.factor ?: 1.0)).toInt().coerceAtLeast(1)

    /**
     * The bitrate the MediaCodec encoder is given: [baseCodecTargetBps] scaled down by the measured
     * [bitrateAdjuster] correction (never above it).
     */
    private fun codecTargetBps(): Int {
        val base = baseCodecTargetBps()
        if (framerateAdjusterArm) return CodecPolicy.framerateAdjustedBps(base, settings.fps, frameRateLimitFps)
        return bitrateAdjuster?.adjustedBps(base) ?: base
    }

    /** Why [codecTargetBps] differs from the rate controller's [bitRate], for status lines. */
    private fun codecTargetCorrections(): String = listOfNotNull(
        bitrateAdjuster?.takeIf { it.scaleExponent != 0 }?.let { "bitrate adjuster x${roundedForLog(it.scale, 2)}" },
        frameRateLimitFps.takeIf { framerateAdjusterArm && it in 1 until settings.fps }
            ?.let { "experimental framerate adjuster x${roundedForLog(settings.fps.toDouble() / it, 2)}" },
        quantizerHeadroom?.takeIf { it.factor != 1.0 }?.let { "experimental quantizer headroom x${roundedForLog(it.factor, 2)}" },
    ).joinToString(", ")

    /** Re-applies [codecTargetBps] unless a shaped keyframe or scene cut still holds a lower one. */
    private fun applyCodecTarget(reason: String) {
        if (shapedKeyFramePending || sceneCutShaper.pending) return
        val activeCodec = codec ?: return
        val target = codecTargetBps()
        runCatching {
            activeCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, target) })
        }.onSuccess {
            Log.i("BluetoothVideo", "Encoder target: ${target / 1_000} kbps ($reason)")
        }.onFailure { error ->
            onStatus("Encoder rejected bitrate update: ${error.message ?: error.javaClass.simpleName}")
        }
    }
    /** Automatic only: corrects this encoder's measured output toward [activeBitrate]. */
    private val rateTrim = EncoderRateTrim()
    private val isVp9 = videoCodec.mimeType == MediaFormat.MIMETYPE_VIDEO_VP9
    /** Diagnostic (on): base_q_idx parsed from each VP9 output frame (worker thread). */
    private var lastVp9QIdx: Int? = null
    private val quantizerWindow = QuantizerWindow()
    /** Per-viewer link share last handed to [updateLinkFrameBudget] (0 = not measured yet). */
    @Volatile private var frameBudgetLinkKbps = 0
    /**
     * Experimental scene-cut shaping (DebugTunables.sceneCutShaping, off by default). The detector
     * also runs in benchmark-traced sessions with the experiment off, so both A/B arms log the same
     * cut decisions. Worker thread only.
     */
    private val sceneCutShaper = SceneCutShaper(
        if (usesNativeAom) SceneCutShapingMode.OFF else DebugTunables.sceneCutShaping,
    )
    private val sceneCutDetection = sceneCutShaper.mode != SceneCutShapingMode.OFF ||
        BuildConfig.DEBUG && !debugTraceSessionName.isNullOrBlank()
    private var sceneCutDetector: SceneCutDetector? = null
    private var sceneCutsDetected = 0
    /** Experimental VP9 quantizer headroom (DebugTunables.vp9QuantizerFloor, off by default). */
    private val quantizerHeadroom = DebugTunables.vp9QuantizerFloor
        ?.takeIf { isVp9 && !usesNativeAom && rateControl == EncoderRateControl.AUTOMATIC }
        ?.let(::QuantizerHeadroomGovernor)
    /**
     * Default-on measured output correction for MediaCodec encoders under Automatic rate control
     * (EncoderBitrateAdjuster, a WebRTC DynamicBitrateAdjuster port); null when switched off with
     * the DEBUG extra `test_encoder_bitrate_adjuster=false`, for native libaom (its own rate control
     * and the output-rate trim) and for explicitly chosen bitrate modes. Worker thread only.
     */
    /**
     * Experimental A/B arm (off): WebRTC FramerateBitrateAdjuster within its exact scope (OMX.Exynos.
     * VP9/H.264, CodecPolicy.framerateAdjusterScope). It replaces [bitrateAdjuster] for the session.
     */
    private val framerateAdjusterArm = DebugTunables.encoderFramerateAdjuster && !usesNativeAom &&
        rateControl == EncoderRateControl.AUTOMATIC &&
        CodecPolicy.framerateAdjusterScope(videoCodec.encoderName, videoCodec.mimeType)
    /**
     * Delivered frame rate the arm uses: encoded frames per second of presentation time (camera,
     * link/thermal limit or raw fixture alike); the codec stays configured at settings.fps.
     */
    @Volatile private var frameRateLimitFps = settings.fps
    private val deliveredFrameRate = DeliveredFrameRate()
    private var framerateAppliedNs = 0L
    /** yuv-image-crop-rect (YuvPolicy): copies start from Image.getCropRect(). */
    private val honourCropRect = YuvPolicy.cropRectHonoured(DeviceTraits.current())
    private val bitrateAdjuster = EncoderBitrateAdjuster().takeIf {
        DebugTunables.encoderBitrateAdjuster && !usesNativeAom && rateControl == EncoderRateControl.AUTOMATIC &&
            !framerateAdjusterArm
    }
    private var bitrateAdjusterLogStartedNs = 0L
    private var bitrateAdjusterLogBytes = 0L
    /** Measured burst behaviour of this encoder (recorded every session; ranking is experimental). */
    private val burstAccumulator = EncoderBurstAccumulator()
    private val activeBitrateMode = DebugTunables.bitrateModeOverride
        .takeIf { videoCodec.backend != VideoEncoderBackend.NATIVE_LIBAOM }
        ?: videoCodec.resolveBitrateMode(rateControl)
    private val activeComplexity = videoCodec.realtimeComplexity(settings)
    private val keyFrameRequestBackoff = KeyFrameRequestBackoff()
    private var firstVideoFrameSeen = false
    private val encodedFrameInterval = RollingLatencyStats()
    private val captureToEncodedOutput = RollingLatencyStats()
    private val inputToEncodedOutput = RollingLatencyStats()
    private val captureToCodecInput = RollingLatencyStats()
    private val yuvCopyLatency = RollingLatencyStats()
    private var exposureTimeNs: Long? = null
    private var sensorFrameDurationNs: Long? = null
    private var cameraPipelineDepth: Byte? = null
    private val queuedInputTimesNs = LinkedHashMap<Long, Long>()
    private var cameraTimestampSource: Int? = null
    private var lastEncodedPresentationTimeUs = Long.MIN_VALUE
    private var cadenceReportStartedNs = 0L
    private var cadenceReportFrames = 0
    private var cadenceReportBytes = 0L
    private var encodingStatisticsEnabled = false
    /**
     * Experimental temporal layering only: MediaCodec frames proven droppable from their bitstream.
     * Null (nothing is ever marked) while the experiment is off and for native AV1.
     */
    private var bitstreamDroppability: BitstreamDroppability? = null
    private var temporalLayeringRequested = false
    private var layeringFormatLogged = false
    private var debugMediaTrace: DebugMediaTrace? = null
    private val debugStageTrace = DebugStageTrace.startIfRequested(debugTraceSessionName, "video_encoder")
    private var codecPresentationTimeOffsetUs: Long? = null
    private var rawInput: RandomAccessFile? = null
    private var rawFrame: ByteArray? = null
    private var rawStartedNs = 0L
    @Volatile private var rawStartedUs = 0L
    private var rawNextFrameNs = 0L
    private var rawInputFps = settings.fps
    private var rawInputDrops = 0L
    val benchmarkRawClock: RawReferenceClock? get() =
        if (BuildConfig.DEBUG && rawStartedUs > 0L) RawReferenceClock(rawStartedUs, rawStartedNs) else null

    @SuppressLint("MissingPermission")
    fun start() {
        check(debugRawInputFile == null || BuildConfig.DEBUG) { "Raw fixture input is debug-only" }
        val selectedCameraId = if (debugRawInputFile == null) {
            cameraManager.cameraIdFor(cameraLens)
                ?: error("${cameraLens.displayName} camera is unavailable")
        } else null
        val cameraCharacteristics = selectedCameraId?.let(cameraManager::getCameraCharacteristics)
        cameraTimestampSource = cameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        if (!videoCodec.usesSurfaceInput && cameraCharacteristics != null) {
            val yuvSizes = cameraCharacteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
                .orEmpty()
            (yuvSizes.firstOrNull { size ->
                size.width == settings.resolution.width && size.height == settings.resolution.height
            } ?: yuvSizes.filter { size ->
                    size.width >= settings.resolution.width && size.height >= settings.resolution.height
                }
                .minByOrNull { size -> size.width.toLong() * size.height })
                ?.let { size ->
                    cameraInputWidth = size.width
                    cameraInputHeight = size.height
                }
        }
        if (cameraCharacteristics != null) {
            Log.i("BluetoothVideo", "Camera clock: id=$selectedCameraId, timestamp source=$cameraTimestampSource (0=UNKNOWN, 1=REALTIME), input=${cameraInputWidth}x$cameraInputHeight")
        }
        availableFpsRanges = cameraCharacteristics
            ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            .orEmpty()
            .toList()
        targetFpsRange = selectFpsRange(settings.fps)
        continuousAutoFocusAvailable = cameraCharacteristics
            ?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true

        reusableViewfinderSurface?.let { (surface, owner) ->
            previewSurface = surface
            viewfinderSurface = owner
        }

        if (usesNativeAom) {
            val defaultCpuUsed = checkNotNull(videoCodec.nativeCpuUsed(settings))
            val store = NativeEncoderSpeedPreferences(context)
            nativeSpeedStore = store
            val remembered = runCatching { store.load(nativeSpeedKey) }.getOrNull()
            val learner = NativeEncoderSpeedLearner(defaultCpuUsed, nativeUsesFastToolProfile(settings), remembered)
            nativeSpeedLearner = learner
            nativeFastTools = learner.fastTools
            val cpuUsed = learner.startCpuUsed
            val frameBytes = YuvPlaneCopy.i420Bytes(settings.resolution.width, settings.resolution.height)
            // A slow frame waits in a buffer (adds latency) instead of losing the next camera frame.
            nativeInputBuffers = ArrayBlockingQueue<ByteBuffer>(NATIVE_INPUT_BUFFERS).apply {
                repeat(NATIVE_INPUT_BUFFERS) { add(ByteBuffer.allocateDirect(frameBytes)) }
            }
            val threadCount = nativeThreadCount(settings.resolution)
            nativeSpeedGovernor = NativeEncoderSpeedGovernor(cpuUsed, settings.fps)
            nativeEncoder = createNativeEncoder(cpuUsed, activeBitrate)
            nativeTemporalLayers = experimentalTemporalLayers
            nativeLayerFrameIndex = 0L
            Log.i(
                "BluetoothVideo",
                "Native libaom ${NativeAomBridge.nativeVersion()}: cpu-used=$cpuUsed " +
                    "(table $defaultCpuUsed, remembered ${remembered?.cpuUsed ?: "none"} for $nativeSpeedKey), " +
                    "fastTools=$nativeFastTools (table ${nativeUsesFastToolProfile(settings)}, " +
                    "remembered ${remembered?.fastTools ?: "none"}), inputBuffers=$NATIVE_INPUT_BUFFERS, " +
                    "threads=$threadCount, lag=0, AQ=cyclic-refresh, WebRTC real-time tools",
            )
        } else {
            val format = MediaFormat.createVideoFormat(
                videoCodec.mimeType,
                settings.resolution.width,
                settings.resolution.height,
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    if (videoCodec.usesSurfaceInput && debugRawInputFile == null) {
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    } else {
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    },
                )
                val startupShaping = keyFrameShapingFraction()
                setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    startupShaping?.let { (activeBitrate * it).toInt() } ?: activeBitrate,
                )
                if (startupShaping != null) shapedKeyFramePending = true
                setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps)
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, settings.fps.toFloat())
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, settings.keyFrameIntervalSeconds)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                activeBitrateMode?.let { setInteger(MediaFormat.KEY_BITRATE_MODE, it) }
                activeComplexity?.let { setInteger(MediaFormat.KEY_COMPLEXITY, it) }
                // Experimental (off): cyclic intra refresh instead of periodic keyframes, where the
                // encoder supports it; the applied period is read back from the output format.
                DebugTunables.intraRefreshFrames?.let { setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, it) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    DebugTunables.hardwareQpMin?.let { qp ->
                        setInteger(MediaFormat.KEY_VIDEO_QP_I_MIN, qp)
                        setInteger(MediaFormat.KEY_VIDEO_QP_P_MIN, qp)
                    }
                    DebugTunables.hardwareQpMax?.let { qp ->
                        setInteger(MediaFormat.KEY_VIDEO_QP_I_MAX, qp)
                        setInteger(MediaFormat.KEY_VIDEO_QP_P_MAX, qp)
                    }
                }
                encodingStatisticsEnabled = supportsEncodingStatistics()
                if (encodingStatisticsEnabled) {
                    setInteger(
                        MediaFormat.KEY_VIDEO_ENCODING_STATISTICS_LEVEL,
                        MediaFormat.VIDEO_ENCODING_STATISTICS_LEVEL_1,
                    )
                }
                // Experimental temporal layering: two layers are requested from the hardware
                // encoder only with the extra DEBUG switch (error-resilient layered VP9 cost ~11 VMAF
                // on the M31); output frames are only marked droppable after their bitstream proves it.
                temporalLayeringRequested = experimentalTemporalLayers && DebugTunables.hardwareTemporalLayers
                if (temporalLayeringRequested) {
                    setString(MediaFormat.KEY_TEMPORAL_LAYERING, TEMPORAL_LAYERING_SCHEMA)
                }
            }
            // Experimental bounded media age also verifies AV1 frames an encoder makes droppable on
            // its own (no layering is requested), so call queues can skip proven non-reference frames.
            // VP9 is excluded (see BoundedMediaAge.nonReferenceSkipAllowed).
            if (experimentalTemporalLayers ||
                DebugTunables.experimentalBoundedMediaAge && videoCodec.mimeType == MediaFormat.MIMETYPE_VIDEO_AV1
            ) {
                bitstreamDroppability = BitstreamDroppability(videoCodec.mimeType)
            }

            codec = MediaCodec.createByCodecName(videoCodec.encoderName)
            var configured = false
            if (temporalLayeringRequested) {
                try {
                    checkNotNull(codec).setCallback(codecCallback, worker)
                    checkNotNull(codec).configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    configured = true
                } catch (error: Exception) {
                    // An encoder may reject the schema outright; encode single-layer instead.
                    Log.w(
                        "BluetoothVideo",
                        "${videoCodec.encoderName} rejected temporal layering: ${error.message}; " +
                            "encoding a single layer",
                    )
                    codec?.release()
                    temporalLayeringRequested = false
                    format.removeKey(MediaFormat.KEY_TEMPORAL_LAYERING)
                    codec = MediaCodec.createByCodecName(videoCodec.encoderName)
                }
            }
            checkNotNull(codec).let { encoder ->
                if (!configured) {
                    encoder.setCallback(codecCallback, worker)
                    encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
                if (videoCodec.usesSurfaceInput && debugRawInputFile == null) {
                    encoderSurface = encoder.createInputSurface()
                }
                logEncoderCapabilities(encoder)
                encoder.start()
            }
        }
        debugMediaTrace = DebugMediaTrace.startIfRequested(
            context,
            debugTraceSessionName,
            "sender",
            videoCodec.protocolId,
            settings.resolution.width,
            settings.resolution.height,
            settings.fps,
            videoCodec.encoderName,
        )
        Log.i(
            "BluetoothVideo",
            "Encoder statistics ${if (encodingStatisticsEnabled) "enabled" else "unavailable"} " +
                "for ${videoCodec.encoderName}",
        )

        if (debugRawInputFile != null) {
            startRawInput(debugRawInputFile)
            return
        }
        if (!videoCodec.usesSurfaceInput) {
            imageReader = ImageReader.newInstance(
                cameraInputWidth,
                cameraInputHeight,
                android.graphics.ImageFormat.YUV_420_888,
                2,
            ).also { reader ->
                reader.setOnImageAvailableListener({ availableReader ->
                    if (closed.get()) {
                        availableReader.acquireLatestImage()?.close()
                        return@setOnImageAvailableListener
                    }
                    if (usesNativeAom) {
                        availableReader.acquireLatestImage()?.use(::queueNativeFrame)
                    } else {
                        pendingImage?.close()
                        pendingImage = availableReader.acquireLatestImage()
                        queuePendingFrame()
                    }
                }, worker)
            }
        } else if (referenceRecorder != null) {
            referenceImageReader = ImageReader.newInstance(
                settings.resolution.width,
                settings.resolution.height,
                android.graphics.ImageFormat.YUV_420_888,
                3,
            ).also { reader ->
                reader.setOnImageAvailableListener({ availableReader ->
                    availableReader.acquireLatestImage()?.use(referenceRecorder::record)
                }, worker)
            }
        }

        if (previewSurface != null) {
            cameraManager.openCamera(checkNotNull(selectedCameraId), cameraCallback, worker)
        } else {
            viewfinderSurface = CameraViewfinderSurface(
                context,
                viewfinder,
                checkNotNull(cameraCharacteristics),
                settings.resolution.width,
                settings.resolution.height,
                scaleMode,
                { surface ->
                    worker.post {
                        if (!closed.get()) {
                            previewSurface = surface
                            runCatching {
                                cameraManager.openCamera(checkNotNull(selectedCameraId), cameraCallback, worker)
                            }.onFailure { error ->
                                notifyFailure("Camera could not open: ${error.message}", error)
                            }
                        }
                    }
                },
                { error -> notifyFailure("Camera preview surface failed: ${error.message ?: error.javaClass.simpleName}", error) },
            ).also(CameraViewfinderSurface::request)
        }
    }

    private fun startRawInput(file: File) {
        val width = settings.resolution.width
        val height = settings.resolution.height
        require(width % 2 == 0 && height % 2 == 0) { "I420 needs even dimensions" }
        val frameBytes = width.toLong() * height * 3 / 2
        require(frameBytes in 1..Int.MAX_VALUE.toLong() && file.length() >= frameBytes &&
            file.length() % frameBytes == 0L
        ) { "Raw fixture must contain complete ${width}x$height I420 frames" }
        rawFrame = ByteArray(frameBytes.toInt())
        rawInput = RandomAccessFile(file, "r")
        worker.post(rawFeed)
    }

    private val rawFeed = object : Runnable {
        override fun run() {
            if (closed.get()) return
            if (rawStartedUs == 0L) {
                if (!usesNativeAom && pendingInputBuffers.isEmpty()) {
                    worker.postDelayed(this, 1L)
                    return
                }
                rawStartedNs = System.nanoTime()
                rawStartedUs = SystemClock.elapsedRealtimeNanos() / 1_000L
                rawNextFrameNs = rawStartedNs
                val file = checkNotNull(debugRawInputFile)
                val width = settings.resolution.width
                val height = settings.resolution.height
                val frameBytes = width.toLong() * height * 3 / 2
                Log.i("BluetoothVideo", "Benchmark raw source: ${file.name}, ${file.length() / frameBytes} looping I420 frames, ${width}x$height at ${settings.fps} FPS, ${videoCodec.encoderName}, ${settings.bitRate / 1_000} kbps, ${rateControl.label}, frame-zero PTS $rawStartedUs us; Camera2 and physical motion unmeasured")
                onStatus("Streaming ${videoCodec.displayName} at ${width}x$height, ${settings.fps} FPS, " +
                    "${settings.bitRate / 1_000} kbps, ${rateControl.label} mode $activeBitrateMode (recorded raw motion fixture)")
            }
            val nowNs = System.nanoTime()
            val periodNs = 1_000_000_000L / rawInputFps
            if (nowNs < rawNextFrameNs) {
                worker.postDelayed(this, maxOf(1L, (rawNextFrameNs - nowNs) / 1_000_000L))
                return
            }
            val missedFrames = (nowNs - rawNextFrameNs) / periodNs
            rawInputDrops += missedFrames
            rawNextFrameNs += missedFrames * periodNs
            val sourceFrame = ((rawNextFrameNs - rawStartedNs) * settings.fps /
                1_000_000_000.0).roundToLong()
            val presentationTimeUs = rawStartedUs + sourceFrame * 1_000_000L / settings.fps
            debugStageTrace?.record("raw_capture", presentationTimeUs, checkNotNull(rawFrame).size)
            try {
                val input = checkNotNull(rawInput)
                val frame = checkNotNull(rawFrame)
                val fixtureFrame = sourceFrame % (input.length() / frame.size)
                if (usesNativeAom) {
                    val buffers = checkNotNull(nativeInputBuffers)
                    val target = buffers.poll()
                    if (target == null) {
                        rawInputDrops++
                    } else {
                        try {
                            input.seek(fixtureFrame * frame.size)
                            input.readFully(frame)
                            // Detection only: libaom already caps inter frames at the link budget.
                            inspectEncoderInput(presentationTimeUs, width = settings.resolution.width,
                                height = settings.resolution.height) {
                                lumaPlaneOf(frame, 0, settings.resolution.width)
                            }
                            target.clear()
                            target.put(frame).flip()
                            if (nativeWorker?.post {
                                    try { encodeNativeFrame(target, presentationTimeUs) }
                                    finally { target.clear(); buffers.offer(target) }
                                } != true
                            ) buffers.offer(target)
                        } catch (error: Exception) {
                            buffers.offer(target)
                            throw error
                        }
                    }
                } else {
                    val inputIndex = pendingInputBuffers.pollFirst()
                    if (inputIndex == null) {
                        rawInputDrops++
                    } else {
                        input.seek(fixtureFrame * frame.size)
                        input.readFully(frame)
                        val encoder = checkNotNull(codec)
                        val image = encoder.getInputImage(inputIndex)
                            ?: error("${encoder.name} does not expose a raw YUV input image")
                        copyRawI420(frame, image)
                        inspectEncoderInput(presentationTimeUs, width = settings.resolution.width,
                            height = settings.resolution.height) {
                            lumaPlaneOf(frame, 0, settings.resolution.width)
                        }
                        if (queuedInputTimesNs.size >= 32) queuedInputTimesNs.remove(queuedInputTimesNs.keys.first())
                        queuedInputTimesNs[presentationTimeUs] = System.nanoTime()
                        debugStageTrace?.record("encode_input", presentationTimeUs, frame.size)
                        encoder.queueInputBuffer(inputIndex, 0, frame.size, presentationTimeUs, 0)
                    }
                }
            } catch (error: Exception) {
                notifyFailure("Raw fixture input failed: ${error.message}", error)
                return
            }
            rawNextFrameNs += periodNs
            worker.postDelayed(this, maxOf(1L, (rawNextFrameNs - System.nanoTime()) / 1_000_000L))
        }
    }

    private fun copyRawI420(frame: ByteArray, image: Image) {
        var sourceOffset = 0
        image.planes.forEachIndexed { index, plane ->
            val width = settings.resolution.width / if (index == 0) 1 else 2
            val height = settings.resolution.height / if (index == 0) 1 else 2
            val target = plane.buffer.duplicate()
            val targetOffset = target.position()
            repeat(height) { row ->
                val targetRow = targetOffset + row * plane.rowStride
                if (plane.pixelStride == 1) {
                    target.position(targetRow)
                    target.put(frame, sourceOffset + row * width, width)
                } else {
                    repeat(width) { column ->
                        target.put(targetRow + column * plane.pixelStride, frame[sourceOffset + row * width + column])
                    }
                }
            }
            sourceOffset += width * height
        }
    }

    private val cameraCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(openedCamera: CameraDevice) {
            if (closed.get()) {
                openedCamera.close()
                return
            }
            camera = openedCamera
            configureCaptureSession()
        }

        override fun onDisconnected(disconnectedCamera: CameraDevice) {
            disconnectedCamera.close()
            notifyFailure("Camera disconnected.")
        }

        override fun onError(failedCamera: CameraDevice, error: Int) {
            failedCamera.close()
            notifyFailure("Camera error $error")
        }
    }

    /** Replaces a SurfaceView surface that Android discarded while the activity was backgrounded. */
    fun refreshViewfinderSurface() {
        if (closed.get() || camera == null) return
        val cameraId = cameraManager.cameraIdFor(cameraLens) ?: return
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        viewfinderSurface?.close()
        viewfinderSurface = null
        previewSurface = null
        CameraViewfinderSurface(
            context,
            viewfinder,
            characteristics,
            settings.resolution.width,
            settings.resolution.height,
            scaleMode,
            { surface ->
                if (!closed.get()) {
                    previewSurface = surface
                    worker.post {
                        if (!closed.get()) configureCaptureSession()
                    }
                }
            },
            { error ->
                if (!closed.get()) {
                    notifyFailure(
                        "Camera preview surface failed: ${error.message ?: error.javaClass.simpleName}",
                        error,
                    )
                }
            },
        ).also { replacement ->
            viewfinderSurface = replacement
            replacement.request()
        }
    }

    private fun configureCaptureSession() {
        val openedCamera = camera ?: return
        val videoInputSurface = encoderSurface ?: imageReader?.surface ?: return
        val targets = listOfNotNull(previewSurface, videoInputSurface, referenceImageReader?.surface)
        val generation = ++captureSessionGeneration
        session?.close()
        session = null
        captureTargets = targets
        runCatching { openedCamera.createCaptureSession(
            targets,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configuredSession: CameraCaptureSession) {
                    if (closed.get() || generation != captureSessionGeneration) {
                        configuredSession.close()
                        return
                    }
                    session = configuredSession
                    applyRepeatingRequest()
                    val cameraFps = targetFpsRange?.let { "camera ${it.lower}–${it.upper} FPS" }
                        ?: "camera default FPS"
                    onStatus("Streaming ${videoCodec.displayName} at " +
                        "${settings.resolution.width}×${settings.resolution.height}, ${settings.fps} FPS, " +
                        "${settings.bitRate / 1_000} kbps, ${settings.latencyMs} ms budget, " +
                        "${rateControl.label} mode $activeBitrateMode" +
                        (activeComplexity?.let { ", complexity $it" } ?: "") +
                        (nativeSpeedGovernor?.cpuUsed?.let { ", libaom cpu-used $it" } ?: "") +
                        " ($cameraFps)")
                }

                override fun onConfigureFailed(failedSession: CameraCaptureSession) {
                    failedSession.close()
                    if (generation == captureSessionGeneration && !closed.get()) {
                        notifyFailure("Camera capture session could not be configured.")
                    }
                }
            },
            worker,
        ) }.onFailure { error ->
            notifyFailure("Camera capture session could not start: ${error.message}", error)
        }
    }

    private fun notifyFailure(message: String, error: Throwable = IllegalStateException(message)) {
        if (closed.get()) return
        onStatus(message)
        onFailure(error)
    }

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (closed.get()) return
            if (!videoCodec.usesSurfaceInput || debugRawInputFile != null) {
                pendingInputBuffers.addLast(index)
                if (debugRawInputFile == null) queuePendingFrame()
            }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (closed.get()) return
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val bytes = ByteArray(info.size)
                buffer.get(bytes)
                // The droppability verifier (experiments only, null otherwise) also sees configuration
                // and keyframes so it knows the AV1 sequence header before judging inter frames.
                val flags = encodedBufferFlags(
                    bitstreamDroppability,
                    codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0,
                    keyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
                    bytes = bytes,
                )
                if (flags and StreamProtocol.FLAG_KEY_FRAME != 0) {
                    val shaped = shapedKeyFramePending
                    if (shaped) {
                        worker.removeCallbacks(restoreShapedTarget)
                        worker.post(restoreShapedTarget)
                    }
                    onStatus("Encoded keyframe: ${info.size} bytes${if (shaped) " (shaped)" else ""}")
                }
                if (!firstVideoFrameSeen && flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                    firstVideoFrameSeen = true
                    onFirstVideoFrame()
                }
                if (flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                    queuedInputTimesNs.remove(info.presentationTimeUs)?.let { queuedAtNs ->
                        inputToEncodedOutput.recordNanos(System.nanoTime() - queuedAtNs)
                    }
                    val transportPresentationTimeUs = normalizedCodecPresentationTimeUs(
                        info.presentationTimeUs,
                        SystemClock.elapsedRealtimeNanos() / 1_000L,
                    )
                    // Diagnostic (on): this frame's VP9 quantizer, parsed from its uncompressed header.
                    val vp9Frame = if (isVp9) Vp9FrameHeader.accessUnit(bytes) else null
                    vp9Frame?.let { frame ->
                        lastVp9QIdx = frame.baseQIdx
                        quantizerWindow.record(frame.baseQIdx)
                    }
                    burstAccumulator.record(info.size, activeBitrate)
                    recordBitrateAdjusterFrame(info.presentationTimeUs, info.size, vp9Frame)
                    afterEncodedFrame(info.presentationTimeUs, info.size, flags, vp9Frame)
                    val frameFormat = if (encodingStatisticsEnabled) {
                        runCatching { codec.getOutputFormat(index) }.getOrNull()
                    } else {
                        null
                    }
                    val qp = frameFormat?.takeIf { it.containsKey(MediaFormat.KEY_VIDEO_QP_AVERAGE) }
                        ?.getInteger(MediaFormat.KEY_VIDEO_QP_AVERAGE)
                    val pictureType = frameFormat?.takeIf { it.containsKey(MediaFormat.KEY_PICTURE_TYPE) }
                        ?.getInteger(MediaFormat.KEY_PICTURE_TYPE)
                    val encodeLatencyUs =
                        SystemClock.elapsedRealtimeNanos() / 1_000L - info.presentationTimeUs
                    val encodeLatencyMs = encodeLatencyUs.takeIf { it in 0..MAX_TRACE_ENCODE_LATENCY_US }
                        ?.div(1_000.0)
                    if (encodeLatencyMs != null) captureToEncodedOutput.recordNanos(encodeLatencyUs * 1_000L)
                    debugMediaTrace?.record(
                        transportPresentationTimeUs,
                        flags,
                        bytes,
                        qp,
                        pictureType,
                        encodeLatencyMs,
                        activeBitrate,
                        "pre_transport",
                    )
                    val previousPresentationTimeUs = lastEncodedPresentationTimeUs
                    if (previousPresentationTimeUs != Long.MIN_VALUE &&
                        info.presentationTimeUs > previousPresentationTimeUs
                    ) {
                        encodedFrameInterval.recordNanos(
                            (info.presentationTimeUs - previousPresentationTimeUs) * 1_000L,
                        )
                    }
                    lastEncodedPresentationTimeUs = info.presentationTimeUs
                    if (cadenceReportStartedNs == 0L) cadenceReportStartedNs = System.nanoTime()
                    cadenceReportFrames++
                    cadenceReportBytes += info.size
                    reportEncoderCadenceIfDue()
                    debugStageTrace?.record(
                        "encode_output",
                        transportPresentationTimeUs,
                        bytes.size,
                        extra = vp9Frame?.let { mapOf("q_idx" to it.baseQIdx, "frame_type" to it.label) },
                    )
                    onPacket(flags, transportPresentationTimeUs, bytes)
                } else {
                    onPacket(flags, info.presentationTimeUs, bytes)
                }
            }
            codec.releaseOutputBuffer(index, false)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            debugMediaTrace?.setCodecFormat(format)
            if (closed.get()) return
            if (temporalLayeringRequested && !layeringFormatLogged) {
                layeringFormatLogged = true
                Log.i(
                    "BluetoothVideo",
                    "${videoCodec.encoderName} temporal layering requested $TEMPORAL_LAYERING_SCHEMA, " +
                        "reported ${format.getStringOrNull(MediaFormat.KEY_TEMPORAL_LAYERING) ?: "none"}",
                )
            }
            format.getByteBuffer("csd-0")?.let { data ->
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                onPacket(StreamProtocol.FLAG_CODEC_CONFIG, 0, bytes)
            }
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            if (closed.get()) return
            notifyFailure("Encoder error: ${error.diagnosticInfo}", error)
        }
    }

    private fun normalizedCodecPresentationTimeUs(
        presentationTimeUs: Long,
        elapsedRealtimeUs: Long,
    ): Long {
        val monotonicTimeUs = System.nanoTime() / 1_000L
        val offsetUs = codecPresentationTimeOffsetUs ?: codecPresentationTimeEpochOffsetUs(
            presentationTimeUs,
            elapsedRealtimeUs,
            monotonicTimeUs,
        ).also { selectedOffsetUs ->
            codecPresentationTimeOffsetUs = selectedOffsetUs
            Log.i("BluetoothVideo", "Encoder clock: raw PTS=$presentationTimeUs us, elapsed=$elapsedRealtimeUs us, nano=$monotonicTimeUs us, offset=$selectedOffsetUs us")
            if (selectedOffsetUs != 0L) {
                Log.i(
                    "BluetoothVideo",
                    "Normalized ${videoCodec.encoderName} video timestamps to the elapsed-realtime A/V epoch.",
                )
            }
        }
        return presentationTimeUs + offsetUs
    }

    private fun reportEncoderCadenceIfDue() {
        val nowNs = System.nanoTime()
        val elapsedNs = nowNs - cadenceReportStartedNs
        if (elapsedNs < CADENCE_REPORT_INTERVAL_NS) return
        val elapsedSeconds = elapsedNs / 1_000_000_000.0
        val cadence = encodedFrameInterval.snapshot()
        val outputLatency = captureToEncodedOutput.snapshot()
        val outputLabel = if (debugRawInputFile != null) "raw-input-to-output" else if (cameraTimestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME &&
            codecPresentationTimeOffsetUs == 0L) "capture-to-output" else "raw-PTS age (clock unverified)"
        val outputTiming = if (outputLatency.samples > 0) {
            "$outputLabel p50/p95/max %.1f/%.1f/%.1f ms".format(outputLatency.p50Ms, outputLatency.p95Ms, outputLatency.maxMs)
        } else "$outputLabel unavailable"
        val inputLatency = inputToEncodedOutput.snapshot()
        val inputTiming = if (inputLatency.samples > 0) {
            ", codec input-to-output p50/p95/max %.1f/%.1f/%.1f ms".format(inputLatency.p50Ms, inputLatency.p95Ms, inputLatency.maxMs)
        } else ", codec input-to-output unavailable"
        val cameraTiming = if (BuildConfig.DEBUG) {
            val inputAge = captureToCodecInput.snapshot()
            val copy = yuvCopyLatency.snapshot()
            ", capture-to-input p50/p95 %.1f/%.1f ms (%d samples), YUV copy p95 %.1f ms, exposure %s ms, sensor frame %s ms, pipeline depth %s".format(
                inputAge.p50Ms, inputAge.p95Ms, inputAge.samples, copy.p95Ms,
                exposureTimeNs?.div(1_000_000.0) ?: "unavailable",
                sensorFrameDurationNs?.div(1_000_000.0) ?: "unavailable",
                cameraPipelineDepth ?: "unavailable",
            )
        } else ""
        val nativeTiming = if (usesNativeAom) {
            val copy = nativeCopyLatency.snapshot()
            val encode = nativeEncodeLatency.snapshot()
            ", native copy p95 %.1f ms, encode p95 %.1f ms, capture drops %d".format(
                copy.p95Ms,
                encode.p95Ms,
                nativeInputDrops,
            )
        } else {
            ""
        } + layeringSummary() + quantizerSummary()
        Log.i(
            "BluetoothVideo",
            ("Encoder output: %.1f FPS, %.0f kbps, PTS cadence p50/p95/max %.1f/%.1f/%.1f ms, " +
                "%s%s%s%s%s").format(
                cadenceReportFrames / elapsedSeconds,
                cadenceReportBytes * 8.0 / elapsedSeconds / 1_000.0,
                cadence.p50Ms,
                cadence.p95Ms,
                cadence.maxMs,
                outputTiming,
                inputTiming,
                nativeTiming,
                cameraTiming,
                if (debugRawInputFile != null) ", raw input drops $rawInputDrops" else "",
            ),
        )
        // The bitrate adjuster replaces the 5 s output-rate trim for MediaCodec encoders while it is on.
        if (rateControl == EncoderRateControl.AUTOMATIC && DebugTunables.encoderRateTrim && bitrateAdjuster == null) {
            val requested = activeBitrate
            rateTrim.onWindow(requested, cadenceReportBytes * 8.0 / elapsedSeconds)?.let { target ->
                Log.i(
                    "BluetoothVideo",
                    "Encoder rate trim: output %.0f kbps for %d kbps requested; encoder target now %d kbps".format(
                        cadenceReportBytes * 8.0 / elapsedSeconds / 1_000.0,
                        requested / 1_000,
                        target / 1_000,
                    ),
                )
                applyEncoderTarget(target)
            }
        }
        cadenceReportStartedNs = nowNs
        cadenceReportFrames = 0
        cadenceReportBytes = 0
    }

    /**
     * Diagnostic (on, once per MediaCodec encoder start): the burst-control knobs this encoder
     * actually offers, read from the platform instead of assumed (BENCHMARK encoder_capabilities).
     * Framework facts (android-frameworks-av): on the ACodec/OMX path (M31 OMX.Exynos.VP9)
     * getSupportedVendorParameters() is always empty because ACodec::querySupportedParameters lists
     * nothing, video-qp-* bounds are never passed to the component, and OMX vendor extensions show
     * only as vendor.* keys of the port formats; intra-refresh-period is applied through
     * OMX_IndexConfigAndroidIntraRefresh (or cyclic macroblocks at configure) and reported back in
     * the output format only when the component accepted it. Codec2 (S24 c2.exynos.vp9) lists its
     * vendor parameters and maps QP bounds and intra refresh to C2 tunings.
     */
    private fun logEncoderCapabilities(encoder: MediaCodec) {
        val line = runCatching {
            val capabilities = encoder.codecInfo.getCapabilitiesForType(videoCodec.mimeType)
            fun feature(name: String) = runCatching { capabilities.isFeatureSupported(name) }.getOrDefault(false)
            val path = when {
                videoCodec.encoderName.startsWith("OMX.", ignoreCase = true) -> "omx"
                videoCodec.encoderName.startsWith("c2.", ignoreCase = true) -> "codec2"
                else -> "other"
            }
            val vendorParameters = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    JSONArray().apply {
                        encoder.supportedVendorParameters.sorted().forEach { name ->
                            val type = runCatching { encoder.getParameterDescriptor(name)?.type }.getOrNull()
                            put(if (type == null) name else "$name:$type")
                        }
                    }
                }.getOrElse { error -> JSONArray().put("error: ${error.message}") }
            } else {
                null
            }
            val input = runCatching { encoder.inputFormat }.getOrNull()
            val output = runCatching { encoder.outputFormat }.getOrNull()
            fun MediaFormat?.keysMatching(predicate: (String) -> Boolean): JSONObject {
                val values = JSONObject()
                if (this == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return values
                keys.filter(predicate).sorted().forEach { key ->
                    values.put(key, runCatching { getInteger(key).toString() }.getOrNull()
                        ?: runCatching { getString(key) }.getOrNull()
                        ?: runCatching { getFloat(key).toString() }.getOrNull()
                        ?: "?")
                }
                return values
            }
            val burstKeys: (String) -> Boolean = { key ->
                key.startsWith("vendor.") || "qp" in key || key == "intra-refresh-period" ||
                    key == MediaFormat.KEY_BITRATE_MODE || key == MediaFormat.KEY_BIT_RATE
            }
            JSONObject()
                .put("encoder", videoCodec.encoderName)
                .put("path", path)
                .put("api", Build.VERSION.SDK_INT)
                .put("hardware", videoCodec.hardwareAccelerated)
                .put("surface_input", videoCodec.usesSurfaceInput && debugRawInputFile == null)
                .put("feature_intra_refresh", feature(MediaCodecInfo.CodecCapabilities.FEATURE_IntraRefresh))
                .put("feature_qp_bounds", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    feature(MediaCodecInfo.CodecCapabilities.FEATURE_QpBounds))
                .put("feature_encoding_statistics", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    feature(MediaCodecInfo.CodecCapabilities.FEATURE_EncodingStatistics))
                .put("bitrate_modes", JSONArray(videoCodec.supportedBitrateModes.sorted()))
                .put("requested_bitrate_mode", activeBitrateMode ?: JSONObject.NULL)
                .put("requested_intra_refresh_frames", DebugTunables.intraRefreshFrames ?: JSONObject.NULL)
                .put("vendor_parameters", vendorParameters ?: "unavailable before API 31")
                .put("input_format", input.keysMatching(burstKeys))
                .put("output_format", output.keysMatching(burstKeys))
                .toString()
        }.getOrElse { error -> "{\"encoder\":\"${videoCodec.encoderName}\",\"error\":\"${error.javaClass.simpleName}\"}" }
        Log.i("BluetoothVideo", "BENCHMARK encoder_capabilities v1 $line")
    }

    private fun supportsEncodingStatistics(): Boolean {
        if (usesNativeAom) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .first { it.name == videoCodec.encoderName }
                .getCapabilitiesForType(videoCodec.mimeType)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_EncodingStatistics)
        }.getOrDefault(false)
    }

    private fun queueNativeFrame(source: Image) {
        val buffers = nativeInputBuffers ?: return
        val input = buffers.poll()
        if (input == null) {
            nativeInputDrops++
            if (nativeInputDrops == 1 || nativeInputDrops % 30 == 0) {
                Log.w("BluetoothVideo", "Native encoder dropped $nativeInputDrops camera frame(s)")
            }
            return
        }
        try {
            val startedCopyNs = System.nanoTime()
            val planes = source.planes
            // The visible region (Image.getCropRect) shifts each plane's start; the full image keeps
            // the former call exactly.
            val visible = visibleRegion(source)
            fun start(index: Int): Int {
                val area = YuvPlaneCopy.planeRegion(visible, index)
                return planes[index].buffer.position() + area.top * planes[index].rowStride +
                    area.left * planes[index].pixelStride
            }
            val nativeCopied = planes.size >= 3 && NativeAomBridge.nativeCopyYuv420ToI420(
                planes[0].buffer,
                start(0),
                planes[0].buffer.limit(),
                planes[0].rowStride,
                planes[0].pixelStride,
                planes[1].buffer,
                start(1),
                planes[1].buffer.limit(),
                planes[1].rowStride,
                planes[1].pixelStride,
                planes[2].buffer,
                start(2),
                planes[2].buffer.limit(),
                planes[2].rowStride,
                planes[2].pixelStride,
                visible.width,
                visible.height,
                input,
                settings.resolution.width,
                settings.resolution.height,
            )
            if (!nativeCopied) copyYuv420ToI420(source, input)
            nativeCopyLatency.recordNanos(System.nanoTime() - startedCopyNs)
            referenceRecorder?.record(source, visible)
            val presentationTimeUs = source.timestamp / 1_000
            inspectEncoderInput(presentationTimeUs, width = settings.resolution.width, height = settings.resolution.height) {
                lumaPlaneOf(input, 0, settings.resolution.width, 1)
            }
            nativeWorker?.post {
                try {
                    encodeNativeFrame(input, presentationTimeUs)
                } finally {
                    input.clear()
                    buffers.offer(input)
                }
            } ?: buffers.offer(input)
        } catch (error: Exception) {
            buffers.offer(input)
            onStatus("Native libaom input error: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun createNativeEncoder(cpuUsed: Int, bitrate: Int): NativeAomEncoder = NativeAomEncoder(
        width = settings.resolution.width,
        height = settings.resolution.height,
        fps = settings.fps,
        bitrate = bitrate,
        cpuUsed = cpuUsed,
        threads = nativeThreadCount(settings.resolution),
        variableBitrate = activeBitrateMode ==
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
        // Automatic keeps every frame: libaom frame dropping froze the hardest coverage
        // segment for up to 533 ms; rate control raises quantization instead.
        dropFrames = rateControl == EncoderRateControl.CBR_FD,
        tuning = NativeAomTuning(realtimeToolProfile = nativeFastTools),
    )

    /**
     * The governor is pinned at the fastest preset and still over budget with the full tool set:
     * re-create the encoder with the real-time fast tool profile (sequence-level tools cannot
     * change on a live libaom encoder), costing one keyframe.
     */
    private fun recreateNativeEncoderWithFastTools(): NativeAomEncoder? {
        val previous = nativeEncoder ?: return null
        val cpuUsed = nativeSpeedGovernor?.cpuUsed ?: checkNotNull(videoCodec.nativeCpuUsed(settings))
        val target = if (rateControl == EncoderRateControl.AUTOMATIC) {
            rateTrim.encoderTargetBps(activeBitrate)
        } else {
            activeBitrate
        }
        nativeFastTools = true
        val replacement = runCatching { createNativeEncoder(cpuUsed, target) }.getOrElse { error ->
            nativeFastTools = false
            Log.w("BluetoothVideo", "Native encoder could not be re-created with the fast tool profile", error)
            return previous
        }
        runCatching { previous.close() }
        if (nativeLastInterQuantizer >= 0) replacement.setKeyFrameQuantizerReference(nativeLastInterQuantizer)
        if (nativeInterBitratePct > 0) replacement.updateMaxInterBitratePct(nativeInterBitratePct)
        nativeEncoder = replacement
        nativeExplicitReferences = false
        nativeKeyFrameRequested.set(true)
        return replacement
    }

    /**
     * Experimental temporal layering: back to libaom's own reference structure (full quality) after
     * the links calmed down. libaom clears explicit references only for a new encoder, so this costs
     * one keyframe, floored near the current quantizer, at a time when every link has had room for
     * 20 s.
     */
    private fun recreateNativeEncoderForFullReferences(): NativeAomEncoder? {
        val previous = nativeEncoder ?: return null
        val cpuUsed = nativeSpeedGovernor?.cpuUsed ?: checkNotNull(videoCodec.nativeCpuUsed(settings))
        val target = if (rateControl == EncoderRateControl.AUTOMATIC) {
            rateTrim.encoderTargetBps(activeBitrate)
        } else {
            activeBitrate
        }
        val replacement = runCatching { createNativeEncoder(cpuUsed, target) }.getOrElse { error ->
            Log.w("BluetoothVideo", "Native encoder could not be re-created; keeping explicit references", error)
            return previous
        }
        runCatching { previous.close() }
        if (nativeLastInterQuantizer >= 0) replacement.setKeyFrameQuantizerReference(nativeLastInterQuantizer)
        if (nativeInterBitratePct > 0) replacement.updateMaxInterBitratePct(nativeInterBitratePct)
        nativeEncoder = replacement
        nativeExplicitReferences = false
        Log.i(
            "BluetoothVideo",
            "Native encoder back to libaom's own references (links calm); one keyframe floored near QP $nativeLastInterQuantizer",
        )
        return replacement
    }

    private fun encodeNativeFrame(input: ByteBuffer, presentationTimeUs: Long) {
        debugStageTrace?.record("encode_input", presentationTimeUs, input.remaining())
        var encoder = nativeEncoder ?: return
        try {
            val keyFrameIntervalUs = settings.keyFrameIntervalSeconds * 1_000_000L
            // Experimental temporal layering only; without it wantLayers is false, no encoder is
            // re-created and every frame is encoded with layer -1 (libaom's own references).
            val wantLayers = nativeTemporalLayers && droppableFramesWanted
            var recreated = false
            if (wantLayers) {
                nativeExplicitReferences = true
            } else if (nativeExplicitReferences) {
                encoder = recreateNativeEncoderForFullReferences() ?: return
                recreated = !nativeExplicitReferences
            }
            val forceKeyFrame = recreated || nativeKeyFrameRequested.getAndSet(false) ||
                lastNativeKeyFramePtsUs == Long.MIN_VALUE ||
                presentationTimeUs - lastNativeKeyFramePtsUs >= keyFrameIntervalUs
            if (forceKeyFrame) nativeLayerFrameIndex = 0L
            val temporalLayer = nativeTemporalLayer(wantLayers, nativeLayerFrameIndex)
            nativeLayerFrameIndex++
            val startedNs = System.nanoTime()
            val packets = encoder.encode(
                input,
                presentationTimeUs,
                forceKeyFrame,
                layer = if (nativeExplicitReferences) temporalLayer else -1,
            )
            val encodeLatencyMs = (System.nanoTime() - startedNs) / 1_000_000.0
            nativeEncodeLatency.recordNanos(System.nanoTime() - startedNs)
            var escalateToFastTools = false
            nativeSpeedGovernor?.let { governor ->
                val previous = governor.cpuUsed
                governor.applyThermalFloor(thermalSpeedFloor)?.let { raised ->
                    encoder.updateCpuUsed(raised)
                    Log.i("BluetoothVideo", "Native libaom speed: cpu-used $previous -> $raised (thermal floor $thermalSpeedFloor)")
                }
                governor.record(encodeLatencyMs, System.nanoTime())?.let { next ->
                    encoder.updateCpuUsed(next)
                    Log.i(
                        "BluetoothVideo",
                        "Native libaom speed: cpu-used $previous -> $next " +
                            "(frame budget %.1f ms)".format(1_000.0 / settings.fps),
                    )
                }
                val overloadReport = onNativeOverloadAtFastest
                if (overloadReport != null && !nativeOverloadReported && nativeFastTools &&
                    governor.overloadedAtFastestWindows >= CallEncoderFit.OVERLOAD_WINDOWS
                ) {
                    nativeOverloadReported = true
                    overloadReport(nativeSpeedKey)
                }
                nativeSpeedLearner?.let { learner ->
                    escalateToFastTools = learner.shouldEscalateToFastTools(governor)
                    // A thermally raised preset is not this phone's settled speed.
                    learner.settledRecord(governor)?.takeIf { thermalSpeedFloor == 0 }?.let { record ->
                        runCatching { nativeSpeedStore?.save(nativeSpeedKey, record) }
                        Log.i(
                            "BluetoothVideo",
                            "Native libaom settled: cpu-used ${record.cpuUsed}, fastTools=${record.fastTools} " +
                                "remembered for $nativeSpeedKey",
                        )
                    }
                }
            }
            packets.forEach { packet ->
                val flags = if (nativeTemporalLayers) {
                    if (!packet.keyFrame && packet.quantizer >= 0) nativeLastInterQuantizer = packet.quantizer
                    val verifiedDroppable = nativeDroppability.isDroppable(packet.bytes, packet.keyFrame)
                    if (temporalLayer == 1 && !packet.keyFrame && !verifiedDroppable) {
                        nativeUnverifiedDroppable++
                        if (nativeUnverifiedDroppable == 1L || nativeUnverifiedDroppable % 100 == 0L) {
                            Log.w(
                                "BluetoothVideo",
                                "Native frame requested droppable but its AV1 header refreshes references; " +
                                    "sent as a reference frame ($nativeUnverifiedDroppable so far)",
                            )
                        }
                    }
                    nativePacketFlags(packet.keyFrame, if (verifiedDroppable) 1 else 0)
                } else {
                    if (packet.keyFrame) StreamProtocol.FLAG_KEY_FRAME else 0
                }
                val transportPresentationTimeUs = normalizedCodecPresentationTimeUs(
                    packet.presentationTimeUs,
                    SystemClock.elapsedRealtimeNanos() / 1_000L,
                )
                if (packet.keyFrame) lastNativeKeyFramePtsUs = packet.presentationTimeUs
                burstAccumulator.record(packet.bytes.size, activeBitrate)
                if (!firstVideoFrameSeen) {
                    firstVideoFrameSeen = true
                    onFirstVideoFrame()
                }
                debugMediaTrace?.record(
                    transportPresentationTimeUs,
                    flags,
                    packet.bytes,
                    packet.quantizer,
                    if (packet.keyFrame) 1 else 2,
                    encodeLatencyMs,
                    activeBitrate,
                    "pre_transport",
                )
                val previousPresentationTimeUs = lastEncodedPresentationTimeUs
                if (previousPresentationTimeUs != Long.MIN_VALUE &&
                    packet.presentationTimeUs > previousPresentationTimeUs
                ) {
                    encodedFrameInterval.recordNanos(
                        (packet.presentationTimeUs - previousPresentationTimeUs) * 1_000L,
                    )
                }
                lastEncodedPresentationTimeUs = packet.presentationTimeUs
                if (cadenceReportStartedNs == 0L) cadenceReportStartedNs = System.nanoTime()
                cadenceReportFrames++
                cadenceReportBytes += packet.bytes.size
                reportEncoderCadenceIfDue()
                debugStageTrace?.record("encode_output", transportPresentationTimeUs, packet.bytes.size)
                onPacket(flags, transportPresentationTimeUs, packet.bytes)
            }
            if (escalateToFastTools) {
                val overloadedWindows = nativeSpeedGovernor?.overloadedAtFastestWindows ?: 0
                recreateNativeEncoderWithFastTools()
                Log.i(
                    "BluetoothVideo",
                    "Native libaom fast tool profile ${if (nativeFastTools) "enabled" else "unavailable"}: " +
                        "cpu-used ${nativeSpeedGovernor?.cpuUsed} still over the %.1f ms budget for $overloadedWindows windows; "
                            .format(1_000.0 / settings.fps) +
                        "encoder re-created (one keyframe)",
                )
            }
        } catch (error: Exception) {
            onStatus("Native libaom input error: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /**
     * A raw frame is about to enter the encoder (worker thread). Scene-cut detection runs when the
     * shaping experiment is on or the session is benchmark-traced; with the experiment on, a costly
     * cut lowers the MediaCodec target (and, in SYNC mode, requests a sync frame) before the frame
     * is queued; both the ACodec/OMX and CCodec paths deliver it to the component ahead of the next
     * queued input (see SceneCutShaper). The full target returns when that frame is encoded.
     */
    private inline fun inspectEncoderInput(
        presentationTimeUs: Long,
        width: Int,
        height: Int,
        plane: () -> LumaPlane,
    ) {
        val nowNs = System.nanoTime()
        if (sceneCutShaper.timedOut(nowNs)) {
            Log.i("BluetoothVideo", "Scene-cut shaping: the shaped frame was not returned within 1 s; target restored")
            applyCodecTarget("scene-cut restore after timeout")
        }
        if (!sceneCutDetection) return
        val detector = sceneCutDetector?.takeIf { it.width == width && it.height == height }
            ?: runCatching { SceneCutDetector(width, height) }.getOrNull()?.also { sceneCutDetector = it }
            ?: return
        val result = runCatching { detector.analyze(plane()) }.getOrNull() ?: return
        if (!result.cut) return
        sceneCutsDetected++
        val targetBps = if (usesNativeAom) activeBitrate else codecTargetBps()
        val shapingPlan = sceneCutShaper.plan(
            presentationTimeUs,
            nowNs,
            result,
            lastVp9QIdx,
            frameBudgetLinkKbps,
            targetBps,
            settings.fps,
        )
        var applied = false
        if (shapingPlan != null) {
            applied = runCatching {
                checkNotNull(codec).setParameters(
                    Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, shapingPlan.shapedBps)
                        if (shapingPlan.requestSync) putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    },
                )
            }.isSuccess
            if (!applied) sceneCutShaper.cancel()
        }
        Log.i(
            "BluetoothVideo",
            "BENCHMARK scene_cut v1 " + JSONObject()
                .put("pts_us", presentationTimeUs)
                .put("encoder", videoCodec.encoderName)
                .put("changed_fraction", roundedForLog(result.changedFraction, 3))
                .put("mean_delta", roundedForLog(result.meanDelta, 1))
                .put("recent_median_delta", roundedForLog(result.recentMedianDelta, 1))
                .put("detail", roundedForLog(result.detail, 1))
                .put("previous_detail", roundedForLog(result.previousDetail, 1))
                .put("detail_rise", result.detailRise)
                .put("last_q_idx", lastVp9QIdx ?: JSONObject.NULL)
                .put("mode", sceneCutShaper.mode.label)
                .put("action", when {
                    shapingPlan == null -> "none"
                    !applied -> "rejected"
                    shapingPlan.requestSync -> "sync"
                    else -> "target"
                })
                .put("skip_reason", sceneCutShaper.skipReason ?: JSONObject.NULL)
                .put("target_kbps", targetBps / 1_000)
                .put("shaped_kbps", shapingPlan?.shapedBps?.div(1_000) ?: JSONObject.NULL)
                .put("link_kbps", frameBudgetLinkKbps)
                .put("budget_bytes", shapingPlan?.budgetBytes ?: SceneCutShaper.cutBudgetBytes(frameBudgetLinkKbps, targetBps))
                .put("expected_bytes", shapingPlan?.expectedBytes ?: JSONObject.NULL)
                .put("learned_cut_frames", roundedForLog(sceneCutShaper.cutFrames, 1))
                .toString(),
        )
    }

    /**
     * A MediaCodec frame came out (worker thread): feeds [bitrateAdjuster], re-applies the codec
     * target when its scale changed and logs it at most once per second.
     */
    private fun recordBitrateAdjusterFrame(ptsUs: Long, bytes: Int, vp9: Vp9FrameInfo?) {
        if (framerateAdjusterArm) recordDeliveredFrameRate(ptsUs)
        val adjuster = bitrateAdjuster ?: return
        adjuster.setTarget(baseCodecTargetBps())
        val atCeiling = vp9 != null && vp9.baseQIdx >= Vp9FrameHeader.MAX_Q_IDX - 5
        if (adjuster.onEncodedFrame(ptsUs, bytes, settings.fps, atCeiling)) {
            applyCodecTarget("bitrate adjuster x${roundedForLog(adjuster.scale, 2)}")
        }
        val nowNs = System.nanoTime()
        if (bitrateAdjusterLogStartedNs == 0L) {
            bitrateAdjusterLogStartedNs = nowNs
            bitrateAdjusterLogBytes = 0L
            return
        }
        bitrateAdjusterLogBytes += bytes
        val elapsedNs = nowNs - bitrateAdjusterLogStartedNs
        if (elapsedNs < BITRATE_ADJUSTER_LOG_INTERVAL_NS) return
        Log.i(
            "BluetoothVideo",
            "BENCHMARK bitrate_adjuster: target=${adjuster.targetBps / 1_000} | " +
                "requested=${codecTargetBps() / 1_000} | " +
                "actualKbps=${(bitrateAdjusterLogBytes * 8_000_000.0 / elapsedNs).toInt()} | " +
                "scale=${roundedForLog(adjuster.scale, 3)} | exponent=${adjuster.scaleExponent} | " +
                "deviationKB=${roundedForLog(adjuster.deviationBytes / 1_000.0, 1)} | " +
                "adjustments=${adjuster.adjustments} | ceilingHolds=${adjuster.ceilingHolds} | " +
                "encoder=${videoCodec.encoderName}",
        )
        bitrateAdjusterLogStartedNs = nowNs
        bitrateAdjusterLogBytes = 0L
    }

    /** A MediaCodec frame came out (worker thread): scene-cut restore and quantizer headroom. */
    private fun afterEncodedFrame(ptsUs: Long, bytes: Int, flags: Int, vp9: Vp9FrameInfo?) {
        if (sceneCutShaper.pending) {
            val shapedPtsUs = sceneCutShaper.pendingPtsUs
            if (sceneCutShaper.onEncoded(ptsUs, bytes, settings.fps)) {
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK scene_cut_result v1 " + JSONObject()
                        .put("pts_us", ptsUs)
                        .put("shaped_pts_us", shapedPtsUs)
                        .put("same_frame", ptsUs == shapedPtsUs)
                        .put("bytes", bytes)
                        .put("key_frame", flags and StreamProtocol.FLAG_KEY_FRAME != 0)
                        .put("q_idx", vp9?.baseQIdx ?: JSONObject.NULL)
                        .put("budget_bytes", SceneCutShaper.cutBudgetBytes(frameBudgetLinkKbps, codecTargetBps()))
                        .put("learned_cut_frames", roundedForLog(sceneCutShaper.cutFrames, 1))
                        .toString(),
                )
                applyCodecTarget("scene cut encoded")
            }
        }
        val governor = quantizerHeadroom ?: return
        val qIdx = vp9?.baseQIdx ?: return
        governor.onFrame(qIdx, System.nanoTime())?.let { factor ->
            Log.i(
                "BluetoothVideo",
                "Experimental VP9 quantizer headroom: q_idx median ${governor.lastWindowMedian} vs floor " +
                    "${DebugTunables.vp9QuantizerFloor}; target factor ${roundedForLog(factor, 2)}",
            )
            applyCodecTarget("quantizer headroom x${roundedForLog(factor, 2)}")
        }
    }

    private fun queuePendingFrame() {
        if (closed.get()) {
            pendingImage?.close()
            pendingImage = null
            return
        }
        val source = pendingImage ?: return
        val inputIndex = pendingInputBuffers.pollFirst() ?: return
        pendingImage = null
        val sourcePresentationTimeUs = source.timestamp / 1_000L
        try {
            val target = codec?.getInputImage(inputIndex)
                ?: throw IllegalStateException("Encoder did not expose a flexible YUV input image")
            val copyStartedNs = if (BuildConfig.DEBUG) System.nanoTime() else 0L
            copyYuv420(source, target)
            if (BuildConfig.DEBUG) {
                yuvCopyLatency.recordNanos(System.nanoTime() - copyStartedNs)
                if (cameraTimestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
                    captureToCodecInput.recordNanos(SystemClock.elapsedRealtimeNanos() - source.timestamp)
                }
            }
            referenceRecorder?.record(source, visibleRegion(source))
            inspectEncoderInput(sourcePresentationTimeUs, width = source.width, height = source.height) {
                val luma = source.planes[0]
                lumaPlaneOf(luma.buffer, luma.buffer.position(), luma.rowStride, luma.pixelStride)
            }
            if (queuedInputTimesNs.size >= 32) queuedInputTimesNs.remove(queuedInputTimesNs.keys.first())
            queuedInputTimesNs[sourcePresentationTimeUs] = System.nanoTime()
            codec?.queueInputBuffer(
                inputIndex,
                0,
                settings.resolution.width * settings.resolution.height * 3 / 2,
                source.timestamp / 1_000,
                0,
            )
        } catch (error: Exception) {
            queuedInputTimesNs.remove(sourcePresentationTimeUs)
            if (!closed.get()) {
                runCatching {
                    codec?.queueInputBuffer(inputIndex, 0, 0, source.timestamp / 1_000, 0)
                }
                onStatus("${videoCodec.displayName} input error: ${error.message ?: error.javaClass.simpleName}")
            }
        } finally {
            source.close()
        }
    }

    private fun copyYuv420ToI420(source: Image, target: ByteBuffer) {
        target.clear()
        val visible = visibleRegion(source)
        var planeOffset = 0
        source.planes.forEachIndexed { planeIndex, sourcePlane ->
            val targetWidth = planeSize(settings.resolution.width, planeIndex)
            val targetHeight = planeSize(settings.resolution.height, planeIndex)
            val sourceCrop = YuvPlaneCopy.centerCropWithin(
                YuvPlaneCopy.planeRegion(visible, planeIndex), targetWidth, targetHeight,
            )
            val sourceBuffer = sourcePlane.buffer
            check(
                YuvPlaneCopy.copyPlane(
                    sourceBuffer, sourceBuffer.position(), sourcePlane.rowStride, sourcePlane.pixelStride, sourceCrop,
                    target, planeOffset, targetWidth, 1, targetWidth, targetHeight,
                ),
            ) { "YUV plane $planeIndex layout is outside its buffer (row ${sourcePlane.rowStride}, pixel ${sourcePlane.pixelStride})" }
            planeOffset += targetWidth * targetHeight
        }
        target.position(0)
        target.limit(planeOffset)
    }

    /** Luma size for plane 0, 4:2:0 chroma size otherwise. */
    private fun planeSize(lumaSamples: Int, planeIndex: Int): Int =
        if (planeIndex == 0) lumaSamples else YuvPlaneCopy.chromaSize(lumaSamples)

    /** The frame's valid region: Image.getCropRect() under the yuv-image-crop-rect entry, else the whole image. */
    private fun visibleRegion(source: Image): PlaneRegion = YuvPlaneCopy.visibleRegion(
        source.width,
        source.height,
        if (honourCropRect) source.cropRect?.let { intArrayOf(it.left, it.top, it.right, it.bottom) } else null,
    )

    /**
     * Workstream C: one inter frame may use at most [FRAME_AIRTIME_MS] of the link it is sent on
     * ([linkKbps], already divided among viewers). Scene-cut P frames reached 50-62 KB (16x the
     * average) and froze links for over a second; libaom then clamps that frame's target.
     */
    fun updateLinkFrameBudget(linkKbps: Int) {
        if (linkKbps > 0) frameBudgetLinkKbps = linkKbps
        if (!usesNativeAom || rateControl != EncoderRateControl.AUTOMATIC || linkKbps <= 0) return
        val percent = maxInterFramePercent(linkKbps, activeBitrate, settings.fps)
        nativeWorker?.post {
            nativeInterBitratePct = percent
            runCatching { nativeEncoder?.updateMaxInterBitratePct(percent) }
                .onSuccess {
                    Log.i(
                        "BluetoothVideo",
                        "Native frame budget: inter frames <= $percent% of average ($FRAME_AIRTIME_MS ms of $linkKbps kbps)",
                    )
                }
        }
    }

    fun updateBitrate(bitRate: Int) {
        if (bitRate <= 0 || bitRate == activeBitrate) return
        activeBitrate = bitRate
        rateTrim.onRequestChanged()
        applyEncoderTarget(if (rateControl == EncoderRateControl.AUTOMATIC) rateTrim.encoderTargetBps(bitRate) else bitRate)
    }

    /** Sets the bitrate the encoder itself targets; [activeBitrate] stays the app's request. */
    private fun applyEncoderTarget(bitRate: Int) {
        if (usesNativeAom) {
            nativeWorker?.post {
                runCatching { nativeEncoder?.updateBitrate(bitRate) }
                    .onSuccess {
                        onStatus("Encoder target: ${bitRate / 1_000} kbps")
                    }
                    .onFailure { error ->
                        onStatus(
                            "Native libaom rejected bitrate update: " +
                                (error.message ?: error.javaClass.simpleName),
                        )
                    }
            }
            return
        }
        worker.post {
            encoderTargetBps = bitRate
            // A shaped keyframe or scene cut is in flight: its restore applies this target.
            if (shapedKeyFramePending || sceneCutShaper.pending) return@post
            val activeCodec = codec ?: return@post
            val codecTarget = codecTargetBps()
            runCatching {
                activeCodec.setParameters(
                    Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, codecTarget) },
                )
            }.onSuccess {
                onStatus(
                    "Encoder target: ${codecTarget / 1_000} kbps" +
                        if (codecTarget != bitRate) " (${codecTargetCorrections()} of ${bitRate / 1_000})" else "",
                )
            }.onFailure { error ->
                onStatus("Encoder rejected bitrate update: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    /** Thermal speed floor for the native encoder (MemoryThermalPolicy.nativeSpeedFloor); 0 clears it. */
    @Volatile private var thermalSpeedFloor = 0

    fun updateThermalSpeedFloor(floor: Int) {
        thermalSpeedFloor = floor
    }

    /**
     * Experimental frame-rate arm: re-applies the codec target when the delivered FPS (measured over
     * the last second of output timestamps) changes, at most once per second.
     */
    private fun recordDeliveredFrameRate(ptsUs: Long) {
        val fps = deliveredFrameRate.record(ptsUs)?.coerceIn(1, settings.fps) ?: return
        val nowNs = System.nanoTime()
        if (fps == frameRateLimitFps || nowNs - framerateAppliedNs < 1_000_000_000L) return
        frameRateLimitFps = fps
        framerateAppliedNs = nowNs
        applyCodecTarget("experimental framerate adjuster at $fps delivered FPS")
    }

    fun updateFrameRate(maximumFps: Int) {
        val requestedFps = minOf(settings.fps, maximumFps).coerceAtLeast(1)
        worker.post {
            if (debugRawInputFile != null) {
                rawInputFps = requestedFps
                onStatus("Raw fixture frame rate: $requestedFps FPS")
                return@post
            }
            val nextRange = selectFpsRange(requestedFps)
            if (nextRange == targetFpsRange) return@post
            targetFpsRange = nextRange
            applyRepeatingRequest()
            onStatus("Frame rate limit: ${nextRange?.upper ?: settings.fps} FPS")
        }
    }

    private fun keyFrameShapingFraction(): Double? =
        DebugTunables.keyFrameShaping?.takeIf { !usesNativeAom && rateControl == EncoderRateControl.AUTOMATIC }

    /**
     * Experimental temporal layering: LayeringPolicy turns droppable frames on while a link of this
     * source is tight. Without the experiment it has no effect.
     */
    fun setDroppableFrames(wanted: Boolean) {
        droppableFramesWanted = wanted
    }

    /** Parsed VP9 quantizers of the last report window, plus the active burst experiments. */
    private fun quantizerSummary(): String {
        val quantizers = quantizerWindow.summaryAndReset()?.let { ", $it" }.orEmpty()
        val headroom = quantizerHeadroom?.let { ", experimental q-floor ${DebugTunables.vp9QuantizerFloor} " +
            "factor ${roundedForLog(it.factor, 2)}" }.orEmpty()
        val cuts = if (sceneCutsDetected > 0 || sceneCutShaper.shapedCuts > 0) {
            ", scene cuts $sceneCutsDetected detected/${sceneCutShaper.shapedCuts} shaped (${sceneCutShaper.mode.label})"
        } else {
            ""
        }
        return quantizers + headroom + cuts
    }

    private fun layeringSummary(): String = when {
        !experimentalTemporalLayers -> ""
        usesNativeAom -> ", experimental temporal layers ${if (nativeTemporalLayers) 2 else 1}, droppable " +
            "${nativeDroppability.droppableFrames}/${nativeDroppability.examinedFrames} verified"
        else -> bitstreamDroppability?.let {
            ", experimental droppable ${it.droppableFrames}/${it.examinedFrames} verified" +
                if (temporalLayeringRequested) " (layering requested)" else ""
        } ?: ""
    }

    fun requestKeyFrame(force: Boolean = false) {
        if (usesNativeAom) {
            if (!keyFrameRequestBackoff.shouldHonor(System.nanoTime(), force)) return
            nativeKeyFrameRequested.set(true)
            onStatus("Requested a native libaom recovery keyframe")
            return
        }
        worker.post {
            val activeCodec = codec ?: return@post
            if (!keyFrameRequestBackoff.shouldHonor(System.nanoTime(), force)) return@post
            val shaping = keyFrameShapingFraction()
            runCatching {
                activeCodec.setParameters(
                    Bundle().apply {
                        shaping?.let { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, (codecTargetBps() * it).toInt()) }
                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    },
                )
            }.onSuccess {
                if (shaping != null) {
                    shapedKeyFramePending = true
                    worker.removeCallbacks(restoreShapedTarget)
                    worker.postDelayed(restoreShapedTarget, SHAPED_KEY_FRAME_TIMEOUT_MS)
                }
                onStatus("Requested a recovery keyframe")
            }.onFailure { error ->
                onStatus("Encoder rejected keyframe request: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun selectFpsRange(maximumFps: Int): Range<Int>? =
        availableFpsRanges
            .filter { it.upper <= maximumFps }
            .maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
            // No range reaches down to the limit: the lowest-ceiling range, never one that lets the
            // camera run faster (a [7,30] range containing a 7 FPS limit delivered up to 30 FPS).
            ?: availableFpsRanges
                .minWithOrNull(compareBy<Range<Int>> { it.upper }.thenByDescending { it.lower })

    private fun applyRepeatingRequest() {
        val activeCamera = camera ?: return
        val activeSession = session ?: return
        if (captureTargets.isEmpty()) return
        runCatching {
            val request = activeCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                captureTargets.forEach(::addTarget)
                if (continuousAutoFocusAvailable) {
                    set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                    )
                }
                targetFpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }.build()
            activeSession.setRepeatingRequest(request, if (BuildConfig.DEBUG) captureTimingCallback else null, worker)
        }.onFailure { error ->
            notifyFailure("Camera frame-rate update failed: ${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    private val captureTimingCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (closed.get()) return
            exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            sensorFrameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION)
            cameraPipelineDepth = result.get(CaptureResult.REQUEST_PIPELINE_DEPTH)
        }
    }

    private fun copyYuv420(source: Image, target: Image) {
        val visible = visibleRegion(source)
        source.planes.zip(target.planes).forEachIndexed { planeIndex, (sourcePlane, targetPlane) ->
            val targetWidth = planeSize(settings.resolution.width, planeIndex)
            val targetHeight = planeSize(settings.resolution.height, planeIndex)
            val sourceCrop = YuvPlaneCopy.centerCropWithin(
                YuvPlaneCopy.planeRegion(visible, planeIndex), targetWidth, targetHeight,
            )
            val sourceBuffer = sourcePlane.buffer
            val targetBuffer = targetPlane.buffer
            val sourceOffset = sourceBuffer.position()
            val targetOffset = targetBuffer.position()
            if (sourceCrop.width == targetWidth && sourceCrop.height == targetHeight &&
                !targetBuffer.isReadOnly && NativeAomBridge.nativeCopyPlane(
                    sourceBuffer, sourceOffset + sourceCrop.top * sourcePlane.rowStride +
                        sourceCrop.left * sourcePlane.pixelStride, sourceBuffer.limit(),
                    sourcePlane.rowStride, sourcePlane.pixelStride,
                    targetBuffer, targetOffset, targetBuffer.limit(),
                    targetPlane.rowStride, targetPlane.pixelStride, targetWidth, targetHeight,
                )
            ) return@forEachIndexed
            check(
                YuvPlaneCopy.copyPlane(
                    sourceBuffer, sourceOffset, sourcePlane.rowStride, sourcePlane.pixelStride, sourceCrop,
                    targetBuffer, targetOffset, targetPlane.rowStride, targetPlane.pixelStride, targetWidth, targetHeight,
                ),
            ) { "YUV plane $planeIndex layout is outside its buffer (row ${sourcePlane.rowStride}/${targetPlane.rowStride})" }
        }
    }

    private fun nativeThreadCount(resolution: VideoResolution): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val pixels = resolution.width.toLong() * resolution.height
        return when {
            pixels <= 640L * 360L && cores > 2 -> 2
            // Row-MT uses the extra cores; the speed governor turns the headroom into quality.
            pixels <= 720L * 480L && cores >= 8 -> 4
            pixels <= 720L * 480L && cores > 3 -> 3
            pixels > 720L * 480L && cores > 4 -> 4
            pixels >= 320L * 180L && cores > 2 -> 2
            else -> 1
        }
    }

    private companion object {
        /**
         * Camera frames that may wait for the native encoder. Two dropped source frames on the A15s
         * whenever one encode overran the 66.7 ms budget (lane-a 2026-10-08); four turn such a burst
         * into latency instead of frame loss. Phones within budget never hold more than one.
         */
        const val NATIVE_INPUT_BUFFERS = 4
        /** An encoder that ignores the keyframe request gets its full target back after this. */
        const val SHAPED_KEY_FRAME_TIMEOUT_MS = 1_000L
        const val CADENCE_REPORT_INTERVAL_NS = 5_000_000_000L
        const val BITRATE_ADJUSTER_LOG_INTERVAL_NS = 1_000_000_000L
        const val MAX_TRACE_ENCODE_LATENCY_US = 60_000_000L
        const val CODEC_CLOSE_TIMEOUT_SECONDS = 5L
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (debugRawInputFile != null && rawStartedUs > 0L) {
            val frameBytes = settings.resolution.width.toLong() * settings.resolution.height * 3 / 2
            debugMediaTrace?.setRawTimeline(debugRawInputFile.name, debugRawInputFile.length() / frameBytes,
                rawStartedUs, rawStartedUs + (System.nanoTime() - rawStartedNs) / 1_000L)
        }
        worker.removeCallbacks(rawFeed)
        imageReader?.setOnImageAvailableListener(null, null)
        referenceImageReader?.setOnImageAvailableListener(null, null)
        closeCameraAndCodecOnWorker()
        rawInput?.close()
        rawInput = null
        rawFrame = null
        pendingImage?.close()
        pendingImage = null
        imageReader?.close()
        referenceImageReader?.close()
        nativeEncoder?.let {
            val closed = CountDownLatch(1)
            nativeWorker?.post {
                runCatching { nativeEncoder?.close() }
                nativeEncoder = null
                closed.countDown()
            }
            runCatching { closed.await(5, TimeUnit.SECONDS) }
        }
        nativeEncoderThread?.quitSafely()
        runCatching { nativeEncoderThread?.join(5_000) }
        nativeInputBuffers?.clear()
        nativeInputBuffers = null
        referenceRecorder?.close()
        if (BuildConfig.DEBUG && (debugRawInputFile != null && rawStartedUs > 0L ||
                debugRawInputFile == null && usesNativeAom && firstVideoFrameSeen)) Log.i(
            "BluetoothVideo",
            "Encoder input FINAL: capture drops $nativeInputDrops" +
                if (debugRawInputFile != null) ", raw input drops $rawInputDrops" else "",
        )
        recordBurstProfile()
        debugMediaTrace?.close()
        debugStageTrace?.close()
        debugMediaTrace = null
        previewSurface = null
        captureTargets = emptyList()
        (viewfinderSurface ?: reusableViewfinderSurface?.second)?.close()
        viewfinderSurface = null
        encoderSurface?.release()
        workerThread.quitSafely()
    }

    /**
     * Records this session's largest frame (in seconds of its own stream) and, for libaom, its encode
     * load, for the experimental encoder ranking. Recorded in every session so the
     * experiment has measurements when it is turned on; nothing reads them while it is off.
     */
    private fun recordBurstProfile() {
        val sessionBurst = burstAccumulator.sessionBurstSeconds(settings.fps * EncoderBurstRanking.MIN_SESSION_SECONDS)
            ?: return
        val encodeLoad = if (usesNativeAom) {
            nativeEncodeLatency.snapshot().takeIf { it.samples > 0 }?.let { it.p95Ms * settings.fps / 1_000.0 }
        } else {
            null
        }
        runCatching {
            val store = EncoderBurstProfileStore(context)
            val record = EncoderBurstRanking.merge(
                store.load(videoCodec.encoderName),
                sessionBurst,
                encodeLoad,
                System.currentTimeMillis(),
            )
            store.save(videoCodec.encoderName, record)
            Log.i(
                "BluetoothVideo",
                "BENCHMARK encoder_burst_profile v1 " + JSONObject()
                    .put("encoder", videoCodec.encoderName)
                    .put("session_burst_s", roundedForLog(sessionBurst, 3))
                    .put("largest_bytes", burstAccumulator.largestBytes)
                    .put("frames", burstAccumulator.frames)
                    .put("encode_load", encodeLoad?.let { roundedForLog(it, 2) } ?: JSONObject.NULL)
                    .put("record_burst_s", roundedForLog(record.burstSeconds, 3))
                    .put("record_fits", record.fits)
                    .put("sessions", record.sessions)
                    .toString(),
            )
        }.onFailure { error -> Log.w("BluetoothVideo", "Encoder burst profile not saved", error) }
    }

    private fun closeCameraAndCodecOnWorker() {
        val released = CountDownLatch(1)
        val releaseCodec = Runnable {
            try {
                captureSessionGeneration++
                runCatching { session?.stopRepeating() }
                runCatching { session?.close() }
                session = null
                runCatching { camera?.close() }
                camera = null
                val activeCodec = codec
                codec = null
                runCatching { activeCodec?.stop() }
                runCatching { activeCodec?.release() }
            } finally {
                released.countDown()
            }
        }
        if (Looper.myLooper() == workerThread.looper || !worker.post(releaseCodec)) {
            releaseCodec.run()
            return
        }
        runCatching { released.await(CODEC_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
    }
}

/** Locale-independent rounding for JSON log values (a comma decimal separator must not throw). */
internal fun roundedForLog(value: Double, decimals: Int): Double {
    var scale = 1.0
    repeat(decimals) { scale *= 10 }
    return Math.round(value * scale) / scale
}

internal fun codecPresentationTimeEpochOffsetUs(
    presentationTimeUs: Long,
    elapsedRealtimeUs: Long,
    monotonicTimeUs: Long,
): Long {
    val deltaUs = elapsedRealtimeUs - presentationTimeUs
    if (kotlin.math.abs(deltaUs) <= MAX_CODEC_PRESENTATION_EPOCH_DELTA_US) return 0L
    return if (kotlin.math.abs(monotonicTimeUs - presentationTimeUs) <= MAX_CODEC_PRESENTATION_EPOCH_DELTA_US) {
        // Preserve capture age when converting CLOCK_MONOTONIC to the audio's boot-time epoch.
        elapsedRealtimeUs - monotonicTimeUs
    } else {
        deltaUs
    }
}

private const val MAX_CODEC_PRESENTATION_EPOCH_DELTA_US = 5_000_000L

/**
 * Recovery keyframes on hard content can be 40-130 KB; when one overflowed a link, an immediate
 * second request produced another equally large keyframe (2-27 keyframes per 30 s run on the same
 * input). Unforced requests are spaced 1 s apart, doubling to 8 s while they keep arriving back to
 * back, and reset after 10 calm seconds. Forced requests (a new viewer joining) are never delayed.
 */
internal class KeyFrameRequestBackoff(
    private val minIntervalNs: Long = 1_000_000_000L,
    private val maxIntervalNs: Long = 8_000_000_000L,
    private val calmResetNs: Long = 10_000_000_000L,
) {
    private var lastHonoredNs: Long? = null
    private var intervalNs = minIntervalNs

    @Synchronized
    fun shouldHonor(nowNs: Long, force: Boolean): Boolean {
        val last = lastHonoredNs
        if (!force && last != null) {
            val sinceNs = nowNs - last
            if (sinceNs < intervalNs) return false
            intervalNs = when {
                sinceNs >= calmResetNs -> minIntervalNs
                sinceNs < intervalNs * 2 -> (intervalNs * 2).coerceAtMost(maxIntervalNs)
                else -> intervalNs
            }
        }
        lastHonoredNs = nowNs
        return true
    }
}

/** Experimental temporal layering: the MediaCodec schema requested from hardware encoders. */
private const val TEMPORAL_LAYERING_SCHEMA = "android.generic.2"

private fun MediaFormat.getStringOrNull(key: String): String? =
    if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null

/** Layer 0 on even frames since the last keyframe; always 0 without layering. */
internal fun nativeTemporalLayer(enabled: Boolean, frameIndexSinceKey: Long): Int =
    if (enabled && frameIndexSinceKey % 2 == 1L) 1 else 0

internal fun nativePacketFlags(keyFrame: Boolean, temporalLayer: Int): Int = when {
    keyFrame -> StreamProtocol.FLAG_KEY_FRAME
    temporalLayer == 1 -> StreamProtocol.FLAG_DISCARDABLE
    else -> 0
}

private const val FRAME_AIRTIME_MS = 250L

/** Largest inter frame as a percentage of the average frame that crosses [linkKbps] in FRAME_AIRTIME_MS. */
internal fun maxInterFramePercent(linkKbps: Int, bitrateBps: Int, fps: Int): Int =
    (100L * linkKbps * FRAME_AIRTIME_MS * fps / bitrateBps.coerceAtLeast(1)).toInt().coerceAtLeast(MIN_INTER_FRAME_PERCENT)

/** Below this the rate control cannot follow normal motion. */
private const val MIN_INTER_FRAME_PERCENT = 150
