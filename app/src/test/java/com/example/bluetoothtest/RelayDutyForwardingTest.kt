package com.example.bluetoothtest

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stage of an on-demand relay that no phone had ever reached before rotid-958c8a53-p4 (the helper died at
 * its first line): READY goes upstream once, then the ordered end-to-end records cross both hops. Run here over
 * real record sessions, with the Bluetooth connect/authenticate stage replaced by already-authenticated pairs
 * (a JVM cannot open GATT). The Android 11 crash itself (an API 33 method) cannot happen on a JVM; see
 * [ApiLevelGuardTest] and [RelayDutySupportTest].
 */
class RelayDutyForwardingTest {
    private class Pipe {
        private val queue = LinkedBlockingQueue<Int>()
        @Volatile var closed = false
        val input = object : InputStream() {
            override fun read(): Int {
                while (true) {
                    val next = queue.poll(20, TimeUnit.MILLISECONDS)
                    if (next != null) return next
                    if (closed && queue.isEmpty()) throw IOException("Pipe closed")
                }
            }
        }
        val output = object : OutputStream() {
            override fun write(b: Int) {
                if (closed) throw IOException("Pipe closed")
                queue.put(b and 0xff)
            }
        }

        fun close() {
            closed = true
        }
    }

    /** One authenticated link seen from both ends: [near] talks to [far]. */
    private class Link(val near: SecureBluetoothSession, val far: SecureBluetoothSession, private val pipes: List<Pipe>) {
        fun close() = pipes.forEach(Pipe::close)
    }

    private fun caps() = RelayedCalls.linkCapabilities(
        NodeCapabilities(NodeCapabilities.TRANSPORT_BLE_GATT, 3, 3, 500, 0), featureOn = true, helperActive = true,
    )

    /** [near] is the session whose peer is [farId]; [far] is the session on the other phone whose peer is [nearId]. */
    private fun link(nearId: NodeId, farId: NodeId): Link {
        val toFar = Pipe()
        val toNear = Pipe()
        val keyUp = ByteArray(32).also(SecureRandom()::nextBytes)
        val keyDown = ByteArray(32).also(SecureRandom()::nextBytes)
        val near = SecureBluetoothSession(
            farId, caps(), SecureRecordReader(toNear.input, keyDown), SecureRecordWriter(toFar.output, keyUp),
        )
        val far = SecureBluetoothSession(
            nearId, caps(), SecureRecordReader(toFar.input, keyUp), SecureRecordWriter(toNear.output, keyDown),
        )
        return Link(near, far, listOf(toFar, toNear))
    }

    private val caller = NodeId.fromBytes(ByteArray(16) { (0x70 + it).toByte() })
    private val helper = NodeId.fromBytes(ByteArray(16) { (0x10 + it).toByte() })
    private val callee = NodeId.fromBytes(ByteArray(16) { (0x30 + it).toByte() })

    private class Duty(val relay: BluetoothPrivateCallRelay, val finished: CountDownLatch, val parentClosed: AtomicBoolean)

    /**
     * Starts [BluetoothPrivateCallRelay.forwardOnDemand] on a thread, as the relay worker does: [parentLink] is
     * caller<->helper (the relay holds `far`), [childLink] is helper<->callee (the relay holds `near`).
     */
    private fun startDuty(
        parentLink: Link,
        childLink: Link,
        setupId: ByteArray,
        relaysBelow: Int = 0,
        replied: AtomicBoolean = AtomicBoolean(false),
        readyCalls: AtomicInteger = AtomicInteger(),
        failure: AtomicReference<Throwable?> = AtomicReference(null),
    ): Duty {
        val relay = BluetoothPrivateCallRelay(onStatus = {}, onStopped = {})
        relay.markStartedForTest()
        val parentClosed = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        thread(name = "test-relay-duty", isDaemon = true) {
            try {
                relay.forwardOnDemand(
                    BluetoothPrivateCallRelay.OnDemandParent(parentLink.far, { parentClosed.set(true); parentLink.close() }),
                    childLink.near,
                    relaysBelow,
                    replied,
                    { relays ->
                        // The helper's onReady: READY goes to the parent on its own link, exactly once.
                        readyCalls.incrementAndGet()
                        RelaySetupExchange.writeMessage(parentLink.far, RelaySetupMessage.Ready(setupId, relays))
                    },
                )
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }
        return Duty(relay, finished, parentClosed)
    }

    @Test
    fun readyGoesUpstreamFirstThenEndToEndRecordsCrossBothHops() {
        val parent = link(caller, helper)
        val child = link(helper, callee)
        val setupId = RelaySetupCodec.newSetupId()
        val readyCalls = AtomicInteger()
        val failure = AtomicReference<Throwable?>(null)
        val duty = startDuty(parent, child, setupId, readyCalls = readyCalls, failure = failure)

        // The caller's first record from the helper is READY, with one relay for a target one hop below.
        val reply = RelaySetupExchange.awaitReply(parent.near, setupId, 5_000) { parent.close() }
        assertEquals(1, (reply as RelaySetupMessage.Ready).relays)
        assertEquals(1, readyCalls.get())

        // The end-to-end handshake crosses to the callee unchanged, and the callee's answer comes back.
        val hello = byteArrayOf(1, 2, 3, 4)
        parent.near.writer.write(SecureRecordType.END_TO_END_HANDSHAKE, hello)
        parent.near.writer.flush()
        val atCallee = child.far.reader.read()
        assertEquals(SecureRecordType.END_TO_END_HANDSHAKE, atCallee.type)
        assertArrayEquals(hello, atCallee.payload)

        val ringing = byteArrayOf(9, 8, 7)
        child.far.writer.write(SecureRecordType.END_TO_END_CONTENT, ringing)
        child.far.writer.flush()
        val atCaller = parent.near.reader.read()
        assertEquals(SecureRecordType.END_TO_END_CONTENT, atCaller.type)
        assertArrayEquals(ringing, atCaller.payload)
        assertEquals("READY is sent once", 1, readyCalls.get())
        assertNull(failure.get())

        // The caller leaving ends the duty and closes the parent link the endpoint handed over.
        parent.close()
        assertTrue(duty.finished.await(5, TimeUnit.SECONDS))
        assertTrue(duty.parentClosed.get())
        child.close()
    }

    @Test
    fun readyCarriesTheRelayCountBelowPlusOne() {
        val parent = link(caller, helper)
        val child = link(helper, callee)
        val setupId = RelaySetupCodec.newSetupId()
        startDuty(parent, child, setupId, relaysBelow = 1)
        val reply = RelaySetupExchange.awaitReply(parent.near, setupId, 5_000) { parent.close() }
        assertEquals(2, (reply as RelaySetupMessage.Ready).relays)
        parent.close()
        child.close()
    }

    /**
     * Two helpers (caller -> H1 -> H2 -> callee): each hop regenerates its own setup id, H2's READY (1 relay) reaches
     * H1, H1 answers the caller READY with 2 relays (what `require=2` compares), and ordered end-to-end records cross
     * all three links both ways. The Bluetooth connect/authenticate stages are replaced by authenticated pairs.
     */
    @Test
    fun twoHelpersAnswerReadyWithTwoRelaysAndCarryOrderedRecordsAcrossThreeLinks() {
        val helper2 = NodeId.fromBytes(ByteArray(16) { (0x50 + it).toByte() })
        val callerLink = link(caller, helper)
        val middle = link(helper, helper2)
        val lastLink = link(helper2, callee)
        val callerSetupId = RelaySetupCodec.newSetupId()
        val firstHopSetupId = RelaySetupCodec.newSetupId()
        assertFalse("each hop regenerates its setup id", callerSetupId.contentEquals(firstHopSetupId))

        // H2 first (nothing below it): its READY says 1 relay, exactly what H1's awaitReply reads in startOnDemand.
        val secondFailure = AtomicReference<Throwable?>(null)
        val duty2 = startDuty(middle, lastLink, firstHopSetupId, relaysBelow = 0, failure = secondFailure)
        val fromSecond = RelaySetupExchange.awaitReply(middle.near, firstHopSetupId, 5_000) { middle.close() }
        val below = (fromSecond as RelaySetupMessage.Ready).relays
        assertEquals(1, below)

        // H1 forwards with relaysBelow = 1; the caller reads READY with 2 relays.
        val firstFailure = AtomicReference<Throwable?>(null)
        val duty1 = startDuty(callerLink, middle, callerSetupId, relaysBelow = below, failure = firstFailure)
        val fromFirst = RelaySetupExchange.awaitReply(callerLink.near, callerSetupId, 5_000) { callerLink.close() }
        assertEquals(2, (fromFirst as RelaySetupMessage.Ready).relays)

        // Ordered records, caller -> callee, then the callee's answer back.
        val records = (1..6).map { byteArrayOf(it.toByte(), (it * 3).toByte()) }
        records.forEach { callerLink.near.writer.write(SecureRecordType.END_TO_END_CONTENT, it) }
        callerLink.near.writer.flush()
        records.forEach {
            val atCallee = lastLink.far.reader.read()
            assertEquals(SecureRecordType.END_TO_END_CONTENT, atCallee.type)
            assertArrayEquals(it, atCallee.payload)
        }
        val ringing = byteArrayOf(9, 8, 7)
        lastLink.far.writer.write(SecureRecordType.END_TO_END_CONTENT, ringing)
        lastLink.far.writer.flush()
        val atCaller = callerLink.near.reader.read()
        assertArrayEquals(ringing, atCaller.payload)
        assertNull(firstFailure.get())
        assertNull(secondFailure.get())

        // The caller leaving ends H1's duty; its closed child link then ends H2's.
        callerLink.close()
        assertTrue(duty1.finished.await(5, TimeUnit.SECONDS))
        assertTrue(duty1.parentClosed.get())
        middle.close()
        assertTrue(duty2.finished.await(5, TimeUnit.SECONDS))
        assertTrue(duty2.parentClosed.get())
        lastLink.close()
    }

    @Test
    fun theSetupBudgetAndDeadlineLeaveRoomForTwoSlowHops() {
        // SETUP_DEADLINE_MS 20 s: each helper passes on what remains after its own connect/authenticate and the reserve.
        val atFirst = RelayedCalls.nextHopRemainingMs(RelayedCalls.SETUP_DEADLINE_MS, 5_000)!!
        assertEquals(14_000, atFirst)
        assertEquals(8_000, RelayedCalls.nextHopRemainingMs(atFirst, 5_000))
        // Two hops that each need ~9 s leave the last one under the 2 s minimum: a visible FAIL, not a hang.
        assertNull(RelayedCalls.nextHopRemainingMs(RelayedCalls.nextHopRemainingMs(RelayedCalls.SETUP_DEADLINE_MS, 9_000)!!, 9_000))
    }

    @Test
    fun aSetupTheDeadlineAlreadyAnsweredForwardsNothing() {
        val parent = link(caller, helper)
        val child = link(helper, callee)
        val readyCalls = AtomicInteger()
        val failure = AtomicReference<Throwable?>(null)
        val duty = startDuty(
            parent, child, RelaySetupCodec.newSetupId(),
            replied = AtomicBoolean(true), readyCalls = readyCalls, failure = failure,
        )
        assertTrue(duty.finished.await(5, TimeUnit.SECONDS))
        assertEquals(0, readyCalls.get())
        assertNull(failure.get())
        assertFalse("nothing was closed by the stage itself", duty.parentClosed.get())
        parent.close()
        child.close()
    }

    @Test
    fun aCalleeThatLeavesEndsTheDutyAndClosesTheParent() {
        val parent = link(caller, helper)
        val child = link(helper, callee)
        val setupId = RelaySetupCodec.newSetupId()
        val duty = startDuty(parent, child, setupId)
        RelaySetupExchange.awaitReply(parent.near, setupId, 5_000) { parent.close() }
        child.close()
        assertTrue(duty.finished.await(5, TimeUnit.SECONDS))
        assertTrue(duty.parentClosed.get())
        parent.close()
    }

    @Test
    fun anErrorThatEndsTheSetupBecomesARefusedFailNotACrash() {
        val relay = BluetoothPrivateCallRelay(onStatus = {}, onStopped = {})
        for (stage in BluetoothPrivateCallRelay.OnDemandStage.entries) {
            val contained = NoSuchMethodError("java.io.InputStream.nullInputStream()").asRelayDutyException()
            assertTrue(contained is RelayDutyError)
            assertEquals(RelaySetupFailure.REFUSED, relay.onDemandFailure(stage, contained!!))
        }
        // The ordinary reasons are unchanged.
        assertEquals(
            RelaySetupFailure.TIMEOUT,
            relay.onDemandFailure(BluetoothPrivateCallRelay.OnDemandStage.WAITING_FOR_NEXT, IOException("silent")),
        )
        assertEquals(
            RelaySetupFailure.CHILD_AUTHENTICATION,
            relay.onDemandFailure(BluetoothPrivateCallRelay.OnDemandStage.AUTHENTICATING, IllegalArgumentException("wrong phone")),
        )
        assertEquals(
            RelaySetupFailure.NO_ROUTE,
            relay.onDemandFailure(BluetoothPrivateCallRelay.OnDemandStage.CONNECTING, IOException("no connection")),
        )
    }
}
