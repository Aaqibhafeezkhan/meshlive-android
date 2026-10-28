package com.example.bluetoothtest

// Debug-build offline codec benchmark implementation.

import android.content.Context
import android.content.Intent
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.roundToInt

data class EncoderFileBenchmarkSpec(
    val caseName: String,
    val codec: String,
    val codecName: String?,
    val inputFile: File,
    val outputDirectory: File,
    val width: Int,
    val height: Int,
    val fps: Int,
    val frames: Int,
    val bitrateKbps: Int,
    val complexity: Int,
    val rateMode: String,
    val keyFrameIntervalSeconds: Int,
    val nativeTuning: NativeAomTuning = NativeAomTuning.DEFAULT,
    val nativeThreads: Int? = null,
    /** Design VX: the libvpx arm's tuning (`benchmark_codec=libvpx`). */
    val vpxTuning: NativeVpxTuning = NativeVpxTuning.DEFAULT,
)

object EncoderFileBenchmark {
    fun run(context: Context, spec: EncoderFileBenchmarkSpec): File {
        if (spec.codec.equals("native_av1", ignoreCase = true) ||
            spec.codec.equals("libaom", ignoreCase = true)
        ) {
            return runNative(context, spec, vpx = false)
        }
        // Design VX: the bundled libvpx VP9 encoder, same file/trace format as the libaom arm (IVF "VP90").
        if (spec.codec.lowercase() in setOf("libvpx", "libvpx-vp9", "native_vp9")) {
            return runNative(context, spec, vpx = true)
        }
        spec.outputDirectory.mkdirs()
        val safeCaseName = spec.caseName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val resultFile = File(spec.outputDirectory, "$safeCaseName-result.json")
        // Design HC quality per bit: H.264/HEVC are written as a raw Annex B elementary stream (config included).
        val annexB = spec.codec.lowercase() in setOf("avc", "h264", "hevc", "h265")
        val outputFile = File(spec.outputDirectory, "$safeCaseName.${encodedFileExtension(spec.codec)}")
        val frameTraceFile = File(spec.outputDirectory, "$safeCaseName-frames.csv")
        outputFile.delete()
        resultFile.delete()
        frameTraceFile.delete()

        val result = JSONObject().apply {
            put("case", safeCaseName)
            put("codec", spec.codec)
            put("width", spec.width)
            put("height", spec.height)
            put("fps", spec.fps)
            put("requested_frames", spec.frames)
            put("target_kbps", spec.bitrateKbps)
            put("requested_complexity", spec.complexity)
            put("rate_mode", spec.rateMode)
            put("keyframe_interval_seconds", spec.keyFrameIntervalSeconds)
        }

        try {
            val mime = when (spec.codec.lowercase()) {
                "av1" -> MediaFormat.MIMETYPE_VIDEO_AV1
                "vp9" -> MediaFormat.MIMETYPE_VIDEO_VP9
                "avc", "h264" -> MediaFormat.MIMETYPE_VIDEO_AVC
                "hevc", "h265" -> MediaFormat.MIMETYPE_VIDEO_HEVC
                else -> error("Unsupported benchmark codec ${spec.codec}")
            }
            val codecInfo = selectEncoder(mime, spec.codecName)
            val capabilities = codecInfo.getCapabilitiesForType(mime)
            require(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in capabilities.colorFormats) {
                "${codecInfo.name} does not accept flexible YUV input"
            }
            val encoderCapabilities = capabilities.encoderCapabilities
                ?: error("${codecInfo.name} did not expose encoder capabilities")
            val complexityRange = encoderCapabilities.complexityRange
            if (spec.complexity >= 0) {
                require(complexityRange.contains(spec.complexity)) {
                    "Complexity ${spec.complexity} is outside $complexityRange for ${codecInfo.name}"
                }
            }
            val bitrateMode = when (spec.rateMode.lowercase()) {
                "cbr" -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                "vbr" -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                "cbr_fd", "cbr-fd" -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD
                // Design HC: no KEY_BITRATE_MODE, the product's path for an encoder that declares no mode.
                "default" -> null
                else -> error("Rate mode must be cbr, vbr, cbr_fd or default")
            }
            require(bitrateMode == null || encoderCapabilities.isBitrateModeSupported(bitrateMode)) {
                "${codecInfo.name} does not support ${spec.rateMode.uppercase()}"
            }

            val frameSize = spec.width * spec.height * 3 / 2
            val inputBytes = spec.inputFile.length()
            result.put("input_path", spec.inputFile.absolutePath)
            result.put("input_bytes", inputBytes)
            require(inputBytes >= frameSize) {
                "${spec.inputFile.absolutePath} has $inputBytes bytes; one frame needs $frameSize"
            }
            result.put("source_frames", inputBytes / frameSize)

            result.put("encoder_name", codecInfo.name)
            result.put("hardware_accelerated", codecInfo.isHardwareAccelerated)
            result.put("complexity_range", complexityRange.toString())
            result.put("thermal_status_before", thermalStatus(context))
            result.put("battery_temperature_c_before", batteryTemperatureC(context))
            val encodingStatisticsEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_EncodingStatistics)
            result.put("encoding_statistics_enabled", encodingStatisticsEnabled)

            val format = MediaFormat.createVideoFormat(mime, spec.width, spec.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrateKbps * 1_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, spec.keyFrameIntervalSeconds)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                bitrateMode?.let { setInteger(MediaFormat.KEY_BITRATE_MODE, it) }
                if (spec.complexity >= 0) setInteger(MediaFormat.KEY_COMPLEXITY, spec.complexity)
                if (annexB) {
                    // The product's HC profile choice (HEVC Main; H.264 High, Main, Constrained Baseline, Baseline).
                    val protocolId = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) StreamProtocol.CODEC_HEVC else StreamProtocol.CODEC_H264
                    HcEncoderEligibility.profileFor(protocolId, capabilities.profileLevels.map { it.profile to it.level })?.let { (profile, level) ->
                        setInteger(MediaFormat.KEY_PROFILE, profile)
                        setInteger(MediaFormat.KEY_LEVEL, level)
                        result.put("profile", profile)
                        result.put("level", level)
                    }
                }
                if (encodingStatisticsEnabled) {
                    setInteger(
                        MediaFormat.KEY_VIDEO_ENCODING_STATISTICS_LEVEL,
                        MediaFormat.VIDEO_ENCODING_STATISTICS_LEVEL_1,
                    )
                }
            }

            val codec = MediaCodec.createByCodecName(codecInfo.name)
            var writer: IvfWriter? = null
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                writer = IvfWriter(
                    outputFile,
                    if (mime == MediaFormat.MIMETYPE_VIDEO_AV1) "AV01" else "VP90",
                    spec.width,
                    spec.height,
                    spec.fps,
                    annexB = annexB,
                )
                val measurement = frameTraceFile.bufferedWriter().use { frameTrace ->
                    encode(codec, writer, frameTrace, spec, frameSize, encodingStatisticsEnabled)
                }
                result.put("status", "ok")
                result.put("input_frames", measurement.inputFrames)
                result.put("output_frames", measurement.outputFrames)
                result.put("dropped_frames", measurement.inputFrames - measurement.outputFrames)
                result.put("encoded_bytes", measurement.encodedBytes)
                result.put("actual_kbps", measurement.encodedBytes * 8.0 / (measurement.inputFrames.toDouble() / spec.fps) / 1_000)
                result.put("wall_seconds", measurement.wallSeconds)
                result.put("encode_fps", measurement.inputFrames / measurement.wallSeconds)
                result.put("realtime_margin", measurement.inputFrames / measurement.wallSeconds / spec.fps)
                result.put("output_latency_ms_p50", percentile(measurement.outputLatencyMs, 0.50))
                result.put("output_latency_ms_p95", percentile(measurement.outputLatencyMs, 0.95))
                result.put("output_latency_ms_p99", percentile(measurement.outputLatencyMs, 0.99))
                result.put("keyframe_count", measurement.keyFrameSizes.size)
                result.put("keyframe_sizes_bytes", JSONArray(measurement.keyFrameSizes))
                result.put("keyframe_size_bytes", sizeStats(measurement.keyFrameSizes))
                result.put("delta_frame_size_bytes", sizeStats(measurement.deltaFrameSizes))
                result.put("caller_process_cpu_ms", measurement.callerProcessCpuMs)
                result.put("qp_samples", measurement.qpValues.size)
                if (measurement.qpValues.isNotEmpty()) {
                    result.put("qp_average", measurement.qpValues.average())
                    result.put("qp_min", measurement.qpValues.min())
                    result.put("qp_max", measurement.qpValues.max())
                }
                result.put("frame_trace", frameTraceFile.name)
                result.put(
                    "caller_cpu_core_equivalent_percent",
                    measurement.callerProcessCpuMs / (measurement.wallSeconds * 1_000.0) * 100.0,
                )
            } finally {
                writer?.close()
                runCatching { codec.stop() }
                codec.release()
            }
            result.put("thermal_status_after", thermalStatus(context))
            result.put("battery_temperature_c_after", batteryTemperatureC(context))
        } catch (error: Throwable) {
            result.put("status", "error")
            result.put("error", "${error.javaClass.simpleName}: ${error.message}")
            outputFile.delete()
            frameTraceFile.delete()
        }

        resultFile.writeText(result.toString(2) + "\n")
        return resultFile
    }

    private fun runNative(context: Context, spec: EncoderFileBenchmarkSpec, vpx: Boolean): File {
        spec.outputDirectory.mkdirs()
        val safeCaseName = spec.caseName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val resultFile = File(spec.outputDirectory, "$safeCaseName-result.json")
        val outputFile = File(spec.outputDirectory, "$safeCaseName.ivf")
        val frameTraceFile = File(spec.outputDirectory, "$safeCaseName-frames.csv")
        outputFile.delete()
        resultFile.delete()
        frameTraceFile.delete()
        val result = JSONObject().apply {
            put("case", safeCaseName)
            if (vpx) {
                put("codec", "native_vp9")
                put("encoder_name", VideoCodec.LIBVPX_ENCODER_NAME)
                put("libvpx_version", NativeVpxBridge.nativeVersion())
            } else {
                put("codec", "native_av1")
                put("encoder_name", "libaom-native")
                put("libaom_version", NativeAomBridge.nativeVersion())
            }
            put("width", spec.width)
            put("height", spec.height)
            put("fps", spec.fps)
            put("requested_frames", spec.frames)
            put("target_kbps", spec.bitrateKbps)
            put("cpu_used", spec.complexity)
            put("rate_mode", spec.rateMode)
            put("keyframe_interval_seconds", spec.keyFrameIntervalSeconds)
            if (vpx) {
                put("error_resilient", spec.vpxTuning.errorResilient)
                put("aq_mode", spec.vpxTuning.aqMode)
                put("max_quantizer", spec.vpxTuning.maxQuantizer)
                put("rate_buffer_ms", spec.vpxTuning.rateBufferMs)
            } else {
                put("quality_mode", spec.nativeTuning.qualityMode)
                put("lag_frames", spec.nativeTuning.lagFrames)
                put("aq_mode", spec.nativeTuning.aqMode)
                put("tune_ssim", spec.nativeTuning.tuneSsim)
                put("noise_sensitivity", spec.nativeTuning.noiseSensitivity)
                put("max_quantizer", spec.nativeTuning.maxQuantizer)
                put("rate_buffer_ms", spec.nativeTuning.rateBufferMs)
                put("enable_cdef", spec.nativeTuning.enableCdef)
                put("max_reference_frames", spec.nativeTuning.maxReferenceFrames)
                put("realtime_tool_profile", spec.nativeTuning.realtimeToolProfile)
            }
            put("threads", spec.nativeThreads ?: nativeBenchmarkThreads(spec.width, spec.height))
            put("thermal_status_before", thermalStatus(context))
            put("battery_temperature_c_before", batteryTemperatureC(context))
        }
        try {
            require(spec.width > 0 && spec.height > 0 && spec.width % 2 == 0 && spec.height % 2 == 0)
            require(spec.fps > 0 && spec.frames > 0 && spec.bitrateKbps > 0)
            val cpuRange = if (vpx) NativeVpxBridge.MIN_REALTIME_CPU_USED..NativeVpxBridge.MAX_REALTIME_CPU_USED else 4..10
            require(spec.complexity in cpuRange) {
                "Native ${if (vpx) "libvpx" else "libaom"} experiment uses cpu-used $cpuRange; received ${spec.complexity}"
            }
            val rateMode = spec.rateMode.lowercase()
            require(rateMode in setOf("cbr", "vbr", "cbr_fd", "cbr-fd")) {
                "Rate mode must be cbr, vbr, or cbr_fd"
            }
            val frameSize = spec.width * spec.height * 3 / 2
            require(spec.inputFile.length() >= frameSize) {
                "${spec.inputFile.absolutePath} has ${spec.inputFile.length()} bytes; one frame needs $frameSize"
            }
            result.put("input_path", spec.inputFile.absolutePath)
            result.put("input_bytes", spec.inputFile.length())
            result.put("source_frames", spec.inputFile.length() / frameSize)
            val inputFrame = ByteArray(frameSize)
            val directInput = ByteBuffer.allocateDirect(frameSize)
            val keyFrameSizes = mutableListOf<Int>()
            val deltaFrameSizes = mutableListOf<Int>()
            val encodeLatencies = mutableListOf<Double>()
            val qpValues = mutableListOf<Int>()
            val bitRateWindow = ArrayDeque<Pair<Long, Int>>()
            var encodedBytes = 0L
            var outputFrames = 0
            val startedNs = System.nanoTime()
            val startedCpuMs = android.os.Process.getElapsedCpuTime()
            val threads = spec.nativeThreads ?: nativeBenchmarkThreads(spec.width, spec.height)
            val dropFrames = rateMode == "cbr_fd" || rateMode == "cbr-fd"
            val nativeEncoder: NativeVideoEncoder = if (vpx) {
                NativeVpxEncoder(
                    spec.width, spec.height, spec.fps, spec.bitrateKbps * 1_000, spec.complexity, threads,
                    variableBitrate = rateMode == "vbr", dropFrames = dropFrames, tuning = spec.vpxTuning,
                )
            } else {
                NativeAomEncoder(
                    spec.width,
                    spec.height,
                    spec.fps,
                    spec.bitrateKbps * 1_000,
                    spec.complexity,
                    threads,
                    variableBitrate = rateMode == "vbr",
                    dropFrames = dropFrames,
                    tuning = spec.nativeTuning,
                )
            }
            IvfWriter(outputFile, if (vpx) "VP90" else "AV01", spec.width, spec.height, spec.fps).use { writer ->
                nativeEncoder.use { encoder ->
                    frameTraceFile.bufferedWriter().use { trace ->
                        trace.appendLine(
                            "frame_id,pts_us,frame_type,qp,bytes,encode_ms,requested_bitrate," +
                                "rolling_actual_bitrate,sha256,pre_transport_hash,receiver_hash",
                        )
                        fun recordPackets(packets: Array<NativeAomPacket>, encodeMs: Double) {
                            packets.forEach { packet ->
                                encodeLatencies += encodeMs
                                qpValues += packet.quantizer
                                encodedBytes += packet.bytes.size
                                if (packet.keyFrame) keyFrameSizes += packet.bytes.size
                                else deltaFrameSizes += packet.bytes.size
                                writer.writeFrame(packet.bytes, outputFrames.toLong())
                                bitRateWindow.addLast(packet.presentationTimeUs to packet.bytes.size)
                                while (bitRateWindow.size > 1 &&
                                    packet.presentationTimeUs - bitRateWindow.first().first > 1_000_000L
                                ) {
                                    bitRateWindow.removeFirst()
                                }
                                val windowDurationUs = maxOf(
                                    1_000_000L / spec.fps,
                                    packet.presentationTimeUs - bitRateWindow.first().first +
                                        1_000_000L / spec.fps,
                                )
                                val rollingBitrate =
                                    bitRateWindow.sumOf { it.second } * 8_000_000L / windowDurationUs
                                val digest = MessageDigest.getInstance("SHA-256")
                                    .digest(packet.bytes)
                                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                                trace.append(outputFrames.toString()).append(',')
                                    .append(packet.presentationTimeUs.toString()).append(',')
                                    .append(if (packet.keyFrame) "I" else "P").append(',')
                                    .append(packet.quantizer.toString()).append(',')
                                    .append(packet.bytes.size.toString()).append(',')
                                    .append(String.format(Locale.US, "%.3f", encodeMs)).append(',')
                                    .append((spec.bitrateKbps * 1_000).toString()).append(',')
                                    .append(rollingBitrate.toString()).append(',')
                                    .append(digest).append(',').append(digest).append(',').appendLine()
                                outputFrames++
                            }
                        }
                        RandomAccessFile(spec.inputFile, "r").use { input ->
                            repeat(spec.frames) { frameIndex ->
                                if (input.filePointer + frameSize > input.length()) input.seek(0)
                                input.readFully(inputFrame)
                                directInput.clear()
                                directInput.put(inputFrame)
                                directInput.position(0)
                                val ptsUs = frameIndex * 1_000_000L / spec.fps
                                val keyInterval = spec.fps * spec.keyFrameIntervalSeconds
                                val startedFrameNs = System.nanoTime()
                                val packets = encoder.encode(
                                    directInput,
                                    ptsUs,
                                    frameIndex == 0 || frameIndex % keyInterval == 0,
                                    -1,
                                )
                                val encodeMs = (System.nanoTime() - startedFrameNs) / 1_000_000.0
                                recordPackets(packets, encodeMs)
                            }
                        }
                        while (true) {
                            val startedFlushNs = System.nanoTime()
                            val packets = encoder.flush()
                            val flushMs = (System.nanoTime() - startedFlushNs) / 1_000_000.0
                            if (packets.isEmpty()) break
                            recordPackets(packets, flushMs)
                        }
                    }
                }
            }
            val wallSeconds = (System.nanoTime() - startedNs) / 1_000_000_000.0
            val callerCpuMs = android.os.Process.getElapsedCpuTime() - startedCpuMs
            result.put("status", "ok")
            result.put("input_frames", spec.frames)
            result.put("output_frames", outputFrames)
            result.put("dropped_frames", spec.frames - outputFrames)
            result.put("encoded_bytes", encodedBytes)
            result.put("actual_kbps", encodedBytes * 8.0 / (spec.frames.toDouble() / spec.fps) / 1_000)
            result.put("wall_seconds", wallSeconds)
            result.put("encode_fps", spec.frames / wallSeconds)
            result.put("realtime_margin", spec.frames / wallSeconds / spec.fps)
            result.put("output_latency_ms_p50", percentile(encodeLatencies, 0.50))
            result.put("output_latency_ms_p95", percentile(encodeLatencies, 0.95))
            result.put("output_latency_ms_p99", percentile(encodeLatencies, 0.99))
            result.put("keyframe_count", keyFrameSizes.size)
            result.put("keyframe_sizes_bytes", JSONArray(keyFrameSizes))
            result.put("keyframe_size_bytes", sizeStats(keyFrameSizes))
            result.put("delta_frame_size_bytes", sizeStats(deltaFrameSizes))
            result.put("caller_process_cpu_ms", callerCpuMs)
            result.put("caller_cpu_core_equivalent_percent", callerCpuMs / (wallSeconds * 1_000.0) * 100.0)
            result.put("qp_samples", qpValues.size)
            if (qpValues.isNotEmpty()) {
                result.put("qp_average", qpValues.average())
                result.put("qp_min", qpValues.min())
                result.put("qp_max", qpValues.max())
            }
            result.put("frame_trace", frameTraceFile.name)
            result.put("thermal_status_after", thermalStatus(context))
            result.put("battery_temperature_c_after", batteryTemperatureC(context))
        } catch (error: Throwable) {
            result.put("status", "error")
            result.put("error", "${error.javaClass.simpleName}: ${error.message}")
            outputFile.delete()
            frameTraceFile.delete()
        }
        resultFile.writeText(result.toString(2) + "\n")
        return resultFile
    }

    private fun nativeBenchmarkThreads(width: Int, height: Int): Int {
        val pixels = width.toLong() * height
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            pixels >= 640L * 360L && cores > 4 -> 4
            pixels >= 320L * 180L && cores > 2 -> 2
            else -> 1
        }
    }

    private fun selectEncoder(mime: String, requestedName: String?): MediaCodecInfo {
        val candidates = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                runCatching {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in
                        info.getCapabilitiesForType(mime).colorFormats
                }.getOrDefault(false)
        }
        if (requestedName != null) {
            return candidates.firstOrNull { it.name == requestedName }
                ?: error("YUV-capable encoder $requestedName was not found; available: ${candidates.map { it.name }}")
        }
        return candidates.sortedWith(
            compareBy<MediaCodecInfo> { if (it.name.startsWith("c2.android.")) 0 else 1 }
                .thenBy { it.name },
        ).firstOrNull() ?: error("No YUV-capable encoder for $mime")
    }

    private data class Measurement(
        val inputFrames: Int,
        val outputFrames: Int,
        val encodedBytes: Long,
        val wallSeconds: Double,
        val outputLatencyMs: List<Double>,
        val keyFrameSizes: List<Int>,
        val deltaFrameSizes: List<Int>,
        val callerProcessCpuMs: Long,
        val qpValues: List<Int>,
    )

    private fun encode(
        codec: MediaCodec,
        writer: IvfWriter,
        frameTrace: java.io.BufferedWriter,
        spec: EncoderFileBenchmarkSpec,
        frameSize: Int,
        encodingStatisticsEnabled: Boolean,
    ): Measurement {
        val frame = ByteArray(frameSize)
        val queuedAtNs = LongArray(spec.frames)
        val info = MediaCodec.BufferInfo()
        var inputFrames = 0
        var outputFrames = 0
        var encodedBytes = 0L
        var inputEosQueued = false
        var outputEosSeen = false
        val outputLatencies = mutableListOf<Double>()
        val keyFrameSizes = mutableListOf<Int>()
        val deltaFrameSizes = mutableListOf<Int>()
        val qpValues = mutableListOf<Int>()
        val bitRateWindow = ArrayDeque<Pair<Long, Int>>()
        val startedNs = System.nanoTime()
        val startedCpuMs = android.os.Process.getElapsedCpuTime()
        frameTrace.appendLine(
            "frame_id,pts_us,frame_type,qp,bytes,encode_ms,requested_bitrate," +
                "rolling_actual_bitrate,sha256,pre_transport_hash,receiver_hash",
        )

        RandomAccessFile(spec.inputFile, "r").use { input ->
            while (!outputEosSeen) {
                check((System.nanoTime() - startedNs) < 300_000_000_000L) {
                    "Encoder did not finish within 300 seconds"
                }

                if (!inputEosQueued) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        if (inputFrames < spec.frames) {
                            if (input.filePointer + frameSize > input.length()) input.seek(0)
                            input.readFully(frame)
                            val inputImage = codec.getInputImage(inputIndex)
                                ?: error("${codec.name} did not expose a flexible YUV input image")
                            copyI420(frame, inputImage, spec.width, spec.height)
                            val presentationTimeUs = inputFrames * 1_000_000L / spec.fps
                            queuedAtNs[inputFrames] = System.nanoTime()
                            codec.queueInputBuffer(inputIndex, 0, frameSize, presentationTimeUs, 0)
                            inputFrames++
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                inputFrames * 1_000_000L / spec.fps,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEosQueued = true
                        }
                    }
                }

                while (true) {
                    val outputIndex = codec.dequeueOutputBuffer(info, if (inputEosQueued) 10_000 else 0)
                    if (outputIndex < 0) break
                    try {
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && isConfig) {
                            codec.getOutputBuffer(outputIndex)?.let { buffer ->
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                writer.writeConfig(ByteArray(info.size).also { buffer.get(it) })
                            }
                        }
                        if (info.size > 0 && !isConfig) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                                ?: error("${codec.name} returned an empty output buffer")
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            val payload = ByteArray(info.size)
                            outputBuffer.get(payload)
                            writer.writeFrame(payload, outputFrames.toLong())
                            encodedBytes += payload.size
                            if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
                                keyFrameSizes += payload.size
                            } else {
                                deltaFrameSizes += payload.size
                            }
                            val sourceFrame = (info.presentationTimeUs * spec.fps / 1_000_000.0)
                                .roundToInt().coerceIn(0, spec.frames - 1)
                            val encodeMs = queuedAtNs[sourceFrame]
                                .takeIf { it != 0L }
                                ?.let { (System.nanoTime() - it) / 1_000_000.0 }
                            if (encodeMs != null) outputLatencies += encodeMs
                            val outputFormat = if (encodingStatisticsEnabled) {
                                runCatching { codec.getOutputFormat(outputIndex) }.getOrNull()
                            } else {
                                null
                            }
                            val qp = outputFormat
                                ?.takeIf { it.containsKey(MediaFormat.KEY_VIDEO_QP_AVERAGE) }
                                ?.getInteger(MediaFormat.KEY_VIDEO_QP_AVERAGE)
                            if (qp != null) qpValues += qp
                            val pictureType = outputFormat
                                ?.takeIf { it.containsKey(MediaFormat.KEY_PICTURE_TYPE) }
                                ?.getInteger(MediaFormat.KEY_PICTURE_TYPE)
                            bitRateWindow.addLast(info.presentationTimeUs to payload.size)
                            while (bitRateWindow.size > 1 &&
                                info.presentationTimeUs - bitRateWindow.first().first > 1_000_000L
                            ) {
                                bitRateWindow.removeFirst()
                            }
                            val windowDurationUs = maxOf(
                                1_000_000L / spec.fps,
                                info.presentationTimeUs - bitRateWindow.first().first + 1_000_000L / spec.fps,
                            )
                            val rollingBitrate = bitRateWindow.sumOf { it.second } * 8_000_000L / windowDurationUs
                            val digest = MessageDigest.getInstance("SHA-256").digest(payload)
                                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                            frameTrace.append(outputFrames.toString()).append(',')
                                .append(info.presentationTimeUs.toString()).append(',')
                                .append(pictureTypeLabel(pictureType, info.flags)).append(',')
                                .append(qp?.toString().orEmpty()).append(',')
                                .append(payload.size.toString()).append(',')
                                .append(encodeMs?.let { String.format(Locale.US, "%.3f", it) }.orEmpty())
                                .append(',').append((spec.bitrateKbps * 1_000).toString()).append(',')
                                .append(rollingBitrate.toString()).append(',')
                                .append(digest).append(',').append(digest).append(',').appendLine()
                            outputFrames++
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEosSeen = true
                        }
                    } finally {
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }

        return Measurement(
            inputFrames,
            outputFrames,
            encodedBytes,
            (System.nanoTime() - startedNs) / 1_000_000_000.0,
            outputLatencies,
            keyFrameSizes,
            deltaFrameSizes,
            android.os.Process.getElapsedCpuTime() - startedCpuMs,
            qpValues,
        )
    }

    private fun pictureTypeLabel(pictureType: Int?, flags: Int): String = when (pictureType) {
        MediaFormat.PICTURE_TYPE_I -> "I"
        MediaFormat.PICTURE_TYPE_P -> "P"
        MediaFormat.PICTURE_TYPE_B -> "B"
        else -> if (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) "I" else "delta"
    }

    private fun copyI420(source: ByteArray, image: Image, width: Int, height: Int) {
        require(image.format == android.graphics.ImageFormat.YUV_420_888)
        copyPlane(source, 0, width, height, image.planes[0])
        copyPlane(source, width * height, width / 2, height / 2, image.planes[1])
        copyPlane(source, width * height + width * height / 4, width / 2, height / 2, image.planes[2])
    }

    private fun copyPlane(
        source: ByteArray,
        sourceOffset: Int,
        width: Int,
        height: Int,
        plane: Image.Plane,
    ) {
        val target = plane.buffer
        val targetOffset = target.position()
        for (row in 0 until height) {
            for (column in 0 until width) {
                target.put(
                    targetOffset + row * plane.rowStride + column * plane.pixelStride,
                    source[sourceOffset + row * width + column],
                )
            }
        }
    }

    private fun percentile(values: List<Double>, fraction: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[(sorted.lastIndex * fraction).roundToInt().coerceIn(sorted.indices)]
    }

    private fun sizeStats(values: List<Int>): JSONObject = JSONObject().apply {
        put("samples", values.size)
        if (values.isEmpty()) return@apply
        val doubles = values.map(Int::toDouble)
        put("mean", doubles.average())
        put("p50", percentile(doubles, 0.50))
        put("p95", percentile(doubles, 0.95))
        put("max", values.max())
    }

    private fun thermalStatus(context: Context): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(PowerManager::class.java).currentThermalStatus
    } else {
        -1
    }

    private fun batteryTemperatureC(context: Context): Double {
        val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return battery?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10.0) ?: 0.0
    }

    /** The pulled file's extension: `ivf` for VP9/AV1, `h264` / `hevc` (raw Annex B) for Design HC codecs. */
    fun encodedFileExtension(codec: String): String = when (codec.lowercase()) {
        "avc", "h264" -> "h264"
        "hevc", "h265" -> "hevc"
        else -> "ivf"
    }

    /** IVF for VP9/AV1; with [annexB] a raw Annex B elementary stream (codec config, then every access unit). */
    private class IvfWriter(
        file: File,
        fourCc: String,
        width: Int,
        height: Int,
        fps: Int,
        private val annexB: Boolean = false,
    ) : AutoCloseable {
        private val output = RandomAccessFile(file, "rw")
        private var frames = 0

        /** Codec-config output (SPS/PPS, VPS): kept in the Annex B stream, never in IVF (as before). */
        fun writeConfig(payload: ByteArray) {
            if (annexB) output.write(payload)
        }

        init {
            output.setLength(0)
            if (!annexB) writeIvfHeader(fourCc, width, height, fps)
        }

        private fun writeIvfHeader(fourCc: String, width: Int, height: Int, fps: Int) {
            output.write("DKIF".toByteArray(Charsets.US_ASCII))
            writeShortLe(0)
            writeShortLe(32)
            output.write(fourCc.toByteArray(Charsets.US_ASCII))
            writeShortLe(width)
            writeShortLe(height)
            writeIntLe(fps)
            writeIntLe(1)
            writeIntLe(0)
            writeIntLe(0)
        }

        fun writeFrame(payload: ByteArray, timestamp: Long) {
            if (!annexB) {
                writeIntLe(payload.size)
                writeLongLe(timestamp)
            }
            output.write(payload)
            frames++
        }

        override fun close() {
            if (!annexB) {
                output.seek(24)
                writeIntLe(frames)
            }
            output.close()
        }

        private fun writeShortLe(value: Int) {
            output.write(value and 0xff)
            output.write(value ushr 8 and 0xff)
        }

        private fun writeIntLe(value: Int) {
            repeat(4) { byte -> output.write(value ushr (byte * 8) and 0xff) }
        }

        private fun writeLongLe(value: Long) {
            repeat(8) { byte -> output.write((value ushr (byte * 8) and 0xff).toInt()) }
        }
    }
}
