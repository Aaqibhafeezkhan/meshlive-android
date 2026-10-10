package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CocSocketOutputTest {
    @Test
    fun cocWritesNeverExceedTheSmallerSocketPacketLimit() {
        // ASUS sending: peer allows 65,535-byte SDUs, its own stack only 8,087.
        assertEquals(8_087, cocWriteChunkBytes(65_535, 8_087))
        assertEquals(990, cocWriteChunkBytes(990, 0))
        assertEquals(null, cocWriteChunkBytes(0, 0))
    }

    @Test
    fun singlePduSdusFitOneDataLengthPdu() {
        // A15s offers MPS 251: 255-octet K-frames split into 251 + 4 byte ACL packets before.
        assertEquals(245, cocWriteChunkBytes(65_535, 8_087, singlePduSdus = true))
        assertEquals(200, cocWriteChunkBytes(200, 0, singlePduSdus = true))
        assertEquals(245, cocWriteChunkBytes(0, 0, singlePduSdus = true))
        // 245 payload + 2 SDU length + 4 basic header = one 251-octet PDU.
        assertEquals(251, COC_SINGLE_PDU_SDU_BYTES + 2 + 4)
    }

    @Test
    fun aLargeRecordIsSplitWithoutChangingItsBytes() {
        val sizes = mutableListOf<Int>()
        val collected = ByteArrayOutputStream()
        val sink = object : OutputStream() {
            override fun write(b: Int) = error("unexpected single-byte write")
            override fun write(b: ByteArray, off: Int, len: Int) {
                sizes += len
                collected.write(b, off, len)
            }
        }
        val record = ByteArray(20_506) { (it * 31).toByte() }
        ChunkedOutputStream(sink, 8_087).write(record, 0, record.size)
        assertEquals(listOf(8_087, 8_087, 4_332), sizes)
        assertArrayEquals(record, collected.toByteArray())
    }

    @Test
    fun controlConnectionsAlwaysInitiateOnLe1m() {
        // Legacy advertising is on 1M; initiating on 2M alone failed every M31-initiated CoC relay.
        assertEquals(
            android.bluetooth.BluetoothDevice.PHY_LE_1M_MASK or android.bluetooth.BluetoothDevice.PHY_LE_2M_MASK,
            controlInitiatingPhys(BlePhyPreference.TWO_M),
        )
    }
}
