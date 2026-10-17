package com.example.bluetoothtest

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.util.Range
import android.util.Size
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

data class DeviceVideoMode(
    val resolution: VideoResolution,
    val fpsOptions: List<Int>,
    val encoderFpsEstimate: Range<Double>?,
)

class DeviceVideoCapabilities(context: Context) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)

    fun availableCameraLenses(): List<CameraLens> =
        CameraLens.entries.filter { cameraManager.cameraIdFor(it) != null }

    fun modesFor(codec: VideoCodec, bitRate: Int, cameraLens: CameraLens): List<DeviceVideoMode> {
        val cameraId = cameraManager.cameraIdFor(cameraLens) ?: return emptyList()
        val camera = cameraManager.getCameraCharacteristics(cameraId)
        val streamMap = safeCameraRead("SCALER_STREAM_CONFIGURATION_MAP") {
            camera.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        } ?: return emptyList()
        val cameraSizes = if (codec.usesSurfaceInput) {
            streamMap.getOutputSizes(MediaCodec::class.java)
        } else {
            streamMap.getOutputSizes(ImageFormat.YUV_420_888)
        } ?: return emptyList()

        val video = if (codec.backend == VideoEncoderBackend.MEDIA_CODEC) {
            val codecInfo = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .firstOrNull { it.name == codec.encoderName }
                ?: return emptyList()
            runCatching { codecInfo.getCapabilitiesForType(codec.mimeType).videoCapabilities }
                .getOrNull()
                ?: return emptyList()
        } else {
            null
        }
        if (video != null && !video.bitrateRange.contains(bitRate)) return emptyList()

        val aeRanges = safeCameraRead("CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES") {
            camera.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        }?.toList().orEmpty()
        val fpsCandidates = buildSet {
            addAll(VideoSettings.FPS_OPTIONS)
            add(60)
            aeRanges.forEach { range ->
                add(range.lower)
                add(range.upper)
            }
        }.filter { it in 1..60 }.sorted()

        return cameraSizes
            .asSequence()
            .filter { it.width in 1..4096 && it.height in 1..4096 }
            .filter { it.width % 2 == 0 && it.height % 2 == 0 }
            .filter { video == null || sizeAllowed(codec, video, it.width, it.height, bitRate) }
            .distinctBy { it.width to it.height }
            .mapNotNull { size ->
                val cameraMaxFps = cameraMaxFps(streamMap, codec, size, aeRanges)
                val fps = fpsCandidates.filter { candidate ->
                    candidate <= cameraMaxFps &&
                        (aeRanges.isEmpty() || aeRanges.any { candidate in it }) &&
                        (video == null ||
                            video.areSizeAndRateSupported(size.width, size.height, candidate.toDouble()))
                }
                if (fps.isEmpty()) return@mapNotNull null
                DeviceVideoMode(
                    resolution = VideoResolution(size.width, size.height, "${size.height}p"),
                    fpsOptions = fps,
                    encoderFpsEstimate = video?.let {
                        runCatching { it.getAchievableFrameRatesFor(size.width, size.height) }.getOrNull()
                    },
                )
            }
            .sortedWith(
                compareBy<DeviceVideoMode> {
                    it.resolution.width.toLong() * it.resolution.height
                }.thenBy { it.resolution.width },
            )
            .toList()
    }

    /**
     * Whether [codec] can encode [width]x[height] (same rule as the camera-mode list, V-08 included), independent of
     * any camera mode: a DEBUG raw fixture needs only the encoder. A software (non-MediaCodec) encoder is not limited.
     */
    fun encoderAllowsSize(codec: VideoCodec, width: Int, height: Int, bitRate: Int): Boolean {
        if (codec.backend != VideoEncoderBackend.MEDIA_CODEC) return true
        val video = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .firstOrNull { it.name == codec.encoderName }
            ?.let { info -> runCatching { info.getCapabilitiesForType(codec.mimeType).videoCapabilities }.getOrNull() }
            ?: return true
        return sizeAllowed(codec, video, width, height, bitRate)
    }

    /**
     * Whether [codec] can encode [width]x[height] at [fps] (`areSizeAndRateSupported`), independent of any camera mode:
     * the camera-mode list used to enforce the rate for a DEBUG raw fixture. A software encoder is not limited.
     */
    fun encoderAllowsRate(codec: VideoCodec, width: Int, height: Int, fps: Int): Boolean {
        if (codec.backend != VideoEncoderBackend.MEDIA_CODEC) return true
        val video = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .firstOrNull { it.name == codec.encoderName }
            ?.let { info -> runCatching { info.getCapabilitiesForType(codec.mimeType).videoCapabilities }.getOrNull() }
            ?: return true
        return video.areSizeAndRateSupported(width, height, fps.toDouble())
    }

    /**
     * GPU scale ladder ([GpuScaleFeature]): the rungs of a session whose largest picture is [session], as
     * modes. They are not limited by the camera's mode list: one capture mode covers the session and every
     * rung is a GPU crop/scale of it ([GpuScaleMath]). Sizes the encoder rejects or the capture cannot
     * supply without an upscale are left out; a frame rate needs the capture mode, the camera's AE
     * ranges and the encoder at that rung. Empty for a software (YUV input) encoder or a camera with no
     * usable capture mode.
     */
    fun gpuModesFor(
        codec: VideoCodec,
        bitRate: Int,
        cameraLens: CameraLens,
        session: VideoResolution,
        fps: Int,
    ): List<DeviceVideoMode> {
        if (!codec.usesSurfaceInput || codec.backend != VideoEncoderBackend.MEDIA_CODEC) return emptyList()
        val cameraId = cameraManager.cameraIdFor(cameraLens) ?: return emptyList()
        val camera = cameraManager.getCameraCharacteristics(cameraId)
        val capture = GpuScaleMath.chooseCapture(GpuScaleCapture.captureModes(camera), session, fps)
            ?: return emptyList()
        val video = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .firstOrNull { it.name == codec.encoderName }
            ?.let { info -> runCatching { info.getCapabilitiesForType(codec.mimeType).videoCapabilities }.getOrNull() }
            ?: return emptyList()
        if (!video.bitrateRange.contains(bitRate)) return emptyList()
        val aeRanges = safeCameraRead("CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES") {
            camera.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        }?.toList().orEmpty()
        val fpsCandidates = buildSet {
            addAll(VideoSettings.FPS_OPTIONS)
            aeRanges.forEach { range ->
                add(range.lower)
                add(range.upper)
            }
        }.filter { it in 1..60 }.sorted()
        return GpuScaleMath.rungSizes(session, video.widthAlignment, video.heightAlignment) { width, height ->
            GpuScaleMath.fitsCapture(capture, width, height) && sizeAllowed(codec, video, width, height, bitRate)
        }.mapNotNull { rung ->
            val rates = fpsCandidates.filter { candidate ->
                candidate <= capture.maxFps &&
                    (aeRanges.isEmpty() || aeRanges.any { candidate in it }) &&
                    video.areSizeAndRateSupported(rung.width, rung.height, candidate.toDouble())
            }
            if (rates.isEmpty()) return@mapNotNull null
            DeviceVideoMode(
                resolution = rung,
                fpsOptions = rates,
                encoderFpsEstimate = runCatching { video.getAchievableFrameRatesFor(rung.width, rung.height) }.getOrNull(),
            )
        }.sortedWith(compareBy<DeviceVideoMode> { it.resolution.width.toLong() * it.resolution.height }.thenBy { it.resolution.width })
    }

    /**
     * `isSizeSupported`, plus the V-08 false-negative candidate: a size within 8 px of the reported maximum that the
     * check rejects is logged once (BENCHMARK capability_size_candidate) and, only with the Experimental
     * `test_capability_probe_configure`, probe-configured once on a throwaway codec (cached per component and size)
     * and allowed when that succeeds. With the switch off the answer is exactly isSizeSupported.
     */
    private fun sizeAllowed(
        codec: VideoCodec,
        video: MediaCodecInfo.VideoCapabilities,
        width: Int,
        height: Int,
        bitRate: Int,
    ): Boolean {
        if (video.isSizeSupported(width, height)) return true
        val maxWidth = runCatching { video.supportedWidths.upper }.getOrNull()
        val maxHeight = runCatching { video.getSupportedHeightsFor(width).upper }.getOrNull()
            ?: runCatching { video.supportedHeights.upper }.getOrNull()
        val candidate = SizeSupportCheck.isFalseNegativeCandidate(false, width, height, maxWidth, maxHeight)
        if (!candidate) return false
        val key = "${codec.encoderName}:${width}x$height"
        val probeEnabled = CameraSwitches.capabilityProbeConfigure
        val decision = SizeSupportCheck.decide(false, true, probeEnabled) {
            probeResults.getOrPut(key) { probeConfigure(codec, width, height, bitRate) }
        }
        if (loggedCandidates.add(key + ":" + probeEnabled)) {
            Log.i(
                "BluetoothVideo",
                SizeSupportCheck.candidateLine(codec.encoderName, width, height, maxWidth, maxHeight, probeEnabled, decision.probed),
            )
        }
        return decision.allowed
    }

    /** Creates and configures the encoder once at [width]x[height] and releases it; any failure is "not supported". */
    private fun probeConfigure(codec: VideoCodec, width: Int, height: Int, bitRate: Int): Boolean {
        val format = MediaFormat.createVideoFormat(codec.mimeType, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                if (codec.usesSurfaceInput) {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                } else {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                },
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = runCatching { MediaCodec.createByCodecName(codec.encoderName) }.getOrNull() ?: return false
        return try {
            BoundedCall.run("size-probe-configure", PROBE_TIMEOUT_MS) {
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            true
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            false
        } finally {
            BoundedCall.runQuietly("size-probe-release", PROBE_TIMEOUT_MS, { encoder.release() })
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 5_000L
        val probeResults = ConcurrentHashMap<String, Boolean>()
        val loggedCandidates: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }

    private fun cameraMaxFps(
        streamMap: StreamConfigurationMap,
        codec: VideoCodec,
        size: Size,
        aeRanges: List<Range<Int>>,
    ): Int {
        val minFrameDuration = runCatching {
            if (codec.usesSurfaceInput) {
                streamMap.getOutputMinFrameDuration(MediaCodec::class.java, size)
            } else {
                streamMap.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size)
            }
        }.getOrDefault(0L)
        return if (minFrameDuration > 0L) {
            floor(1_000_000_000.0 / minFrameDuration).toInt().coerceAtLeast(1)
        } else {
            aeRanges.maxOfOrNull(Range<Int>::getUpper) ?: 30
        }
    }
}
