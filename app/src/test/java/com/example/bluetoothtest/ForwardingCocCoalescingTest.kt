package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardingCocCoalescingTest {
    private val m31 = DeviceTraits(hardware = "exynos9611", board = "universal9611", sdkInt = 31)
    private val a15s = DeviceTraits(hardware = "mt6765", board = "mt6765", sdkInt = 29)
    private val s24 = DeviceTraits(hardware = "s5e9945", board = "s5e9945", sdkInt = 36)

    /** Records every write the stack would see as one SDU, and the bytes the peer would read. */
    private class SduSink : OutputStream() {
        val sdus = mutableListOf<Int>()
        val bytes = ByteArrayOutputStream()
        override fun write(b: Int) = error("single-byte writes are not SDUs")
        override fun write(b: ByteArray, off: Int, len: Int) {
            sdus += len
            bytes.write(b, off, len)
        }
    }

    @Test
    fun onlyPreAndroid13StacksWithTheSwitchOnCoalesce() {
        assertTrue(ForwardingCocCoalescing.effective(requested = true, traits = m31, disabled = emptySet()))
        assertTrue(ForwardingCocCoalescing.effective(requested = true, traits = a15s, disabled = emptySet()))
        assertFalse(ForwardingCocCoalescing.effective(requested = true, traits = s24, disabled = emptySet()))
        assertFalse(ForwardingCocCoalescing.effective(requested = false, traits = m31, disabled = emptySet()))
        assertFalse(ForwardingCocCoalescing.effective(requested = true, traits = m31, disabled = setOf(ForwardingCocCoalescing.ENTRY_ID)))
        assertFalse(ForwardingCocCoalescing.effective(requested = true, traits = m31, disabled = setOf("all")))
        assertFalse(ForwardingCocCoalescing.effective(requested = true, traits = null, disabled = emptySet()))
    }

    @Test
    fun aHelpersParentLinkSwitchesToCoalescedSdusWithoutChangingTheBytes() {
        val sink = SduSink()
        // The parent link was opened as an ordinary call link: 245-byte SDUs, the peer accepts 65,535-byte SDUs.
        val link = CocSduOutputStream(sink, COC_SINGLE_PDU_SDU_BYTES, maxTransmitBytes = 65_535, maxReceiveBytes = 65_535)
        val before = ByteArray(1_000) { it.toByte() }
        link.write(before)
        assertEquals(listOf(245, 245, 245, 245, 20), sink.sdus)

        val line = ForwardingCocCoalescing.applyToHandedOffLink(link, "call-relay-parent", requested = true, effective = true, singlePdu = true)

        assertTrue(line.orEmpty(), line.orEmpty().endsWith(
            "coc_sdu: scope=forwarding_handoff | link=call-relay-parent | relayCoalesce=requested | relayEffective=true | " +
                "sduBytesBefore=245 | sduBytes=8000",
        ))
        sink.sdus.clear()
        val keyframe = ByteArray(50_000) { (it % 251).toByte() }
        link.write(keyframe)
        assertEquals(7, sink.sdus.size)
        assertTrue(sink.sdus.all { it <= COC_COALESCED_SDU_BYTES })
        assertTrue((before + keyframe).contentEquals(sink.bytes.toByteArray()))
    }

    @Test
    fun theReceiversSduLimitStillWins() {
        val sink = SduSink()
        // The M31 -> ASUS hop: the ASUS accepts 8,087-byte SDUs; a smaller peer limit caps the coalesced size.
        val toAsus = CocSduOutputStream(sink, COC_SINGLE_PDU_SDU_BYTES, maxTransmitBytes = 8_087, maxReceiveBytes = 65_535)
        ForwardingCocCoalescing.applyToHandedOffLink(toAsus, "call-relay-parent", requested = true, effective = true, singlePdu = true)
        assertEquals(COC_COALESCED_SDU_BYTES, toAsus.chunkBytes)

        val toSmallPeer = CocSduOutputStream(sink, COC_SINGLE_PDU_SDU_BYTES, maxTransmitBytes = 4_000, maxReceiveBytes = 65_535)
        ForwardingCocCoalescing.applyToHandedOffLink(toSmallPeer, "call-relay-parent", requested = true, effective = true, singlePdu = true)
        assertEquals(4_000, toSmallPeer.chunkBytes)
    }

    @Test
    fun anUnmatchedStackOrTheSwitchOffLeavesTheLinkAlone() {
        val link = CocSduOutputStream(SduSink(), COC_SINGLE_PDU_SDU_BYTES, maxTransmitBytes = 65_535, maxReceiveBytes = 65_535)

        assertNull(ForwardingCocCoalescing.applyToHandedOffLink(link, "call-relay-parent", requested = false, effective = false))
        assertEquals(COC_SINGLE_PDU_SDU_BYTES, link.chunkBytes)

        val line = ForwardingCocCoalescing.applyToHandedOffLink(link, "call-relay-parent", requested = true, effective = false)
        assertEquals(COC_SINGLE_PDU_SDU_BYTES, link.chunkBytes)
        assertTrue(line.orEmpty(), line.orEmpty().contains("relayEffective=false | sduBytesBefore=245 | sduBytes=245 | relayReason=stack_not_matched"))

        // A GATT or Classic hop has no CoC media stream to change.
        val gatt = ForwardingCocCoalescing.applyToHandedOffLink(ByteArrayOutputStream(), "call-relay-parent", requested = true, effective = true)
        assertTrue(gatt.orEmpty(), gatt.orEmpty().contains("relayEffective=false | sduBytesBefore=unchunked | sduBytes=unchunked | relayReason=not_a_coc_media_stream"))
    }

    @Test
    fun forwardingSocketsLogTheirRequestedAndEffectiveScope() {
        assertEquals(
            " | scope=forwarding | relayCoalesce=requested | relayEffective=false | relayReason=stack_not_matched",
            ForwardingCocCoalescing.suffix(requested = true, effective = false),
        )
        assertEquals(" | scope=forwarding | relayCoalesce=off | relayEffective=false", ForwardingCocCoalescing.suffix(requested = false, effective = false))
    }

    @Test
    fun theProposalIsInertSourcedAndScopedToAndroid12AndOlder() {
        val entry = DeviceFamilyPolicy.entries.single { it.id == ForwardingCocCoalescing.ENTRY_ID }
        assertTrue(entry.proposed)
        assertTrue(entry.effects.isEmpty())
        assertEquals(32, entry.matcher.sdkMax)
        assertEquals(PolicyConfidence.LOW, entry.confidence)
        assertTrue(entry.sources.any { "46bd51ed" in it.reference })
        assertFalse(DeviceFamilyPolicy.resolve(m31, emptySet()).isActive(ForwardingCocCoalescing.ENTRY_ID))
    }

    @Test
    fun theExperimentIsOffByDefault() = assertFalse(DebugTunables.relayCocCoalesce)
}
