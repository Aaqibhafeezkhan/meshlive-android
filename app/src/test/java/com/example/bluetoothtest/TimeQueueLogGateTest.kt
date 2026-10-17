package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * lane10 b5-q31-drain-193409 (S24 -> ASUS -> A15s): `relay_time_queue` came 1-2 times per 3 minutes because the writer
 * loop logs only after a write returns. The gate adds lines on a change of drainSource or overload and at least every
 * 10 s while the queue is non-empty.
 */
class TimeQueueLogGateTest {
    private val second = 1_000_000_000L

    @Test
    fun aNonEmptyQueueLogsEveryTenSecondsEvenWhileTheWriterIsBlocked() {
        val gate = TimeQueueLogGate()
        assertEquals("periodic", gate.trigger(0, queueNonEmpty = true, source = "train", overload = false))
        for (t in 1..9) assertNull("t=$t", gate.trigger(t * second, true, "train", false))
        assertEquals("periodic", gate.trigger(10 * second, true, "train", false))
        assertNull(gate.trigger(19 * second, true, "train", false))
        assertEquals("periodic", gate.trigger(20 * second, true, "train", false))
    }

    @Test
    fun anEmptyQueueIsQuiet() {
        val gate = TimeQueueLogGate()
        for (t in 0..60) assertNull(gate.trigger(t * second, queueNonEmpty = false, source = "none", overload = false))
    }

    @Test
    fun aChangeOfDrainSourceOrOverloadLogsAtOnce() {
        val gate = TimeQueueLogGate()
        gate.trigger(0, true, "none", false)
        assertEquals("change", gate.trigger(1 * second, true, "train", false))
        assertNull(gate.trigger(2 * second, true, "train", false))
        assertEquals("change", gate.trigger(3 * second, true, "sustained", false))
        assertEquals("change", gate.trigger(4 * second, true, "sustained", true))
        // A change logs even when the queue has just emptied.
        assertEquals("change", gate.trigger(5 * second, false, "sustained", false))
    }

    @Test
    fun theWriterLoopsOwnLineCountsTowardTheTenSeconds() {
        val gate = TimeQueueLogGate()
        gate.noteLogged(0, "train", false)
        assertNull(gate.trigger(5 * second, true, "train", false))
        gate.noteLogged(8 * second, "train", false)
        assertNull(gate.trigger(17 * second, true, "train", false))
        assertEquals("periodic", gate.trigger(18 * second, true, "train", false))
        // The writer loop's state change is a change the timer would otherwise report again.
        gate.noteLogged(19 * second, "sustained", false)
        assertNull(gate.trigger(20 * second, true, "sustained", false))
    }
}
