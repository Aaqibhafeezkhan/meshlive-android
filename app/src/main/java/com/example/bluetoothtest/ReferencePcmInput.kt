package com.example.bluetoothtest

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

data class RawReferenceClock(val ptsOriginUs: Long, val monotonicOriginNs: Long) {
    fun duePacketSample(nowNs: Long): Long =
        (maxOf(0L, nowNs - monotonicOriginNs) / 20_000_000L) * 960

    fun packetDeadlineNs(sample: Long): Long = monotonicOriginNs + sample * 1_000_000_000L / 48_000
}

internal fun requireNativeRawVideoMode(requested: VideoSettings, effective: VideoSettings) {
    require(requested.resolution.width == effective.resolution.width &&
        requested.resolution.height == effective.resolution.height && requested.fps == effective.fps
    ) {
        "Native raw video ${requested.resolution.width}x${requested.resolution.height}@${requested.fps} " +
            "differs from negotiated ${effective.resolution.width}x${effective.resolution.height}@${effective.fps}; no conversion"
    }
}

/** Exact reference samples; no resampling, gain, channel mixing or padded partial packets. */
internal class ReferencePcmInput(file: File) : Closeable {
    private val input = RandomAccessFile(file, "r")
    private var dataOffset = 0L
    private var dataBytes = 0L
    val sampleCount: Long get() = dataBytes / 2
    private var cursor = 0L

    init {
        try {
            require(input.length() >= 44) { "Reference WAV is truncated" }
            require(tag() == "RIFF") { "Reference needs a RIFF PCM WAV" }
            val declaredBytes = uint32() + 8
            require(declaredBytes == input.length() && tag() == "WAVE") { "Reference WAV size/header mismatch" }
            var formatSeen = false
            while (input.filePointer < declaredBytes) {
                require(declaredBytes - input.filePointer >= 8) { "Reference WAV chunk header is truncated" }
                val name = tag()
                val size = uint32()
                val start = input.filePointer
                val end = start + size
                require(end <= declaredBytes) { "Reference WAV chunk exceeds the file" }
                when (name) {
                    "fmt " -> {
                        require(!formatSeen && size >= 16) { "Reference WAV format is missing or duplicated" }
                        require(uint16() == 1 && uint16() == 1 && uint32() == 48_000L &&
                            uint32() == 96_000L && uint16() == 2 && uint16() == 16
                        ) { "Reference must be PCM16 little-endian mono 48000 Hz; no conversion" }
                        formatSeen = true
                    }
                    "data" -> {
                        require(dataBytes == 0L && size > 0 && size % 2 == 0L) { "Reference WAV has invalid/duplicate PCM data" }
                        dataOffset = start
                        dataBytes = size
                    }
                }
                val next = end + size % 2
                require(next <= declaredBytes) { "Reference WAV padding is truncated" }
                input.seek(next)
            }
            require(formatSeen && dataBytes > 0) { "Reference WAV needs format and samples" }
            input.seek(dataOffset)
        } catch (error: Throwable) {
            input.close()
            throw error
        }
    }

    fun requireVideoTimeline(frameCount: Long, fps: Int) {
        require(frameCount > 0 && fps > 0 && frameCount <= Long.MAX_VALUE / 48_000) { "Invalid reference video timeline" }
        require(frameCount * 48_000 % fps == 0L && sampleCount == frameCount * 48_000 / fps) {
            "Reference WAV must cover the exact video loop in whole 48000 Hz samples; fractional sample loops unsupported"
        }
    }

    fun readPacket(bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size % 2 == 0) { "Reference packets need whole PCM16 samples" }
        var offset = 0
        while (offset < bytes.size) {
            if (cursor == dataBytes) {
                cursor = 0
                input.seek(dataOffset)
            }
            val count = minOf(bytes.size - offset, (dataBytes - cursor).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            input.readFully(bytes, offset, count)
            cursor += count
            offset += count
        }
    }

    fun seekSample(sample: Long) {
        require(sample >= 0) { "Reference sample cannot be negative" }
        cursor = sample % sampleCount * 2
        input.seek(dataOffset + cursor)
    }

    private fun tag(): String = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
    private fun uint16(): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
    private fun uint32(): Long = (0..3).sumOf { input.readUnsignedByte().toLong() shl (it * 8) }
    override fun close() = input.close()
}
