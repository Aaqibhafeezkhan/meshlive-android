package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CallVideoFragmentsTest {
    private fun packetPlaintext(size: Int, flags: Int = StreamProtocol.FLAG_KEY_FRAME, ptsUs: Long = 1_234_567L): ByteArray =
        EndToEndStreamProtocol.encodePacket(flags, ptsUs, ByteArray(size) { (it * 31 + 7).toByte() })

    @Test
    fun largeFrameRoundTripsThroughFragmentsAndDecodesLikeAWholePacket() {
        val flags = StreamProtocol.FLAG_KEY_FRAME
        val plaintext = packetPlaintext(30_000, flags)
        val fragments = CallVideoFragments.split(plaintext)
        assertEquals((plaintext.size + CallVideoFragments.FRAGMENT_BYTES - 1) / CallVideoFragments.FRAGMENT_BYTES, fragments.size)
        assertTrue(fragments.all { it.size <= CallVideoFragments.HEADER_BYTES + CallVideoFragments.FRAGMENT_BYTES })

        val reassembler = CallVideoReassembler()
        var complete: ByteArray? = null
        fragments.forEachIndexed { index, fragment ->
            val result = reassembler.accept(flags, fragment)
            if (index < fragments.lastIndex) {
                assertNull(result)
                assertTrue(reassembler.inProgress)
            } else {
                complete = result
            }
        }
        assertFalse(reassembler.inProgress)
        assertArrayEquals(plaintext, complete)
        val packet = EndToEndStreamProtocol.decodeReassembledPacket(flags, requireNotNull(complete))
        assertEquals(1_234_567L, packet.presentationTimeUs)
        assertEquals(30_000, packet.payload.size)
    }

    @Test
    fun onlyLargeVideoIsFragmented() {
        assertFalse(CallVideoFragments.shouldFragment(CallVideoFragments.MIN_FRAGMENTED_PACKET_BYTES, 0))
        assertTrue(CallVideoFragments.shouldFragment(CallVideoFragments.MIN_FRAGMENTED_PACKET_BYTES + 1, 0))
        assertFalse(CallVideoFragments.shouldFragment(50_000, StreamProtocol.FLAG_AUDIO))
        assertFalse(CallVideoFragments.shouldFragment(50_000, StreamProtocol.FLAG_CODEC_CONFIG))
    }

    @Test
    fun negotiationNeedsBothEndpointsAndADirectCall() {
        val base = NodeCapabilities(NodeCapabilities.TRANSPORT_BLE_GATT, 3, 3, 1_100, 0)
        val advertised = CallVideoFragments.advertise(base, enabled = true)
        assertEquals(base, CallVideoFragments.advertise(base, enabled = false))
        // The bit survives the signed hello encoding and leaves the transport bit intact.
        val decoded = NodeCapabilitiesCodec.decode(NodeCapabilitiesCodec.encode(advertised))
        assertTrue(decoded.transportMask and NodeCapabilities.TRANSPORT_BLE_GATT != 0)
        assertTrue(CallVideoFragments.negotiated(true, decoded, relayHops = 0))
        assertFalse(CallVideoFragments.negotiated(false, decoded, relayHops = 0))
        assertFalse(CallVideoFragments.negotiated(true, base, relayHops = 0))
        assertFalse(CallVideoFragments.negotiated(true, decoded, relayHops = 1))
    }

    @Test
    fun outOfOrderMissingOrChangedFragmentsFailClosed() {
        val flags = 0
        val fragments = CallVideoFragments.split(packetPlaintext(9_000, flags))
        assertTrue(fragments.size >= 3)

        expectFailure { CallVideoReassembler().accept(flags, fragments[1]) } // starts mid-frame
        expectFailure {
            CallVideoReassembler().apply { accept(flags, fragments[0]); accept(flags, fragments[2]) } // gap
        }
        expectFailure {
            CallVideoReassembler().apply { accept(flags, fragments[0]); accept(flags, fragments[0]) } // replayed start
        }
        expectFailure {
            CallVideoReassembler().apply { accept(flags, fragments[0]); accept(StreamProtocol.FLAG_KEY_FRAME, fragments[1]) }
        }
        expectFailure { CallVideoReassembler().accept(StreamProtocol.FLAG_AUDIO, fragments[0]) }
        expectFailure { CallVideoReassembler().accept(flags, fragments[0].copyOf(CallVideoFragments.HEADER_BYTES)) }
        // A total above the endpoint maximum is refused before any allocation.
        val oversized = fragments[0].copyOf().also { it[0] = 0x7f }
        expectFailure { CallVideoReassembler().accept(flags, oversized) }
        // A fragment that runs past the declared total is refused.
        val small = CallVideoFragments.split(packetPlaintext(4_000, flags), chunkBytes = 3_000)
        val overrun = small[1].copyOf(small[1].size + 10)
        expectFailure { CallVideoReassembler().apply { accept(flags, small[0]); accept(flags, overrun) } }
    }

    @Test
    fun anythingButAudioInsideAnUnfinishedFrameIsRefused() {
        val flags = 0
        val fragments = CallVideoFragments.split(packetPlaintext(6_000, flags))
        val reassembler = CallVideoReassembler()
        reassembler.accept(flags, fragments[0])
        expectFailure { reassembler.requireIdleFor("A whole call video packet") }
        // Finishing the frame makes the reassembler idle again.
        fragments.drop(1).forEach { reassembler.accept(flags, it) }
        reassembler.requireIdleFor("A whole call video packet")
    }

    @Test
    fun fragmentRecordsAreSealedOrderedAndCarryTheFrameFlags() {
        val key = ByteArray(32) { it.toByte() }
        val sealer = EndToEndContentSealer(key)
        val opener = EndToEndContentOpener(key)
        val flags = StreamProtocol.FLAG_KEY_FRAME
        val plaintext = packetPlaintext(5_000, flags)
        val sealed = CallVideoFragments.split(plaintext).map { sealer.seal(EndToEndContentType.VIDEO_FRAGMENT, flags, it) }
        // Every opener that did not advertise fragments (streams, relays, experiment off) still
        // rejects type 9 as unknown, exactly as before.
        expectFailure(IllegalStateException::class.java) { EndToEndContentProtocol.inspect(sealed[0]) }
        expectFailure(IllegalStateException::class.java) { EndToEndContentOpener(key).open(sealed[0]) }
        opener.acceptVideoFragments = true
        val metadata = EndToEndContentProtocol.inspect(sealed[0], acceptVideoFragments = true)
        assertEquals(EndToEndContentType.VIDEO_FRAGMENT, metadata.type)
        assertEquals(flags, metadata.flags)
        val reassembler = CallVideoReassembler()
        var complete: ByteArray? = null
        sealed.forEach { record ->
            val opened = opener.open(record)
            complete = reassembler.accept(opened.flags, opened.payload) ?: complete
        }
        assertArrayEquals(plaintext, complete)
        // Replaying an earlier fragment record is rejected by the endpoint opener.
        expectFailure { opener.open(sealed[0]) }
    }

    @Test
    fun interleavedAudioIsTakenOldestFirst() {
        val queue = java.util.concurrent.ArrayBlockingQueue<Pair<Boolean, Int>>(8)
        queue.add(false to 1)
        queue.add(true to 2)
        queue.add(false to 3)
        queue.add(true to 4)
        assertEquals(true to 2, takePrivateCallAudio(queue) { it.first })
        assertEquals(true to 4, takePrivateCallAudio(queue) { it.first })
        assertNull(takePrivateCallAudio(queue) { it.first })
        assertEquals(listOf(false to 1, false to 3), queue.toList())
    }

    private fun expectFailure(type: Class<out Throwable>, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (type.isInstance(error)) return
            throw error
        }
        fail("Expected ${type.simpleName}")
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        } catch (_: java.security.GeneralSecurityException) {
            return
        }
        fail("Expected the fragment to be refused")
    }
}
