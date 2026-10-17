package com.example.bluetoothtest

/** A manual clock with scheduled actions, for the batch P tests (no Android, no sleeping). */
internal class FakeTime(var now: Long = 1_000L) {
    private class Timer(val due: Long, val seq: Int, val action: () -> Unit)

    private val timers = ArrayList<Timer>()
    private var seq = 0

    fun schedule(delayMs: Long, action: () -> Unit) {
        timers += Timer(now + delayMs, seq++, action)
    }

    val pending: Int get() = timers.size

    /** Moves the clock forward, running every action that falls due in order (actions may schedule more). */
    fun advance(ms: Long) {
        val target = now + ms
        while (true) {
            val next = timers.filter { it.due <= target }.minWithOrNull(compareBy<Timer>({ it.due }, { it.seq })) ?: break
            timers.remove(next)
            now = maxOf(now, next.due)
            next.action()
        }
        now = target
    }
}
