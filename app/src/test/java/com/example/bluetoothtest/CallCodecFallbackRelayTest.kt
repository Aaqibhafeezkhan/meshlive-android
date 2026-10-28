package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Codec fallback across direct, one-relay and two-relay calls (relaycall-915d7390b/0004: A15s ->
 * S24 relay -> ASUS failed with "Peer cannot decode any locally available call codec" because the
 * first fallback read the relay's link hello). The end-to-end hellos below travel through real
 * BMH2/BMS1 links and opaque relays that copy records unchanged; relays advertise VP9 and AV1
 * encoders in their own link hellos, which must not matter.
 */
class CallCodecFallbackRelayTest {
    /** Safety net: a test that hangs fails after this instead of holding the whole run (a legitimate run on a loaded host takes seconds to a minute or two). */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private val vp9 = NodeCapabilities.CODEC_VP9
    private val av1 = NodeCapabilities.CODEC_AV1
    private val msmnile = BluetoothStackFingerprint("qcom", "msmnile", 30)
    private val gatt = BluetoothTransportOption.BLE_GATT

    private fun codec(name: String, protocolId: Int, hardware: Boolean) = VideoCodec(
        displayName = name, mimeType = if (protocolId == StreamProtocol.CODEC_AV1) "video/av01" else "video/x-vnd.on2.vp9",
        protocolId = protocolId, encoderName = name, hardwareAccelerated = hardware, usesSurfaceInput = false,
        bitrateMode = null, supportedBitrateModes = emptySet(), complexity = null,
    )

    private val libaom = codec("libaom-native", StreamProtocol.CODEC_AV1, hardware = false)
    private val asusVp9 = codec("c2.android.vp9.encoder", StreamProtocol.CODEC_VP9, hardware = false)
    private val s24Vp9 = codec("c2.exynos.vp9.encoder", StreamProtocol.CODEC_VP9, hardware = true)
    private val s24Av1 = codec("c2.android.av1.encoder", StreamProtocol.CODEC_AV1, hardware = false)

    /** What AndroidPrivateVideoCall advertises end to end on the legacy ASUS (VP9-restricted on GATT). */
    private fun asusEndpointCaps(fallback: Boolean): NodeCapabilities {
        val restricted = privateCallDecodeCodecMask(msmnile, gatt, vp9 or av1)
        assertEquals(vp9, restricted)
        return NodeCapabilities(
            NodeCapabilities.TRANSPORT_BLE_GATT, vp9 or av1,
            privateCallEndpointDecodeMask(restricted, vp9 or av1, fallback), 1_100, 0,
        )
    }

    /** The A15s / Xiaomi: AV1 encoders only, VP9 and AV1 decoders. */
    private val a15sCaps = NodeCapabilities(NodeCapabilities.TRANSPORT_BLE_GATT, av1, vp9 or av1, 1_100, 0)
    private val s24Caps = NodeCapabilities(NodeCapabilities.TRANSPORT_BLE_GATT, vp9 or av1, vp9 or av1, 1_100, 0)
    private val relayLinkCaps = NodeCapabilities(NodeCapabilities.TRANSPORT_BLE_GATT, vp9 or av1, vp9 or av1, 1_100, 1)

    @Test
    fun anAv1OnlyPhoneAndALegacyVp9PhoneAgreeOnAv1DirectAndThroughOneOrTwoRelaysInBothRoles() {
        for (relays in 0..2) {
            for (asusCalls in listOf(false, true)) {
                val asus = asusEndpointCaps(fallback = true)
                val (callerSaw, calleeSaw) = if (asusCalls) {
                    negotiate(relays, asus, a15sCaps)
                } else {
                    negotiate(relays, a15sCaps, asus)
                }
                val a15sSawAsus = if (asusCalls) calleeSaw else callerSaw
                val asusSawA15s = if (asusCalls) callerSaw else calleeSaw
                val label = "relays=$relays asusCalls=$asusCalls"
                // A15s -> ASUS: AV1 (it has no VP9 encoder); ASUS -> A15s: its VP9 call codec.
                assertEquals(label, libaom, privateCallCodecForPeer(libaom, listOf(libaom), a15sSawAsus.decodeCodecMask))
                assertEquals(label, asusVp9, privateCallCodecForPeer(asusVp9, listOf(asusVp9, libaom), asusSawA15s.decodeCodecMask))
                // The ASUS's header check accepts the AV1 stream it advertised end to end.
                assertEquals(label, av1, asus.decodeCodecMask and codecCapabilityMask(StreamProtocol.CODEC_AV1))
            }
        }
    }

    @Test
    fun aVp9CapablePeerStillSendsVp9ToTheLegacyPhoneAtEveryHopCount() {
        for (relays in 0..2) {
            val (s24SawAsus, _) = negotiate(relays, s24Caps, asusEndpointCaps(fallback = true))
            // The S24 selected its AV1 encoder, but the legacy phone's signed VP9 preference wins.
            assertEquals("relays=$relays", s24Vp9, privateCallCodecForPeer(s24Av1, listOf(s24Vp9, s24Av1, libaom), s24SawAsus.decodeCodecMask))
            assertEquals(s24Vp9, privateCallCodecForPeer(s24Vp9, listOf(s24Vp9, s24Av1), s24SawAsus.decodeCodecMask))
        }
    }

    @Test
    fun controlArmKeepsTheVp9OnlyAdvertisementAndTheOldFailureAtEveryHopCount() {
        for (relays in 0..2) {
            val (a15sSawAsus, _) = negotiate(relays, a15sCaps, asusEndpointCaps(fallback = false))
            assertEquals(vp9, a15sSawAsus.decodeCodecMask)
            assertNull("relays=$relays", privateCallCodecForPeer(libaom, listOf(libaom), a15sSawAsus.decodeCodecMask))
        }
    }

    @Test
    fun unrestrictedPhonesAdvertiseExactlyAsBefore() {
        assertEquals(vp9 or av1, privateCallEndpointDecodeMask(vp9 or av1, vp9 or av1, fallback = true))
        // A restricted phone without another decoder has nothing to add.
        assertEquals(vp9, privateCallEndpointDecodeMask(vp9, vp9, fallback = true))
        // The preference bit is not a codec: codec tests ignore it and the signed codec keeps it.
        val withBit = asusEndpointCaps(fallback = true)
        assertEquals(vp9 or av1 or NodeCapabilities.DECODE_PREFERS_VP9, withBit.decodeCodecMask)
        assertEquals(withBit, NodeCapabilitiesCodec.decode(NodeCapabilitiesCodec.encode(withBit)))
    }

    /** The end-to-end capabilities each endpoint authenticated for the other (caller's view, callee's view). */
    private fun negotiate(relays: Int, caller: NodeCapabilities, callee: NodeCapabilities): Pair<NodeCapabilities, NodeCapabilities> =
        EndToEndHelloHarness.negotiate(relays, caller, callee, relayLinkCaps)
}
