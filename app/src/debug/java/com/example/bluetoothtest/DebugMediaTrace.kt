package com.example.bluetoothtest

import android.content.Context
import android.media.MediaFormat
import android.util.Log
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in access-unit evidence. It is absent from release builds and never changes the camera preview. */
class DebugMediaTrace private constructor(
    directory: File,
    private val sessionName: String,
    private val role: String,
    private val codecId: Int,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val encoderName: String?,
) : Closeable {
    private data class WindowFrame(val presentationTimeUs: Long, val bytes: Int)

    private val closed = AtomicBoolean(false)
    private val phase = "$sessionName-$role-${android.os.SystemClock.elapsedRealtimeNanos()}"
    private val csvFile = File(directory, "$phase.csv")
    private val ivfFile = File(directory, "$phase.ivf")
    private val summaryFile = File(directory, "$phase-summary.json")
    private val csv: BufferedWriter
    private val ivf: TraceIvfWriter
    private val bitRateWindow = ArrayDeque<WindowFrame>()
    private var frameId = 0L
    private var encodedBytes = 0L
    private var rawTimeline: JSONObject? = null
    private var codecFormat: JSONObject? = null

    init {
        directory.mkdirs()
        csvFile.delete()
        ivfFile.delete()
        summaryFile.delete()
        csv = csvFile.bufferedWriter().apply {
            appendLine(
                "frame_id,pts_us,frame_type,qp,bytes,encode_ms,requested_bitrate," +
                    "rolling_actual_bitrate,sha256,integrity",
            )
        }
        ivf = TraceIvfWriter(ivfFile, codecId, width, height, fps)
        Log.i(TAG, "Media trace armed: ${csvFile.absolutePath}")
    }

    @Synchronized
    fun record(
        presentationTimeUs: Long,
        flags: Int,
        payload: ByteArray,
        qp: Int?,
        pictureType: Int?,
        encodeLatencyMs: Double?,
        requestedBitrate: Int?,
        integrity: String,
    ) {
        check(!closed.get()) { "Media trace is closed" }
        frameId++
        encodedBytes += payload.size
        bitRateWindow.addLast(WindowFrame(presentationTimeUs, payload.size))
        while (bitRateWindow.size > 1 &&
            presentationTimeUs - bitRateWindow.first().presentationTimeUs > BITRATE_WINDOW_US
        ) {
            bitRateWindow.removeFirst()
        }
        val windowDurationUs = maxOf(
            1_000_000L / fps,
            presentationTimeUs - bitRateWindow.first().presentationTimeUs + 1_000_000L / fps,
        )
        val rollingBitrate = bitRateWindow.sumOf(WindowFrame::bytes) * 8_000_000L / windowDurationUs
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).toHex()
        val frameType = pictureTypeLabel(pictureType, flags)
        csv.append(frameId.toString()).append(',')
            .append(presentationTimeUs.toString()).append(',')
            .append(frameType).append(',')
            .append(qp?.toString().orEmpty()).append(',')
            .append(payload.size.toString()).append(',')
            .append(encodeLatencyMs?.let { String.format(Locale.US, "%.3f", it) }.orEmpty()).append(',')
            .append(requestedBitrate?.toString().orEmpty()).append(',')
            .append(rollingBitrate.toString()).append(',')
            .append(digest).append(',')
            .append(integrity)
            .appendLine()
        ivf.writeFrame(payload, frameId - 1)
        if (frameId % FLUSH_EVERY_FRAMES == 0L) csv.flush()
    }

    @Synchronized
    fun setRawTimeline(file: String, fixtureFrames: Long, originPtsUs: Long, endPtsExclusiveUs: Long) {
        check(!closed.get()) { "Media trace is closed" }
        require(fixtureFrames > 0 && endPtsExclusiveUs >= originPtsUs)
        rawTimeline = JSONObject().put("file", file).put("fixture_frames", fixtureFrames)
            .put("origin_pts_us", originPtsUs).put("end_pts_exclusive_us", endPtsExclusiveUs)
    }

    @Synchronized fun setCodecFormat(format: MediaFormat) {
        val observed = JSONObject().put("format", format.toString())
        for (key in listOf(MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_TRANSFER)) {
            observed.put(key, if (format.containsKey(key)) runCatching { format.getInteger(key) }.getOrNull() ?: JSONObject.NULL else JSONObject.NULL)
        }
        codecFormat = observed
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val csvFinalized = runCatching { csv.close() }.isSuccess
        val ivfFinalized = runCatching { ivf.close() }.isSuccess
        runCatching {
            summaryFile.writeText(
                JSONObject()
                    .put("session", sessionName)
                    .put("role", role)
                    .put("pid", android.os.Process.myPid())
                    .put("phase", phase)
                    .put("width", width).put("height", height).put("fps", fps)
                    .put("finalized", csvFinalized && ivfFinalized)
                    .put("raw_timeline", rawTimeline ?: JSONObject.NULL)
                    .put("codec_format", codecFormat ?: JSONObject.NULL)
                    .put("codec", if (codecId == StreamProtocol.CODEC_AV1) "av1" else "vp9")
                    .put("encoder_name", encoderName ?: JSONObject.NULL)
                    .put("frames", frameId)
                    .put("encoded_bytes", encodedBytes)
                    .put("csv", csvFile.name)
                    .put("ivf", ivfFile.name)
                    .toString(2) + "\n",
            )
        }
    }

    private fun pictureTypeLabel(pictureType: Int?, flags: Int): String = when (pictureType) {
        MediaFormat.PICTURE_TYPE_I -> "I"
        MediaFormat.PICTURE_TYPE_P -> "P"
        MediaFormat.PICTURE_TYPE_B -> "B"
        else -> if (flags and StreamProtocol.FLAG_KEY_FRAME != 0) "I" else "delta"
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        private const val TAG = "BluetoothVideo"
        private const val BITRATE_WINDOW_US = 1_000_000L
        private const val FLUSH_EVERY_FRAMES = 30L

        fun startIfRequested(
            context: Context,
            requestedSessionName: String?,
            role: String,
            codecId: Int,
            width: Int,
            height: Int,
            fps: Int,
            encoderName: String? = null,
        ): DebugMediaTrace? {
            val sessionName = requestedSessionName
                ?.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                ?.take(80)
                ?.takeIf(String::isNotBlank)
                ?: return null
            val safeRole = role.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(40)
            return DebugMediaTrace(
                File(context.getExternalFilesDir(null), "media-traces"),
                sessionName,
                safeRole,
                codecId,
                width,
                height,
                fps,
                encoderName,
            )
        }
    }
}

private class TraceIvfWriter(
    file: File,
    codecId: Int,
    width: Int,
    height: Int,
    fps: Int,
) : Closeable {
    private val output = RandomAccessFile(file, "rw")
    private var frames = 0

    init {
        output.setLength(0)
        output.write("DKIF".toByteArray(Charsets.US_ASCII))
        writeShortLe(0)
        writeShortLe(32)
        output.write(
            when (codecId) {
                StreamProtocol.CODEC_AV1 -> "AV01"
                StreamProtocol.CODEC_VP9 -> "VP90"
                StreamProtocol.CODEC_H264 -> "H264"
                StreamProtocol.CODEC_HEVC -> "H265"
                else -> error("Unsupported trace codec $codecId")
            }.toByteArray(Charsets.US_ASCII),
        )
        writeShortLe(width)
        writeShortLe(height)
        writeIntLe(fps)
        writeIntLe(1)
        writeIntLe(0)
        writeIntLe(0)
    }

    fun writeFrame(payload: ByteArray, timestamp: Long) {
        writeIntLe(payload.size)
        writeLongLe(timestamp)
        output.write(payload)
        frames++
    }

    override fun close() {
        output.seek(24)
        writeIntLe(frames)
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
