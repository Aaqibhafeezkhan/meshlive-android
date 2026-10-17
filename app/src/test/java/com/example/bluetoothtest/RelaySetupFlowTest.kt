package com.example.bluetoothtest

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The setup exchange over a real BMS1 record layer: the caller's request and what a helper may answer. */
class RelaySetupFlowTest {
    /** Safety net: a test that hangs fails after this instead of holding the whole run (a legitimate run on a loaded host takes seconds to a minute or two). */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private class Pipe {
        private val queue = java.util.concurrent.LinkedBlockingQueue<Int>()
        @Volatile var closed = false
        val input = object : InputStream() {
            override fun read(): Int {
                while (true) {
                    val next = queue.poll(20, TimeUnit.MILLISECONDS)
                    if (next != null) return if (next < 0) throw IOException("Pipe closed") else next
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

    private class Link(val caller: SecureBluetoothSession, val helper: SecureBluetoothSession, val pipes: List<Pipe>) {
        fun close() = pipes.forEach(Pipe::close)
    }

    private val helperNode = NodeId.fromBytes(ByteArray(16) { (0x10 + it).toByte() })
    private val callerNode = NodeId.fromBytes(ByteArray(16) { (0x70 + it).toByte() })
    private val target = byteArrayOf(1, 2, 3, 4, 5, 6)
    private val helperPrefix = helperNode.toByteArray().copyOf(6)

    private val helperCaps = RelayedCalls.linkCapabilities(
        NodeCapabilities(NodeCapabilities.TRANSPORT_BLE_L2CAP, 3, 3, 500, 0), featureOn = true, helperActive = true,
    )
    private val callerCaps = RelayedCalls.linkCapabilities(
        NodeCapabilities(NodeCapabilities.TRANSPORT_BLE_L2CAP, 3, 3, 500, 0), featureOn = true, helperActive = false,
    )

    private fun link(helperHello: NodeCapabilities = helperCaps, helperId: NodeId = helperNode): Link {
        val toHelper = Pipe()
        val toCaller = Pipe()
        val keyUp = ByteArray(32).also(SecureRandom()::nextBytes)
        val keyDown = ByteArray(32).also(SecureRandom()::nextBytes)
        val caller = SecureBluetoothSession(
            helperId, helperHello, SecureRecordReader(toCaller.input, keyDown), SecureRecordWriter(toHelper.output, keyUp),
        )
        val helper = SecureBluetoothSession(
            callerNode, callerCaps, SecureRecordReader(toHelper.input, keyUp), SecureRecordWriter(toCaller.output, keyDown),
        )
        return Link(caller, helper, listOf(toHelper, toCaller))
    }

    private fun route(relays: Int = 2) = RelayRoute(target, helperPrefix, relays)

    /** Plays a helper: reads the SETUP (hop type 12 for that read only) and answers with [reply]. */
    private fun helper(link: Link, reply: (RelaySetupMessage.Setup) -> RelaySetupMessage?): Thread = thread(isDaemon = true) {
        try {
            link.helper.reader.acceptRelaySetup = true
            val record = link.helper.reader.read()
            val setup = RelaySetupCodec.decode(record.payload) as RelaySetupMessage.Setup
            reply(setup)?.let { RelaySetupExchange.writeMessage(link.helper, it) }
        } catch (_: Exception) {
            // the test closes the link
        }
    }

    @Test
    fun aReadyReplyGivesTheRelayCountAndCarriesTheDocumentedSetup() {
        val link = link()
        var seen: RelaySetupMessage.Setup? = null
        helper(link) { setup ->
            seen = setup
            RelaySetupMessage.Ready(setup.setupId, 2)
        }
        val relays = RelaySetupExchange.requestRoute(link.caller, route(2), 18_000) { link.close() }
        assertEquals(2, relays)
        val setup = requireNotNull(seen)
        assertEquals(2, setup.budget)
        assertEquals(18_000, setup.remainingMs)
        assertTrue(setup.target.contentEquals(target))
        assertFalse("reader opt-in is closed again", link.caller.reader.acceptRelaySetup)
        link.close()
    }

    @Test
    fun aFailReplyBecomesAFailedCandidateWithItsReason() {
        for (reason in RelaySetupFailure.entries) {
            val link = link()
            helper(link) { RelaySetupMessage.Fail(it.setupId, reason) }
            try {
                RelaySetupExchange.requestRoute(link.caller, route(), 18_000) { link.close() }
                fail("expected a failure for $reason")
            } catch (error: RelaySetupFailedException) {
                assertEquals(reason, error.reason)
                assertEquals(reason.userText, error.message)
            } finally {
                link.close()
            }
        }
    }

    @Test
    fun aReadyThatClaimsMoreRelaysThanAskedForIsRefused() {
        val link = link()
        helper(link) { RelaySetupMessage.Ready(it.setupId, 3) }
        try {
            RelaySetupExchange.requestRoute(link.caller, route(2), 18_000) { link.close() }
            fail("expected a refusal")
        } catch (error: RelaySetupFailedException) {
            assertNull(error.reason)
        } finally {
            link.close()
        }
    }

    @Test
    fun aReplyForAnotherRequestOrAnotherMessageIsInvalid() {
        for (wrong in listOf<(RelaySetupMessage.Setup) -> RelaySetupMessage>(
            { RelaySetupMessage.Ready(ByteArray(8) { 9 }, 1) },
            { RelaySetupMessage.Setup(it.target, 1, 5_000, it.setupId) },
        )) {
            val link = link()
            helper(link, wrong)
            try {
                RelaySetupExchange.requestRoute(link.caller, route(), 18_000) { link.close() }
                fail("expected a failure")
            } catch (error: RelaySetupFailedException) {
                assertNull(error.reason)
            } finally {
                link.close()
            }
        }
    }

    @Test
    fun theFirstHopMustBeTheHelperThatWasSelected() {
        // The phone that answered authenticated under another key than the selected helper's prefix.
        val other = NodeId.fromBytes(ByteArray(16) { (0xa0 + it).toByte() })
        val link = link(helperId = other)
        var asked = false
        helper(link) { asked = true; null }
        try {
            RelaySetupExchange.requestRoute(link.caller, route(), 18_000) { link.close() }
            fail("expected a failure")
        } catch (error: RelaySetupFailedException) {
            assertEquals(RelaySetupFailure.CHILD_AUTHENTICATION, error.reason)
        } finally {
            link.close()
        }
        assertFalse("nothing is sent to a phone that is not the selected helper", asked)
    }

    @Test
    fun aFirstHopWithoutTheHelperMarksIsNeverAsked() {
        val plain = NodeCapabilities(NodeCapabilities.TRANSPORT_BLE_L2CAP, 3, 3, 500, 0)
        val link = link(helperHello = plain)
        var asked = false
        helper(link) { asked = true; null }
        try {
            RelaySetupExchange.requestRoute(link.caller, route(), 18_000) { link.close() }
            fail("expected a failure")
        } catch (error: RelaySetupFailedException) {
            assertEquals(RelaySetupFailure.REFUSED, error.reason)
        } finally {
            link.close()
        }
        assertFalse(asked)
    }

    @Test
    fun noTimeLeftFailsBeforeAnythingIsSent() {
        val link = link()
        try {
            RelaySetupExchange.requestRoute(link.caller, route(), RelayedCalls.MIN_REMAINING_MS - 1) { link.close() }
            fail("expected a failure")
        } catch (error: RelaySetupFailedException) {
            assertEquals(RelaySetupFailure.TIMEOUT, error.reason)
        } finally {
            link.close()
        }
    }

    @Test
    fun aSilentHelperIsAbandonedByTheDeadlineWatchdog() {
        val link = link()
        val closed = AtomicBoolean(false)
        val done = CountDownLatch(1)
        thread(isDaemon = true) {
            try {
                RelaySetupExchange.awaitReply(link.caller, ByteArray(8) { 1 }, 150) {
                    closed.set(true)
                    link.close()
                }
                fail("a reply was not expected")
            } catch (_: IOException) {
                done.countDown()
            }
        }
        assertTrue(done.await(120, TimeUnit.SECONDS))
        assertTrue("the link was closed by the watchdog", closed.get())
        assertFalse(link.caller.reader.acceptRelaySetup)
    }

    @Test
    fun theReplyReaderIsOptInAndAnOrdinaryReaderRejectsType12() {
        val link = link()
        RelaySetupExchange.writeMessage(link.helper, RelaySetupMessage.Ready(ByteArray(8), 1))
        val error = try {
            link.caller.reader.read()
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertNotNull(error)
        assertEquals("Unknown secure record type 12", error!!.message)
        link.close()
    }

    // ----- the chain guard -----

    @Test
    fun theChainGuardWantsAHandshakeWithinTenSecondsAndTheCalleesAnswerWithinTwenty() {
        val ready = 1_000_000_000L
        fun at(ms: Long) = ready + ms * 1_000_000L
        val guard = RelayChainGuard(ready)
        assertNull(guard.violation(at(9_999)))
        assertNotNull(guard.violation(at(10_001)))
        // Heartbeats prove nothing.
        guard.onRecord(fromCaller = true, SecureRecordType.LINK_HEARTBEAT)
        guard.onRecord(fromCaller = false, SecureRecordType.LINK_HEARTBEAT)
        assertNotNull(guard.violation(at(10_001)))

        val handshake = RelayChainGuard(ready)
        handshake.onRecord(fromCaller = true, SecureRecordType.END_TO_END_HANDSHAKE)
        assertNull(handshake.violation(at(19_999)))
        assertNotNull(handshake.violation(at(20_001)))
        assertFalse(handshake.satisfied)

        // Caller-originated content (an empty key-frame request, an Invite sent to nobody) satisfies nothing: only
        // the callee, which the caller cannot forge, can answer.
        handshake.onRecord(fromCaller = true, SecureRecordType.END_TO_END_CONTENT)
        assertNotNull(handshake.violation(at(20_001)))
        assertFalse(handshake.satisfied)
        handshake.onRecord(fromCaller = false, SecureRecordType.END_TO_END_CONTENT)
        assertNull(handshake.violation(at(600_000)))
        assertTrue(handshake.satisfied)
    }
}
