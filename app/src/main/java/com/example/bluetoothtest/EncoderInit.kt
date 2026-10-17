package com.example.bluetoothtest

import java.util.concurrent.atomic.AtomicReference

/**
 * Optional MediaFormat keys the encoder may drop when initialisation fails. The declaration order is
 * the drop order: newest first (WebRTC/Media3/Moonlight keep a ladder where the first configurable
 * format wins and keys are added least- to most-risky and removed in reverse). Required keys (colour
 * format, bitrate, frame rate, key-frame interval, bitrate mode, priority) are never dropped.
 */
internal enum class EncoderOptionalKey(val label: String) {
    LATENCY("latency"),
    COLOUR("colour"),
    VENDOR_QP("vendor_qp"),
    INTRA_REFRESH("intra_refresh"),
    QP_BOUNDS("qp_bounds"),
    STATISTICS("statistics"),
    COMPLEXITY("complexity"),
    TEMPORAL_LAYERING("temporal_layering"),
}

internal enum class EncoderInitFailure {
    /** MediaCodec.CodecException ERROR_INSUFFICIENT_RESOURCE / ERROR_RECLAIMED or an out-of-memory start (0xfffffff4). */
    RESOURCE,

    /** configure/start did not return in time. */
    TIMEOUT,
    OTHER;

    companion object {
        const val ERROR_INSUFFICIENT_RESOURCE = 1100
        const val ERROR_RECLAIMED = 1101

        /** [errorCode] is CodecException.getErrorCode() when the error is one, else null. */
        fun classify(error: Throwable, errorCode: Int? = null): EncoderInitFailure {
            if (error is BoundedCall.TimedOut) return TIMEOUT
            if (errorCode == ERROR_INSUFFICIENT_RESOURCE || errorCode == ERROR_RECLAIMED) return RESOURCE
            val text = generateSequence(error) { it.cause }.take(4).joinToString(" ") { it.message.orEmpty() }.lowercase()
            return if ("0xfffffff4" in text || "no_memory" in text || "insufficient resource" in text) RESOURCE else OTHER
        }
    }
}

internal data class EncoderInitAttempt(
    val encoder: String,
    val dropped: Set<EncoderOptionalKey>,
    /** KEY_PRIORITY 1 instead of 0 (only after resource errors). */
    val lowPriority: Boolean,
    val reason: String,
) {
    fun describe(): String = "encoder=$encoder dropped=${dropped.joinToString("+") { it.label }.ifEmpty { "none" }} " +
        "priority=${if (lowPriority) 1 else 0} reason=$reason"
}

internal data class EncoderInitRecord(val attempt: EncoderInitAttempt, val error: String?, val failure: EncoderInitFailure?, val ms: Long)

internal class EncoderInitOutcome(val effective: EncoderInitAttempt, val records: List<EncoderInitRecord>, val totalMs: Long)

/** Thrown when every attempt failed; the first failure is the cause, later ones are suppressed. */
internal class EncoderInitExhausted(message: String, cause: Throwable, val records: List<EncoderInitRecord>) :
    RuntimeException(message, cause)

/**
 * Encoder initialisation fallback chain (V-09). The caller supplies [perform], which creates,
 * configures, takes the input surface of and starts one encoder for one attempt and throws when any
 * stage fails (it releases its own failed instance). Order:
 *
 *  1. the requested encoder with the full format, then with optional keys dropped newest first
 *     (each attempt drops one more key than the one before);
 *  2. a resource error (insufficient resource, no memory) retries the same attempt once after a short
 *     pause, then once with KEY_PRIORITY 1; a resource error that persists, like a timeout, skips straight
 *     to the next encoder (dropping keys neither frees a resource nor un-hangs a component);
 *  3. every other candidate encoder for the same codec and input mode: the full format, then the
 *     format with every optional key dropped.
 *
 * Another codec is never tried here: it is protocol-visible and belongs to the caller. At most
 * [MAX_ATTEMPTS] attempts run.
 */
internal class EncoderInitChain(
    private val candidates: List<String>,
    private val applied: List<EncoderOptionalKey>,
    private val sleep: (Long) -> Unit = { ms -> Thread.sleep(ms) },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init {
        require(candidates.isNotEmpty()) { "an encoder candidate is required" }
    }

    fun run(perform: (EncoderInitAttempt) -> Unit, errorCodeOf: (Throwable) -> Int? = { null }): EncoderInitOutcome {
        val records = mutableListOf<EncoderInitRecord>()
        val startedMs = nowMs()
        var firstError: Throwable? = null
        val ordered = applied.sortedBy { it.ordinal }

        fun tryAttempt(attempt: EncoderInitAttempt): EncoderInitFailure? {
            if (records.size >= MAX_ATTEMPTS) return EncoderInitFailure.OTHER
            val begin = nowMs()
            return try {
                perform(attempt)
                records += EncoderInitRecord(attempt, null, null, nowMs() - begin)
                null
            } catch (error: Throwable) {
                if (error is VirtualMachineError) throw error
                if (firstError == null) firstError = error else firstError!!.addSuppressed(error)
                val failure = EncoderInitFailure.classify(error, errorCodeOf(error))
                records += EncoderInitRecord(
                    attempt, "${error.javaClass.simpleName}: ${error.message.orEmpty().take(120)}", failure, nowMs() - begin,
                )
                failure
            }
        }

        fun success(): EncoderInitOutcome = EncoderInitOutcome(records.last().attempt, records.toList(), nowMs() - startedMs)

        for ((index, encoder) in candidates.withIndex()) {
            val keySets: List<Set<EncoderOptionalKey>> = if (index == 0) {
                (0..ordered.size).map { drop -> ordered.take(drop).toSet() }
            } else {
                listOf(emptySet(), ordered.toSet()).distinct()
            }
            for ((step, dropped) in keySets.withIndex()) {
                val reason = when {
                    index > 0 -> "next_encoder"
                    step == 0 -> "requested"
                    else -> "drop_${dropped.maxByOrNull { it.ordinal }?.label ?: "keys"}"
                }
                var failure = tryAttempt(EncoderInitAttempt(encoder, dropped, false, reason))
                if (failure == null) return success()
                if (failure == EncoderInitFailure.RESOURCE) {
                    sleep(RESOURCE_PAUSE_MS)
                    failure = tryAttempt(EncoderInitAttempt(encoder, dropped, false, "retry_after_resource_error"))
                    if (failure == null) return success()
                    if (failure == EncoderInitFailure.RESOURCE) {
                        sleep(RESOURCE_PAUSE_MS)
                        failure = tryAttempt(EncoderInitAttempt(encoder, dropped, true, "retry_priority_1"))
                        if (failure == null) return success()
                    }
                }
                // Dropping keys does not free a resource or un-hang a component: go to the next encoder.
                if (failure == EncoderInitFailure.TIMEOUT || failure == EncoderInitFailure.RESOURCE) break
            }
        }
        throw EncoderInitExhausted(
            "Encoder could not be started after ${records.size} attempt(s): ${records.lastOrNull()?.error ?: "no attempt"}",
            checkNotNull(firstError ?: IllegalStateException("no encoder attempt ran")),
            records.toList(),
        )
    }

    companion object {
        const val MAX_ATTEMPTS = 12
        const val RESOURCE_PAUSE_MS = 250L

        /** `BENCHMARK encoder_init_fallback`: requested vs effective, every attempt (hand-built JSON: no Android types). */
        fun benchmarkLine(
            requestedEncoder: String,
            requestedKeys: List<EncoderOptionalKey>,
            outcome: EncoderInitOutcome?,
            exhausted: EncoderInitExhausted?,
        ): String {
            val records = outcome?.records ?: exhausted?.records.orEmpty()
            val fields = mutableListOf(
                "\"requested_encoder\":${quote(requestedEncoder)}",
                "\"requested_optional_keys\":${quoteAll(requestedKeys.map { it.label })}",
                "\"result\":${quote(if (outcome != null) "started" else "failed")}",
            )
            if (outcome != null) {
                val effective = outcome.effective
                fields += "\"effective_encoder\":${quote(effective.encoder)}"
                fields += "\"dropped_keys\":${quoteAll(effective.dropped.sortedBy { it.ordinal }.map { it.label })}"
                fields += "\"priority\":${if (effective.lowPriority) 1 else 0}"
                fields += "\"total_ms\":${outcome.totalMs}"
                fields += "\"fell_back\":${effective.encoder != requestedEncoder || effective.dropped.isNotEmpty() || effective.lowPriority}"
            }
            val attempts = records.joinToString(",", "[", "]") { record ->
                "{\"encoder\":${quote(record.attempt.encoder)}," +
                    "\"dropped\":${quoteAll(record.attempt.dropped.sortedBy { it.ordinal }.map { it.label })}," +
                    "\"priority\":${if (record.attempt.lowPriority) 1 else 0}," +
                    "\"reason\":${quote(record.attempt.reason)}," +
                    "\"ok\":${record.error == null}," +
                    "\"failure\":${record.failure?.let { quote(it.name) } ?: "null"}," +
                    "\"error\":${record.error?.let(::quote) ?: "null"}," +
                    "\"ms\":${record.ms}}"
            }
            fields += "\"attempts\":$attempts"
            return "BENCHMARK encoder_init_fallback v1 " + fields.joinToString(",", "{", "}")
        }

        private fun quote(text: String): String = buildString {
            append('"')
            for (ch in text) {
                when {
                    ch == '"' -> append("\\\"")
                    ch == '\\' -> append("\\\\")
                    ch < ' ' -> append(' ')
                    else -> append(ch)
                }
            }
            append('"')
        }

        private fun quoteAll(items: List<String>): String = items.joinToString(",", "[", "]") { quote(it) }
    }
}

/**
 * Runs [block] on a throwaway daemon thread and waits at most [timeoutMs]: MediaCodec configure/start
 * and release can hang in a vendor component (WebRTC runs release() with a 5 s timeout; Chromium
 * treats a first configure over 800 ms as suspicious). A timed-out call is abandoned (the thread is
 * a daemon), the caller carries on.
 */
internal object BoundedCall {
    class TimedOut(val name: String, val timeoutMs: Long) : RuntimeException("$name did not return within $timeoutMs ms")

    fun <T> run(name: String, timeoutMs: Long, block: () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val thread = Thread({ result.set(runCatching(block)) }, "bounded-$name").apply { isDaemon = true }
        thread.start()
        thread.join(timeoutMs)
        val outcome = result.get() ?: throw TimedOut(name, timeoutMs)
        return outcome.getOrThrow()
    }

    /** Like [run] but a timeout or failure is only reported: true when [block] returned in time. */
    fun runQuietly(name: String, timeoutMs: Long, block: () -> Unit, onProblem: (String) -> Unit = {}): Boolean = try {
        run(name, timeoutMs, block)
        true
    } catch (timeout: TimedOut) {
        onProblem("$name timed out after $timeoutMs ms")
        false
    } catch (error: Throwable) {
        if (error is VirtualMachineError) throw error
        onProblem("$name failed: ${error.javaClass.simpleName}")
        false
    }
}

/**
 * The shutdown order of a camera encoder (V-15). A Surface-input encoder must never be stopped while
 * the camera still feeds its Surface (the BufferQueue runs dry and stalls the camera pipeline, scrcpy
 * and CameraX X-4), and a codec Surface that left the session before `stop()` crashed the media
 * server on one Moto (X-5): the sequence is "camera stops feeding, then the encoder", never the other
 * way. `CameraDevice.close()` returns after the HAL has disconnected, so the codec steps that follow
 * run against a quiet Surface. Every step runs even if an earlier one failed; a step with a timeout
 * is bounded (an encoder `release()` that hangs does not hang the caller).
 */
internal class EncoderShutdown(private val log: (String) -> Unit = {}) {
    enum class Group { CAMERA, CODEC }

    class Step(val name: String, val group: Group, val timeoutMs: Long?, val action: () -> Unit)

    class Result(val name: String, val ok: Boolean, val timedOut: Boolean)

    fun run(steps: List<Step>): List<Result> {
        var codecStarted = false
        for (step in steps) {
            if (step.group == Group.CODEC) {
                codecStarted = true
            } else {
                require(!codecStarted) { "camera step ${step.name} after a codec step: the camera must stop feeding first" }
            }
        }
        return steps.map { step ->
            var timedOut = false
            val ok = try {
                if (step.timeoutMs != null) {
                    BoundedCall.run(step.name, step.timeoutMs, step.action)
                } else {
                    step.action()
                }
                true
            } catch (timeout: BoundedCall.TimedOut) {
                timedOut = true
                log("BENCHMARK codec_release_timeout: step=${step.name} | timeoutMs=${step.timeoutMs}")
                false
            } catch (error: Throwable) {
                if (error is VirtualMachineError) throw error
                false
            }
            Result(step.name, ok, timedOut)
        }
    }

    companion object {
        const val CODEC_STEP_TIMEOUT_MS = 5_000L

        fun steps(
            stopRepeating: () -> Unit,
            closeSession: () -> Unit,
            closeCamera: () -> Unit,
            stopCodec: () -> Unit,
            releaseCodec: () -> Unit,
        ): List<Step> = listOf(
            Step("stopRepeating", Group.CAMERA, null, stopRepeating),
            Step("closeSession", Group.CAMERA, null, closeSession),
            Step("closeCamera", Group.CAMERA, null, closeCamera),
            Step("stopCodec", Group.CODEC, CODEC_STEP_TIMEOUT_MS, stopCodec),
            Step("releaseCodec", Group.CODEC, CODEC_STEP_TIMEOUT_MS, releaseCodec),
        )
    }
}

/**
 * Vendor QP range parameters (V-12). OMX.Exynos.VP9 registers vendor.sec-ext-enc-qp-range.{I,P,B}-{min,max}QP
 * (LineageOS slsi OpenMAX Exynos_OMX_Vp9enc.c). Only parameters the component lists are returned.
 */
internal object ExynosQpRange {
    private const val FAMILY = "sec-ext-enc-qp-range"

    fun parameters(listedNames: Collection<String>, range: IntRange): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        for (name in listedNames.sorted()) {
            val bare = name.substringBefore(':')
            val lower = bare.lowercase()
            if (FAMILY !in lower) continue
            when {
                lower.endsWith("minqp") -> result[bare] = range.first
                lower.endsWith("maxqp") -> result[bare] = range.last
            }
        }
        return result
    }

    /** True for the component prefixes the rule is scoped to (Exynos only; OMX.Exynos. exactly as the adjuster scope). */
    fun scopedComponent(name: String): Boolean = name.startsWith("OMX.Exynos.") || name.startsWith("c2.exynos.")
}

/** KEY_LATENCY is not set for an H.264 Baseline path (crbug 409110228) and needs API 30. */
internal fun encoderKeyLatencyAllowed(mime: String, sdkInt: Int, componentName: String): Boolean =
    sdkInt >= 30 && !mime.equals("video/avc", ignoreCase = true) && componentName.startsWith("c2.")
