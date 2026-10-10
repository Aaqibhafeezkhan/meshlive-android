package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.math.ceil

data class VideoResolution(val width: Int, val height: Int, val label: String)

data class VideoSettings(
    val resolution: VideoResolution,
    val fps: Int,
    val bitRate: Int,
    val latencyMs: Int,
) {
    val queueCapacity: Int
        get() = maxOf(
            2,
            ceil((fps + AUDIO_PACKETS_PER_SECOND) * latencyMs / 1_000.0).toInt(),
        )

    val keyFrameIntervalSeconds: Int
        get() = NORMAL_KEY_FRAME_INTERVAL_SECONDS

    companion object {
        val RESOLUTIONS = listOf(
            VideoResolution(426, 240, "240p"),
            VideoResolution(640, 360, "360p"),
            VideoResolution(854, 480, "480p"),
        )
        val FPS_OPTIONS = listOf(15, 24, 30)
        // 240p/200 kbps (design decision 2026-10-10): WebRTC's VP9 start limits put 360p at ~350 kbps, so the
        // former 360p/300 kbps default was under-fed on real Bluetooth links; users can still pick 360p/480p.
        val DEFAULT = VideoSettings(RESOLUTIONS[0], fps = 15, bitRate = 200_000, latencyMs = 500)
        const val MIN_BIT_RATE_KBPS = 100
        const val MAX_BIT_RATE_KBPS = 1_500
        const val BIT_RATE_STEP_KBPS = 25
        const val MIN_LATENCY_MS = 250
        const val MAX_LATENCY_MS = 6_000
        const val LATENCY_STEP_MS = 250
        // Viewers get keyframes on demand (forced on join, requested after loss) over reliable,
        // ordered links; this is only a safety net. A scheduled keyframe on detailed content reached
        // 110-130 KB and was the only freeze left in a run: 635-767 ms on GATT 600 kbps (every 5 s)
        // and 867 ms on CoC 600 kbps at 15 s, with every other 10 s interval at 15.0 FPS.
        private const val NORMAL_KEY_FRAME_INTERVAL_SECONDS = 60
        private const val AUDIO_PACKETS_PER_SECOND = 50
    }
}

object StreamProtocol {
    const val PACKET_HEADER_BYTES = 13
    const val FLAG_CODEC_CONFIG = 1
    const val FLAG_KEY_FRAME = 2
    const val FLAG_DISPLAY_ROTATION = 4
    const val FLAG_AUDIO = 8
    const val FLAG_DEBUG_INTEGRITY = 16
    const val FLAG_DISCONTINUITY = 32
    const val FLAG_AUDIO_PAIR = 64
    /**
     * Experimental temporal layering only: video no later frame references (temporal layer 1, proven
     * from the bitstream); any hop may drop it without a keyframe. Never set while the experiment is off.
     */
    const val FLAG_DISCARDABLE = 128
    const val CODEC_VP9 = 2
    const val CODEC_AV1 = 3
    const val AUDIO_CODEC_NONE = 0
    const val AUDIO_CODEC_OPUS = 1
    const val AUDIO_SAMPLE_RATE = 48_000
    const val AUDIO_CHANNEL_COUNT = 1
    const val AUDIO_BIT_RATE = 32_000
    const val AUDIO_PACKET_DURATION_MS = 20
    const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024

    data class Header(
        val width: Int,
        val height: Int,
        val fps: Int,
        val codecId: Int,
        val rotationDegrees: Int,
        val audioCodecId: Int,
        val audioSampleRate: Int,
        val audioChannelCount: Int,
    ) {
        val displayWidth: Int
            get() = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
        val displayHeight: Int
            get() = if (rotationDegrees == 90 || rotationDegrees == 270) width else height
        val hasAudio: Boolean
            get() = audioCodecId != AUDIO_CODEC_NONE
    }
    data class Packet(val flags: Int, val presentationTimeUs: Long, val payload: ByteArray) {
        val isAudio: Boolean
            get() = flags and FLAG_AUDIO != 0
    }

    data class IntegrityResult(val packet: Packet, val verified: Boolean)

    fun writeHeader(
        output: OutputStream,
        codecId: Int,
        settings: VideoSettings,
        rotationDegrees: Int = 0,
        includeAudio: Boolean = false,
    ) {
        require(rotationDegrees == 0 || rotationDegrees == 90 ||
            rotationDegrees == 180 || rotationDegrees == 270) {
            "Invalid video rotation $rotationDegrees"
        }
        DataOutputStream(output).apply {
            write(byteArrayOf('B'.code.toByte(), 'T'.code.toByte(), 'V'.code.toByte(), '3'.code.toByte()))
            writeShort(settings.resolution.width)
            writeShort(settings.resolution.height)
            writeByte(settings.fps)
            writeByte(codecId) // Codec 2 = VP9, codec 3 = AV1.
            writeByte(rotationDegrees / 90)
            writeByte(if (includeAudio) AUDIO_CODEC_OPUS else AUDIO_CODEC_NONE)
            writeShort(if (includeAudio) AUDIO_SAMPLE_RATE else 0)
            writeByte(if (includeAudio) AUDIO_CHANNEL_COUNT else 0)
        }
    }

    fun writePacket(output: OutputStream, flags: Int, presentationTimeUs: Long, payload: ByteArray) {
        DataOutputStream(output).apply {
            writeInt(payload.size)
            writeByte(flags)
            writeLong(presentationTimeUs)
            write(payload)
        }
    }

    fun attachDebugIntegrity(packet: Packet): Packet {
        require(!packet.isAudio) { "Debug video integrity cannot be attached to audio" }
        require(packet.flags and FLAG_DEBUG_INTEGRITY == 0) { "Debug integrity is already attached" }
        val digest = MessageDigest.getInstance("SHA-256").digest(packet.payload)
        return packet.copy(
            flags = packet.flags or FLAG_DEBUG_INTEGRITY,
            payload = digest + packet.payload,
        )
    }

    fun verifyAndStripDebugIntegrity(packet: Packet): IntegrityResult {
        if (packet.flags and FLAG_DEBUG_INTEGRITY == 0) return IntegrityResult(packet, false)
        require(!packet.isAudio) { "Debug video integrity was attached to audio" }
        require(packet.payload.size >= SHA_256_BYTES) { "Truncated debug video integrity digest" }
        val expected = packet.payload.copyOfRange(0, SHA_256_BYTES)
        val payload = packet.payload.copyOfRange(SHA_256_BYTES, packet.payload.size)
        val actual = MessageDigest.getInstance("SHA-256").digest(payload)
        require(MessageDigest.isEqual(expected, actual)) {
            "Encoded access unit changed between sender and receiver"
        }
        return IntegrityResult(
            packet.copy(flags = packet.flags and FLAG_DEBUG_INTEGRITY.inv(), payload = payload),
            true,
        )
    }

    fun encodeDisplayRotation(rotationDegrees: Int): ByteArray {
        require(rotationDegrees == 0 || rotationDegrees == 90 ||
            rotationDegrees == 180 || rotationDegrees == 270) {
            "Invalid video rotation $rotationDegrees"
        }
        return byteArrayOf((rotationDegrees / 90).toByte())
    }

    fun decodeDisplayRotation(packet: Packet): Int {
        require(packet.flags and FLAG_DISPLAY_ROTATION != 0 && packet.payload.size == 1) {
            "Invalid display rotation packet"
        }
        val quarterTurns = packet.payload[0].toInt() and 0xff
        require(quarterTurns in 0..3) { "Invalid video rotation" }
        return quarterTurns * 90
    }

    fun readHeader(input: InputStream): Header {
        val data = DataInputStream(input)
        val version = String(data.readFullyBytes(4), Charsets.US_ASCII)
        require(version == "BTV3") { "Unsupported stream header $version" }
        val width = data.readUnsignedShort()
        val height = data.readUnsignedShort()
        val fps = data.readUnsignedByte()
        val codecId = data.readUnsignedByte()
        val rotationQuarterTurns = data.readUnsignedByte()
        val audioCodecId = data.readUnsignedByte()
        val audioSampleRate = data.readUnsignedShort()
        val audioChannelCount = data.readUnsignedByte()
        require(width in 1..4096 && height in 1..4096 && fps in 1..120) {
            "Invalid video format ${width}x$height at $fps FPS"
        }
        require(codecId == CODEC_VP9 || codecId == CODEC_AV1) {
            "Unsupported codec ID $codecId"
        }
        require(rotationQuarterTurns in 0..3) { "Invalid video rotation" }
        require(
            audioCodecId == AUDIO_CODEC_NONE && audioSampleRate == 0 && audioChannelCount == 0 ||
                audioCodecId == AUDIO_CODEC_OPUS && audioSampleRate == AUDIO_SAMPLE_RATE &&
                audioChannelCount == AUDIO_CHANNEL_COUNT,
        ) { "Unsupported audio format $audioCodecId/$audioSampleRate/$audioChannelCount" }
        return Header(
            width,
            height,
            fps,
            codecId,
            rotationQuarterTurns * 90,
            audioCodecId,
            audioSampleRate,
            audioChannelCount,
        )
    }

    fun readPacket(input: InputStream): Packet {
        val data = DataInputStream(input)
        val size = data.readInt()
        require(size in 0..MAX_PAYLOAD_BYTES) { "Invalid payload size $size" }
        return Packet(
            flags = data.readUnsignedByte(),
            presentationTimeUs = data.readLong(),
            payload = data.readFullyBytes(size),
        )
    }

    fun pairAudioPackets(first: Packet, second: Packet): Packet {
        require(first.flags == FLAG_AUDIO && second.flags == FLAG_AUDIO) {
            "Only ordinary Opus access units can be paired"
        }
        require(first.payload.size <= MAX_AUDIO_ACCESS_UNIT_BYTES &&
            second.payload.size <= MAX_AUDIO_ACCESS_UNIT_BYTES) {
            "Opus access unit is too large to pair"
        }
        val timestampDeltaUs = second.presentationTimeUs - first.presentationTimeUs
        require(timestampDeltaUs in 0..MAX_AUDIO_PAIR_GAP_US) {
            "Invalid paired-audio timestamp gap $timestampDeltaUs"
        }
        val payload = ByteArrayOutputStream(
            AUDIO_PAIR_HEADER_BYTES + first.payload.size + second.payload.size,
        ).also { output ->
            DataOutputStream(output).apply {
                write(AUDIO_PAIR_MAGIC)
                writeInt(timestampDeltaUs.toInt())
                writeShort(first.payload.size)
                write(first.payload)
                writeShort(second.payload.size)
                write(second.payload)
            }
        }.toByteArray()
        return Packet(
            flags = FLAG_AUDIO or FLAG_AUDIO_PAIR,
            presentationTimeUs = first.presentationTimeUs,
            payload = payload,
        )
    }

    fun batchAudioPackets(packets: List<Packet>): Packet {
        require(packets.size in 3..MAX_AUDIO_BATCH_PACKETS) {
            "An audio batch must contain 3 to $MAX_AUDIO_BATCH_PACKETS access units"
        }
        require(packets.all { it.flags == FLAG_AUDIO }) {
            "Only ordinary Opus access units can be batched"
        }
        require(packets.all { it.payload.size <= MAX_AUDIO_ACCESS_UNIT_BYTES }) {
            "Opus access unit is too large to batch"
        }
        val firstTimeUs = packets.first().presentationTimeUs
        val deltas = packets.map { it.presentationTimeUs - firstTimeUs }
        require(deltas.all { it in 0..MAX_AUDIO_PAIR_GAP_US }) {
            "Invalid batched-audio timestamp gap"
        }
        val payload = ByteArrayOutputStream(
            AUDIO_BATCH_HEADER_BYTES + packets.sumOf { it.payload.size + AUDIO_BATCH_ITEM_HEADER_BYTES },
        ).also { output ->
            DataOutputStream(output).apply {
                write(AUDIO_BATCH_MAGIC)
                writeByte(packets.size)
                packets.zip(deltas).forEach { (packet, deltaUs) ->
                    writeInt(deltaUs.toInt())
                    writeShort(packet.payload.size)
                    write(packet.payload)
                }
            }
        }.toByteArray()
        return Packet(FLAG_AUDIO or FLAG_AUDIO_PAIR, firstTimeUs, payload)
    }

    fun splitAudioPair(packet: Packet): List<Packet> {
        require(packet.flags == (FLAG_AUDIO or FLAG_AUDIO_PAIR)) {
            "Packet is not a paired-audio record"
        }
        val input = ByteArrayInputStream(packet.payload)
        val data = DataInputStream(input)
        val magic = data.readFullyBytes(AUDIO_PAIR_MAGIC.size)
        if (magic.contentEquals(AUDIO_BATCH_MAGIC)) {
            val count = data.readUnsignedByte()
            require(count in 3..MAX_AUDIO_BATCH_PACKETS) { "Invalid audio batch size $count" }
            return List(count) {
                val timestampDeltaUs = data.readInt()
                require(timestampDeltaUs.toLong() in 0..MAX_AUDIO_PAIR_GAP_US) {
                    "Invalid batched-audio timestamp gap $timestampDeltaUs"
                }
                Packet(
                    FLAG_AUDIO,
                    packet.presentationTimeUs + timestampDeltaUs,
                    data.readAudioAccessUnit(),
                )
            }.also {
                require(input.available() == 0) { "Trailing batched-audio data" }
            }
        }
        require(magic.contentEquals(AUDIO_PAIR_MAGIC)) { "Invalid paired-audio header" }
        val timestampDeltaUs = data.readInt()
        require(timestampDeltaUs.toLong() in 0..MAX_AUDIO_PAIR_GAP_US) {
            "Invalid paired-audio timestamp gap $timestampDeltaUs"
        }
        val firstPayload = data.readAudioAccessUnit()
        val secondPayload = data.readAudioAccessUnit()
        require(input.available() == 0) { "Trailing paired-audio data" }
        return listOf(
            Packet(FLAG_AUDIO, packet.presentationTimeUs, firstPayload),
            Packet(FLAG_AUDIO, packet.presentationTimeUs + timestampDeltaUs, secondPayload),
        )
    }

    private fun DataInputStream.readAudioAccessUnit(): ByteArray {
        val size = readUnsignedShort()
        require(size <= MAX_AUDIO_ACCESS_UNIT_BYTES) { "Invalid Opus access-unit size $size" }
        return readFullyBytes(size)
    }

    private fun DataInputStream.readFullyBytes(size: Int): ByteArray =
        ByteArray(size).also(::readFully)

    private val AUDIO_PAIR_MAGIC = byteArrayOf(
        'B'.code.toByte(), 'T'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte(),
    )
    private val AUDIO_BATCH_MAGIC = byteArrayOf(
        'B'.code.toByte(), 'T'.code.toByte(), 'A'.code.toByte(), '2'.code.toByte(),
    )
    private const val AUDIO_PAIR_HEADER_BYTES = 12
    private const val AUDIO_BATCH_HEADER_BYTES = 5
    private const val AUDIO_BATCH_ITEM_HEADER_BYTES = 6
    private const val MAX_AUDIO_BATCH_PACKETS = 20
    private const val MAX_AUDIO_ACCESS_UNIT_BYTES = 16 * 1024
    private const val MAX_AUDIO_PAIR_GAP_US = 1_000_000L
    private const val SHA_256_BYTES = 32
}
