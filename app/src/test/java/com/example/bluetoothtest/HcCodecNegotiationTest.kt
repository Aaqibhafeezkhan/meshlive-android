package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design HC negotiation cases (DECISIONS "Design HC", measurement plan 1): each phone's end-to-end hello travels through
 * real link and end-to-end handshakes, directly and through one and two opaque relays, in both setup roles; then each
 * sender runs the one choice operation for its own direction.
 */
class HcCodecNegotiationTest {
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    @After
    fun reset() = HcComponentHealth.clearForTest()

    private val vp9 = NodeCapabilities.CODEC_VP9
    private val av1 = NodeCapabilities.CODEC_AV1
    private val h264 = NodeCapabilities.CODEC_H264
    private val hevc = NodeCapabilities.CODEC_HEVC
    private val bit4 = NodeCapabilities.DECODE_AV1_SOFTWARE
    private val bit7 = NodeCapabilities.DECODE_PREFERS_VP9
    private val on = CodecChoiceSwitches(hevc = true, h264 = true)
    private val session = CodecSession(640, 360, 15)
    private val none: (VideoCodec) -> Boolean = { false }
    private val relayLinkCaps = NodeCapabilities(NodeCapabilities.TRANSPORT_BLE_GATT, vp9 or av1, vp9 or av1, 1_100, 1)

    private val envelope = EncoderEnvelope(
        minWidth = 96, maxWidth = 1920, minHeight = 96, maxHeight = 1088, widthAlignment = 2, heightAlignment = 2,
        fpsPoints = EncoderEnvelope.SAMPLE_SIZES.map { (w, h) -> FpsPoint(w, h, 120.0, "pp") },
    )

    private fun codec(name: String, protocolId: Int, hardware: Boolean, backend: VideoEncoderBackend = VideoEncoderBackend.MEDIA_CODEC): VideoCodec {
        val hc = protocolId == StreamProtocol.CODEC_H264 || protocolId == StreamProtocol.CODEC_HEVC
        return VideoCodec(
            displayName = name, mimeType = "video/x", protocolId = protocolId, encoderName = name,
            hardwareAccelerated = hardware, usesSurfaceInput = hardware, bitrateMode = 2, supportedBitrateModes = setOf(2),
            complexity = null, backend = backend,
            automaticBlock = if (hc && !hardware) HcEncoderEligibility.BLOCK_SOFTWARE else null,
            envelope = if (hc) envelope else null,
        )
    }

    private val libaom = codec("libaom-native", StreamProtocol.CODEC_AV1, false, VideoEncoderBackend.NATIVE_LIBAOM)
    private val swAv1 = codec("c2.android.av1.encoder", StreamProtocol.CODEC_AV1, false)
    private val swVp9 = codec("c2.android.vp9.encoder", StreamProtocol.CODEC_VP9, false)
    private val swHevc = codec("c2.android.hevc.encoder", StreamProtocol.CODEC_HEVC, false)

    /** ASUS (msmnile): hardware HEVC and H.264, software VP9 and AV1. */
    private val asus = listOf(
        codec("OMX.qcom.video.encoder.hevc", StreamProtocol.CODEC_HEVC, true),
        codec("OMX.qcom.video.encoder.avc", StreamProtocol.CODEC_H264, true),
        libaom, swAv1, swVp9, swHevc,
    ).sortedBy(VideoCodec::selectionPriority)

    /** M31 (Exynos 9611): hardware VP9 and HEVC. */
    private val m31Vp9 = codec("OMX.Exynos.VP9.Encoder", StreamProtocol.CODEC_VP9, true)
    private val m31Hevc = codec("OMX.Exynos.HEVC.Encoder", StreamProtocol.CODEC_HEVC, true)
    private val m31 = listOf(m31Vp9, m31Hevc, libaom, swAv1).sortedBy(VideoCodec::selectionPriority)

    /** A15s (MT6765): hardware H.264 only. */
    private val a15sAvc = codec("OMX.MTK.VIDEO.ENCODER.AVC", StreamProtocol.CODEC_H264, true)
    private val a15s = listOf(a15sAvc, libaom, swAv1).sortedBy(VideoCodec::selectionPriority)

    /** A phone with hardware VP9 only (an HC-unaware or older layout). */
    private val vp9Only = listOf(codec("c2.exynos.vp9.encoder", StreamProtocol.CODEC_VP9, true), libaom, swAv1)
        .sortedBy(VideoCodec::selectionPriority)

    private val hwAv1 = codec("c2.vendor.av1.encoder", StreamProtocol.CODEC_AV1, true)
    private val hwHevc = codec("c2.vendor.hevc.encoder", StreamProtocol.CODEC_HEVC, true)
    private val hwVp9 = codec("c2.vendor.vp9.encoder", StreamProtocol.CODEC_VP9, true)
    private val hwAvc = codec("c2.vendor.avc.encoder", StreamProtocol.CODEC_H264, true)

    private fun call(
        available: List<VideoCodec>,
        peerMask: Int,
        switches: CodecChoiceSwitches = on,
        familyVp9: Boolean = false,
        picked: VideoCodec? = null,
        excluded: (VideoCodec) -> Boolean = none,
    ): CodecChoice = VideoCodecChoice.forCall(
        requested = picked ?: VideoCodecChoice.automatic(available, CodecChoiceMode.CALL, switches, session, excluded).first(),
        userChoice = picked != null,
        available = available,
        peerDecodeMask = peerMask,
        switches = switches,
        familyVp9 = familyVp9,
        session = session,
        excluded = excluded,
    )

    private fun caps(encoders: List<VideoCodec>, decode: Int) = NodeCapabilities(
        NodeCapabilities.TRANSPORT_BLE_GATT,
        encoders.fold(0) { mask, c -> mask or c.capabilityMask }, decode, 1_100, 0,
    )

    @Test
    fun asymmetricDirectionsAgreeDirectAndThroughRelaysInBothRoles() {
        // The M31 sends HEVC to a hardware-VP9-only phone, which sends VP9 back: each direction is its own choice.
        val m31Caps = caps(m31, vp9 or av1 or hevc or h264 or bit4)
        val otherCaps = caps(vp9Only, vp9 or av1 or hevc or h264 or bit4)
        for (relays in 0..2) for (m31Calls in listOf(true, false)) {
            val (callerSaw, calleeSaw) = if (m31Calls) {
                EndToEndHelloHarness.negotiate(relays, m31Caps, otherCaps, relayLinkCaps)
            } else {
                EndToEndHelloHarness.negotiate(relays, otherCaps, m31Caps, relayLinkCaps)
            }
            val m31SawOther = if (m31Calls) callerSaw else calleeSaw
            val otherSawM31 = if (m31Calls) calleeSaw else callerSaw
            val label = "relays=$relays m31Calls=$m31Calls"
            // Relays copy the end-to-end hello unchanged: bit 4 arrives, and so do the HC bits.
            assertEquals(label, otherCaps.decodeCodecMask, m31SawOther.decodeCodecMask)
            val m31Sends = call(m31, m31SawOther.decodeCodecMask)
            assertEquals(label, m31Hevc, m31Sends.sent)
            assertEquals(label, "hw_hevc_preferred", m31Sends.reason)
            val otherSends = call(vp9Only, otherSawM31.decodeCodecMask)
            assertEquals(label, StreamProtocol.CODEC_VP9, otherSends.sent?.protocolId)
        }
    }

    @Test
    fun anOldPeerWithDecodeMask03GetsVp9AndTheReasonNamesHevc() {
        val choice = call(m31, vp9 or av1)
        assertEquals(m31Vp9, choice.sent)
        assertEquals("peer_no_hevc_decode", choice.reason)
        // Fallback walks only the filtered list: nothing the peer cannot decode is in it.
        assertTrue(choice.ordered.none { it.isHcCodec })
    }

    @Test
    fun aBit7PeerThatDecodesHevcGetsHardwareHevcAndOtherwiseVp9() {
        // The ASUS's end-to-end call hello as the Block 1 smoke logged it: VP9, AV1, HEVC, bit 4 and bit 7 (0x9b).
        val asusHello = vp9 or av1 or hevc or bit4 or bit7
        assertEquals(0x9b, asusHello)
        val fromM31 = call(m31, asusHello)
        assertEquals(m31Hevc, fromM31.sent)
        assertTrue(fromM31.notes.contains("bit7_hw_hevc_first"))
        // Bit 7 still puts VP9 above everything else: AV1 (hardware too), H.264 and software.
        assertEquals(listOf(m31Hevc, m31Vp9), fromM31.ordered.take(2))
        assertEquals(listOf(hwHevc, hwVp9, hwAv1, hwAvc), call(listOf(hwAv1, hwHevc, hwVp9, hwAvc), asusHello or h264).ordered)
        val hwAv1AndVp9 = call(listOf(hwAv1, hwVp9, hwAvc), asusHello)
        assertEquals(hwVp9, hwAv1AndVp9.sent)
        assertEquals("peer_prefers_vp9", hwAv1AndVp9.reason)
        // A software HEVC encoder is never promoted (it is not in Automatic at all).
        assertEquals(swVp9, call(listOf(libaom, swVp9, swHevc), asusHello).sent)

        // Without HEVC decode the peer gets VP9, and the log names bit 7, not a missing AV1 decoder.
        val vp9Peer = call(m31, vp9 or av1 or bit7)
        assertEquals(m31Vp9, vp9Peer.sent)
        assertEquals("peer_prefers_vp9", vp9Peer.reason)

        val noVp9 = call(m31, hevc or bit7)
        assertEquals(m31Hevc, noVp9.sent)
        assertTrue(noVp9.notes.contains("bit7_without_vp9_ignored"))
    }

    @Test
    fun theBit7ControlArmPutsVp9AheadOfHardwareHevcAsTheFirstDraftDid() {
        val choice = call(m31, vp9 or av1 or hevc or bit4 or bit7, switches = on.copy(bit7AllowsHwHevc = false))
        assertEquals(m31Vp9, choice.sent)
        assertEquals("peer_prefers_vp9", choice.reason)
        assertFalse(choice.notes.contains("bit7_hw_hevc_first"))
    }

    @Test
    fun aBit7PeerReceivesHardwareHevcDirectAndThroughRelaysInBothRoles() {
        val asusCaps = caps(asus, vp9 or av1 or hevc or bit4 or bit7)
        val m31Caps = caps(m31, vp9 or av1 or hevc or h264 or bit4)
        for (relays in 0..2) for (m31Calls in listOf(true, false)) {
            val (callerSaw, calleeSaw) = if (m31Calls) {
                EndToEndHelloHarness.negotiate(relays, m31Caps, asusCaps, relayLinkCaps)
            } else {
                EndToEndHelloHarness.negotiate(relays, asusCaps, m31Caps, relayLinkCaps)
            }
            val m31SawAsus = if (m31Calls) callerSaw else calleeSaw
            val label = "relays=$relays m31Calls=$m31Calls"
            assertEquals(label, asusCaps.decodeCodecMask, m31SawAsus.decodeCodecMask)
            assertEquals(label, m31Hevc, call(m31, m31SawAsus.decodeCodecMask).sent)
        }
    }

    @Test
    fun bit4MovesAv1BelowHardwareHevcAndVp9ButNeverBelowHardwareH264() {
        val all = vp9 or av1 or hevc or h264 or bit4
        assertEquals(hwHevc, call(listOf(hwAv1, hwHevc, libaom), all).sent)
        assertEquals("peer_av1_software", call(listOf(hwAv1, hwHevc, libaom), all).reason)
        assertEquals(hwVp9, call(listOf(hwAv1, hwVp9, libaom), all).sent)
        assertEquals(hwAv1, call(listOf(hwAv1, hwAvc, libaom), all).sent)
        // Without bit 4 hardware AV1 stays first.
        assertEquals(hwAv1, call(listOf(hwAv1, hwHevc, hwVp9, hwAvc, libaom), all and bit4.inv()).sent)
        // Bit 4 with only software alternatives keeps AV1.
        assertEquals(hwAv1, call(listOf(hwAv1, libaom, swVp9), all).sent)
        // The list after bit 4: AV1 sits right below the last hardware HEVC/VP9 entry, above hardware H.264.
        assertEquals(
            listOf(hwHevc, hwVp9, hwAv1, hwAvc, libaom),
            call(listOf(hwAv1, hwHevc, hwVp9, hwAvc, libaom), all).ordered,
        )
    }

    @Test
    fun fallbackAfterTheTopChoiceIsRefusedWalksTheList() {
        // A15s: hardware H.264 first; a peer without H.264 decode gets libaom AV1.
        val choice = call(a15s, vp9 or av1)
        assertEquals(libaom, choice.sent)
        assertEquals("peer_no_avc_decode", choice.reason)
        // A manual choice the peer cannot decode falls back along the Automatic list, logged as such.
        val manual = call(m31, vp9 or av1, picked = m31Hevc)
        assertEquals(m31Vp9, manual.sent)
        assertEquals("user_choice_fallback", manual.reason)
        assertEquals(m31Hevc, manual.requested)
    }

    @Test
    fun controlArmsRemoveTheirCodecFromAutomaticButNotFromTheManualPicker() {
        val noHevc = on.copy(preferHevc = false)
        val choice = call(m31, vp9 or av1 or hevc, switches = noHevc)
        assertEquals(m31Vp9, choice.sent)
        assertEquals("hevc_not_preferred", choice.reason)
        assertTrue(choice.ordered.none { it.protocolId == StreamProtocol.CODEC_HEVC })
        assertTrue(m31Hevc in VideoCodecChoice.pickerOrder(m31, noHevc, session, none))
        assertEquals(m31Hevc, call(m31, vp9 or av1 or hevc, switches = noHevc, picked = m31Hevc).sent)

        val noAvc = on.copy(preferHwAvc = false)
        val a15sChoice = call(a15s, vp9 or av1 or h264, switches = noAvc)
        assertEquals(libaom, a15sChoice.sent)
        assertEquals("avc_not_preferred", a15sChoice.reason)
        assertTrue(a15sChoice.ordered.none { it.protocolId == StreamProtocol.CODEC_H264 })
    }

    @Test
    fun theFamilyVp9PolicyKeepsAsusCallsOnVp9AndAppliesToManualChoices() {
        val asusChoice = call(asus, vp9 or av1 or hevc or h264, familyVp9 = true)
        assertEquals(swVp9, asusChoice.sent)
        assertEquals("family_vp9_compatibility", asusChoice.reason)
        val manualHevc = call(asus, vp9 or av1 or hevc or h264, familyVp9 = true, picked = asus.first { it.protocolId == StreamProtocol.CODEC_HEVC && it.hardwareAccelerated })
        assertEquals(swVp9, manualHevc.sent)
    }

    @Test
    fun aManualChoiceThePeerDecodesIsNeverOverriddenByBit4OrBit7() {
        assertEquals(m31Hevc, call(m31, vp9 or hevc or bit7, picked = m31Hevc).sent)
        assertEquals(hwAv1, call(listOf(hwAv1, hwHevc), av1 or hevc or bit4, picked = hwAv1).sent)
        assertEquals("user_choice", call(m31, vp9 or hevc or bit7, picked = m31Hevc).reason)
    }

    @Test
    fun streamsFollowQhc1OptionA() {
        fun stream(available: List<VideoCodec>) = VideoCodecChoice.automatic(available, CodecChoiceMode.STREAM, on, session, none).first()
        assertEquals(m31Vp9, stream(m31)) // hardware VP9 stays VP9 (decodable everywhere)
        assertEquals(StreamProtocol.CODEC_HEVC, stream(asus).protocolId) // software AV1 replaced by hardware HEVC
        assertEquals(a15sAvc, stream(a15s))
        assertEquals(hwAv1, stream(listOf(hwAvc, hwHevc, hwVp9, hwAv1, libaom)))
        assertEquals(listOf(hwAv1, hwVp9, hwHevc, hwAvc, libaom), VideoCodecChoice.automatic(listOf(hwAvc, hwHevc, hwVp9, hwAv1, libaom), CodecChoiceMode.STREAM, on, session, none))
        // The stream reason for the M31: no hardware AV1 (today's label), HEVC is simply ranked below VP9.
        val choice = VideoCodecChoice.forStream(m31Vp9, false, m31, on, session, none)
        assertEquals("no_hw_av1", choice.reason)
        assertEquals("hw_hevc_preferred", VideoCodecChoice.forStream(stream(asus), false, asus, on, session, none).reason)
    }

    @Test
    fun softwareAndIneligibleHcComponentsNeverReachAutomatic() {
        // The ASUS software HEVC encoder is in the picker (manual) but never in an Automatic list.
        assertTrue(swHevc in VideoCodecChoice.pickerOrder(asus, on, session, none))
        assertFalse(swHevc in VideoCodecChoice.automatic(asus, CodecChoiceMode.CALL, on, session, none))
        // A session larger than the encoder's envelope makes the hardware component ineligible (size).
        val small = m31Hevc.copy(envelope = envelope.copy(maxWidth = 512, maxHeight = 512))
        val choice = call(listOf(m31Vp9, small, libaom), vp9 or av1 or hevc)
        assertEquals(m31Vp9, choice.sent)
        assertEquals("ineligible:size", choice.reason)
        // The process stopped trusting a component (no_idr): Automatic skips it.
        HcComponentHealth.markIneligible(m31Hevc.encoderName, "no_idr")
        assertEquals(m31Vp9, call(m31, vp9 or av1 or hevc, excluded = VideoCodecChoice::policyExcluded).sent)
        // A static block recorded at enumeration (the ASUS .hevc.cq) names the reason when nothing else is there.
        val reason = VideoCodecChoice.automaticReason(libaom, listOf(libaom), CodecChoiceMode.STREAM, on, session, none, null,
            mapOf(StreamProtocol.CODEC_HEVC to HcEncoderEligibility.BLOCK_CQ_ONLY))
        assertEquals("ineligible:cq_only", reason)
    }

    @Test
    fun aStreamViewerWithoutHevcBehindAnHevcCapableForwarderStaysRefused() {
        val header = StreamProtocol.Header(640, 360, 15, StreamProtocol.CODEC_HEVC, 0, 0, 0, 0)
        // The forwarder's offer summary comes from the authenticated header through the table: 0x80, not 0x08.
        val summary = AudioLive.offerSummary(header, VideoCodecMap.summaryBit(header.codecId))
        assertEquals(0x80, summary)
        // The viewer (decode VP9|AV1) neither lists it as decodable nor passes admission.
        assertFalse(VideoCodecMap.summaryDecodable(summary, vp9 or av1))
        assertEquals(0, (vp9 or av1) and codecCapabilityMask(header.codecId))
        // A viewer that decodes HEVC does.
        assertTrue(VideoCodecMap.summaryDecodable(summary, vp9 or av1 or hevc))
    }

    @Test
    fun oldReadersSeeSummaryBits2And7AsNoCommonCodec() {
        // A shipped reader intersects the summary with its two-codec decode mask.
        for (summary in listOf(0x04, 0x80, 0x84)) {
            assertEquals(0, summary and (vp9 or av1))
            assertFalse(VideoCodecMap.summaryDecodable(summary, vp9 or av1))
        }
        // The dual-listening hint (bit 3) and relay consent (bit 4) never read as HEVC or anything else.
        assertFalse(VideoCodecMap.summaryDecodable(0x08 or 0x10, vp9 or av1 or hevc or h264))
    }

    @Test
    fun aCocCallOfferFromAnHevcPhoneWithDualListeningOffKeepsSummaryBit3Clear() {
        val encode = vp9 or hevc or h264
        val decode = vp9 or av1 or hevc or h264
        val summary = VideoCodecMap.helloMaskToSummary(encode and decode)
        assertEquals(0x01 or 0x80 or 0x04, summary)
        assertEquals(0, summary and DualListeningWire.SUMMARY_BIT)
        assertEquals(0, summary and RelayedCalls.CONSENT_BIT)
        // Preference bits never reach a summary.
        assertEquals(0x03, VideoCodecMap.helloMaskToSummary(vp9 or av1 or bit4 or bit7))
    }

    @Test
    fun codecIdHelloAndSummaryVectors() {
        val rows = listOf(
            Triple(StreamProtocol.CODEC_VP9, 0x01, 0x01),
            Triple(StreamProtocol.CODEC_AV1, 0x02, 0x02),
            Triple(StreamProtocol.CODEC_H264, 0x04, 0x04),
            Triple(StreamProtocol.CODEC_HEVC, 0x08, 0x80),
        )
        for ((codecId, hello, summary) in rows) {
            assertEquals(hello, codecCapabilityMask(codecId))
            assertEquals(summary, VideoCodecMap.summaryBit(codecId))
            assertEquals(summary, VideoCodecMap.helloMaskToSummary(hello))
            assertEquals(hello, VideoCodecMap.summaryToHelloMask(summary))
        }
        assertEquals(0x87, VideoCodecMap.SUMMARY_CODEC_BITS)
        assertEquals(4, StreamProtocol.CODEC_H264)
        assertEquals(5, StreamProtocol.CODEC_HEVC)
        assertEquals(0x10, NodeCapabilities.DECODE_AV1_SOFTWARE)
    }

    @Test
    fun bit4IsSetOnlyForSoftwareAv1DecodingWithAnAlternativeAndOnlyWhenEnabled() {
        val mask = vp9 or av1 or hevc
        assertEquals(mask or bit4, withAv1SoftwareDecodeBit(mask, av1DecoderHardware = false, enabled = true))
        assertEquals(mask, withAv1SoftwareDecodeBit(mask, av1DecoderHardware = true, enabled = true))
        assertEquals(mask, withAv1SoftwareDecodeBit(mask, av1DecoderHardware = false, enabled = false))
        assertEquals(av1, withAv1SoftwareDecodeBit(av1, av1DecoderHardware = false, enabled = true)) // no alternative
        assertEquals(vp9, withAv1SoftwareDecodeBit(vp9, av1DecoderHardware = null, enabled = true)) // no AV1 at all
        // The bit survives the hello codec and is ignored by codec tests.
        val caps = NodeCapabilities(NodeCapabilities.TRANSPORT_BLE_GATT, vp9, mask or bit4, 1_100, 0)
        assertEquals(caps, NodeCapabilitiesCodec.decode(NodeCapabilitiesCodec.encode(caps)))
        assertEquals(0, (mask or bit4) and VideoCodecMap.HELLO_CODEC_BITS.inv() and bit7.inv() and bit4.inv())
    }
}
