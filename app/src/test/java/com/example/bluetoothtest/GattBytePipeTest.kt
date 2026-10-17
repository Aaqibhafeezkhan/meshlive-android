package com.example.bluetoothtest

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GattBytePipeTest {
    @Test
    fun downstreamConnectionCannotOverwriteTheAdmittedUpstreamMtuOrPhy() {
        val upstream = "AA:BB:CC:DD:EE:01"
        val downstream = "AA:BB:CC:DD:EE:02"

        assertTrue(gattServerOwnsCallback(upstream, upstream))
        assertFalse(gattServerOwnsCallback(upstream, downstream))
    }

    @Test
    fun acceptsSetupCallbacksBeforeAParentIsAdmitted() {
        assertTrue(gattServerOwnsCallback(null, "AA:BB:CC:DD:EE:01"))
    }

    @Test
    fun retriesOnlyTransientGattStatus133ConnectionFailures() {
        assertTrue(isRetryableGattVideoFailure("GATT video connection failed (status 133)"))
        assertTrue(isRetryableGattVideoFailure("GATT video receiver disconnected (status 133)"))
        assertFalse(isRetryableGattVideoFailure("GATT video receiver disconnected (status 8)"))
        assertFalse(isRetryableGattVideoFailure(null))
    }

    @Test
    fun pacesLegacyGattCallsByBytesInsteadOfPerPacket() {
        assertEquals(42_833_334L, gattWritePacingIntervalNs(514, 12_000))
        assertEquals(12_500_000L, gattWritePacingIntervalNs(150, 12_000))
    }

    @Test
    fun retriesOnlyAnUnsupportedAcknowledgedWriteWithoutResponse() {
        assertEquals(
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            gattWriteFallbackType(true, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED),
        )
        assertEquals(null, gattWriteFallbackType(false, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED))
        assertEquals(null, gattWriteFallbackType(true, BluetoothGatt.GATT_FAILURE))
    }

    @Test
    fun overflowClosesTheByteStreamInsteadOfAcceptingCorruptPartialData() {
        var overflows = 0
        val pipe = GattBytePipe(onFailure = { overflows++ }, capacity = 2)

        assertTrue(pipe.offer(byteArrayOf(1)))
        assertTrue(pipe.offer(byteArrayOf(2)))
        assertFalse(pipe.offer(byteArrayOf(3)))
        assertEquals(1, overflows)
        assertEquals(-1, pipe.read())
    }

    @Test
    fun readsAnIncomingGattPayloadWithoutCopyingItsTypeByte() {
        val pipe = GattBytePipe(onFailure = {}, capacity = 2)
        val target = ByteArray(2)

        assertTrue(pipe.offer(byteArrayOf(9, 1, 2), start = 1, length = 2))
        assertEquals(2, pipe.read(target))
        assertArrayEquals(byteArrayOf(1, 2), target)
    }

    @Test
    fun ownsCallbackBytesBeforeAndroidCanReuseThem() {
        val pipe = GattBytePipe(onFailure = {}, capacity = 2)
        val callbackBytes = byteArrayOf(9, 1, 2)
        val target = ByteArray(2)

        assertTrue(pipe.offer(callbackBytes, start = 1, length = 2))
        callbackBytes.fill(7)

        assertEquals(2, pipe.read(target))
        assertArrayEquals(byteArrayOf(1, 2), target)
    }

    @Test
    fun ownsEveryOutgoingChunkUntilAndroidFinishesWithIt() {
        val packets = mutableListOf<ByteArray>()
        val output = object : ChunkedGattOutputStream() {
            override val payloadBytes = 2
            override fun sendPacket(value: ByteArray) {
                packets += value
            }
        }

        output.write(byteArrayOf(1, 2, 3, 4))

        assertEquals(2, packets.size)
        assertArrayEquals(byteArrayOf(1, 2), packets[0].copyOfRange(GATT_CHUNK_HEADER_BYTES, packets[0].size))
        assertArrayEquals(byteArrayOf(3, 4), packets[1].copyOfRange(GATT_CHUNK_HEADER_BYTES, packets[1].size))
        assertFalse(packets[0] === packets[1])
    }

    @Test
    fun reassemblesOrderedChunksAfterAndroidReusesEveryCallbackArray() {
        val failures = mutableListOf<String>()
        val pipe = GattBytePipe(onFailure = failures::add, capacity = 1_024)
        val output = object : ChunkedGattOutputStream() {
            override val payloadBytes = 17
            override fun sendPacket(value: ByteArray) {
                assertTrue(handleIncoming(pipe, value))
                value.fill(0x7f)
            }
        }
        val source = ByteArray(16_384) { index -> (index * 31).toByte() }

        output.write(source)
        val received = ByteArray(source.size)
        var offset = 0
        while (offset < received.size) {
            val count = pipe.read(received, offset, minOf(23, received.size - offset))
            assertTrue(count > 0)
            offset += count
        }

        assertTrue(failures.isEmpty())
        assertArrayEquals(source, received)
    }

    @Test
    fun buffersOutOfOrderGattChunksUntilTheMissingChunkArrives() {
        val packets = mutableListOf<ByteArray>()
        val failures = mutableListOf<String>()
        val pipe = GattBytePipe(onFailure = failures::add, capacity = 8)
        val output = object : ChunkedGattOutputStream() {
            override val payloadBytes = 2
            override fun sendPacket(value: ByteArray) {
                packets += value
            }
        }
        output.write(byteArrayOf(1, 2, 3, 4, 5, 6))

        assertTrue(handleIncoming(pipe, packets[0]))
        assertTrue(handleIncoming(pipe, packets[2]))
        assertTrue(handleIncoming(pipe, packets[1]))
        val received = ByteArray(6)
        assertEquals(2, pipe.read(received, 0, 6))
        assertEquals(2, pipe.read(received, 2, 4))
        assertEquals(2, pipe.read(received, 4, 2))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), received)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun rejectsAnUnboundedGattSequenceGap() {
        val failures = mutableListOf<String>()
        val pipe = GattBytePipe(onFailure = failures::add, capacity = 8)
        val packet = byteArrayOf(1, 0, 0, 16, 1, 7)

        assertFalse(handleIncoming(pipe, packet))
        assertEquals(listOf("received chunk 4097; expected 0"), failures)
        assertEquals(-1, pipe.read())
    }

    @Test
    fun ignoresAnIdenticalImmediateGattChunkDuplicateWithoutDuplicatingStreamBytes() {
        val failures = mutableListOf<String>()
        val pipe = GattBytePipe(onFailure = failures::add, capacity = 8)
        val packets = mutableListOf<ByteArray>()
        val collectingOutput = object : ChunkedGattOutputStream() {
            override val payloadBytes = 2
            override fun sendPacket(value: ByteArray) {
                packets += value
            }
        }
        collectingOutput.write(byteArrayOf(1, 2, 3, 4))

        assertTrue(handleIncoming(pipe, packets[0]))
        assertTrue(handleIncoming(pipe, packets[0].copyOf()))
        assertTrue(handleIncoming(pipe, packets[1]))

        val received = ByteArray(4)
        assertEquals(2, pipe.read(received, 0, 4))
        assertEquals(2, pipe.read(received, 2, 2))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), received)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun ignoresAnIdenticalOlderGattChunkReplayWithoutDuplicatingStreamBytes() {
        val failures = mutableListOf<String>()
        val pipe = GattBytePipe(onFailure = failures::add, capacity = 1_024)
        val packets = mutableListOf<ByteArray>()
        val output = object : ChunkedGattOutputStream() {
            override val payloadBytes = 1
            override fun sendPacket(value: ByteArray) {
                packets += value
            }
        }
        val source = ByteArray(700) { it.toByte() }
        output.write(source)

        packets.forEach { assertTrue(handleIncoming(pipe, it)) }
        assertTrue(handleIncoming(pipe, packets[1].copyOf()))

        val received = ByteArray(source.size)
        repeat(received.size) { received[it] = pipe.read().toByte() }
        assertArrayEquals(source, received)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun ignoresARepeatedGattSequenceEvenWhenTheLegacyCallbackReusedItsBytes() {
        val failures = mutableListOf<String>()
        val pipe = GattBytePipe(onFailure = failures::add, capacity = 8)
        val packet = byteArrayOf(1, 0, 0, 0, 0, 7)

        assertTrue(handleIncoming(pipe, packet))
        val changedReplay = packet.copyOf().also { it[it.lastIndex] = 8 }
        assertTrue(handleIncoming(pipe, changedReplay))

        assertTrue(failures.isEmpty())
        assertEquals(7, pipe.read())
    }


    @Test
    fun aBacklogOfSmallAudioRecordsDoesNotOverflowTheDefaultPipe() {
        // b1-a5a8f056 stalled2m d2: 17 s of 80 byte Opus records (850 chunks) arrived while the receive loop paced audio at real time;
        // the old 512-chunk bound overflowed after 11 s. The default pipe is bounded by bytes.
        val failures = mutableListOf<String>()
        val pipe = GattBytePipe(onFailure = failures::add)
        repeat(850) { assertTrue(pipe.offer(ByteArray(80))) }
        assertEquals(emptyList<String>(), failures)
        assertEquals(850 * 80, pipe.available())
    }

    @Test
    fun theByteBoundStillOverflowsAndClosesTheInput() {
        val failures = mutableListOf<String>()
        val pipe = GattBytePipe(onFailure = failures::add, maxQueuedBytes = 1_000)
        repeat(10) { assertTrue(pipe.offer(ByteArray(100))) }
        assertFalse(pipe.offer(ByteArray(100)))
        assertEquals(listOf("input queue overflowed"), failures)
        assertEquals(-1, pipe.read())
    }
}
