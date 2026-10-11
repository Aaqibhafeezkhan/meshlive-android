package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hold-Mute-to-talk (HoldToTalk.kt): the press/hold/release rules, with a hand-driven scheduler. */
class HoldToTalkCoreTest {
    private class ManualScheduler : HoldToTalkCore.Scheduler {
        var pending: (() -> Unit)? = null
        var pendingDelay = 0L
        override fun schedule(delayMs: Long, action: () -> Unit) {
            pending = action
            pendingDelay = delayMs
        }

        override fun cancel() {
            pending = null
        }

        /** The hold time passes. */
        fun elapse() {
            val action = pending ?: return
            pending = null
            action()
        }
    }

    private val scheduler = ManualScheduler()
    private val events = mutableListOf<Boolean>()
    private var muted = true
    private var callExists = true
    private val core = HoldToTalkCore(scheduler, canStart = { muted && callExists }, onTalk = { events += it })

    @Test
    fun aShortTapStaysAClickAndNeverOpensTheMicrophone() {
        core.down()
        assertEquals(HoldToTalkCore.DEFAULT_HOLD_MS, scheduler.pendingDelay)
        assertFalse("released before the hold time: the click toggles Mute", core.up())
        scheduler.elapse() // the cancelled timer must not fire
        assertEquals(emptyList<Boolean>(), events)
        assertFalse(core.talking)
    }

    @Test
    fun holdingPastTheTimeOpensTheMicrophoneAndReleaseClosesItAndSwallowsTheClick() {
        core.down()
        scheduler.elapse()
        assertEquals(listOf(true), events)
        assertTrue(core.talking)
        assertTrue("the hold was the action: no Mute toggle", core.up())
        assertEquals(listOf(true, false), events)
        assertFalse(core.talking)
    }

    @Test
    fun pressingWhileNotMutedDoesNothing() {
        muted = false
        core.down()
        assertEquals("no timer when the microphone is already open", null, scheduler.pending)
        assertFalse(core.up())
        assertEquals(emptyList<Boolean>(), events)
    }

    @Test
    fun theHoldTimeIsCheckedAgainWhenItElapses() {
        core.down()
        callExists = false // the call ended while the finger was down
        scheduler.elapse()
        assertEquals(emptyList<Boolean>(), events)
        assertFalse(core.up())
    }

    @Test
    fun aCancelledTouchEndsTheHoldLikeALiftedFinger() {
        core.down()
        scheduler.elapse()
        assertTrue(core.up()) // ACTION_CANCEL maps to up()
        assertEquals(listOf(true, false), events)
    }

    @Test
    fun aSecondPressStartsAFreshHold() {
        core.down(); scheduler.elapse(); core.up()
        core.down(); scheduler.elapse(); core.up()
        assertEquals(listOf(true, false, true, false), events)
    }

    @Test
    fun releaseFromOutsideMutesAgainAndTellsTheCall() {
        core.down()
        scheduler.elapse()
        core.release()
        assertEquals(listOf(true, false), events)
        assertFalse(core.talking)
    }

    @Test
    fun resetForgetsAHoldSilently() {
        core.down()
        scheduler.elapse()
        core.reset()
        assertFalse(core.talking)
        assertEquals("the call is gone: nobody is told", listOf(true), events)
        assertFalse("the next touch starts from scratch", core.up())
    }

    @Test
    fun screenReaderActivationLatchesTalkingOnAndOff() {
        assertTrue(core.toggleLatched())
        assertEquals(listOf(true), events)
        assertFalse(core.toggleLatched())
        assertEquals(listOf(true, false), events)
    }

    @Test
    fun screenReaderActivationDoesNothingWhenTheMicrophoneIsAlreadyOpen() {
        muted = false
        assertFalse(core.toggleLatched())
        assertEquals(emptyList<Boolean>(), events)
    }
}
