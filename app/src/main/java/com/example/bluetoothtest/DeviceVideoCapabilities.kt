package com.example.bluetoothtest

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.media.MediaCodecList
import android.util.Range
import android.util.Size
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
        val streamMap = camera.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyList()
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

        val aeRanges = camera
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList()
            .orEmpty()
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
            .filter { video == null || video.isSizeSupported(it.width, it.height) }
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
