package com.example.bluetoothtest

/**
 * Why the Automatic encoder sent the codec it did (design decision 2026-10-27, release 0.4.1: hardware AV1
 * wherever the sending phone has it). Logged once per call or stream session as `BENCHMARK encoder_choice`.
 */
internal enum class EncoderChoiceReason(val label: String) {
    /** An eligible hardware AV1 encoder is sending. */
    HW_AV1_PREFERRED("hw_av1_preferred"),
    /** This phone has no eligible hardware AV1 encoder; today's order (hardware VP9, then the rest) applies. */
    NO_HW_AV1("no_hw_av1"),
    /** Hardware AV1 is available, but the call peer cannot decode AV1 (or asks for VP9). */
    PEER_NO_AV1_DECODE("peer_no_av1_decode"),
    /** Hardware AV1 is available, but a DeviceFamilyPolicy call profile (legacy Qualcomm) asks for VP9. */
    FAMILY_VP9_COMPATIBILITY("family_vp9_compatibility"),
    /** The user (or a DEBUG `test_codec` launch) picked the encoder; Automatic did not choose. */
    USER_CHOICE("user_choice"),
    /** DEBUG control arm `test_prefer_hw_av1=false`: hardware AV1 was available but not preferred. */
    HW_AV1_NOT_PREFERRED("hw_av1_not_preferred"),
}

/**
 * The Automatic encoder order and its logged reason. Pure: the caller supplies the encoder list, the switch
 * and the component exclusion rule ([NoAutomaticHardwareAv1] policy entries).
 *
 * Only a MediaCodec component with `MediaCodecInfo.isHardwareAccelerated` counts as hardware; the bundled
 * libaom and Android's software AV1 encoder are never promoted by this rule (heat). A phone without an eligible
 * hardware AV1 encoder keeps the existing static order exactly.
 */
internal object AutomaticEncoderChoice {
    /**
     * The encoder is the user's (or a DEBUG launch's) pick rather than Automatic's: the picker is not on its
     * first entry, or `test_codec` chose it. Set by MainActivity; read by the call endpoint for its log line.
     */
    @Volatile var userChoice: Boolean = false

    fun isEligibleHardwareAv1(codec: VideoCodec, excluded: (String) -> Boolean): Boolean =
        codec.backend == VideoEncoderBackend.MEDIA_CODEC &&
            codec.hardwareAccelerated &&
            codec.protocolId == StreamProtocol.CODEC_AV1 &&
            !excluded(codec.encoderName)

    /** Production exclusion rule: an active component-scoped [NoAutomaticHardwareAv1] entry names the encoder. */
    fun policyExcluded(encoderName: String): Boolean =
        DeviceFamilyPolicy.componentEffects<NoAutomaticHardwareAv1>(encoderName).isNotEmpty()

    /**
     * [available] in [VideoCodec.selectionPriority] order, rearranged for Automatic:
     * - [preferHwAv1] (default): eligible hardware AV1 first; an excluded hardware AV1 component moves to the
     *   end (still selectable by hand, never chosen automatically).
     * - control arm: hardware AV1 ranks after the other hardware encoders (hardware VP9 first when present).
     * Everything else keeps its relative order.
     */
    fun order(
        available: List<VideoCodec>,
        preferHwAv1: Boolean,
        excluded: (String) -> Boolean,
    ): List<VideoCodec> {
        val hardwareAv1 = available.filter {
            it.backend == VideoEncoderBackend.MEDIA_CODEC && it.hardwareAccelerated &&
                it.protocolId == StreamProtocol.CODEC_AV1
        }
        if (hardwareAv1.isEmpty()) return available
        val eligible = hardwareAv1.filter { isEligibleHardwareAv1(it, excluded) }
        val blocked = hardwareAv1 - eligible.toSet()
        val rest = available.filter { it !in hardwareAv1 }
        if (preferHwAv1) return eligible + rest + blocked
        val otherHardware = rest.filter { it.hardwareAccelerated }
        val software = rest.filter { !it.hardwareAccelerated }
        return otherHardware + eligible + software + blocked
    }

    /**
     * The reason for the encoder actually [sent]. [requested] is what the session started from (picker or
     * family policy result) and [peerDecodeMask] the call peer's advertised decode mask (null for streams:
     * a stream keeps its source codec and the existing admission check refuses a viewer that cannot decode it).
     */
    fun reason(
        sent: VideoCodec,
        requested: VideoCodec,
        available: List<VideoCodec>,
        userChoice: Boolean,
        preferHwAv1: Boolean,
        peerDecodeMask: Int?,
        excluded: (String) -> Boolean,
    ): EncoderChoiceReason {
        if (userChoice) return EncoderChoiceReason.USER_CHOICE
        if (isEligibleHardwareAv1(sent, excluded)) return EncoderChoiceReason.HW_AV1_PREFERRED
        if (available.none { isEligibleHardwareAv1(it, excluded) }) return EncoderChoiceReason.NO_HW_AV1
        if (!preferHwAv1) return EncoderChoiceReason.HW_AV1_NOT_PREFERRED
        if (peerDecodeMask != null && requested.protocolId == StreamProtocol.CODEC_AV1 &&
            (peerDecodeMask and NodeCapabilities.CODEC_AV1 == 0 ||
                peerDecodeMask and NodeCapabilities.DECODE_PREFERS_VP9 != 0)
        ) {
            return EncoderChoiceReason.PEER_NO_AV1_DECODE
        }
        return EncoderChoiceReason.FAMILY_VP9_COMPATIBILITY
    }

    fun logLine(
        role: String,
        sent: VideoCodec,
        reason: EncoderChoiceReason,
        preferHwAv1: Boolean,
        peerDecodeMask: Int?,
    ): String =
        "BENCHMARK encoder_choice: role=$role | encoder=${sent.encoderName} | " +
            "codec=${if (sent.protocolId == StreamProtocol.CODEC_AV1) "AV1" else "VP9"} | " +
            "hardware=${sent.hardwareAccelerated} | prefer_hw_av1=$preferHwAv1 | " +
            "peer_decode_mask=${peerDecodeMask ?: "-"} | reason=${reason.label}"
}
