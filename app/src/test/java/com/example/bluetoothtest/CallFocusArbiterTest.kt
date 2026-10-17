package com.example.bluetoothtest

import android.media.AudioManager
import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallFocusArbiterTest {
    private class FakeApi(
        private val results: MutableList<Int?>,
        var mode: Int? = AudioManager.MODE_NORMAL,
        var telephony: Int? = null,
        var fgs: Boolean = false,
    ) : CallFocusApi {
        val log = mutableListOf<String>()
        var requests = 0
        val posted = mutableListOf<Pair<Long, () -> Unit>>()
        var cancelled = 0

        override fun requestFocus(): Int? {
            requests++
            return results.removeAt(0)
        }

        override fun audioMode(): Int? = mode
        override fun telephonyCallState(): Int? = telephony
        override fun foregroundServiceHeld(): Boolean = fgs
        override fun postDelayed(delayMs: Long, block: () -> Unit): () -> Unit {
            val entry = delayMs to block
            posted += entry
            return {
                cancelled++
                posted.remove(entry)
            }
        }

        override fun log(event: String, detail: String) {
            log += "$event|$detail"
        }

        fun runPosted() {
            val due = posted.toList()
            posted.clear()
            due.forEach { it.second() }
        }
    }

    private val failed = AudioManager.AUDIOFOCUS_REQUEST_FAILED
    private val granted = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    private val delayed = AudioManager.AUDIOFOCUS_REQUEST_DELAYED

    private fun newArbiter(api: FakeApi, classify: Boolean = true, actions: MutableList<FocusAction> = mutableListOf()) =
        CallFocusArbiter(api, classify, { actions += it }) to actions

    @Test
    fun refusalWithoutACallDoesNotHoldAndIsRetriedOnce() {
        val api = FakeApi(mutableListOf(failed, granted), mode = AudioManager.MODE_NORMAL)
        val (arbiter, actions) = newArbiter(api)
        assertFalse(arbiter.request())
        assertTrue("no hold for a refusal that is not a call", actions.isEmpty())
        assertTrue(api.log.single().contains("focus=failed_not_foreground"))
        assertEquals(1, api.posted.size)
        assertEquals(CallFocusArbiter.RETRY_DELAY_MS, api.posted.single().first)
        api.runPosted()
        assertEquals(2, api.requests)
        assertTrue(actions.isEmpty())
        assertTrue(api.log.last().contains("result=granted"))
    }

    @Test
    fun foregroundServiceHeldRetriesAtOnceAndCancelsTheTimer() {
        val api = FakeApi(mutableListOf(failed, granted), mode = AudioManager.MODE_IN_COMMUNICATION)
        val (arbiter, actions) = newArbiter(api)
        arbiter.request()
        arbiter.onForegroundHeld()
        assertEquals(2, api.requests)
        assertEquals(1, api.cancelled)
        assertTrue(api.posted.isEmpty())
        assertTrue(actions.isEmpty())
        // A second notification has nothing left to retry.
        arbiter.onForegroundHeld()
        assertEquals(2, api.requests)
    }

    @Test
    fun aSecondRefusalIsFinalAndStillDoesNotHoldWithoutACall() {
        val api = FakeApi(mutableListOf(failed, failed), mode = AudioManager.MODE_NORMAL)
        val (arbiter, actions) = newArbiter(api)
        arbiter.request()
        api.runPosted()
        assertTrue(actions.isEmpty())
        assertTrue(api.posted.isEmpty())
        assertTrue(api.log.last().contains("focus=failed_not_foreground_final"))
    }

    @Test
    fun aRealCellularCallStillHolds() {
        for (mode in listOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_RINGTONE, AudioManager.MODE_CALL_SCREENING)) {
            val api = FakeApi(mutableListOf(failed), mode = mode)
            val (arbiter, actions) = newArbiter(api)
            arbiter.request()
            assertEquals("mode $mode", listOf(FocusAction.HOLD), actions)
            assertTrue(api.posted.isEmpty())
        }
    }

    @Test
    fun aNonIdleTelephonyStateAloneIsEnoughEvidence() {
        val api = FakeApi(mutableListOf(failed), mode = AudioManager.MODE_NORMAL, telephony = TelephonyManager.CALL_STATE_OFFHOOK)
        val (arbiter, actions) = newArbiter(api)
        arbiter.request()
        assertEquals(listOf(FocusAction.HOLD), actions)
        val idle = FakeApi(mutableListOf(failed), mode = AudioManager.MODE_NORMAL, telephony = TelephonyManager.CALL_STATE_IDLE)
        val (idleArbiter, idleActions) = newArbiter(idle)
        idleArbiter.request()
        assertTrue(idleActions.isEmpty())
    }

    @Test
    fun unknownModeIsNotACall() {
        assertEquals(FocusRefusal.NOT_FOREGROUND, CallFocusRefusalPolicy.classify(null, null))
        assertEquals(FocusRefusal.NOT_FOREGROUND, CallFocusRefusalPolicy.classify(AudioManager.MODE_IN_COMMUNICATION, null))
        assertEquals(FocusRefusal.CELLULAR_CALL, CallFocusRefusalPolicy.classify(AudioManager.MODE_IN_CALL, null))
    }

    @Test
    fun delayedFocusWaitsWithoutACallAndHoldsDuringOne() {
        val waiting = FakeApi(mutableListOf(delayed), mode = AudioManager.MODE_NORMAL)
        val (arbiter, actions) = newArbiter(waiting)
        assertFalse(arbiter.request())
        assertTrue(actions.isEmpty())
        assertTrue(waiting.posted.isEmpty())
        assertTrue(waiting.log.single().contains("focus=delayed_wait"))

        val inCall = FakeApi(mutableListOf(delayed), mode = AudioManager.MODE_IN_CALL)
        val (callArbiter, callActions) = newArbiter(inCall)
        callArbiter.request()
        assertEquals(listOf(FocusAction.HOLD), callActions)
    }

    @Test
    fun entryOffRestoresTheFormerRuleEveryRefusalHolds() {
        val api = FakeApi(mutableListOf(failed), mode = AudioManager.MODE_NORMAL)
        val (arbiter, actions) = newArbiter(api, classify = false)
        arbiter.request()
        assertEquals(listOf(FocusAction.HOLD), actions)
        assertTrue(api.posted.isEmpty())
    }

    @Test
    fun grantedAndErrorNeverHold() {
        val grantedApi = FakeApi(mutableListOf(granted))
        val (arbiter, actions) = newArbiter(grantedApi)
        assertTrue(arbiter.request())
        assertTrue(actions.isEmpty())
        val error = FakeApi(mutableListOf<Int?>(null))
        val (errorArbiter, errorActions) = newArbiter(error)
        assertFalse(errorArbiter.request())
        assertTrue(errorActions.isEmpty())
        assertTrue(error.log.single().contains("result=error"))
    }

    @Test
    fun cancelRemovesAPendingRetry() {
        val api = FakeApi(mutableListOf(failed), mode = AudioManager.MODE_NORMAL)
        val (arbiter, _) = newArbiter(api)
        arbiter.request()
        arbiter.cancel()
        assertTrue(api.posted.isEmpty())
        assertEquals(1, api.cancelled)
        arbiter.onForegroundHeld()
        assertEquals(1, api.requests)
    }

    @Test
    fun theLogShowsWhetherTheForegroundServiceHeld() {
        val api = FakeApi(mutableListOf(failed), mode = AudioManager.MODE_NORMAL, fgs = true)
        val (arbiter, _) = newArbiter(api)
        arbiter.request()
        assertTrue(api.log.single().contains("fgsHeld=true"))
        assertEquals("failed", CallFocusRefusalPolicy.resultLabel(failed))
    }
}
