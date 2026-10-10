package com.example.bluetoothtest

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshCoreTest {
    @Test
    fun envelopeRoundTripAndTtlDecrement() {
        val value = envelope(sequence = 8, ttl = 4, flags = MeshEnvelope.FLAG_KEY_FRAME)

        val decoded = MeshEnvelopeCodec.decode(MeshEnvelopeCodec.encode(value))

        assertEquals(value.streamId, decoded.streamId)
        assertEquals(value.sourceNodeId, decoded.sourceNodeId)
        assertEquals(value.sequence, decoded.sequence)
        assertEquals(value.ttl, decoded.ttl)
        assertEquals(value.flags, decoded.flags)
        assertArrayEquals(value.payload, decoded.payload)
        assertEquals(3, decoded.forwarded()?.ttl)
    }

    @Test
    fun duplicateWindowIsBounded() {
        val window = DuplicateWindow(maxEntries = 2)
        val first = envelope(sequence = 1)
        assertTrue(window.markIfNew(first))
        assertFalse(window.markIfNew(first))
        assertTrue(window.markIfNew(envelope(sequence = 2)))
        assertTrue(window.markIfNew(envelope(sequence = 3)))
        assertTrue(window.markIfNew(first))
    }

    @Test
    fun relayKeepsEveryFrameForASlowChildInIndependentQueues() {
        val local = node(1)
        val incoming = node(2)
        val fastChild = node(3)
        val slowChild = node(4)
        val relay = MeshRelayCore(local)
        relay.addChild(fastChild)
        relay.addChild(slowChild)

        for (sequence in 1L..3L) {
            val result = relay.receive(incoming, envelope(sequence = sequence, ttl = 4))
            assertEquals(setOf(fastChild, slowChild), result.queuedChildren)
            assertEquals(3, result.forwardedEnvelope?.ttl)
            assertEquals(sequence, relay.poll(fastChild)?.sequence)
        }
        // The slow child has not read anything: all three frames wait for it, in order.
        assertEquals(listOf(1L, 2L, 3L), generateSequence { relay.poll(slowChild) }.map { it.sequence }.toList())
        assertEquals(RelayRejection.DUPLICATE, relay.receive(incoming, envelope(sequence = 3)).rejection)
        assertEquals(RelayRejection.TTL_EXPIRED, relay.receive(incoming, envelope(sequence = 4, ttl = 1)).rejection)
    }

    @Test
    fun aJoiningChildKeepsAudioAndStartsVideoAtTheNextKeyframe() {
        val queue = RelayChildQueue()
        queue.waitForKeyFrame()
        assertEquals(ChildQueueOffer.WAITING_FOR_JOIN_KEYFRAME, queue.offer(envelope(sequence = 1)))
        val audio = envelope(sequence = 2, flags = MeshEnvelope.FLAG_AUDIO)
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(audio))
        val keyFrame = envelope(sequence = 3, flags = MeshEnvelope.FLAG_KEY_FRAME)
        assertEquals(ChildQueueOffer.STARTED_AT_KEYFRAME, queue.offer(keyFrame))
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(envelope(sequence = 4)))
        assertEquals(listOf(2L, 3L, 4L), generateSequence { queue.poll() }.map { it.sequence }.toList())
        assertEquals(1L, queue.skippedBeforeJoin)
    }

    @Test
    fun twoRelayChainPreservesIdentityAndDecrementsTtlAtEveryHop() {
        val source = node(8)
        val firstRelayNode = node(1)
        val secondRelayNode = node(2)
        val receiver = node(3)
        val firstRelay = MeshRelayCore(firstRelayNode)
        val secondRelay = MeshRelayCore(secondRelayNode)
        firstRelay.addChild(secondRelayNode)
        secondRelay.addChild(receiver)

        val sourceEnvelope = envelope(sequence = 7, ttl = 4, flags = MeshEnvelope.FLAG_KEY_FRAME)
        assertEquals(null, firstRelay.receive(source, sourceEnvelope).rejection)
        val afterFirstHop = requireNotNull(firstRelay.poll(secondRelayNode))
        assertEquals(null, secondRelay.receive(firstRelayNode, afterFirstHop).rejection)
        val afterSecondHop = requireNotNull(secondRelay.poll(receiver))

        assertEquals(sourceEnvelope.streamId, afterSecondHop.streamId)
        assertEquals(sourceEnvelope.sourceNodeId, afterSecondHop.sourceNodeId)
        assertEquals(sourceEnvelope.sequence, afterSecondHop.sequence)
        assertEquals(2, afterSecondHop.ttl)
        assertEquals(
            RelayRejection.DUPLICATE,
            secondRelay.receive(firstRelayNode, afterFirstHop).rejection,
        )
    }

    @Test
    fun lifecycleControlQueuesBehindMediaWithoutDiscardingIt() {
        val queue = RelayChildQueue()
        queue.offer(envelope(sequence = 1))
        queue.offer(envelope(sequence = 2, flags = MeshEnvelope.FLAG_AUDIO))

        val control = envelope(sequence = 3, flags = MeshEnvelope.FLAG_CONTROL)
        assertEquals(ChildQueueOffer.QUEUED, queue.offer(control))
        assertEquals(listOf(1L, 2L, 3L), generateSequence { queue.poll() }.map { it.sequence }.toList())
        assertNull(queue.poll())
    }

    @Test
    fun relayChildTakeBlocksUntilAFrameIsQueued() {
        val queue = RelayChildQueue()
        val completed = CountDownLatch(1)
        var received: MeshEnvelope? = null
        val worker = Thread {
            received = queue.take()
            completed.countDown()
        }.apply { start() }

        assertFalse(completed.await(100, TimeUnit.MILLISECONDS))
        queue.offer(envelope(sequence = 9))

        assertTrue(completed.await(1, TimeUnit.SECONDS))
        assertEquals(9L, received?.sequence)
        worker.join(1_000)
    }

    @Test
    fun admissionCountsInboundAndEveryChildReservation() {
        val admission = LinkAdmissionController(maximumAggregateKbps = 800)
        admission.setInboundKbps(250)

        assertTrue(admission.tryAdmit(node(2), 250).admitted)
        assertTrue(admission.tryAdmit(node(3), 250).admitted)
        val rejected = admission.tryAdmit(node(4), 100)

        assertFalse(rejected.admitted)
        assertEquals(750, admission.aggregateReservedKbps())
        admission.release(node(2))
        assertTrue(admission.tryAdmit(node(4), 100).admitted)
    }

    @Test
    fun routeTableFallsBackWhenBestNeighborDisappears() {
        val local = node(1)
        val destination = node(9)
        val firstHop = node(2)
        val backupHop = node(3)
        val routes = MeshRouteTable(local, staleAfterMs = 1_000)
        routes.update(RouteCandidate(destination, backupHop, 3, observedAtMs = 100))
        routes.update(RouteCandidate(destination, firstHop, 2, observedAtMs = 100))

        assertEquals(firstHop, routes.best(destination, nowMs = 500)?.nextHop)
        routes.removeNextHop(firstHop)
        assertEquals(backupHop, routes.best(destination, nowMs = 500)?.nextHop)
        assertNull(routes.best(destination, nowMs = 1_500))
    }

    private fun envelope(
        sequence: Long,
        ttl: Int = 3,
        flags: Int = 0,
    ) = MeshEnvelope(
        streamId = STREAM_ID,
        sourceNodeId = node(8),
        sequence = sequence,
        ttl = ttl,
        flags = flags,
        payload = byteArrayOf(10, 20, 30),
    )

    private fun node(value: Int): NodeId = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { value.toByte() })

    private companion object {
        val STREAM_ID: UUID = UUID.fromString("f2af30d1-5858-43f6-bdbf-a38efdc64001")
    }
}
