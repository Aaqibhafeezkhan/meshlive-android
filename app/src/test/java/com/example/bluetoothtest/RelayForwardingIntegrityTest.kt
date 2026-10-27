package com.example.bluetoothtest

import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Relay-showcase (c): calls relayed by the A15s ended with "Unexpected secure record sequence 13; expected 12",
 * "Invalid secure record size 655678673", "Secure link decrypt failed ... sequence=68" and "sequence 3; expected 4",
 * always on the hop the A15s transmits to its parent. This drives the on-demand relay's forwarding stage
 * ([BluetoothPrivateCallRelay.forwardOnDemand]) full duplex over byte pipes that behave like the CoC sockets the app
 * uses: every write is cut into 245-byte SDUs by the app's own [ChunkedOutputStream] and every read returns a random
 * short count, so a record arrives split across reads at arbitrary offsets. Mixed record sizes (one-SDU audio, the
 * 1843-byte record that failed, keyframe-sized records) and link heartbeats cross both directions at once. Every
 * record must arrive exactly once, in order, byte-identical, with the receivers' sequence checks passing: the
 * forwarding code neither loses, duplicates, reorders nor interleaves records, so a loss on the A15s's outgoing hop
 * is not produced here.
 */
class RelayForwardingIntegrityTest {
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(300)

    /** A read that returns 1..[maxRead] bytes, as a CoC socket returns one SDU (or part of one) per read. */
    private class ShortReads(private val input: InputStream, seed: Long, private val maxRead: Int) : InputStream() {
        private val random = Random(seed)
        override fun read(): Int = input.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            input.read(b, off, minOf(len, 1 + random.nextInt(maxRead)))
    }

    private class Direction(seed: Long) {
        val sink = PipedOutputStream()
        val source = PipedInputStream(sink, 1 shl 20)
        val output = ChunkedOutputStream(sink, COC_SINGLE_PDU_SDU_BYTES)
        val input: InputStream = ShortReads(source, seed, 300)
        fun close() {
            runCatching { sink.close() }
            runCatching { source.close() }
        }
    }

    private class Link(val near: SecureBluetoothSession, val far: SecureBluetoothSession, val directions: List<Direction>) {
        fun close() = directions.forEach(Direction::close)
    }

    private fun caps() = RelayedCalls.linkCapabilities(
        NodeCapabilities(NodeCapabilities.TRANSPORT_BLE_L2CAP, 3, 3, 500, 0), featureOn = true, helperActive = true,
    )

    private fun link(nearId: NodeId, farId: NodeId, seed: Long): Link {
        val toFar = Direction(seed)
        val toNear = Direction(seed + 1)
        val keyUp = ByteArray(32).also(SecureRandom()::nextBytes)
        val keyDown = ByteArray(32).also(SecureRandom()::nextBytes)
        val near = SecureBluetoothSession(farId, caps(), SecureRecordReader(toNear.input, keyDown), SecureRecordWriter(toFar.output, keyUp))
        val far = SecureBluetoothSession(nearId, caps(), SecureRecordReader(toFar.input, keyUp), SecureRecordWriter(toNear.output, keyDown))
        return Link(near, far, listOf(toFar, toNear))
    }

    private val caller = NodeId.fromBytes(ByteArray(16) { (0x70 + it).toByte() })
    private val helper = NodeId.fromBytes(ByteArray(16) { (0x10 + it).toByte() })
    private val callee = NodeId.fromBytes(ByteArray(16) { (0x30 + it).toByte() })

    /** Record [index] of one direction: its size cycles through what calls send; the bytes name the direction and index. */
    private fun payload(direction: Int, index: Int): ByteArray {
        val sizes = intArrayOf(40, 160, 245, 246, 1843, 700, 8_000, 31_000)
        val size = sizes[index % sizes.size]
        return ByteArray(size) { (direction * 31 + index * 7 + it).toByte() }.also {
            it[0] = direction.toByte()
            it[1] = (index shr 8).toByte()
            it[2] = index.toByte()
        }
    }

    @Test
    fun fullDuplexForwardingOverShortReadsAndSmallSdusDeliversEveryRecordOnceInOrder() {
        val parent = link(caller, helper, 11)
        val child = link(helper, callee, 21)
        val setupId = RelaySetupCodec.newSetupId()
        val relay = BluetoothPrivateCallRelay(onStatus = {}, onStopped = {})
        relay.markStartedForTest()
        val dutyDone = CountDownLatch(1)
        thread(name = "test-relay-duty", isDaemon = true) {
            try {
                relay.forwardOnDemand(
                    BluetoothPrivateCallRelay.OnDemandParent(parent.far, { parent.close() }),
                    child.near,
                    0,
                    AtomicBoolean(false),
                    { relays -> RelaySetupExchange.writeMessage(parent.far, RelaySetupMessage.Ready(setupId, relays)) },
                )
            } finally {
                dutyDone.countDown()
            }
        }
        assertEquals(1, (RelaySetupExchange.awaitReply(parent.near, setupId, 10_000) { parent.close() } as RelaySetupMessage.Ready).relays)

        val count = 240
        val failure = AtomicReference<Throwable?>(null)
        // Pipes end when their writing thread ends, so each sender stays until both receivers are done.
        val received = CountDownLatch(2)
        // Each endpoint opens with its end-to-end handshake record, then sends content with a heartbeat every 10th record.
        fun sender(session: SecureBluetoothSession, direction: Int) = thread(name = "sender-$direction", isDaemon = true) {
            try {
                session.writer.write(SecureRecordType.END_TO_END_HANDSHAKE, byteArrayOf(direction.toByte()))
                session.writer.flush()
                for (index in 0 until count) {
                    session.writer.write(SecureRecordType.END_TO_END_CONTENT, payload(direction, index))
                    if (index % 10 == 9) SecureLinkControlProtocol.writeHeartbeat(session.writer)
                    session.writer.flush()
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
            received.await(240, TimeUnit.SECONDS)
        }
        fun receiver(session: SecureBluetoothSession, direction: Int) = thread(name = "receiver-$direction", isDaemon = true) {
            try {
                val handshake = session.reader.read()
                assertEquals(SecureRecordType.END_TO_END_HANDSHAKE, handshake.type)
                var index = 0
                var heartbeats = 0
                while (index < count) {
                    val record = session.reader.read()
                    if (record.type == SecureRecordType.LINK_HEARTBEAT) {
                        heartbeats++
                        continue
                    }
                    assertEquals(SecureRecordType.END_TO_END_CONTENT, record.type)
                    assertArrayEquals("record $index from direction $direction", payload(direction, index), record.payload)
                    index++
                }
                assertTrue("heartbeats cross the relay as liveness", heartbeats > 0)
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            } finally {
                received.countDown()
            }
        }
        val threads = listOf(
            sender(parent.near, 1), receiver(child.far, 1),
            sender(child.far, 2), receiver(parent.near, 2),
        )
        threads.forEach { it.join(240_000) }
        assertNull(failure.get())
        parent.close()
        child.close()
        assertTrue(dutyDone.await(60, TimeUnit.SECONDS))
    }
}
