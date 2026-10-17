package com.example.bluetoothtest

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AudioLiveChildHealth] keeps running totals of its 10 s window instead of adding the window up for every
 * record. The totals must give exactly the verdicts of the straightforward implementation, whatever the
 * sequence of events, including time that stands still, jumps, or goes backwards.
 */
class AudioLiveChildHealthEquivalenceTest {
    /** The original implementation: sums the whole window on every verdict. */
    private class Reference {
        class Sample(val atMs: Long, val queued: Int, val shed: Int)

        private val samples = ArrayDeque<Sample>()

        fun onQueued(nowMs: Long) {
            samples += Sample(nowMs, 1, 0)
            prune(nowMs)
        }

        fun onShed(count: Int, nowMs: Long) {
            if (count <= 0) return
            samples += Sample(nowMs, 0, count)
            prune(nowMs)
        }

        fun verdict(oldestQueuedAgeMs: Long, nowMs: Long): AudioLiveChildHealth.Reason? {
            prune(nowMs)
            if (oldestQueuedAgeMs > AudioLive.WITHDRAW_AGE_MS) return AudioLiveChildHealth.Reason.QUEUE_AGE
            val queued = samples.sumOf { it.queued }
            val shed = samples.sumOf { it.shed }
            if (queued >= AudioLiveChildHealth.MIN_RECORDS && shed > queued * AudioLive.WITHDRAW_SHED_RATE) {
                return AudioLiveChildHealth.Reason.SHED_RATE
            }
            return null
        }

        private fun prune(nowMs: Long) {
            while (samples.isNotEmpty() && nowMs - samples.first().atMs > AudioLive.SHED_RATE_WINDOW_MS) samples.removeFirst()
        }
    }

    private fun run(seed: Long, steps: Int, maxStepMs: Int, backwards: Boolean) {
        val random = Random(seed)
        val reference = Reference()
        val health = AudioLiveChildHealth()
        var now = 1_000_000L
        var verdicts = 0
        var nonNull = 0
        repeat(steps) { step ->
            now += when (random.nextInt(10)) {
                0 -> random.nextInt(maxStepMs * 20).toLong() // a long gap now and then
                1 -> if (backwards) -random.nextInt(2_000).toLong() else 0L
                else -> random.nextInt(maxStepMs + 1).toLong()
            }
            when (random.nextInt(4)) {
                0, 1 -> {
                    reference.onQueued(now)
                    health.onQueued(now)
                }
                2 -> {
                    val count = random.nextInt(0, 12) - 1 // includes 0 and a negative count (ignored)
                    reference.onShed(count, now)
                    health.onShed(count, now)
                }
                else -> {
                    val age = if (random.nextInt(20) == 0) random.nextLong(25_000, 40_000) else random.nextLong(0, 5_000)
                    val expected = reference.verdict(age, now)
                    assertEquals("seed $seed step $step now $now age $age", expected, health.verdict(age, now))
                    verdicts++
                    if (expected != null) nonNull++
                }
            }
        }
        // The scenarios are only useful if both outcomes occur.
        assert(verdicts > 0)
        if (steps >= 5_000 && maxStepMs <= 200) assert(nonNull > 0) { "no verdict was ever positive for seed $seed" }
    }

    @Test
    fun runningTotalsGiveTheVerdictsOfTheDirectSumOnRandomStreams() {
        for (seed in 1L..60L) run(seed, steps = 3_000, maxStepMs = 200, backwards = false)
    }

    @Test
    fun theSameHoldsWhenTimeJumpsAndEvenGoesBackwards() {
        for (seed in 100L..140L) run(seed, steps = 3_000, maxStepMs = 4_000, backwards = true)
    }

    @Test
    fun theSameHoldsAtTheRecordRateOfARealSourceWithAStalledChild() {
        // 5 records/s, a child that never drains: sheds start after 3 s and the verdict turns positive.
        for (seed in 200L..210L) run(seed, steps = 20_000, maxStepMs = 150, backwards = false)
    }

    @Test
    fun theWindowTotalsReturnToZeroWhenEverythingExpires() {
        val health = AudioLiveChildHealth()
        repeat(500) { health.onQueued(it * 20L) }
        health.onShed(400, 10_000)
        assertEquals(AudioLiveChildHealth.Reason.SHED_RATE, health.verdict(0, 10_000))
        // Everything is older than the window: no verdict until MIN_RECORDS are queued again.
        assertEquals(null, health.verdict(0, 100_000))
        repeat(AudioLiveChildHealth.MIN_RECORDS - 1) { health.onQueued(100_000) }
        health.onShed(AudioLiveChildHealth.MIN_RECORDS, 100_000)
        assertEquals(null, health.verdict(0, 100_000)) // 9 queued: too few to judge
        health.onQueued(100_000)
        assertEquals(AudioLiveChildHealth.Reason.SHED_RATE, health.verdict(0, 100_000))
    }
}
