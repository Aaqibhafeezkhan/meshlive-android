package com.example.bluetoothtest

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns the Opus configuration a sender's encoder reported into the three buffers every Android
 * Opus decoder documents: csd-0 OpusHead, csd-1 codec delay (ns, little-endian long) and csd-2 seek
 * pre-roll (ns). Codec2 encoders may report one combined "AOPUSHDR/AOPUSDLY/AOPUSPRL" block, older
 * ones a bare OpusHead; Android 10's OMX.google.opus.decoder (Oppo A15s) failed on a single csd-0
 * ("Audio pipeline failed" on every stream and call, lane-a-matrix-529f36cc).
 */
internal object OpusCodecConfig {
    private const val OPUS_HEAD = "OpusHead"
    private const val MARKER_HEADER = "AOPUSHDR"
    private const val MARKER_DELAY = "AOPUSDLY"
    private const val MARKER_PREROLL = "AOPUSPRL"
    private const val OPUS_SAMPLE_RATE = 48_000L
    /** Opus's recommended seek pre-roll. */
    const val DEFAULT_PREROLL_NS = 80_000_000L

    fun normalize(configs: List<ByteArray>): List<ByteArray> {
        require(configs.isNotEmpty()) { "Opus codec configuration is missing" }
        val first = configs.first()
        if (startsWith(first, MARKER_HEADER)) return fromMarkers(first)
        require(startsWith(first, OPUS_HEAD) && first.size >= 19) { "Unsupported Opus codec configuration" }
        if (configs.size >= 3 && configs[1].size == 8 && configs[2].size == 8) return configs.take(3)
        val preSkip = (first[10].toInt() and 0xff) or ((first[11].toInt() and 0xff) shl 8)
        return listOf(first, longBytes(preSkip * 1_000_000_000L / OPUS_SAMPLE_RATE), longBytes(DEFAULT_PREROLL_NS))
    }

    private fun fromMarkers(block: ByteArray): List<ByteArray> {
        val buffer = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        var head: ByteArray? = null
        var delay: ByteArray? = null
        var preroll: ByteArray? = null
        while (buffer.remaining() >= 16) {
            val marker = ByteArray(8).also(buffer::get).toString(Charsets.US_ASCII)
            val length = buffer.long
            require(length in 0..buffer.remaining().toLong()) { "Truncated Opus configuration" }
            val value = ByteArray(length.toInt()).also(buffer::get)
            when (marker) {
                MARKER_HEADER -> head = value
                MARKER_DELAY -> delay = value
                MARKER_PREROLL -> preroll = value
            }
        }
        val opusHead = requireNotNull(head) { "Opus configuration has no header" }
        return normalize(listOfNotNull(opusHead, delay, preroll).let { parts ->
            if (parts.size == 3 && parts[1].size == 8 && parts[2].size == 8) parts else listOf(opusHead)
        })
    }

    private fun longBytes(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun startsWith(bytes: ByteArray, prefix: String): Boolean =
        bytes.size >= prefix.length && prefix.indices.all { bytes[it] == prefix[it].code.toByte() }
}
