package com.example.bluetoothtest

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

data class IvfReplayFrame(
    val presentationTimeUs: Long,
    val payload: ByteArray,
    val keyFrame: Boolean,
)

data class IvfReplayStream(
    val codecId: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val frames: List<IvfReplayFrame>,
)

object IvfReader {
    fun read(file: File): IvfReplayStream = read(file.readBytes())

    fun read(bytes: ByteArray): IvfReplayStream {
        require(bytes.size >= HEADER_BYTES) { "IVF file is shorter than its header" }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(readAscii(data, 4) == "DKIF") { "Not an IVF file" }
        require(data.short.toInt() and 0xffff == 0) { "Unsupported IVF version" }
        val headerBytes = data.short.toInt() and 0xffff
        require(headerBytes in HEADER_BYTES..bytes.size) { "Invalid IVF header length" }
        val codecId = when (readAscii(data, 4)) {
            "VP90" -> StreamProtocol.CODEC_VP9
            "AV01" -> StreamProtocol.CODEC_AV1
            else -> error("IVF codec must be VP90 or AV01")
        }
        val width = data.short.toInt() and 0xffff
        val height = data.short.toInt() and 0xffff
        val rate = data.int.toLong() and 0xffff_ffffL
        val scale = data.int.toLong() and 0xffff_ffffL
        val declaredFrames = data.int.toLong() and 0xffff_ffffL
        data.int // unused
        require(width > 0 && height > 0 && rate > 0 && scale > 0) { "Invalid IVF video format" }
        require(rate % scale == 0L) { "IVF rate $rate/$scale is not an integer FPS" }
        val fps = rate / scale
        require(fps <= Int.MAX_VALUE) { "IVF FPS does not fit the video format" }
        data.position(headerBytes)
        val frames = mutableListOf<IvfReplayFrame>()
        var previousTimestamp = 0L
        while (data.remaining() > 0) {
            require(data.remaining() >= FRAME_HEADER_BYTES) { "Truncated IVF frame header" }
            val frameBytes = data.int.toLong() and 0xffff_ffffL
            val timestamp = data.long
            require(timestamp >= previousTimestamp) { "IVF timestamps must be nonnegative and ordered" }
            previousTimestamp = timestamp
            require(frameBytes in 1..StreamProtocol.MAX_PAYLOAD_BYTES.toLong()) {
                "Invalid IVF frame size $frameBytes"
            }
            require(frameBytes <= data.remaining()) { "Truncated IVF frame payload" }
            val payload = ByteArray(frameBytes.toInt()).also(data::get)
            // Divide before multiplying so a valid rational timebase cannot overflow.
            val seconds = timestamp / fps
            val fractionUs = timestamp % fps * 1_000_000L / fps
            val maxTimeUs = Long.MAX_VALUE / 1_000L
            require(seconds <= maxTimeUs / 1_000_000L) { "IVF timestamp exceeds nanosecond replay timing" }
            val timeUs = seconds * 1_000_000L + fractionUs
            require(timeUs <= maxTimeUs) { "IVF timestamp exceeds nanosecond replay timing" }
            frames += IvfReplayFrame(
                presentationTimeUs = timeUs,
                payload = payload,
                keyFrame = frames.isEmpty() || codecId == StreamProtocol.CODEC_VP9 && isVp9KeyFrame(payload),
            )
        }
        require(frames.isNotEmpty()) { "IVF contains no frames" }
        if (declaredFrames != 0L) require(declaredFrames == frames.size.toLong()) {
            "IVF declares $declaredFrames frames but contains ${frames.size}"
        }
        return IvfReplayStream(codecId, width, height, fps.toInt(), frames)
    }

    private fun readAscii(data: ByteBuffer, length: Int): String {
        val value = ByteArray(length).also(data::get)
        return String(value, StandardCharsets.US_ASCII)
    }

    private fun isVp9KeyFrame(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        val first = payload[0].toInt() and 0xff
        if (first ushr 6 != 2) return false
        val profile = (first ushr 5 and 1) or (first ushr 3 and 2)
        val showExistingBit = if (profile == 3) 2 else 3
        if (first ushr showExistingBit and 1 != 0) return false
        return first ushr (showExistingBit - 1) and 1 == 0
    }

    private const val HEADER_BYTES = 32
    private const val FRAME_HEADER_BYTES = 12
}

class IvfVideoReplay(
    file: File,
    expectedCodecId: Int,
    expectedSettings: VideoSettings,
    private val onPacket: (Int, Long, ByteArray) -> Unit,
    private val onStatus: (String) -> Unit,
) : Closeable {
    private val stream = IvfReader.read(file)
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    init {
        require(stream.codecId == expectedCodecId) { "Replay codec does not match the selected encoder" }
        require(stream.width == expectedSettings.resolution.width &&
            stream.height == expectedSettings.resolution.height
        ) { "Replay is ${stream.width}x${stream.height}; select that resolution" }
        require(stream.fps == expectedSettings.fps) {
            "Replay is ${stream.fps} FPS; select that frame rate"
        }
    }

    fun start() {
        check(running.compareAndSet(false, true)) { "Replay already started" }
        worker = thread(name = "IvfVideoReplay") {
            val startNs = System.nanoTime()
            try {
                stream.frames.forEach { frame ->
                    while (running.get()) {
                        val remainingNs = frame.presentationTimeUs * 1_000L - (System.nanoTime() - startNs)
                        if (remainingNs <= 0) break
                        LockSupport.parkNanos(remainingNs.coerceAtMost(20_000_000L))
                    }
                    if (!running.get()) return@thread
                    onPacket(
                        if (frame.keyFrame) StreamProtocol.FLAG_KEY_FRAME else 0,
                        frame.presentationTimeUs,
                        frame.payload,
                    )
                }
                onStatus("Deterministic IVF replay finished: ${stream.frames.size} frames")
            } finally {
                running.set(false)
            }
        }
    }

    override fun close() {
        running.set(false)
        worker?.interrupt()
        runCatching { worker?.join(1_000) }
        worker = null
    }
}
