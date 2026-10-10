package com.example.bluetoothtest

import java.nio.ByteBuffer

data class NativeAomPacket(
    val bytes: ByteArray,
    val presentationTimeUs: Long,
    val keyFrame: Boolean,
    val quantizer: Int,
)

data class NativeAomTuning(
    val qualityMode: Boolean = false,
    val lagFrames: Int = 0,
    val aqMode: Int = 3,
    val tuneSsim: Boolean = false,
    val noiseSensitivity: Int = 0,
    val maxQuantizer: Int = 63,
    val rateBufferMs: Int = 1_000,
    val enableCdef: Boolean = true,
    val maxReferenceFrames: Int = 3,
    val realtimeToolProfile: Boolean = true,
) {
    init {
        require(lagFrames in 0..32)
        require(qualityMode || lagFrames == 0)
        require(aqMode in 0..3)
        require(noiseSensitivity in 0..6)
        require(maxQuantizer in 10..63)
        require(rateBufferMs in 100..6_000)
        require(maxReferenceFrames in 3..7)
    }

    companion object {
        val DEFAULT = NativeAomTuning()
    }
}

internal object NativeAomBridge {
    init {
        System.loadLibrary("bluetooth_video_native")
    }

    external fun nativeVersion(): String

    external fun nativeCopyPlane(
        source: ByteBuffer, sourceOffset: Int, sourceLimit: Int,
        sourceRowStride: Int, sourcePixelStride: Int,
        target: ByteBuffer, targetOffset: Int, targetLimit: Int,
        targetRowStride: Int, targetPixelStride: Int,
        width: Int, height: Int,
    ): Boolean

    external fun nativeCopyYuv420ToI420(
        yBuffer: ByteBuffer,
        yOffset: Int,
        yLimit: Int,
        yRowStride: Int,
        yPixelStride: Int,
        uBuffer: ByteBuffer,
        uOffset: Int,
        uLimit: Int,
        uRowStride: Int,
        uPixelStride: Int,
        vBuffer: ByteBuffer,
        vOffset: Int,
        vLimit: Int,
        vRowStride: Int,
        vPixelStride: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        targetBuffer: ByteBuffer,
        targetWidth: Int,
        targetHeight: Int,
    ): Boolean

    external fun nativeCreate(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        cpuUsed: Int,
        threads: Int,
        qualityMode: Boolean,
        lagFrames: Int,
        variableBitrate: Boolean,
        dropFrames: Boolean,
        aqMode: Int,
        tuneSsim: Boolean,
        noiseSensitivity: Int,
        maxQuantizer: Int,
        rateBufferMs: Int,
        enableCdef: Boolean,
        maxReferenceFrames: Int,
        realtimeToolProfile: Boolean,
    ): Long

    external fun nativeEncode(
        handle: Long,
        i420: ByteBuffer,
        presentationTimeUs: Long,
        forceKeyFrame: Boolean,
        layer: Int,
    ): Array<NativeAomPacket>

    external fun nativeFlush(handle: Long): Array<NativeAomPacket>

    external fun nativeSetBitrate(handle: Long, bitrate: Int)

    external fun nativeSetCpuUsed(handle: Long, cpuUsed: Int)

    external fun nativeSetMaxInterBitratePct(handle: Long, percent: Int)

    external fun nativeSetKeyFrameQuantizerReference(handle: Long, quantizer: Int)

    external fun nativeClose(handle: Long)

    fun versionOrNull(): String? = runCatching(::nativeVersion).getOrNull()
}

/**
 * The WebRTC-derived tool restrictions save encode time but cost quality: on the 20 s mosaic at
 * 640x480/15/500 kbps (cpu-used 7, same rate control) VMAF 58.1 with them versus 66.6 without, for
 * 1.5x encode time. ASUS measured 21.6 ms p95 per frame with them against a 66.7 ms frame budget,
 * so modes up to 640x480 at 15 FPS use the full tool set; heavier modes keep the fast profile.
 */
internal fun nativeUsesFastToolProfile(settings: VideoSettings): Boolean =
    settings.resolution.width.toLong() * settings.resolution.height * settings.fps > 640L * 480L * 15L

internal class NativeAomEncoder(
    width: Int,
    height: Int,
    fps: Int,
    bitrate: Int,
    cpuUsed: Int,
    threads: Int,
    variableBitrate: Boolean,
    dropFrames: Boolean,
    tuning: NativeAomTuning = NativeAomTuning.DEFAULT,
) : AutoCloseable {
    private var handle = NativeAomBridge.nativeCreate(
        width,
        height,
        fps,
        bitrate,
        cpuUsed,
        threads,
        tuning.qualityMode,
        tuning.lagFrames,
        variableBitrate,
        dropFrames,
        tuning.aqMode,
        tuning.tuneSsim,
        tuning.noiseSensitivity,
        tuning.maxQuantizer,
        tuning.rateBufferMs,
        tuning.enableCdef,
        tuning.maxReferenceFrames,
        tuning.realtimeToolProfile,
    ).also { check(it != 0L) { "Could not create native libaom encoder" } }

    /**
     * [layer] -1 (default) keeps libaom's own references and sets no extra control; 0 refreshes
     * LAST (base frame); 1 refreshes nothing, so any hop may skip the frame (verified from the
     * bitstream before it is marked). Layers other than -1 come only from the Experimental
     * temporal layering switch.
     */
    fun encode(
        i420: ByteBuffer,
        presentationTimeUs: Long,
        forceKeyFrame: Boolean,
        layer: Int = -1,
    ): Array<NativeAomPacket> = NativeAomBridge.nativeEncode(
        checkNotNull(handle.takeIf { it != 0L }) { "Native libaom encoder is closed" },
        i420,
        presentationTimeUs,
        forceKeyFrame,
        layer,
    )

    fun flush(): Array<NativeAomPacket> = NativeAomBridge.nativeFlush(
        checkNotNull(handle.takeIf { it != 0L }) { "Native libaom encoder is closed" },
    )

    fun updateBitrate(bitrate: Int) {
        NativeAomBridge.nativeSetBitrate(
            checkNotNull(handle.takeIf { it != 0L }) { "Native libaom encoder is closed" },
            bitrate,
        )
    }

    /** Experimental temporal layering: floors a re-created encoder's first keyframe. */
    fun setKeyFrameQuantizerReference(quantizer: Int) {
        NativeAomBridge.nativeSetKeyFrameQuantizerReference(
            checkNotNull(handle.takeIf { it != 0L }) { "Native libaom encoder is closed" },
            quantizer,
        )
    }

    fun updateMaxInterBitratePct(percent: Int) {
        NativeAomBridge.nativeSetMaxInterBitratePct(
            checkNotNull(handle.takeIf { it != 0L }) { "Native libaom encoder is closed" },
            percent,
        )
    }

    fun updateCpuUsed(cpuUsed: Int) {
        NativeAomBridge.nativeSetCpuUsed(
            checkNotNull(handle.takeIf { it != 0L }) { "Native libaom encoder is closed" },
            cpuUsed,
        )
    }

    override fun close() {
        val activeHandle = handle
        if (activeHandle == 0L) return
        handle = 0L
        NativeAomBridge.nativeClose(activeHandle)
    }
}
