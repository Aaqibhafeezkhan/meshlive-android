package com.example.bluetoothtest

enum class VideoFlushPolicy(
    val debugValue: String,
    val periodicFlushMs: Long,
    val byteThreshold: Int,
) {
    EVERY_FRAME("frame", 0, 0),
    EVERY_5_MS("5ms", 5, Int.MAX_VALUE),
    EVERY_10_MS("10ms", 10, Int.MAX_VALUE),
    BUFFER_16_KIB_OR_50_MS("16k", 50, 16 * 1024),
    BUFFER_32_KIB_OR_50_MS("32k", 50, 32 * 1024),
    ;

    companion object {
        fun fromDebugValue(value: String?): VideoFlushPolicy = entries.firstOrNull {
            it.debugValue.equals(value, ignoreCase = true)
        } ?: EVERY_5_MS
    }
}

data class VideoStreamIoConfig(
    val bufferBytes: Int = DEFAULT_BUFFER_BYTES,
    val flushPolicy: VideoFlushPolicy = VideoFlushPolicy.EVERY_5_MS,
) {
    init {
        require(bufferBytes in MIN_BUFFER_BYTES..MAX_BUFFER_BYTES)
    }

    companion object {
        const val MIN_BUFFER_BYTES = 4 * 1024
        const val MAX_BUFFER_BYTES = 128 * 1024
        const val DEFAULT_BUFFER_BYTES = 32 * 1024
        val DEFAULT = VideoStreamIoConfig()
    }
}

internal fun defaultVideoStreamBufferBytes(transport: BluetoothTransportOption): Int =
    if (transport == BluetoothTransportOption.BLE_L2CAP) {
        4 * 1024
    } else {
        VideoStreamIoConfig.DEFAULT_BUFFER_BYTES
    }
