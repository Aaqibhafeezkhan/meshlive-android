package com.example.bluetoothtest

/**
 * Pure source-side rules of the experimental audio-only live stream (Design AL; PROTOCOL "Experimental
 * audio-only live streams"): batching with an atomic Mute, the per-child shed plan, and the child health
 * rules. The Android classes (AudioLiveEncoder, AudioLiveSource, the sender and broadcaster hooks) call
 * these; nothing here touches a radio or a codec.
 */

/**
 * Collects encoded 20 ms Opus frames into records of [framesPerRecord] units and stamps them on a
 * sample-count clock: unit n of a run is exactly 20,000 us after unit n-1, which is what the receiver's
 * contiguity rule requires. [emit] receives the inner packet flags, the first unit's timestamp and the
 * unsigned body (a single unit, a `BTA1` pair or a `BTA2` batch); the caller signs it.
 *
 * Mute (review 16) is atomic with respect to [add]: the call replaces every pending unit with silence and
 * from then on every frame the encoder delivers, including frames captured before the tap but still in the
 * encoder pipeline, is replaced by a silence frame, so nothing recorded before the tap goes out afterwards
 * and the cadence and timestamps never change. Pause and Stop [flush] a partial batch; Mute does not.
 */
class AudioLiveBatcher(
    framesPerRecord: Int,
    private val emit: (flags: Int, presentationTimeUs: Long, body: ByteArray) -> Unit,
) {
    private val framesPerRecord = framesPerRecord.coerceIn(AudioLive.MIN_BATCH_FRAMES, AudioLive.MAX_BATCH_FRAMES)
    private val pending = ArrayList<ByteArray>()
    private var pendingStartUs = 0L
    private var nextUnitUs = 0L
    private var anchored = false

    @get:Synchronized
    var muted = false
        private set

    /** Frames replaced by silence while muted (diagnostic). */
    @get:Synchronized
    var silencedFrames = 0L
        private set

    /** Starts (or, after a pause, restarts) the timeline at [presentationTimeUs]; flushes anything pending first. */
    @Synchronized
    fun anchor(presentationTimeUs: Long) {
        flushLocked()
        nextUnitUs = presentationTimeUs
        anchored = true
    }

    @Synchronized
    fun add(opusFrame: ByteArray) {
        check(anchored) { "The audio timeline has not been anchored" }
        val unit = if (muted) {
            silencedFrames++
            SILENCE_FRAME.copyOf()
        } else {
            opusFrame
        }
        if (pending.isEmpty()) pendingStartUs = nextUnitUs
        pending += unit
        nextUnitUs += AudioLive.FRAME_US
        if (pending.size >= framesPerRecord) flushLocked()
    }

    /** Gates capture: pending units become silence, later frames are silence until [unmute]. */
    @Synchronized
    fun mute() {
        if (muted) return
        muted = true
        for (index in pending.indices) {
            silencedFrames++
            pending[index] = SILENCE_FRAME.copyOf()
        }
    }

    @Synchronized
    fun unmute() {
        muted = false
    }

    /** Sends a partial batch (Pause, Stop). */
    @Synchronized
    fun flush() = flushLocked()

    private fun flushLocked() {
        if (pending.isEmpty()) return
        val units = pending.mapIndexed { index, bytes ->
            StreamProtocol.Packet(StreamProtocol.FLAG_AUDIO, pendingStartUs + index * AudioLive.FRAME_US, bytes)
        }
        pending.clear()
        when (units.size) {
            1 -> emit(StreamProtocol.FLAG_AUDIO, units[0].presentationTimeUs, units[0].payload)
            2 -> StreamProtocol.pairAudioPackets(units[0], units[1]).let { emit(it.flags, it.presentationTimeUs, it.payload) }
            else -> StreamProtocol.batchAudioPackets(units).let { emit(it.flags, it.presentationTimeUs, it.payload) }
        }
    }

    companion object {
        /** A CELT fullband 20 ms mono frame that decodes to silence (TOC 0xF8, code 0, silence flag). */
        val SILENCE_FRAME: ByteArray = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
    }
}

/**
 * What one child's queue holds, as far as shedding is concerned. [sheddable] is decided by the caller with the
 * predicate of its own namespace ([AudioLiveShed.isSourceRecordSheddable] for a source's plaintext records,
 * [AudioLiveShed.isRelayEnvelopeSheddable] for a relay's sealed envelopes); this class never looks at flags.
 */
class AudioLiveQueued(val sheddable: Boolean, val queuedAtNs: Long)

/**
 * Per-child age shedding (PROTOCOL "Age shedding"). When the oldest sheddable record is older than
 * [AudioLive.SHED_TRIGGER_MS], every sheddable record older than [AudioLive.SHED_KEEP_MS] is dropped, whole,
 * oldest first, leaving the newest 1.5 s. Only entries the caller marked sheddable are candidates: the Opus
 * configuration, lifecycle markers and everything else stay.
 */
object AudioLiveQueuePolicy {
    /** Indices (into [queue], ascending) of the records to drop; empty when nothing should be shed. */
    fun planShed(queue: List<AudioLiveQueued>, nowNs: Long, enabled: Boolean): List<Int> {
        if (!enabled) return emptyList()
        fun ageMs(entry: AudioLiveQueued) = (nowNs - entry.queuedAtNs).coerceAtLeast(0L) / 1_000_000L
        val oldest = queue.firstOrNull { it.sheddable } ?: return emptyList()
        if (ageMs(oldest) <= AudioLive.SHED_TRIGGER_MS) return emptyList()
        return queue.indices.filter { index ->
            val entry = queue[index]
            entry.sheddable && ageMs(entry) > AudioLive.SHED_KEEP_MS
        }
    }
}

/**
 * When a source or relay gives up on a child (route withdrawal, so it reroutes). Shedding keeps a child's
 * backlog between 1.5 s and 3 s, so the video thresholds would never fire (review AL-1, AL-2):
 *  - more than [AudioLive.WITHDRAW_SHED_RATE] of the records queued for it were shed over
 *    [AudioLive.SHED_RATE_WINDOW_MS] (at least [MIN_RECORDS] queued in the window), or
 *  - its oldest queued record is older than [AudioLive.WITHDRAW_AGE_MS] (a time limit, independent of the
 *    byte limit; it applies with or without shedding).
 */
class AudioLiveChildHealth {
    enum class Reason { SHED_RATE, QUEUE_AGE }

    private class Sample(val atMs: Long, val queued: Int, val shed: Int)

    private val samples = ArrayDeque<Sample>()

    // Running totals of the samples in the window: a verdict is judged for every record sent, so it must not
    // add the whole window up each time (500 samples at 50 records/s over 10 s). Each sample is added once and
    // subtracted once, which makes every call amortised O(1).
    private var queuedInWindow = 0L
    private var shedInWindow = 0L

    @Synchronized
    fun onQueued(nowMs: Long) {
        samples += Sample(nowMs, 1, 0)
        queuedInWindow += 1
        prune(nowMs)
    }

    @Synchronized
    fun onShed(count: Int, nowMs: Long) {
        if (count <= 0) return
        samples += Sample(nowMs, 0, count)
        shedInWindow += count
        prune(nowMs)
    }

    @Synchronized
    fun verdict(oldestQueuedAgeMs: Long, nowMs: Long): Reason? {
        prune(nowMs)
        if (oldestQueuedAgeMs > AudioLive.WITHDRAW_AGE_MS) return Reason.QUEUE_AGE
        val queued = queuedInWindow
        val shed = shedInWindow
        if (queued >= MIN_RECORDS && shed > queued * AudioLive.WITHDRAW_SHED_RATE) return Reason.SHED_RATE
        return null
    }

    private fun prune(nowMs: Long) {
        while (samples.isNotEmpty() && nowMs - samples.first().atMs > AudioLive.SHED_RATE_WINDOW_MS) {
            val old = samples.removeFirst()
            queuedInWindow -= old.queued
            shedInWindow -= old.shed
        }
    }

    companion object {
        const val MIN_RECORDS = 10
    }
}

/**
 * The microphone must be held by a service of the microphone type for a background `AudioRecord` to
 * deliver audio (Android 14 silences it otherwise). If the service ended up holding only
 * `connectedDevice` (the existing refusal fallback), a "live" broadcast would silently deliver silence once
 * the app leaves the screen, which is not allowed (review AL-3): the broadcast stops with a visible error.
 */
object AudioLiveMicrophonePolicy {
    const val ERROR_TEXT = "Audio broadcast stopped: Android did not allow background microphone use. " +
        "Open the app and start again."

    /** [holdsMicrophoneType] is whether the foreground service currently holds the microphone type. */
    fun mustStop(holdsMicrophoneType: Boolean): Boolean = !holdsMicrophoneType
}

/** What an audio-only broadcast gives each child sender and the broadcaster. */
class AudioLiveSendConfig(
    /** Signs every record once; its stream ID is the mesh stream ID of every child connection. */
    val signer: AudioLiveSigner,
    /** `test_audio_live_age_bound`: false is the control arm (no shedding; the 30 s withdrawal still applies). */
    val ageBound: Boolean = true,
    /** `test_audio_live_max_children`. */
    val maxChildren: Int = AudioLive.DEFAULT_MAX_CHILDREN,
    /** Frames per record, for admission planning. */
    val framesPerRecord: Int = AudioLive.DEFAULT_BATCH_FRAMES,
) {
    val preset: Int get() = signer.preset
    val kbps: Int get() = AudioLive.presetKbps(signer.preset)

    companion object {
        /**
         * The sender and broadcaster take a video codec argument for their video path. An audio-only stream
         * passes this inert value (capability mask 0) so a phone without a video encoder can still broadcast.
         */
        val PLACEHOLDER_CODEC = VideoCodec(
            displayName = "Audio only",
            mimeType = "audio/opus",
            protocolId = 0,
            encoderName = "none",
            hardwareAccelerated = false,
            usesSurfaceInput = false,
            bitrateMode = null,
            supportedBitrateModes = emptySet(),
            complexity = null,
        )
    }
}

/** The input meter: 0..1 level of one PCM16 little-endian frame. */
object AudioLiveLevel {
    /** RMS against about -24 dBFS full scale, so ordinary speech moves the meter across most of its range. */
    fun rms(pcm: ByteArray): Float {
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < pcm.size) {
            val sample = ((pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 0xff)).toShort().toInt()
            sum += sample.toDouble() * sample
            count++
            index += 2
        }
        if (count == 0) return 0f
        return (kotlin.math.sqrt(sum / count) / 4_000.0).coerceIn(0.0, 1.0).toFloat()
    }
}

/** What the source hands signed records to: the broadcaster in the app, a fake in tests. */
interface AudioLiveSink {
    fun sendAudioLiveRecord(record: AudioLiveSignedRecord)

    /** Mute: drops every unsent audio record queued for any listener; returns how many. */
    fun discardQueuedAudio(): Int
}

/** The microphone and Opus encoder as the source sees them; the app's [AudioLiveEncoder] implements it. */
interface AudioLiveEncoderPort : java.io.Closeable {
    val encoderName: String
    fun start()
}

/**
 * One listener's outgoing queue as the broadcaster's sender uses it (Design AL): the sender's own
 * [queue] (its writer thread takes from it), the age shedding of stale whole records and the health verdict.
 * Extracted from the sender so the concurrency of the producer (encoder thread), the writer thread and the
 * heartbeat/ack threads is tested without a radio.
 *
 * Every read of [queue] other than the writer's `take` goes through [snapshot]. Kotlin's `Collection.toList()`
 * reads `size` and then, for a size of one, calls `iterator().next()`: with a writer taking the last element
 * in between, that throws a bare `NoSuchElementException`. On the ASUS that ended every UI-started audio
 * broadcast within about a second (the queue holds one record most of the time on a healthy link).
 */
internal class AudioLiveSendQueue<T : Any>(
    val queue: java.util.concurrent.LinkedBlockingQueue<T>,
    private val sheddable: (T) -> Boolean,
    private val queuedAtNs: (T) -> Long,
    private val sizeOf: (T) -> Int,
    private val ageBound: Boolean,
) {
    val health = AudioLiveChildHealth()
    private val shedTotalCounter = java.util.concurrent.atomic.AtomicLong()

    class Outcome(
        /** Records dropped by this call (whole records, oldest first). */
        val removed: Int,
        val removedBytes: Long,
        /** All records shed on this queue so far. */
        val shedTotal: Long,
        /** Non-null: this listener should be withdrawn (rerouted). */
        val verdict: AudioLiveChildHealth.Reason?,
        val oldestAgeMs: Long,
    )

    /** Call after [item] was put on [queue]: sheds stale records and judges the listener. Thread-safe. */
    fun afterEnqueue(item: T, nowNs: Long): Outcome {
        val nowMs = nowNs / 1_000_000L
        if (sheddable(item)) health.onQueued(nowMs)
        val seen = snapshot(queue)
        val plan = AudioLiveQueuePolicy.planShed(seen.map { AudioLiveQueued(sheddable(it), queuedAtNs(it)) }, nowNs, ageBound)
        var removed = 0
        var removedBytes = 0L
        for (index in plan) {
            val victim = seen[index]
            // The writer may have taken it already: then it was sent, not shed.
            if (queue.remove(victim)) {
                removed++
                removedBytes += sizeOf(victim)
            }
        }
        if (removed > 0) health.onShed(removed, nowMs)
        val total = if (removed > 0) shedTotalCounter.addAndGet(removed.toLong()) else shedTotalCounter.get()
        val oldest = oldestAgeMs(nowNs)
        return Outcome(removed, removedBytes, total, health.verdict(oldest, nowMs), oldest)
    }

    fun oldestAgeMs(nowNs: Long): Long =
        queue.peek()?.let { ((nowNs - queuedAtNs(it)).coerceAtLeast(0L) / 1_000_000L) } ?: 0L

    /** Mute: removes every queued sheddable record and returns it. */
    fun discardSheddable(): List<T> {
        val dropped = ArrayList<T>()
        for (queued in snapshot(queue)) {
            if (sheddable(queued) && queue.remove(queued)) dropped += queued
        }
        return dropped
    }

    companion object {
        /** A consistent copy of a queue another thread is draining (`ArrayList(Collection)` uses the queue's atomic `toArray`). */
        fun <E> snapshot(source: Collection<E>): List<E> = ArrayList(source)
    }
}

/**
 * Failure reporting for the audio-only stream: every failure is logged with its stack, plus one BENCHMARK line
 * with the exception class and the top frame, so a bare message ("NoSuchElementException") is never all there is.
 */
object AudioLiveLog {
    /** (level, line, error); the default writes to logcat. A test replaces it. */
    @Volatile var emit: (Int, String, Throwable?) -> Unit = { level, line, error ->
        // android.util.Log is not available in a plain JVM test; logging must never change behaviour.
        try {
            when (level) {
                ERROR -> android.util.Log.e(TAG, line, error)
                else -> android.util.Log.i(TAG, line)
            }
        } catch (_: RuntimeException) {
        }
    }

    const val TAG = "BluetoothVideo"
    const val INFO = 4
    const val ERROR = 6

    fun info(line: String) = emit(INFO, line, null)

    /** The exception class and the first stack frame of [error], for the BENCHMARK line. */
    fun describe(error: Throwable): Pair<String, String> {
        val frame = error.stackTrace.firstOrNull()
        return error.javaClass.name to
            (frame?.let { "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" } ?: "none")
    }

    fun benchmarkLine(where: String, error: Throwable?, message: String?): String {
        val (type, frame) = error?.let(::describe) ?: ("none" to "none")
        return "BENCHMARK audio_live_failure: where=$where | class=$type | topFrame=${frame.replace('|', '/')} | " +
            "message=${(error?.message ?: message ?: "").take(120).replace('|', '/')}"
    }

    /** Logs [error] with its stack and the BENCHMARK line. [where] names the thread or stage. */
    fun failure(where: String, error: Throwable?, message: String? = null) {
        emit(ERROR, "audio live failure in $where: ${error?.message ?: message ?: error?.javaClass?.simpleName}", error)
        emit(INFO, benchmarkLine(where, error, message), null)
    }
}

/**
 * Bit-identical delivery check: one BENCHMARK line per record with a short SHA-256 of every Opus unit. The
 * source logs `role=tx` when it signs, a listener `role=rx` after verification; lines join on `seq` and the
 * unit hashes must be equal wherever nothing was shed. Off unless `test_audio_live_digests=true` (DEBUG).
 */
object AudioLiveDigest {
    fun unitHash(unit: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(unit).take(4).joinToString("") { "%02x".format(it) }

    fun line(role: String, label: String, seq: Long, firstPtsUs: Long, units: List<ByteArray>): String =
        "BENCHMARK audio_live_units: role=$role | stream=$label | seq=$seq | pts=$firstPtsUs | n=${units.size} | " +
            "bytes=${units.sumOf { it.size }} | h=${units.joinToString(",") { unitHash(it) }}"

    /** The units of an unsigned record body, as the receiver splits them. */
    fun unitsOf(flags: Int, ptsUs: Long, body: ByteArray): List<ByteArray> =
        if (flags and StreamProtocol.FLAG_AUDIO_PAIR != 0) {
            StreamProtocol.splitAudioPair(StreamProtocol.Packet(flags, ptsUs, body)).map { it.payload }
        } else {
            listOf(body)
        }
}

/** The codec a stage chose, and what was asked for: one BENCHMARK line per encoder and decoder start. */
object AudioLiveCodecChoice {
    fun line(role: String, impl: String, requested: String, name: String, reason: String? = null): String =
        "BENCHMARK audio_live_codec_choice: role=$role | impl=$impl | requested=$requested | name=$name" +
            (reason?.let { " | reason=${it.replace('|', '/')}" } ?: "")
}

/** `test_audio_live_autostart=true` (DEBUG): start a radio without taps. Preset names: voice, lowdata, music. */
object AudioLiveAutoStart {
    class Request(val preset: Int, val title: String?)

    /** Null when the extra is absent or false. An unknown preset name falls back to Voice. */
    fun parse(boolExtra: (String, Boolean) -> Boolean, stringExtra: (String) -> String?): Request? {
        if (!boolExtra(EXTRA_AUTOSTART, false)) return null
        val preset = when (stringExtra(EXTRA_PRESET)?.trim()?.lowercase()) {
            "lowdata", "low", "low_data", "16" -> AudioLive.PRESET_LOW_DATA
            "music", "48" -> AudioLive.PRESET_MUSIC
            else -> AudioLive.PRESET_VOICE
        }
        return Request(preset, stringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() })
    }

    const val EXTRA_AUTOSTART = "test_audio_live_autostart"
    const val EXTRA_PRESET = "test_audio_live_preset"
    const val EXTRA_TITLE = "test_audio_title"
}
