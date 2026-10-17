package com.example.bluetoothtest

import android.content.Context
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File

/**
 * Messaging v2 phase 1c: the hold-to-record voice note of the thread composer. The same recorder settings as the v1 Messages
 * screen (AAC in MP4, mono, 16 kHz, 24 kbps, at most two minutes), so a voice note is about 22 KB per 60 s and always an
 * inline attachment. [pressDecision] is the pure part of the gesture, covered by JVM tests.
 */
class VoiceNoteRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedMs = 0L

    val recording: Boolean get() = recorder != null

    /** Milliseconds recorded so far. */
    fun elapsedMs(): Long = if (recorder == null) 0L else SystemClock.elapsedRealtime() - startedMs

    /** Starts recording; false when the microphone could not be opened. [onMaxDuration] runs when the limit ended it. */
    fun start(onMaxDuration: () -> Unit): Boolean {
        if (recorder != null) return false
        val target = File(context.cacheDir, StoreForwardPlaintextFiles.RECORDING).also { it.delete() }
        val started = runCatching {
            @Suppress("DEPRECATION")
            MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(24_000)
                setMaxDuration(VoiceNoteGesture.MAX_MS.toInt())
                setOutputFile(target.absolutePath)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxDuration()
                }
                prepare()
                start()
            }
        }
        val active = started.getOrNull() ?: return false
        recorder = active
        file = target
        startedMs = SystemClock.elapsedRealtime()
        return true
    }

    /** The recording and how long it ran, or null when it was cancelled, failed or is empty. The file is wiped either way. */
    fun finish(keep: Boolean): Pair<ByteArray, Long>? {
        val active = recorder ?: return null
        recorder = null
        val duration = SystemClock.elapsedRealtime() - startedMs
        val ok = runCatching { active.stop() }.isSuccess
        runCatching { active.release() }
        val target = file
        file = null
        val bytes = if (keep && ok && target != null && target.exists()) runCatching { target.readBytes() }.getOrNull() else null
        target?.let { AttachmentPreparer.wipeFile(it) }
        return bytes?.takeIf { it.isNotEmpty() }?.let { it to duration }
    }
}

/** What a press on the microphone button means, from how long and how far it went. */
object VoiceNoteGesture {
    const val MAX_MS = 120_000L
    const val MIN_MS = 800L

    /** Sliding the finger this far (dp) to the left cancels the note. */
    const val CANCEL_SLIDE_DP = 96f

    enum class Decision { SEND, CANCELLED, TOO_SHORT }

    fun pressDecision(durationMs: Long, slideLeftDp: Float): Decision = when {
        slideLeftDp >= CANCEL_SLIDE_DP -> Decision.CANCELLED
        durationMs < MIN_MS -> Decision.TOO_SHORT
        else -> Decision.SEND
    }

    /** "0:07" for the recording line. */
    fun clock(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
