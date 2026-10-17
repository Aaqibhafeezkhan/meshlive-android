package com.example.bluetoothtest

import java.util.concurrent.LinkedBlockingQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay of the b8 F / b10 3-asus-src picture: an ASUS source whose 2M link wrote 48 to 74 kbps queued 6.7 s of video before the
 * one-PDU rule moved the link to 1M. With the experiment on, that backlog is dropped once, codec-safely.
 */
class PhyMoveSkipTest {
    private data class Item(val cls: ShedClass, val ageMs: Long, val label: String)

    private val playoutMs = 2_000L // Auto profile 250 ms base plus the 1750 ms the viewer reported in F

    /** Oldest first: a keyframe at 6.7 s, then inter frames every 67 ms, audio interleaved, the newest frame at 0 ms. */
    private fun backlog(freshKeyFrameAtMs: Long? = null): List<Item> {
        val items = ArrayList<Item>()
        var age = 6_700L
        var n = 0
        items += Item(ShedClass.KEEP, age + 1, "config")
        items += Item(ShedClass.KEY_FRAME, age, "key-old")
        while (age > 0) {
            age -= 67
            items += Item(
                if (freshKeyFrameAtMs != null && age in (freshKeyFrameAtMs - 33)..(freshKeyFrameAtMs + 33)) ShedClass.KEY_FRAME else ShedClass.VIDEO,
                age.coerceAtLeast(0),
                "v$age",
            )
            if (++n % 3 == 0) items += Item(ShedClass.KEEP, age.coerceAtLeast(0), "audio$age")
        }
        return items
    }

    private fun plan(items: List<Item>) =
        planCodecSafeShed(items, { it.cls }, { it.ageMs <= playoutMs })

    @Test
    fun theSixPointSevenSecondBacklogIsAboveTheThresholdAndAFreshOneIsNot() {
        assertEquals(4_000L, PhyMoveSkip.thresholdMs(playoutMs))
        assertTrue(PhyMoveSkip.shouldSkip(true, true, 6_700L, playoutMs))
        assertFalse(PhyMoveSkip.shouldSkip(true, true, 4_000L, playoutMs))
        assertFalse(PhyMoveSkip.shouldSkip(true, true, 900L, playoutMs))
        // A viewer that grew its playout to 3 s raises the bar to 6 s; a tiny playout never lowers it below 4 s.
        assertEquals(6_000L, PhyMoveSkip.thresholdMs(3_000L))
        assertEquals(4_000L, PhyMoveSkip.thresholdMs(250L))
    }

    @Test
    fun offCallsAndAudioLiveNeverSkip() {
        assertFalse(PhyMoveSkip.shouldSkip(enabled = false, isStream = true, oldestVideoAgeMs = 65_000L, viewerPlayoutMs = playoutMs))
        assertFalse(PhyMoveSkip.shouldSkip(enabled = true, isStream = false, oldestVideoAgeMs = 65_000L, viewerPlayoutMs = playoutMs))
    }

    @Test
    fun withNoFreshKeyframeAllQueuedVideoGoesAndAudioAndConfigStay() {
        val items = backlog()
        val result = plan(items)
        assertTrue(result.awaitKeyFrame)
        val dropped = result.drop.map { items[it] }
        assertTrue(dropped.all { it.cls == ShedClass.VIDEO || it.cls == ShedClass.KEY_FRAME })
        assertEquals(items.count { it.cls != ShedClass.KEEP }, dropped.size)
        // The queue that remains is exactly the records that must survive a skip.
        val kept = items.filterIndexed { i, _ -> i !in result.drop }
        assertTrue(kept.all { it.cls == ShedClass.KEEP })
        assertTrue(kept.any { it.label == "config" })
        assertTrue(kept.any { it.label.startsWith("audio") })
    }

    @Test
    fun aKeyframeStillInsideThePlayoutIsTheResumePoint() {
        val items = backlog(freshKeyFrameAtMs = 1_200L)
        val result = plan(items)
        assertFalse(result.awaitKeyFrame)
        val firstKept = items.indices.first { it !in result.drop && items[it].cls != ShedClass.KEEP }
        assertEquals(ShedClass.KEY_FRAME, items[firstKept].cls)
        assertTrue(items[firstKept].ageMs <= playoutMs)
        // Everything after the keyframe stays decodable; nothing older than it is left.
        assertTrue(items.indices.filter { it !in result.drop && items[it].cls == ShedClass.VIDEO }.all { it > firstKept })
    }

    @Test
    fun shedAtomicallyRemovesTheSameRecordsFromARealQueue() {
        val items = backlog()
        val queue = LinkedBlockingQueue(items)
        val (plan, removed) = shedAtomically(Object(), queue, { it.cls }, { it.ageMs <= playoutMs })
        assertTrue(plan.awaitKeyFrame)
        assertEquals(items.count { it.cls != ShedClass.KEEP }, removed.size)
        assertTrue(queue.all { it.cls == ShedClass.KEEP })
    }

    @Test
    fun afterADropWithoutAKeyframeInterFramesAreDroppedUntilOneArrives() {
        val wait = PhyMoveSkip.AwaitKeyFrame()
        assertFalse(wait.dropArrivingVideo(keyFrame = false, nowMs = 0))
        wait.arm(1_000L)
        assertTrue(wait.dropArrivingVideo(keyFrame = false, nowMs = 1_100L))
        assertTrue(wait.dropArrivingVideo(keyFrame = false, nowMs = 2_000L))
        assertFalse(wait.dropArrivingVideo(keyFrame = true, nowMs = 2_500L))
        assertFalse(wait.armed)
        assertFalse(wait.dropArrivingVideo(keyFrame = false, nowMs = 2_600L))
    }

    @Test
    fun theWaitForAKeyframeIsBounded() {
        val wait = PhyMoveSkip.AwaitKeyFrame()
        wait.arm(0L)
        assertTrue(wait.dropArrivingVideo(keyFrame = false, nowMs = PhyMoveSkip.AWAIT_KEY_FRAME_MAX_MS))
        assertFalse(wait.dropArrivingVideo(keyFrame = false, nowMs = PhyMoveSkip.AWAIT_KEY_FRAME_MAX_MS + 1))
        assertFalse(wait.armed)
    }

    // --- flood guard (b1-a5a8f056 stalled2m d2): 17 s of audio and 20 s of video queued at the move ---

    @Test
    fun theFloodGuardFiresOnlyForStaleMediaOnAStream() {
        assertTrue(PhyMoveSkip.floodGuard(true, true, oldestVideoAgeMs = 18_000L, oldestAudioAgeMs = 10_000L))
        assertTrue(PhyMoveSkip.floodGuard(true, true, oldestVideoAgeMs = 500L, oldestAudioAgeMs = 17_700L))
        assertFalse(PhyMoveSkip.floodGuard(true, true, oldestVideoAgeMs = 8_000L, oldestAudioAgeMs = 7_999L))
        // The first one-PDU moves seen (F: 6.7 s queue) are below it: only the experiment would skip those.
        assertFalse(PhyMoveSkip.floodGuard(true, true, oldestVideoAgeMs = 6_700L, oldestAudioAgeMs = 100L))
        assertFalse(PhyMoveSkip.floodGuard(false, true, 26_000L, 17_000L))
        assertFalse(PhyMoveSkip.floodGuard(true, false, 26_000L, 17_000L))
    }

    private data class Rec(val audio: Boolean, val ageMs: Long)

    @Test
    fun theGuardRemovesAudioOlderThanThePlayoutAndKeepsTheNewestSeconds() {
        // 17 s of 20 ms Opus records at 50 per second, oldest first, plus the two newest seconds.
        val queue = java.util.concurrent.LinkedBlockingQueue<Rec>()
        for (i in 0 until 850) queue.add(Rec(audio = true, ageMs = 17_000L - i * 20L))
        queue.add(Rec(audio = false, ageMs = 5_000L))
        val removed = PhyMoveSkip.removeStale(queue) { it.audio && it.ageMs > playoutMs }
        assertEquals(750, removed.size) // everything older than 2 s
        assertTrue(queue.filter { it.audio }.all { it.ageMs <= playoutMs })
        assertEquals(1, queue.count { !it.audio })
    }

    @Test
    fun theSkipIsOnByDefaultOnlyForAFixedRateStream() {
        // Q-SKIP1 (2026-10-14): fixed (manual) bitrate on; Auto quality and audio-only live (fixedRateStream false) off.
        assertTrue(PhyMoveSkip.enabled(override = null, fixedRateStream = true))
        assertFalse(PhyMoveSkip.enabled(override = null, fixedRateStream = false))
    }

    @Test
    fun anExplicitSwitchWinsEitherWay() {
        // test_phy_move_skip_backlog=false is the benchmark control arm; =true forces it on even under Auto quality.
        assertFalse(PhyMoveSkip.enabled(override = false, fixedRateStream = true))
        assertTrue(PhyMoveSkip.enabled(override = true, fixedRateStream = false))
    }

    @Test
    fun aCallNeverSkipsWhateverTheDefault() {
        val on = PhyMoveSkip.enabled(override = null, fixedRateStream = true)
        assertFalse(PhyMoveSkip.shouldSkip(enabled = on, isStream = false, oldestVideoAgeMs = 65_000L, viewerPlayoutMs = playoutMs))
    }
}
