package com.example.bluetoothtest

/**
 * JNI entry points of the Experimental native libopus call codec (`native_opus_codec.cpp`, DEBUG
 * `test_call_native_opus`, default off). The bridge lives in the app's single native library. Host JVM
 * tests have no Android library; they load a desktop build named by `BTV_NATIVE_OPUS_HOST_LIB`
 * (CMake `-DBTV_OPUS_HOST_TEST=ON`), and are skipped without one.
 */
internal object NativeOpusBridge {
    /** Why the native codec cannot be used here, or null when it loaded. */
    val loadError: Throwable? = runCatching { load() }.exceptionOrNull()

    val available: Boolean
        get() = loadError == null

    private fun load() {
        try {
            System.loadLibrary("bluetooth_video_native")
        } catch (error: UnsatisfiedLinkError) {
            val hostLibrary = System.getenv(HOST_LIBRARY_ENV) ?: throw error
            System.load(hostLibrary)
        }
        nativeOpusVersion()
    }

    external fun nativeOpusVersion(): String

    external fun nativeEncoderCreate(
        sampleRate: Int,
        channels: Int,
        bitRate: Int,
        complexity: Int,
        fecLossPercent: Int,
        dtx: Boolean,
        constrainedVbr: Boolean,
    ): Long

    external fun nativeEncoderLookahead(handle: Long): Int

    external fun nativeEncoderSetLossPercent(handle: Long, percent: Int): Int

    external fun nativeEncode(handle: Long, pcm: ByteArray, frameSamples: Int, output: ByteArray): Int

    external fun nativeEncoderDestroy(handle: Long)

    external fun nativeDecoderCreate(sampleRate: Int, channels: Int): Long

    external fun nativeDecode(
        handle: Long,
        packet: ByteArray?,
        packetLength: Int,
        frameSamples: Int,
        fec: Boolean,
        output: ByteArray,
    ): Int

    external fun nativeDecoderDestroy(handle: Long)

    external fun nativePacketSamples(packet: ByteArray, packetLength: Int, sampleRate: Int): Int

    external fun nativePacketHasFec(packet: ByteArray, packetLength: Int): Int

    const val HOST_LIBRARY_ENV = "BTV_NATIVE_OPUS_HOST_LIB"
}
