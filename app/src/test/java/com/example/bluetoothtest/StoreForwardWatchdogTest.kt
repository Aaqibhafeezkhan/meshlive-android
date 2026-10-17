package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dead GATT-server link (b1-mv2, A15s, build f0552884): the peer vanished, and neither close nor interrupt unblocked the
 * session's read or write. The first deadline must end the session for good: logged once, the helper threads gone, the node and
 * the endpoint free for the next exchange. Before, the watchdog logged the same deadline every 50 ms for nine minutes (9,565
 * lines) because the session never finished, and an interrupt of the calling thread skipped the rest of the clean-up.
 */
class StoreForwardWatchdogTest {
    /** A hung exchange fails the test instead of the build. */
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(120)

    private val rig = SfRig()
    private val releases = ArrayList<CountDownLatch>()

    @After
    fun cleanUp() {
        releases.forEach { it.countDown() }
        rig.cleanUp()
    }

    /**
     * A link like the GATT server's after its peer vanished: serves the peer's pre-encoded records, then reads block; writes block
     * once [blockWrites] is set. Both ignore interrupt and [close], and only [release] lets a blocked call go (test clean-up).
     */
    private class DeadLink(peerRecords: ByteArray, private val interruptible: Boolean) {
        val release = CountDownLatch(1)
        @Volatile var blockWrites = false
        val writesBlocked = AtomicInteger()
        val closeCalls = AtomicInteger()
        val interrupts = AtomicInteger()
        private var position = 0
        private val inbound = peerRecords

        private fun blockUntilReleased(): Nothing {
            while (release.count > 0) {
                try {
                    release.await()
                } catch (error: InterruptedException) {
                    interrupts.incrementAndGet()
                    if (interruptible) throw IOException("interrupted")
                }
            }
            throw IOException("released")
        }

        val input = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (position >= inbound.size) blockUntilReleased()
                val count = minOf(len, inbound.size - position)
                System.arraycopy(inbound, position, b, off, count)
                position += count
                return count
            }
        }

        val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (blockWrites) {
                    writesBlocked.incrementAndGet()
                    blockUntilReleased()
                }
            }
        }
    }

    private class Dead(val link: SecureBluetoothSession, val dead: DeadLink)

    private fun chatPair(graceMs: Long = 100): Pair<SfRig.Phone, SfRig.Phone> {
        // A short grace so the test does not wait for the production second.
        val alice = rig.Phone(chat = true, limits = StoreForwardLimits(suspendGraceMs = graceMs))
        val bob = rig.Phone(chat = true)
        rig.contacts(alice, bob)
        return alice to bob
    }

    /** The session under test (responder, chat) plus the dead link, with the peer's HELLO and an empty SUMMARY already queued. */
    private fun deadLink(peer: SfRig.Phone, interruptible: Boolean = false): Dead {
        val keyIn = ByteArray(32).also(SecureRandom()::nextBytes)
        val keyOut = ByteArray(32).also(SecureRandom()::nextBytes)
        val records = ByteArrayOutputStream()
        val peerWriter = SecureRecordWriter(records, keyIn)
        listOf<StoreForwardMessage>(
            rig.hello(2, StoreForwardHelloFlags.FILES or StoreForwardHelloFlags.CHAT),
            StoreForwardMessage.Summary(emptyList(), true),
        ).forEach {
            peerWriter.write(SecureRecordType.STORE_FORWARD, StoreForwardWire.encode(it))
            peerWriter.flush()
        }
        val dead = DeadLink(records.toByteArray(), interruptible)
        releases += dead.release
        val caps = NodeCapabilities(NodeCapabilities.TRANSPORT_CLASSIC, 0, 0, 0, 0)
        val link = SecureBluetoothSession(peer.nodeId, caps, SecureRecordReader(dead.input, keyIn), SecureRecordWriter(dead.output, keyOut))
        link.reader.acceptStoreForward = true
        return Dead(link, dead)
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 60_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < end) { "Timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    private fun watchdogThreads(): Set<Thread> = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name == "StoreForwardWatchdog" }.toSet()

    /**
     * Watchdogs of this test's sessions that are still alive. Identity, not a count: a watchdog left by an earlier test or class
     * (its session finished, the thread is still winding down) is in the baseline, and its later exit must not look like a leak
     * or hide one.
     */
    private fun watchdogsStartedSince(baseline: Set<Thread>): Set<Thread> = watchdogThreads() - baseline

    private fun deadlineLines(logs: List<String>) = synchronized(logs) { logs.count { "message_v2 deadline" in it } }

    @Test
    fun theFirstLivenessDeadlineEndsASessionWhoseReaderAndWriterAreStuckOnTheLink() {
        val (alice, bob) = chatPair()
        val baselineWatchdogs = watchdogThreads()
        val clock = AtomicLong(0)
        val dead = deadLink(bob)
        val logs = ArrayList<String>()
        val session = StoreForwardLinkSession(
            dead.link, alice.node, initiator = false, closeLink = { dead.dead.closeCalls.incrementAndGet() },
            log = { line -> println("S: $line"); synchronized(logs) { logs += line } },
            chatCapable = true, monotonicMs = clock::get,
        )
        var result: StoreForwardSyncResult? = null
        val runner = thread(name = "sf-test-stuck-run") { result = session.run() }
        waitUntil("the chat session to be established") { session.isChat && session.debugState().contains("summaryDone=true") }
        assertTrue(alice.node.exchangeRunning(bob.nodeId))

        // The peer goes silent. The writer's next PING blocks inside the link, the reader is already blocked in it.
        dead.dead.blockWrites = true
        clock.addAndGet(25_000)
        waitUntil("the writer to stick in its PING") { dead.dead.writesBlocked.get() >= 1 }
        clock.addAndGet(60_000)

        runner.join(120_000)
        assertFalse("run() must return although the link ignores close and interrupt", runner.isAlive)
        assertEquals(StoreForwardSyncOutcome.TIMEOUT, result!!.outcome)
        assertTrue("the link was closed", dead.dead.closeCalls.get() >= 1)
        assertTrue("reader and writer were interrupted before being abandoned", dead.dead.interrupts.get() >= 1)
        assertEquals("the deadline is logged once", 1, deadlineLines(logs))
        assertFalse("the node forgot the session", alice.node.exchangeRunning(bob.nodeId))
        assertFalse(alice.node.chatSessionActive(bob.nodeId))

        // Nothing keeps logging or running afterwards.
        Thread.sleep(500)
        assertEquals(1, deadlineLines(logs))
        waitUntil("the stuck session's watchdog to end") { watchdogsStartedSince(baselineWatchdogs).isEmpty() }

        // The node starts the next exchange as if nothing had happened.
        dead.dead.release.countDown()
        val next = rig.open(alice, bob)
        waitUntil("the next chat session") { next.sessionA.isChat && next.sessionB.isChat }
        val sent = bob.engine.sendText(alice.nodeId, "the next exchange works")
        waitUntil("the text to arrive") { alice.engine.threads.count() == 1 }
        waitUntil("the delivery report") { bob.engine.threads.get(sent.id)!!.delivered }
        next.sessionB.endChat()
        val finished = next.join()
        assertEquals(StoreForwardSyncOutcome.COMPLETE, finished.initiator.outcome)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, finished.responder.outcome)
    }

    @Test
    fun anInterruptOfTheCallingThreadStillFinishesTheSessionAndStopsItsHelpers() {
        val (alice, bob) = chatPair()
        val baselineWatchdogs = watchdogThreads()
        val clock = AtomicLong(0)
        val dead = deadLink(bob)
        val logs = ArrayList<String>()
        val session = StoreForwardLinkSession(
            dead.link, alice.node, initiator = false, closeLink = { dead.dead.closeCalls.incrementAndGet() },
            log = { line -> synchronized(logs) { logs += line } },
            chatCapable = true, monotonicMs = clock::get,
        )
        var result: StoreForwardSyncResult? = null
        var interruptedAfter = false
        val runner = thread(name = "sf-test-interrupted-run") {
            result = session.run()
            interruptedAfter = Thread.currentThread().isInterrupted
        }
        waitUntil("the chat session to be established") { session.isChat && session.debugState().contains("summaryDone=true") }

        // The endpoint closes (b1-mv2: connectionThread.interrupt() while run() was joining its writer): run() used to throw.
        runner.interrupt()
        runner.join(120_000)
        assertFalse(runner.isAlive)
        assertEquals(StoreForwardSyncOutcome.INTERRUPTED, result!!.outcome)
        assertTrue("the interrupt is passed on once the clean-up is done", interruptedAfter)
        assertTrue(dead.dead.closeCalls.get() >= 1)
        assertFalse(alice.node.exchangeRunning(bob.nodeId))
        waitUntil("the watchdog to end") { watchdogsStartedSince(baselineWatchdogs).isEmpty() }

        // Time passing afterwards cannot start a watchdog storm: the session is finished.
        clock.addAndGet(10 * 60_000)
        Thread.sleep(300)
        assertEquals(0, deadlineLines(logs))
    }

    @Test
    fun aLinkThatHonoursCloseStillEndsThroughTheSamePath() {
        val (alice, bob) = chatPair(graceMs = 5_000)
        val clock = AtomicLong(0)
        val dead = deadLink(bob, interruptible = true)
        val logs = ArrayList<String>()
        val session = StoreForwardLinkSession(
            dead.link, alice.node, initiator = false, closeLink = { dead.dead.closeCalls.incrementAndGet(); dead.dead.release.countDown() },
            log = { line -> synchronized(logs) { logs += line } },
            chatCapable = true, monotonicMs = clock::get,
        )
        var result: StoreForwardSyncResult? = null
        val runner = thread(name = "sf-test-closable-run") { result = session.run() }
        waitUntil("the chat session to be established") { session.isChat && session.debugState().contains("summaryDone=true") }
        clock.addAndGet(61_000)
        runner.join(120_000)
        assertFalse(runner.isAlive)
        assertEquals(StoreForwardSyncOutcome.TIMEOUT, result!!.outcome)
        assertEquals(1, deadlineLines(logs))
        assertEquals("a link that closes needs no abandon", 0, synchronized(logs) { logs.count { "abandoned" in it } })
    }
}
