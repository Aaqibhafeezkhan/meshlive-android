package com.example.bluetoothtest

/** The part of this phone's own media pipeline that failed. */
internal enum class LocalMediaStage(val label: String) {
    VIDEO_DECODER_SETUP("video decoder setup"),
    VIDEO_DECODER("video decoder"),
    AUDIO_DECODER_SETUP("audio decoder setup"),
    AUDIO_PIPELINE("audio decode/playback pipeline"),
}

/**
 * A failure inside this phone's own codec/playback pipeline (MediaCodec error, decoder setup,
 * the decoders' own "pipeline failed"/stall errors), as opposed to a lost Bluetooth link.
 * Reconnecting cannot repair it: on the Oppo A15s (Android 10, lane-a 2026-10-08) an Opus
 * "Audio pipeline failed" was retried as link loss ~10 times per 30 s, each retry opened a new
 * LE CoC listener, and after ~12 listens the stack refused `l2cap_listen` until Bluetooth
 * restarted.
 */
internal class LocalMediaPipelineException(
    val stage: LocalMediaStage,
    cause: Throwable,
) : RuntimeException("Local ${stage.label} failed: ${describeFailureChain(cause)}", cause)

/**
 * Runs a call into a local decoder and classifies its failure as local. Only wraps calls whose
 * input is already-received media, so a socket/link error can never be misclassified here.
 * Interruption stays interruption (shutdown), not a media failure.
 */
internal inline fun <T> inLocalMediaStage(stage: LocalMediaStage, block: () -> T): T = try {
    block()
} catch (error: InterruptedException) {
    throw error
} catch (error: LocalMediaPipelineException) {
    throw error
} catch (error: Exception) {
    throw LocalMediaPipelineException(stage, error)
}

/** "Type: message <- CauseType: message", at most [maxDepth] links. */
internal fun describeFailureChain(error: Throwable, maxDepth: Int = 3): String =
    generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }
        .take(maxDepth)
        .joinToString(" <- ") { link ->
            link.javaClass.simpleName + (link.message?.let { ": $it" } ?: "")
        }

/**
 * Stable identity of a failure: exception types and messages with numbers removed, so the same
 * fault seen with a different PSM, byte count or timestamp still matches.
 */
internal fun failureSignature(error: Throwable, maxDepth: Int = 4): String =
    generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }
        .take(maxDepth)
        .joinToString(" <- ") { link ->
            link.javaClass.name + ":" + (link.message ?: "").replace(DIGITS, "#")
        }

private val DIGITS = Regex("\\d+")

internal fun localMediaFailureNotice(error: LocalMediaPipelineException): String =
    "Playback stopped: this phone's ${error.stage.label} failed " +
        "(${error.cause?.let(::describeFailureChain) ?: "unknown error"}). " +
        "The Bluetooth link was not the cause, so the app is not reconnecting. " +
        "Choose the stream again to retry."

internal fun privateCallLocalMediaFailureMessage(error: LocalMediaPipelineException): String =
    "Call stopped: this phone's ${error.stage.label} failed " +
        "(${error.cause?.let(::describeFailureChain) ?: "unknown error"}); the Bluetooth link was not the cause"

internal enum class ReceiverStopKind {
    /** Socket/link I/O ended or failed. */
    LINK,
    /** The immediate parent withdrew the route. */
    ROUTE_WITHDRAWN,
    /** This phone's own decoder/playback failed; never retried automatically. */
    LOCAL_MEDIA,
    /** Handshake, protocol or another non-local error while receiving. */
    OTHER,
    /** The listener/advertiser could not start. */
    STARTUP,
}

internal data class ReceiverStopCause(
    val kind: ReceiverStopKind,
    val signature: String,
) {
    val retriesAutomatically: Boolean get() = kind != ReceiverStopKind.LOCAL_MEDIA

    companion object {
        fun of(kind: ReceiverStopKind, error: Throwable) =
            ReceiverStopCause(kind, "${kind.name}|${failureSignature(error)}")
    }
}

/**
 * Backs off automatic reconnects that keep ending with the same failure. The existing loss
 * policy restarts its count whenever a reconnect becomes active, so a fault that recurs a few
 * seconds after every reconnect retried at 0 ms forever. The first [repeatsBeforeBackoff] - 1
 * identical failures add nothing (real link-loss recovery is unchanged); from the
 * [repeatsBeforeBackoff]th identical failure inside [windowMs] of the previous one the minimum
 * retry delay doubles from [firstDelayMs] up to [maxDelayMs]. A different failure, or one after a
 * quiet [windowMs], starts a new count.
 */
internal class RepeatedFailureBackoff(
    private val repeatsBeforeBackoff: Int = 3,
    private val windowMs: Long = 60_000L,
    private val firstDelayMs: Long = 2_000L,
    private val maxDelayMs: Long = 30_000L,
) {
    private var signature: String? = null
    private var lastFailureAtMs = 0L

    var repeats: Int = 0
        private set

    /** Records one failure; returns the minimum delay before the next automatic retry. */
    fun record(signature: String, nowMs: Long): Long {
        if (signature == this.signature && nowMs - lastFailureAtMs <= windowMs) {
            repeats++
        } else {
            this.signature = signature
            repeats = 1
        }
        lastFailureAtMs = nowMs
        if (repeats < repeatsBeforeBackoff) return 0L
        val doublings = (repeats - repeatsBeforeBackoff).coerceAtMost(20)
        return (firstDelayMs shl doublings).coerceAtMost(maxDelayMs)
    }

    fun reset() {
        signature = null
        repeats = 0
        lastFailureAtMs = 0L
    }
}
