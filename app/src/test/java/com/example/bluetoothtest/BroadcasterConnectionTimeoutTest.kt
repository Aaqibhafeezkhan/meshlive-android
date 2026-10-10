package com.example.bluetoothtest

import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Test

class BroadcasterConnectionTimeoutTest {
    private class Attempt : Closeable {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test
    fun oldAttemptTimeoutCannotCloseAReplacementWithTheSameNodeIdentity() {
        val old = Attempt()
        val replacement = Attempt()

        broadcasterTimedOutAttempts(mapOf("node" to old), mapOf("node" to replacement), emptySet())
            .forEach(Attempt::close)

        assertEquals(0, old.closes)
        assertEquals(0, replacement.closes)
    }

    @Test
    fun timeoutClosesOnlyItsOwnUnconnectedAttempt() {
        val pending = Attempt()
        val connected = Attempt()
        val newer = Attempt()
        val detached = Attempt()
        val replacement = Attempt()
        val attempts = mapOf("pending" to pending, "connected" to connected,
            "replaced" to newer, "detached" to detached)
        val current = mapOf("pending" to pending, "connected" to connected, "replaced" to replacement)

        broadcasterTimedOutAttempts(attempts, current, setOf("connected")).forEach(Attempt::close)

        assertEquals(1, pending.closes)
        assertEquals(0, connected.closes)
        assertEquals(0, newer.closes)
        assertEquals(0, detached.closes)
        assertEquals(0, replacement.closes)
    }

    @Test
    fun duplicateScanWithNoNewAttemptCannotCloseAnyCurrentConnection() {
        val current = Attempt()

        broadcasterTimedOutAttempts(emptyMap(), mapOf("node" to current), emptySet()).forEach(Attempt::close)

        assertEquals(0, current.closes)
    }
}
