package com.example.bluetoothtest

import java.nio.ByteBuffer

/**
 * The bundled software encoders behind one interface (libaom AV1 and, DECISIONS "Design VX", libvpx VP9), so the
 * camera/raw input path, rate control, speed governor and thermal hooks are shared. Packets use [NativeAomPacket]
 * for both (bytes, PTS, keyframe, last quantizer on the 0-63 scale).
 */
internal interface NativeVideoEncoder : AutoCloseable {
    /** [layer] -1 keeps the encoder's own references; other values exist only on the libaom path. */
    fun encode(i420: ByteBuffer, presentationTimeUs: Long, forceKeyFrame: Boolean, layer: Int): Array<NativeAomPacket>
    fun flush(): Array<NativeAomPacket>
    fun updateBitrate(bitrate: Int)
    fun updateCpuUsed(cpuUsed: Int)
    fun updateMaxInterBitratePct(percent: Int)
    fun setKeyFrameQuantizerReference(quantizer: Int)
}

/**
 * libvpx VP9 real-time configuration (DECISIONS "Design VX"). Rate-control values follow libvpx's documented one-pass
 * CBR fields (vpx_encoder.h) at the values WebRTC documents for real-time VP9: 50% under/overshoot, 500/600 ms initial
 * and optimal buffer, cyclic-refresh AQ.
 *
 * [maxQuantizer] is libvpx's full range (63, its own default and Android's C2SoftVpxEnc's). WebRTC caps it lower only
 * because it also drops frames and scales resolution to hold the rate; this path keeps every frame in Automatic, so
 * the cap made CBR overshoot instead: at 56 the coarsest step is 55% of 63's, and 400 kbps on the 640x480 coverage
 * input came out at 870-890 kbps (DECISIONS "Design VX", "Rate control fix").
 */
data class NativeVpxTuning(
    /** VPX_ERROR_RESILIENT_DEFAULT: frames do not depend on backward-adapted probability contexts. */
    val errorResilient: Boolean = true,
    val aqMode: Int = 3,
    val maxQuantizer: Int = 63,
    val rateBufferMs: Int = 1_000,
) {
    init {
        require(aqMode in 0..3)
        require(maxQuantizer in 20..63)
        require(rateBufferMs in 100..6_000)
    }

    companion object {
        val DEFAULT = NativeVpxTuning()
    }
}

internal object NativeVpxBridge {
    /** libvpx is linked into the same library as libaom; [NativeAomBridge] loads it once. */
    val available: Boolean get() = NativeAomBridge.available

    /** libvpx's real-time speeds (VP9 cpu-used 5..9: 9 is the fastest). */
    const val MIN_REALTIME_CPU_USED = 5
    const val MAX_REALTIME_CPU_USED = 9

    external fun nativeVersion(): String

    external fun nativeCreate(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        cpuUsed: Int,
        threads: Int,
        variableBitrate: Boolean,
        dropFrames: Boolean,
        errorResilient: Boolean,
        aqMode: Int,
        maxQuantizer: Int,
        rateBufferMs: Int,
    ): Long

    external fun nativeEncode(handle: Long, i420: ByteBuffer, presentationTimeUs: Long, forceKeyFrame: Boolean): Array<NativeAomPacket>

    external fun nativeFlush(handle: Long): Array<NativeAomPacket>

    external fun nativeSetBitrate(handle: Long, bitrate: Int)

    external fun nativeSetCpuUsed(handle: Long, cpuUsed: Int)

    external fun nativeSetMaxInterBitratePct(handle: Long, percent: Int)

    external fun nativeSetKeyFrameQuantizerReference(handle: Long, quantizer: Int)

    external fun nativeClose(handle: Long)

    fun versionOrNull(): String? = if (!available) null else runCatching(::nativeVersion).getOrNull()
}

/** The default libvpx speed for a mode before the governor measures this phone (VP9 cpu-used 5..9). */
internal fun nativeVpxCpuUsed(width: Int, height: Int, fps: Int): Int {
    val pixels = width.toLong() * height
    return when {
        pixels <= 640L * 360L && fps <= 24 -> 7
        pixels <= 640L * 360L -> 8
        pixels <= 720L * 480L && fps <= 20 -> 8
        else -> 9
    }
}

internal class NativeVpxEncoder(
    width: Int,
    height: Int,
    fps: Int,
    bitrate: Int,
    cpuUsed: Int,
    threads: Int,
    variableBitrate: Boolean,
    dropFrames: Boolean,
    tuning: NativeVpxTuning = NativeVpxTuning.DEFAULT,
) : NativeVideoEncoder {
    private var handle = NativeVpxBridge.nativeCreate(
        width, height, fps, bitrate, cpuUsed, threads, variableBitrate, dropFrames,
        tuning.errorResilient, tuning.aqMode, tuning.maxQuantizer, tuning.rateBufferMs,
    ).also { check(it != 0L) { "Could not create native libvpx encoder" } }

    private fun live(): Long = checkNotNull(handle.takeIf { it != 0L }) { "Native libvpx encoder is closed" }

    override fun encode(i420: ByteBuffer, presentationTimeUs: Long, forceKeyFrame: Boolean, layer: Int): Array<NativeAomPacket> {
        require(layer < 0) { "libvpx has no temporal layering on this path" }
        return NativeVpxBridge.nativeEncode(live(), i420, presentationTimeUs, forceKeyFrame)
    }

    override fun flush(): Array<NativeAomPacket> = NativeVpxBridge.nativeFlush(live())

    override fun updateBitrate(bitrate: Int) = NativeVpxBridge.nativeSetBitrate(live(), bitrate)

    override fun updateCpuUsed(cpuUsed: Int) = NativeVpxBridge.nativeSetCpuUsed(
        live(),
        cpuUsed.coerceIn(NativeVpxBridge.MIN_REALTIME_CPU_USED, NativeVpxBridge.MAX_REALTIME_CPU_USED),
    )

    override fun updateMaxInterBitratePct(percent: Int) = NativeVpxBridge.nativeSetMaxInterBitratePct(live(), percent)

    override fun setKeyFrameQuantizerReference(quantizer: Int) =
        NativeVpxBridge.nativeSetKeyFrameQuantizerReference(live(), quantizer)

    override fun close() {
        val activeHandle = handle
        if (activeHandle == 0L) return
        handle = 0L
        NativeVpxBridge.nativeClose(activeHandle)
    }
}
