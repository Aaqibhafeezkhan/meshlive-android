package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The announcement hint of dual listening (PROTOCOL "Experimental dual listening"): its vectors, the claim
 * that every existing consumer of `capabilitySummary` ignores bit 3, who may carry it, and byte identity
 * with the switch off.
 */
class DualListeningWireTest {
    private val prefix = byteArrayOf(1, 2, 3, 4, 5, 6)
    private val rdi = hex("44e69c0a7a7d")
    private val vp9Av1 = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1

    private fun callOffer(
        flags: Int = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE,
        summary: Int = vp9Av1,
        routeCost: Int = 0,
        version: Int = DiscoveryAnnouncement.FIXED_PREFIX_VERSION,
        node: ByteArray = prefix,
    ) = DiscoveryAnnouncement(
        nodeIdPrefix = node,
        flags = flags,
        capabilitySummary = summary,
        streamToken = 0,
        routeCost = routeCost,
        sequence = 0,
        version = version,
    )

    @Test
    fun vectorsMatchProtocol() {
        val plain = callOffer()
        val hinted = DualListeningWire.offerFor(plain, enabled = true, secondaryReady = true)
        assertEquals("02010203040506410300000000000000000000000000", DiscoveryAnnouncementCodec.encode(plain).toHex())
        assertEquals("02010203040506410b00000000000000000000000000", DiscoveryAnnouncementCodec.encode(hinted).toHex())
        val gattPrimary = callOffer(
            flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or
                DiscoveryAnnouncement.FLAG_GATT_TRANSPORT,
        )
        assertEquals("02010203040506490300000000000000000000000000", DiscoveryAnnouncementCodec.encode(gattPrimary).toHex())
        val rotating = DualListeningWire.offerFor(
            callOffer(node = rdi, version = DiscoveryAnnouncement.ROTATING_VERSION),
            enabled = true,
            secondaryReady = true,
        )
        assertEquals("0544e69c0a7a7d410b00000000000000000000000000", DiscoveryAnnouncementCodec.encode(rotating).toHex())
    }

    @Test
    fun hintedOfferDiffersFromTheOldOfferInExactlyOneByte() {
        val plain = DiscoveryAnnouncementCodec.encode(callOffer())
        val hinted = DiscoveryAnnouncementCodec.encode(DualListeningWire.offerFor(callOffer(), true, true))
        assertEquals(plain.size, hinted.size)
        val different = plain.indices.filter { plain[it] != hinted[it] }
        assertEquals(listOf(8), different) // version, prefix(6), flags, then capabilitySummary
        assertEquals(plain[8].toInt() or DualListeningWire.SUMMARY_BIT, hinted[8].toInt())
    }

    @Test
    fun theCurrentDecoderIgnoresTheBitAndEveryOtherFieldIsUnchanged() {
        val plain = DiscoveryAnnouncementCodec.decode(DiscoveryAnnouncementCodec.encode(callOffer()))
        val hinted = DiscoveryAnnouncementCodec.decode(DiscoveryAnnouncementCodec.encode(DualListeningWire.offerFor(callOffer(), true, true)))
        assertArrayEquals(plain.nodeIdPrefix, hinted.nodeIdPrefix)
        assertEquals(plain.flags, hinted.flags)
        assertEquals(plain.streamToken, hinted.streamToken)
        assertEquals(plain.routeCost, hinted.routeCost)
        assertEquals(plain.version, hinted.version)
        assertEquals(plain.capabilitySummary or DualListeningWire.SUMMARY_BIT, hinted.capabilitySummary)
        // The predicates every list and connect path uses read the flags only.
        assertEquals(plain.isPrivateCallOffer(), hinted.isPrivateCallOffer())
        assertEquals(plain.isStreamOffer(), hinted.isStreamOffer())
        assertEquals(plain.transportOption(), hinted.transportOption())
        assertEquals(plain.usesGattTransport(), hinted.usesGattTransport())
        assertEquals(plain.usesClassicTransport(), hinted.usesClassicTransport())
        assertTrue(hinted.isPrivateCallOffer())
        assertEquals(BluetoothTransportOption.BLE_L2CAP, hinted.transportOption())
        // Version 5 decodes the same way (and, as today, only when the reader accepts rotating IDs).
        val rotating = DualListeningWire.offerFor(callOffer(node = rdi, version = 5), true, true)
        val decoded = DiscoveryAnnouncementCodec.decode(DiscoveryAnnouncementCodec.encode(rotating), acceptRotating = true)
        assertEquals(rotating.capabilitySummary, decoded.capabilitySummary)
        assertThrows(IllegalArgumentException::class.java) {
            DiscoveryAnnouncementCodec.decode(DiscoveryAnnouncementCodec.encode(rotating), acceptRotating = false)
        }
    }

    @Test
    fun everyConsumerOfTheSummaryByteIgnoresBitThree() {
        val bit = DualListeningWire.SUMMARY_BIT
        // The bit is a free one: codec bits 0..2 (2 reserved for H.264) and relay consent (4) do not use it.
        val assigned = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1 or 4 or RelayedCalls.CONSENT_BIT
        assertEquals(0, assigned and bit)
        assertEquals(8, bit)
        // Relayed-call consent reads bit 4 only.
        val hinted = DualListeningWire.offerFor(callOffer(), true, true)
        assertFalse(RelayedCalls.consents(hinted))
        assertTrue(RelayedCalls.consents(hinted.copy(capabilitySummary = hinted.capabilitySummary or RelayedCalls.CONSENT_BIT)))
        assertTrue(RelayedCalls.consents(callOffer(summary = vp9Av1 or RelayedCalls.CONSENT_BIT)))
        // The codec check (summary != 0 and summary & decoderMask != 0) is unchanged by an extra bit.
        for (decoderMask in 0..7) {
            val before = vp9Av1 != 0 && vp9Av1 and decoderMask != 0
            val after = (vp9Av1 or bit) != 0 && (vp9Av1 or bit) and decoderMask != 0
            assertEquals(before, after)
        }
        // Stream offers match on the whole byte, which is why the bit is never set on one.
        val stream = DiscoveryAnnouncement(
            nodeIdPrefix = prefix,
            flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE,
            capabilitySummary = NodeCapabilities.CODEC_VP9,
            streamToken = Int.MIN_VALUE or 77,
            routeCost = 0,
            sequence = 0,
        )
        val target = LogicalStreamTarget.from(stream)
        assertTrue(target.matches(stream))
        val streamWithBit = stream.copy(capabilitySummary = stream.capabilitySummary or bit)
        assertFalse(target.matches(streamWithBit))
        assertSame(stream, DualListeningWire.offerFor(stream, true, true))
    }

    @Test
    fun theCompactFormNeverCarriesTheBit() {
        // Android's reference compact encoder refuses an unrepresentable summary bit (and never advertises the form).
        val hinted = DualListeningWire.offerFor(callOffer(), true, true)
        assertThrows(IllegalArgumentException::class.java) { CompactDiscoveryAnnouncement.encode(hinted) }
        // Its decoder cannot produce the bit: a call offer's summary is the codec field plus the consent bit.
        val uuid = CompactDiscoveryAnnouncement.encode(callOffer())
        val decoded = CompactDiscoveryAnnouncement.decode(uuid)
        assertEquals(0, decoded.capabilitySummary and DualListeningWire.SUMMARY_BIT)
        assertFalse(DualListeningWire.hasHint(decoded))
    }

    @Test
    fun onlyACostZeroCoCCallOfferCarriesOrHonorsTheHint() {
        val callFlags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE
        val shapes = mapOf(
            "CoC call offer" to callOffer(),
            "CoC call offer, v5" to callOffer(node = rdi, version = 5),
        )
        shapes.values.forEach { offer ->
            assertTrue(DualListeningWire.mayCarry(offer))
            assertTrue(DualListeningWire.hasHint(DualListeningWire.offerFor(offer, true, true)))
        }
        val never = mapOf(
            "GATT-primary" to callOffer(flags = callFlags or DiscoveryAnnouncement.FLAG_GATT_TRANSPORT),
            "Classic" to callOffer(flags = callFlags or DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT),
            "relayed (cost 1)" to callOffer(flags = callFlags or DiscoveryAnnouncement.FLAG_RELAY_CAPABLE, routeCost = 1),
            "cost 1 without helper flag" to callOffer(routeCost = 1),
            "relay helper" to callOffer(flags = callFlags or DiscoveryAnnouncement.FLAG_RELAY_CAPABLE),
            "stream offer" to callOffer(flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE),
            "viewer request" to callOffer(flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS),
        )
        never.forEach { (name, offer) ->
            assertFalse(name, DualListeningWire.mayCarry(offer))
            assertSame(name, offer, DualListeningWire.offerFor(offer, true, true))
            // A forged bit on such an offer is ignored, never honored.
            val forged = offer.copy(capabilitySummary = offer.capabilitySummary or DualListeningWire.SUMMARY_BIT)
            assertFalse(name, DualListeningWire.hasHint(forged))
            assertEquals(name, setOf(offer.transportOption()), DualListeningWire.offeredTransports(forged))
        }
    }

    @Test
    fun theHintNeedsTheSwitchAndARunningSecondListener() {
        val plain = callOffer()
        assertSame(plain, DualListeningWire.offerFor(plain, enabled = false, secondaryReady = true))
        assertSame(plain, DualListeningWire.offerFor(plain, enabled = true, secondaryReady = false))
        assertSame(plain, DualListeningWire.offerFor(plain, enabled = false, secondaryReady = false))
        assertNotEquals(plain.capabilitySummary, DualListeningWire.offerFor(plain, true, true).capabilitySummary)
        // The byte image with the switch off is the offer encoded today.
        assertArrayEquals(
            DiscoveryAnnouncementCodec.encode(plain),
            DiscoveryAnnouncementCodec.encode(DualListeningWire.offerFor(plain, enabled = false, secondaryReady = true)),
        )
    }

    @Test
    fun strippedClearsOnlyTheBitAndKeepsTheSameInstanceWhenThereIsNone() {
        val plain = callOffer(summary = vp9Av1 or RelayedCalls.CONSENT_BIT)
        assertSame(plain, DualListeningWire.stripped(plain))
        val hinted = plain.copy(capabilitySummary = plain.capabilitySummary or DualListeningWire.SUMMARY_BIT)
        assertEquals(plain.capabilitySummary, DualListeningWire.stripped(hinted).capabilitySummary)
        // A relay is single-bearer: the offer it mirrors from its child carries no second transport, and a
        // reader would ignore the bit at cost 1 anyway.
        val mirrored = PrivateCallRelayChain.parentOffer(hinted, DiscoveryAnnouncement.FLAG_GATT_TRANSPORT)
        assertEquals(0, mirrored.capabilitySummary and DualListeningWire.SUMMARY_BIT)
        assertEquals(plain.capabilitySummary, mirrored.capabilitySummary)
        assertFalse(DualListeningWire.hasHint(mirrored))
        assertEquals(setOf(BluetoothTransportOption.BLE_GATT), DualListeningWire.offeredTransports(mirrored))
        val forgedHint = mirrored.copy(capabilitySummary = mirrored.capabilitySummary or DualListeningWire.SUMMARY_BIT)
        assertFalse(DualListeningWire.hasHint(forgedHint))
    }

    @Test
    fun offeredTransportsAddGattOnlyForAValidHint() {
        assertEquals(setOf(BluetoothTransportOption.BLE_L2CAP), DualListeningWire.offeredTransports(callOffer()))
        assertEquals(
            setOf(BluetoothTransportOption.BLE_L2CAP, BluetoothTransportOption.BLE_GATT),
            DualListeningWire.offeredTransports(DualListeningWire.offerFor(callOffer(), true, true)),
        )
    }

    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
