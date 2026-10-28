package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticEncoderChoiceTest {
    private val softwareAv1 = VideoCodec(
        displayName = "AV1 - Android compatibility",
        mimeType = "video/av01",
        protocolId = StreamProtocol.CODEC_AV1,
        encoderName = "c2.android.av1.encoder",
        hardwareAccelerated = false,
        usesSurfaceInput = false,
        bitrateMode = null,
        supportedBitrateModes = emptySet(),
        complexity = 4,
    )
    private val nativeAv1 = softwareAv1.copy(
        displayName = "AV1 - optimized software",
        encoderName = "libaom-native",
        complexity = null,
        backend = VideoEncoderBackend.NATIVE_LIBAOM,
    )
    private val hardwareAv1 = softwareAv1.copy(
        displayName = "AV1 - hardware",
        encoderName = "c2.vendor.av1.encoder",
        hardwareAccelerated = true,
        usesSurfaceInput = true,
    )
    private val hardwareVp9 = hardwareAv1.copy(
        displayName = "VP9 - hardware",
        mimeType = "video/x-vnd.on2.vp9",
        protocolId = StreamProtocol.CODEC_VP9,
        encoderName = "c2.vendor.vp9.encoder",
        complexity = null,
    )
    private val noExclusions: (String) -> Boolean = { false }
    private val bothDecoders = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1

    /** What a phone offers, in VideoCodec.availableEncoders order. */
    private fun offered(vararg codecs: VideoCodec) = codecs.sortedBy(VideoCodec::selectionPriority)

    /** The call path: Automatic's first encoder, then the peer's decoders decide what is sent. */
    private fun callSends(
        available: List<VideoCodec>,
        peerMask: Int,
        prefer: Boolean = true,
        excluded: (String) -> Boolean = noExclusions,
        picked: VideoCodec? = null,
    ): Pair<VideoCodec, EncoderChoiceReason> {
        val ordered = AutomaticEncoderChoice.order(available, prefer, excluded)
        val requested = picked ?: ordered.first()
        val sent = requireNotNull(privateCallCodecForPeer(requested, ordered, peerMask))
        val reason = AutomaticEncoderChoice.reason(
            sent, requested, ordered, userChoice = picked != null, preferHwAv1 = prefer,
            peerDecodeMask = peerMask, excluded = excluded,
        )
        return sent to reason
    }

    @Test
    fun hardwareAv1WithPeerAv1DecodeSendsAv1() {
        val (sent, reason) = callSends(offered(hardwareVp9, hardwareAv1, nativeAv1), bothDecoders)
        assertEquals(hardwareAv1, sent)
        assertEquals(EncoderChoiceReason.HW_AV1_PREFERRED, reason)
    }

    @Test
    fun noHardwareAv1KeepsHardwareVp9() {
        // S24/M31/A55 shape: hardware VP9 plus software AV1 encoders; Automatic still sends hardware VP9.
        val (sent, reason) = callSends(offered(hardwareVp9, nativeAv1, softwareAv1), bothDecoders)
        assertEquals(hardwareVp9, sent)
        assertEquals(EncoderChoiceReason.NO_HW_AV1, reason)
    }

    @Test
    fun noHardwareEncoderKeepsTodaysSoftwareOrder() {
        // S22/Xiaomi/ASUS shape: the rule never promotes or demotes anything; the static order stands.
        val available = offered(softwareAv1, nativeAv1)
        assertEquals(available, AutomaticEncoderChoice.order(available, true, noExclusions))
    }

    @Test
    fun peerWithoutAv1DecodeGetsVp9() {
        val (sent, reason) = callSends(offered(hardwareVp9, hardwareAv1), NodeCapabilities.CODEC_VP9)
        assertEquals(hardwareVp9, sent)
        assertEquals(EncoderChoiceReason.PEER_NO_AV1_DECODE, reason)
    }

    @Test
    fun legacyPeerPreferringVp9GetsVp9() {
        val (sent, reason) = callSends(
            offered(hardwareVp9, hardwareAv1),
            bothDecoders or NodeCapabilities.DECODE_PREFERS_VP9,
        )
        assertEquals(hardwareVp9, sent)
        assertEquals(EncoderChoiceReason.PEER_PREFERS_VP9, reason)
    }

    @Test
    fun userManualChoiceWins() {
        val (sent, reason) = callSends(offered(hardwareVp9, hardwareAv1), bothDecoders, picked = hardwareVp9)
        assertEquals(hardwareVp9, sent)
        assertEquals(EncoderChoiceReason.USER_CHOICE, reason)
    }

    @Test
    fun excludedHardwareAv1IsSkippedButStaysSelectable() {
        val excluded: (String) -> Boolean = { it == hardwareAv1.encoderName }
        val available = offered(hardwareVp9, hardwareAv1, nativeAv1)
        val ordered = AutomaticEncoderChoice.order(available, true, excluded)
        assertEquals(listOf(hardwareVp9, nativeAv1, hardwareAv1), ordered)
        val (sent, reason) = callSends(available, bothDecoders, excluded = excluded)
        assertEquals(hardwareVp9, sent)
        assertEquals(EncoderChoiceReason.NO_HW_AV1, reason)
    }

    @Test
    fun softwareAv1IsNeverTreatedAsHardware() {
        assertEquals(false, AutomaticEncoderChoice.isEligibleHardwareAv1(nativeAv1, noExclusions))
        assertEquals(false, AutomaticEncoderChoice.isEligibleHardwareAv1(softwareAv1, noExclusions))
        // A native backend wrongly flagged hardware is still not a MediaCodec hardware encoder.
        assertEquals(
            false,
            AutomaticEncoderChoice.isEligibleHardwareAv1(nativeAv1.copy(hardwareAccelerated = true), noExclusions),
        )
    }

    @Test
    fun controlArmSendsHardwareVp9() {
        val (sent, reason) = callSends(offered(hardwareVp9, hardwareAv1, nativeAv1), bothDecoders, prefer = false)
        assertEquals(hardwareVp9, sent)
        assertEquals(EncoderChoiceReason.HW_AV1_NOT_PREFERRED, reason)
    }

    @Test
    fun streamUsesHardwareAv1WithoutViewerCheck() {
        val ordered = AutomaticEncoderChoice.order(offered(hardwareVp9, hardwareAv1), true, noExclusions)
        val reason = AutomaticEncoderChoice.reason(
            ordered.first(), ordered.first(), ordered, userChoice = false, preferHwAv1 = true,
            peerDecodeMask = null, excluded = noExclusions,
        )
        assertEquals(hardwareAv1, ordered.first())
        assertEquals(EncoderChoiceReason.HW_AV1_PREFERRED, reason)
    }
}
