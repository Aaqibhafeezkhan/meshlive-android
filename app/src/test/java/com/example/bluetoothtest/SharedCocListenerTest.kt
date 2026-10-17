package com.example.bluetoothtest

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Android 10 never stops a closed LE CoC server (btsock_l2cap_free_l has no BTA_JvL2capStopServer),
 * so every closed listener leaked a stack registration until the A15s refused all listens. These
 * cover the shared per-role listener that replaced one listener per session.
 */
class SharedCocListenerTest {
    /** Safety net: a test that hangs fails after this instead of holding the whole run (a legitimate run on a loaded host takes seconds to a minute or two). */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private class FakeSocket(val id: Int) : Closeable {
        @Volatile var closed = false
        override fun close() {
            closed = true
        }
    }

    private class FakeServer(override val psm: Int) : CocServerHandle<FakeSocket> {
        val incoming = LinkedBlockingQueue<Any>()
        @Volatile var closed = false

        override fun accept(): FakeSocket {
            val item = incoming.take()
            if (item is IOException) {
                incoming.offer(item)
                throw item
            }
            return item as FakeSocket
        }

        fun connect(socket: FakeSocket) = incoming.offer(socket)

        override fun close() {
            closed = true
            incoming.offer(IOException("server closed"))
        }
    }

    private val servers = CopyOnWriteArrayList<FakeServer>()
    private val logs = CopyOnWriteArrayList<String>()
    private val listener = SharedCocListener(
        "private-call",
        open = { FakeServer(0x80 + servers.size).also { servers += it } },
        log = { logs += it },
    )

    @Test
    fun successiveSessionsReuseOneStackListenerAndPsm() {
        val first = requireNotNull(listener.acquire("call-1"))
        val psm = first.psm
        first.close()
        repeat(20) { index ->
            val lease = requireNotNull(listener.acquire("call-${index + 2}"))
            assertEquals(psm, lease.psm)
            assertTrue(lease.shared)
            lease.close()
        }
        assertEquals("21 sessions cost one stack listen", 1, listener.listenCount)
        assertEquals(1, servers.size)
        assertFalse(servers.single().closed)
    }

    @Test
    fun acceptedConnectionGoesToTheCurrentSession() {
        val lease = requireNotNull(listener.acquire("callee"))
        val socket = FakeSocket(1)
        servers.single().connect(socket)
        assertSame(socket, lease.accept(120_000))
        assertFalse(socket.closed)
        lease.close()
    }

    @Test
    fun connectionWithNoSessionIsClosedLikeARefusedListen() {
        listener.acquire("first")!!.close()
        val stale = FakeSocket(7)
        servers.single().connect(stale)
        waitUntil { stale.closed }
        assertEquals(1, listener.refusedCount)
        val next = requireNotNull(listener.acquire("second"))
        val fresh = FakeSocket(8)
        servers.single().connect(fresh)
        assertSame(fresh, next.accept(2_000))
        next.close()
    }

    @Test
    fun aSecondConcurrentSessionGetsNullAndUsesADedicatedListener() {
        val first = requireNotNull(listener.acquire("availability"))
        assertNull(listener.acquire("store-forward-reopen"))
        assertTrue(logs.any { it.contains("busy") })
        first.close()
        assertNotNull(listener.acquire("after-release"))
    }

    @Test
    fun closingTheLeaseWakesABlockedAcceptWithoutClosingTheStackListener() {
        val lease = requireNotNull(listener.acquire("call"))
        val failure = arrayOfNulls<Throwable>(1)
        val done = CountDownLatch(1)
        thread {
            try {
                lease.accept()
            } catch (error: Throwable) {
                failure[0] = error
            }
            done.countDown()
        }
        Thread.sleep(50)
        lease.close()
        assertTrue(done.await(120, TimeUnit.SECONDS))
        assertTrue(failure[0] is IOException)
        assertFalse(servers.single().closed)
        assertTrue(listener.listening)
    }

    @Test
    fun queuedButUnacceptedConnectionsAreClosedWithTheLease() {
        val lease = requireNotNull(listener.acquire("call"))
        val queued = FakeSocket(3)
        servers.single().connect(queued)
        Thread.sleep(100)
        lease.close()
        waitUntil { queued.closed }
    }

    @Test
    fun acceptTimeoutThrows() {
        val lease = requireNotNull(listener.acquire("receiver"))
        try {
            lease.accept(50)
            fail("expected a timeout")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("timed out"))
        }
        lease.close()
    }

    @Test
    fun listenerFailureFailsTheSessionAndTheNextSessionListensAgain() {
        val lease = requireNotNull(listener.acquire("call"))
        servers.single().incoming.offer(IOException("Bluetooth off"))
        try {
            lease.accept(120_000)
            fail("expected the listener failure")
        } catch (expected: IOException) {
            assertEquals("Bluetooth off", expected.message)
        }
        lease.close()
        waitUntil { !listener.listening }
        val next = requireNotNull(listener.acquire("after-restart"))
        assertEquals(2, listener.listenCount)
        assertEquals(servers[1].psm, next.psm)
        next.close()
    }

    @Test
    fun aFailedListenerFreesTheRoleEvenBeforeTheOldSessionCloses() {
        val stale = requireNotNull(listener.acquire("old-session"))
        servers.single().incoming.offer(IOException("l2cap listen dropped"))
        waitUntil { !listener.listening }
        val next = requireNotNull(listener.acquire("new-session"))
        stale.close()
        assertNotNull("closing the stale lease keeps the new one", next)
        assertNull(listener.acquire("third"))
        next.close()
    }

    @Test
    fun aConnectionAcceptedDuringAnEarlierSessionNeverReachesTheNextOne() {
        val first = requireNotNull(listener.acquire("call-1"))
        val acceptedWhileFirst = listener.currentGeneration
        first.close()
        val second = requireNotNull(listener.acquire("call-2"))
        val late = FakeSocket(11)
        listener.deliver(servers.single(), late, acceptedWhileFirst)
        assertTrue("delivered after the session changed: closed", late.closed)
        assertEquals(1, listener.refusedCount)
        try {
            second.accept(50)
            fail("the second session must not receive it")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("timed out"))
        }
        second.close()
    }

    @Test
    fun aConnectionAcceptedBetweenSessionsIsClosed() {
        listener.acquire("call-1")!!.close()
        val between = listener.currentGeneration
        val next = requireNotNull(listener.acquire("call-2"))
        val socket = FakeSocket(12)
        listener.deliver(servers.single(), socket, between)
        assertTrue(socket.closed)
        next.close()
    }

    @Test
    fun aQueuedConnectionIsNotReturnedAfterTheListenerFails() {
        val lease = requireNotNull(listener.acquire("receiver"))
        val queued = FakeSocket(13)
        servers.single().connect(queued)
        waitUntil { listener.refusedCount == 0 && !queued.closed }
        Thread.sleep(50)
        servers.single().incoming.offer(IOException("listener dropped"))
        waitUntil { !listener.listening }
        try {
            lease.accept(120_000)
            fail("a failed lease must not hand out its queued connection")
        } catch (expected: IOException) {
            assertEquals("listener dropped", expected.message)
        }
        waitUntil { queued.closed }
        lease.close()
    }

    @Test
    fun adapterResetDropsTheListenerSoTheNextLeaseListensAgain() {
        listener.acquire("call")!!.close()
        listener.reset("adapter_off")
        assertTrue(servers.single().closed)
        assertFalse(listener.listening)
        requireNotNull(listener.acquire("call")).close()
        assertEquals(2, listener.listenCount)
    }

    @Test
    fun openFailureLeavesNoListenerAndNoLease() {
        val failing = SharedCocListener<FakeSocket>(
            "receiver",
            open = { throw IOException("Error starting l2cap_listen") },
            log = {},
        )
        try {
            failing.acquire("stream")
            fail("expected the listen failure")
        } catch (expected: IOException) {
            assertEquals("Error starting l2cap_listen", expected.message)
        }
        assertFalse(failing.listening)
        assertEquals(0, failing.listenCount)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("condition not reached")
            Thread.sleep(5)
        }
    }
}
