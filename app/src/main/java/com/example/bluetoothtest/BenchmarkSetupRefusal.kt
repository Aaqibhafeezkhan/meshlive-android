package com.example.bluetoothtest

/**
 * DEBUG benchmark launches that ask for something this phone cannot do (an encoder or bitrate allocation it does not
 * offer, a raw fixture whose geometry the encoder does not run at) are refused visibly: one
 * `BENCHMARK setup_refused: reason=... | key=value ...` line and a clean finish, never a crash and never a
 * substitution (AGENTS: no silent change of a requested encoder, mode, geometry or rate).
 */
internal object BenchmarkSetupRefusal {
    fun line(reason: String, vararg details: Pair<String, Any?>): String =
        "BENCHMARK setup_refused: reason=$reason" + details.joinToString("") { " | ${it.first}=${it.second}" }

    /** The raw fixture's declared size is not the size the encoder runs at; nothing is rescaled or reinterpreted. */
    fun rawFixtureGeometryLine(
        requestedWidth: Int, requestedHeight: Int, effectiveWidth: Int, effectiveHeight: Int, detail: String,
    ): String = line(
        "raw_fixture_geometry",
        "requested" to "${requestedWidth}x$requestedHeight",
        "effective" to "${effectiveWidth}x$effectiveHeight",
        "detail" to detail,
    )

    /** The selected encoder cannot encode the raw fixture's declared size; nothing else is substituted. */
    fun rawEncoderSizeLine(encoderName: String, declaredWidth: Int, declaredHeight: Int): String = line(
        "raw_fixture_encoder_size",
        "requested" to "${declaredWidth}x$declaredHeight",
        "encoder" to encoderName,
        "detail" to "encoder does not support the declared size; no substitution",
    )

    /** The selected encoder cannot encode the raw fixture's declared size at the requested rate; nothing is substituted. */
    fun rawEncoderRateLine(encoderName: String, declaredWidth: Int, declaredHeight: Int, fps: Int): String = line(
        "raw_fixture_encoder_rate",
        "requested" to "${declaredWidth}x$declaredHeight@$fps",
        "encoder" to encoderName,
        "detail" to "encoder does not support the declared size at this frame rate; no substitution",
    )

    /** How long a DEBUG launch may sit with its auto-start pending before it must say why (setup_stalled). */
    const val STALL_WATCHDOG_MS = 5_000L

    /** The watchdog fires only for a launch that is still waiting to start, was not already refused, and is alive. */
    fun stallWatchdogShouldFire(autoStartPending: Boolean, alreadyRefused: Boolean, activityGone: Boolean): Boolean =
        autoStartPending && !alreadyRefused && !activityGone

    /**
     * What a stalled launch does. A gate that is false for a local reason (mode, codec, Bluetooth, permissions,
     * thermal, busy controls) will not fix itself: REFUSE. A launch whose only missing term is a peer (a sender's
     * destination, a relay's children) is legitimately waiting for the other phone to be discovered: WAIT, and say so.
     */
    enum class StallVerdict { REFUSE, WAIT }

    fun stallVerdict(localGatesOk: Boolean, peersReady: Boolean): StallVerdict =
        if (!localGatesOk) StallVerdict.REFUSE else if (!peersReady) StallVerdict.WAIT else StallVerdict.REFUSE

    /** The start gate's inputs at the moment the watchdog fired: each is one term of canStart. */
    fun setupStalledLine(verdict: StallVerdict, gates: List<Pair<String, Any?>>): String =
        "BENCHMARK " + (if (verdict == StallVerdict.REFUSE) "setup_stalled" else "setup_waiting") + ": " +
            gates.joinToString(" | ") { "${it.first}=${it.second}" } +
            if (verdict == StallVerdict.REFUSE) " | detail=auto-start never became startable; no stream was started"
            else " | detail=only the peer is missing; still waiting"

    /** A benchmark launch asked for temporal layers the encoder did not take; a single layer is not substituted. */
    fun temporalLayeringRefusalLine(requestedSchema: String, encoderName: String, detail: String): String = line(
        "temporal_layering_unsupported",
        "requested" to requestedSchema,
        "effective" to "none",
        "encoder" to encoderName,
        "detail" to TemporalLayeringReport.clean(detail),
    )

    /**
     * Whether `test_codec` names this encoder: the family names av1 and vp9 (Design HC adds hevc and h264/avc, present
     * only while their masters are on), else an exact encoder name.
     */
    fun codecMatches(
        requested: String,
        isAv1: Boolean,
        isVp9: Boolean,
        encoderName: String,
        isHevc: Boolean = false,
        isH264: Boolean = false,
    ): Boolean =
        when (requested.lowercase()) {
            "av1" -> isAv1
            // Design VX: the bundled libvpx encoder is named explicitly (`libvpx-vp9` or `libvpx`), so an existing
            // `test_codec=vp9` case keeps meaning a MediaCodec VP9 encoder (refused where the phone has none).
            "vp9" -> isVp9 && !encoderName.equals(VideoCodec.LIBVPX_ENCODER_NAME, ignoreCase = true)
            "libvpx" -> encoderName.equals(VideoCodec.LIBVPX_ENCODER_NAME, ignoreCase = true)
            "hevc", "h265" -> isHevc
            "h264", "avc" -> isH264
            else -> encoderName.equals(requested, ignoreCase = true)
        }

    fun codecRefusalLine(requested: String): String =
        line("encoder_unavailable", "requested" to requested)

    /** The allocation named by `test_rate_control`, or null when the name is unknown. */
    fun rateControlFor(requested: String): EncoderRateControl? = when (requested.lowercase()) {
        "automatic" -> EncoderRateControl.AUTOMATIC
        "cbr" -> EncoderRateControl.CBR
        "vbr" -> EncoderRateControl.VBR
        "cbr_fd", "cbr-fd" -> EncoderRateControl.CBR_FD
        else -> null
    }

    /** The refusal line for a bitrate allocation request, or null when the encoder offers it. */
    fun rateControlRefusalLine(
        requested: String,
        encoderName: String?,
        available: Collection<EncoderRateControl>,
    ): String? {
        val control = rateControlFor(requested)
            ?: return line("rate_control_unknown", "requested" to requested)
        if (encoderName == null) return line("rate_control_no_encoder", "requested" to requested)
        if (control !in available) {
            return line(
                "rate_control_unavailable",
                "requested" to control.name,
                "encoder" to encoderName,
                "available" to available.joinToString(",") { it.name },
            )
        }
        return null
    }
}
