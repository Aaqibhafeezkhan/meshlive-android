package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class CocSduCoalescingTest {
    private val asus = DeviceTraits(hardware = "qcom", board = "msmnile", sdkInt = 30)
    private val m31 = DeviceTraits(hardware = "exynos9611", board = "universal9611", sdkInt = 31)

    @Test
    fun coalescingReplacesSinglePduSdusUpToTheSafeSize() {
        assertEquals(245, cocWriteChunkBytes(65_535, 65_535, singlePduSdus = true))
        assertEquals(COC_COALESCED_SDU_BYTES, cocWriteChunkBytes(65_535, 65_535, singlePduSdus = true, coalesceSdus = true))
        // A smaller socket limit still wins.
        assertEquals(4_000, cocWriteChunkBytes(4_000, 65_535, singlePduSdus = true, coalesceSdus = true))
        assertEquals(COC_COALESCED_SDU_BYTES, cocWriteChunkBytes(0, 0, coalesceSdus = true))
    }

    @Test
    fun aKeyframeNeedsAboutTenOrFewerSdusAndNoByteIsLost() {
        val sdus = mutableListOf<Int>()
        val sink = ByteArrayOutputStream()
        val counting = object : OutputStream() {
            override fun write(b: Int) = error("single-byte writes are not SDUs")
            override fun write(b: ByteArray, off: Int, len: Int) {
                sdus += len
                sink.write(b, off, len)
            }
        }
        val record = ByteArray(50_000) { (it % 251).toByte() }
        ChunkedOutputStream(counting, cocWriteChunkBytes(65_535, 65_535, singlePduSdus = true, coalesceSdus = true)!!).write(record)
        assertEquals(7, sdus.size)
        assertTrue(sdus.all { it <= COC_COALESCED_SDU_BYTES })
        assertTrue(record.contentEquals(sink.toByteArray()))
        // The default single-PDU arm needs 205 SDUs for the same record.
        assertEquals(205, (record.size + 244) / 245)
    }

    @Test
    fun onlyMatchingStacksWithTheSwitchOnCoalesce() {
        assertTrue(CocSduCoalescing.effective(requested = true, traits = asus, disabled = emptySet()))
        assertFalse(CocSduCoalescing.effective(requested = false, traits = asus, disabled = emptySet()))
        assertFalse(CocSduCoalescing.effective(requested = true, traits = m31, disabled = emptySet()))
        assertFalse(CocSduCoalescing.effective(requested = true, traits = asus, disabled = setOf(CocSduCoalescing.ENTRY_ID)))
        assertFalse(CocSduCoalescing.effective(requested = true, traits = null, disabled = emptySet()))
    }

    @Test
    fun theProposalIsInertAndSourced() {
        val entry = DeviceFamilyPolicy.entries.single { it.id == CocSduCoalescing.ENTRY_ID }
        assertTrue(entry.proposed)
        assertTrue(entry.effects.isEmpty())
        assertEquals(30, entry.matcher.sdkMax)
        assertTrue(entry.sources.any { "471249f4" in it.reference })
        assertTrue(entry.sources.any { "46bd51ed" in it.reference })
        assertFalse(DeviceFamilyPolicy.resolve(asus, emptySet()).isActive(CocSduCoalescing.ENTRY_ID))
    }

    @Test
    fun requestedAndEffectiveAreBothLogged() {
        assertEquals(
            "BENCHMARK coc_sdu: requested=coalesce | effective=single_pdu | sduBytes=245 | maxTx=251 | maxRx=251 | reason=stack_not_matched",
            CocSduCoalescing.line(requested = true, effective = false, singlePdu = true, chunk = 245, maxTransmit = 251, maxReceive = 251),
        )
    }

    @Test
    fun theExperimentIsOffByDefault() = assertFalse(DebugTunables.cocCoalesceSdus)
}
