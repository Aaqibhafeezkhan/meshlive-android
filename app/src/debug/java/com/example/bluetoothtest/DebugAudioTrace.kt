package com.example.bluetoothtest

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Opt-in PCM evidence. AudioTrack timestamps are output-clock proxies, never speaker recordings. */
class DebugAudioTrace private constructor(directory: File, session: String, role: String) : Closeable {
    private data class Chunk(val stage: String, val ptsUs: Long, val bytes: ByteArray,
        val sampleStart: Long, val atNs: Long, val trackFrame: Long?, val trackNs: Long?, val trackUnderruns: Int?)
    private val open = AtomicBoolean(true)
    private val overflow = AtomicLong()
    private val attemptedSamples = AtomicLong()
    private val invalidReason = AtomicReference<String?>()
    private val queue = ArrayBlockingQueue<Chunk>(256)
    private val phase = "$session-audio-$role-${android.os.SystemClock.elapsedRealtimeNanos()}"
    private val worker: Thread

    init {
        directory.mkdirs()
        worker = thread(name = "BenchmarkPcmTrace") {
            var writtenSamples = 0L
            var complete = false
            var error: String? = null
            try {
                RandomAccessFile(File(directory, "$phase.wav"), "rw").use { wav ->
                    wav.setLength(0)
                    wav.write(ByteArray(44))
                    File(directory, "$phase.csv").bufferedWriter().use { csv ->
                        csv.appendLine("sample_start,sample_count,source_pts_us,stage,at_ns,audio_frame_position,audio_timestamp_ns,audio_underrun_count")
                        while (open.get() || queue.isNotEmpty()) {
                            val chunk = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                            require(writtenSamples * 2 + chunk.bytes.size <= 0xffff_ffffL - 36) { "PCM trace exceeds RIFF capacity" }
                            wav.write(chunk.bytes)
                            writtenSamples += chunk.bytes.size / 2
                            csv.appendLine("${chunk.sampleStart},${chunk.bytes.size / 2},${chunk.ptsUs},${chunk.stage},${chunk.atNs},${chunk.trackFrame ?: ""},${chunk.trackNs ?: ""},${chunk.trackUnderruns ?: ""}")
                        }
                    }
                    wav.seek(0)
                    fun le(value: Long, bytes: Int) { repeat(bytes) { wav.write((value ushr (it * 8)).toInt() and 255) } }
                    wav.write("RIFF".toByteArray()); le(writtenSamples * 2 + 36, 4)
                    wav.write("WAVEfmt ".toByteArray()); le(16, 4); le(1, 2); le(1, 2)
                    le(48_000, 4); le(96_000, 4); le(2, 2); le(16, 2)
                    wav.write("data".toByteArray()); le(writtenSamples * 2, 4)
                    complete = overflow.get() == 0L && writtenSamples == attemptedSamples.get() && invalidReason.get() == null
                }
            } catch (failure: Throwable) { error = failure.message ?: failure.javaClass.simpleName }
            finally {
                runCatching { File(directory, "$phase-summary.json").writeText(JSONObject()
                    .put("schema", "benchmark-pcm-trace-v1").put("session", session).put("phase", phase).put("role", role)
                    .put("pid", android.os.Process.myPid()).put("clock", "System.nanoTime")
                    .put("audio_track_initial_written_frames", if (role == "receiver") 0 else JSONObject.NULL)
                    .put("sample_rate", 48_000).put("channels", 1).put("sample_count", writtenSamples)
                    .put("attempted_samples", attemptedSamples.get()).put("trace_overflow_chunks", overflow.get())
                    .put("finalized", complete).put("error", error ?: invalidReason.get() ?: JSONObject.NULL)
                    .put("wav", "$phase.wav").put("csv", "$phase.csv")
                    .put("scope", "Reference input or AudioTrack accepted PCM; not acoustic output or physical A/V sync")
                    .toString(2) + "\n") }.onFailure { Log.e("BluetoothVideo", "BENCHMARK audio summary write failed: $phase", it) }
            }
        }
        Log.i("BluetoothVideo", "BENCHMARK audio trace armed: phase=$phase clock=System.nanoTime pid=${android.os.Process.myPid()}")
    }

    @Synchronized fun record(stage: String, ptsUs: Long, bytes: ByteArray, offset: Int = 0, count: Int = bytes.size,
        trackFrame: Long? = null, trackNs: Long? = null, trackUnderruns: Int? = null) {
        if (!open.get() || count <= 0) return
        require(offset >= 0 && count % 2 == 0 && offset + count <= bytes.size)
        val start = attemptedSamples.getAndAdd(count.toLong() / 2)
        if (!queue.offer(Chunk(stage, ptsUs, bytes.copyOfRange(offset, offset + count), start,
                System.nanoTime(), trackFrame, trackNs, trackUnderruns))) overflow.incrementAndGet()
    }

    @Synchronized override fun close() {
        if (!open.getAndSet(false)) return
        runCatching { worker.join(2_000) }
        if (worker.isAlive) Log.i("BluetoothVideo", "BENCHMARK audio trace finalization still pending: $phase")
    }

    fun invalidate(reason: String) {
        invalidReason.compareAndSet(null, reason)
        Log.i("BluetoothVideo", "BENCHMARK audio trace invalid: phase=$phase reason=$reason")
    }

    companion object {
        fun startIfRequested(context: Context?, session: String?, role: String): DebugAudioTrace? {
            if (context == null || session.isNullOrBlank()) return null
            val safeSession = session.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)
            require(role in setOf("sender", "receiver"))
            return DebugAudioTrace(File(context.getExternalFilesDir(null), "media-traces"), safeSession, role)
        }
    }
}
