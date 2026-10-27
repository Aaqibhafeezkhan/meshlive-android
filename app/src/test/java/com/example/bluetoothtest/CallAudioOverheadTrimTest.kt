package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Call audio overhead trims (`test_call_audio_coalesce` default on, Experimental `test_call_audio_pairs`): the sender
 * combines 20 ms Opus units into the existing BTA1/BTA2 records, so an unchanged receiver must get exactly the
 * units, order and timestamps it would have got from single records, for fewer bytes on the link.
 */
class CallAudioOverheadTrimTest {
    private data class Item(val flags: Int, val timeUs: Long, val payload: ByteArray) {
        val isAudio get() = flags and StreamProtocol.FLAG_AUDIO != 0
    }

    private fun unit(index: Int, bytes: Int = 80) =
        Item(StreamProtocol.FLAG_AUDIO, 1_000_000L + index * 20_000L, ByteArray(bytes) { (index * 31 + it).toByte() })

    private fun video(index: Int) = Item(0, 1_000_000L + index * 66_666L, ByteArray(2_500) { index.toByte() })

    /** The writer's view: take the first queued audio (audio passes queued video), then coalesce what still waits. */
    private fun writerTakes(queue: ArrayBlockingQueue<Item>): List<Item>? {
        val first = pollNextPrivateCallMedia(queue, 0L, Item::isAudio) ?: return null
        return coalesceQueuedCallAudio(first, Item::flags, Item::timeUs, { it.payload.size }, { accept ->
            takePrivateCallAudioIf(queue, Item::isAudio, accept)
        })
    }

    @Test
    fun audioThatQueuedBehindAVideoWriteLeavesAsOneRecordAndVideoKeepsItsPlace() {
        val queue = ArrayBlockingQueue<Item>(16)
        listOf(unit(0), video(0), unit(1), unit(2), video(1), unit(3)).forEach(queue::add)

        val first = writerTakes(queue)!!
        assertEquals(listOf(0, 1, 2, 3).map { unit(it).timeUs }, first.map(Item::timeUs))
        // Video stays queued, in order, for the next takes.
        assertEquals(listOf(video(0).timeUs, video(1).timeUs), queue.map(Item::timeUs))
        assertEquals(listOf(video(0).timeUs), writerTakes(queue)!!.map(Item::timeUs))
    }

    @Test
    fun nothingWaitingMeansTheSingleUnitIsSentAsBefore() {
        val queue = ArrayBlockingQueue<Item>(4)
        queue.add(unit(0))
        val taken = writerTakes(queue)!!
        assertEquals(1, taken.size)
        assertEquals(StreamProtocol.FLAG_AUDIO, taken[0].flags)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun configurationCombinedRecordsAndLateUnitsAreNeverMerged() {
        val config = Item(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG, 0L, ByteArray(19))
        val pair = Item(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_AUDIO_PAIR, 1_000_000L, ByteArray(170))

        // Codec configuration is queued behind the first unit: coalescing stops there and keeps it queued.
        var queue = ArrayBlockingQueue<Item>(8).apply { add(unit(0)); add(config); add(unit(1)) }
        assertEquals(1, writerTakes(queue)!!.size)
        assertEquals(listOf(config.flags, unit(1).flags), queue.map(Item::flags))

        // An already-combined record is never merged and is never the base of a merge.
        queue = ArrayBlockingQueue<Item>(8).apply { add(pair); add(unit(1)) }
        assertEquals(listOf(pair), writerTakes(queue))
        queue = ArrayBlockingQueue<Item>(8).apply { add(unit(0)); add(pair) }
        assertEquals(1, writerTakes(queue)!!.size)

        // A unit more than the format's 1 s span after the first stays for the next record.
        val late = unit(0).copy(timeUs = unit(0).timeUs + StreamProtocol.MAX_AUDIO_PAIR_GAP_US + 1)
        queue = ArrayBlockingQueue<Item>(8).apply { add(unit(0)); add(late) }
        assertEquals(1, writerTakes(queue)!!.size)
        assertEquals(listOf(late), queue.toList())
    }

    @Test
    fun aLongBacklogIsCutAtTheFormatsTwentyUnits() {
        val queue = ArrayBlockingQueue<Item>(64)
        (0 until 45).forEach { queue.add(unit(it)) }
        assertEquals(listOf(20, 20, 5), generateSequence { writerTakes(queue)?.size }.toList())
    }

    @Test
    fun anUnchangedReceiverGetsExactlyTheOriginalUnitsForFewerLinkBytes() {
        val linkKey = ByteArray(32) { 7 }
        val mediaKey = ByteArray(32) { 9 }
        val units = (0 until 6).map { unit(it, bytes = 60 + it * 7) }

        fun send(records: List<StreamProtocol.Packet>): ByteArray {
            val out = ByteArrayOutputStream()
            val writer = SecureRecordWriter(out, linkKey)
            val sealer = EndToEndContentSealer(mediaKey)
            records.forEach { EndToEndStreamProtocol.writePacket(writer, sealer, it.flags, it.presentationTimeUs, it.payload) }
            return out.toByteArray()
        }

        fun receive(bytes: ByteArray, records: Int): List<StreamProtocol.Packet> {
            val reader = SecureRecordReader(ByteArrayInputStream(bytes), linkKey)
            val opener = EndToEndContentOpener(mediaKey)
            return (0 until records).flatMap {
                val packet = EndToEndStreamProtocol.readPacket(reader, opener)
                // The receiver's existing call path (deliverMedia).
                if (packet.flags and StreamProtocol.FLAG_AUDIO_PAIR != 0) StreamProtocol.splitAudioPair(packet) else listOf(packet)
            }
        }

        val singles = units.map { StreamProtocol.Packet(it.flags, it.timeUs, it.payload) }
        val queue = ArrayBlockingQueue<Item>(8).apply { units.forEach(::add) }
        val combined = combineCallAudio(writerTakes(queue)!!.map { StreamProtocol.Packet(it.flags, it.timeUs, it.payload) })

        val singleBytes = send(singles)
        val combinedBytes = send(listOf(combined))
        val received = receive(combinedBytes, records = 1)
        assertEquals(singles.size, received.size)
        singles.zip(received).forEach { (sent, got) ->
            assertEquals(sent.flags, got.flags)
            assertEquals(sent.presentationTimeUs, got.presentationTimeUs)
            assertArrayEquals(sent.payload, got.payload)
        }
        // Each merged unit saves the per-record framing (BMS1 17 + 16, BME1 18 + 16, packet 13); the batch adds a
        // 6-byte item header per unit and a 5-byte header once.
        val perRecordFraming = 17 + 16 + 18 + 16 + StreamProtocol.PACKET_HEADER_BYTES
        assertEquals((singles.size - 1) * perRecordFraming - singles.size * 6 - 5, singleBytes.size - combinedBytes.size)
    }

    @Test
    fun twoUnitsUseTheExistingPairRecordByteForByte() {
        val a = StreamProtocol.Packet(StreamProtocol.FLAG_AUDIO, 5_000L, ByteArray(80) { 1 })
        val b = StreamProtocol.Packet(StreamProtocol.FLAG_AUDIO, 25_000L, ByteArray(77) { 2 })
        var offered = false
        val viaCoalescing = combineCallAudio(
            coalesceQueuedCallAudio(a, StreamProtocol.Packet::flags, StreamProtocol.Packet::presentationTimeUs,
                { it.payload.size }, { accept -> if (!offered && accept(b)) b.also { offered = true } else null }),
        )
        val legacyPair = StreamProtocol.pairAudioPackets(a, b)
        assertEquals(legacyPair.flags, viaCoalescing.flags)
        assertEquals(legacyPair.presentationTimeUs, viaCoalescing.presentationTimeUs)
        assertArrayEquals(legacyPair.payload, viaCoalescing.payload)
    }

    @Test
    fun alwaysPairChangesOnlyRoutesThatSendSingleUnits() {
        val samsung = BluetoothStackFingerprint("exynos9611", "universal9611", 31)
        val legacyQti = BluetoothStackFingerprint("qcom", "msmnile", 30)
        for (transport in BluetoothTransportOption.entries) {
            for (hops in 0..2) {
                for (fingerprint in listOf(samsung, legacyQti, null)) {
                    for (peer in listOf(null, 110, 500)) {
                        val off = privateCallAudioBatchSizeForRoute(transport, hops, fingerprint, peer)
                        val on = privateCallAudioBatchSizeForRoute(transport, hops, fingerprint, peer, alwaysPair = true)
                        assertEquals("$transport hops=$hops $fingerprint peer=$peer", if (off == 1) 2 else off, on)
                    }
                }
            }
        }
    }
}
