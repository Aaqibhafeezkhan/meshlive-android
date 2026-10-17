package com.example.bluetoothtest

/**
 * Temporal layering is requested from a MediaCodec encoder through KEY_TEMPORAL_LAYERING, and an encoder may reject
 * it (configure error) or the init-fallback chain may drop the optional key. Requested vs effective must never be
 * silent: every encoder start logs one `BENCHMARK temporal_layering` line, and a DEBUG benchmark launch that asked for
 * layers without getting them is refused (BenchmarkSetupRefusal, `temporal_layering_unsupported`) because the case
 * would otherwise measure a single layer (AGENTS: no silent change of a requested setting). User sessions stay best
 * effort (single layer), now with the line.
 */
internal object TemporalLayeringReport {
    enum class Action { NOT_REQUESTED, APPLIED, SINGLE_LAYER_BEST_EFFORT, REFUSE }

    /** [requestedSchema] / [effectiveSchema] are null when not requested / not taken. */
    fun action(requestedSchema: String?, effectiveSchema: String?, benchmarkLaunch: Boolean): Action = when {
        requestedSchema == null -> Action.NOT_REQUESTED
        effectiveSchema != null -> Action.APPLIED
        benchmarkLaunch -> Action.REFUSE
        else -> Action.SINGLE_LAYER_BEST_EFFORT
    }

    fun line(requestedSchema: String?, effectiveSchema: String?, encoder: String, reason: String): String =
        "BENCHMARK temporal_layering: requested=${requestedSchema ?: "none"} | effective=${effectiveSchema ?: "none"} | " +
            "encoder=$encoder | reason=${clean(reason)}"

    /** Why the init-fallback chain's final encoder does or does not carry the key. */
    fun initFallbackReason(requested: Boolean, dropped: Boolean, firstFailure: String?): String = when {
        !requested -> "not_requested"
        dropped -> "init_fallback_dropped_key" + (firstFailure?.let { ": $it" } ?: "")
        else -> "configure_accepted"
    }

    /** One line, no field separator, bounded: log text only. */
    fun clean(text: String): String = text.replace('|', '/').replace(Regex("\\s+"), " ").trim().take(160)
}

/** Thrown by the encoder when a DEBUG benchmark launch asked for temporal layers and the encoder did not take them. */
internal class TemporalLayeringRefused(message: String) : IllegalStateException(message)
