package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F17 (longcall-8143c157 m31-s24-voice300, longcall-ee3558f9 xiaomi-s24-voice300). */
class CallStallToleranceTest {
    private fun source(name: String): String = listOf(
        File("src/main/java/com/example/bluetoothtest/$name"),
        File("app/src/main/java/com/example/bluetoothtest/$name"),
    ).first { it.exists() }.readText().replace("\r\n", "\n")

    private val s = 1_000_000_000L

    @Test
    fun offTheRuleIsStillEightSecondsOfSilenceWithNoNewStatus() {
        val v = { silentS: Long -> CallStallTolerance.verdict(false, 100 * s, 100 * s - silentS * s, 100 * s) }
        assertEquals(CallStallTolerance.Verdict.OK, v(0))
        assertEquals("no Poor connection when off", CallStallTolerance.Verdict.OK, v(5))
        assertEquals(CallStallTolerance.Verdict.END, v(8))
    }

    @Test
    fun onTheF17StallIsKeptWhileOurWritesCompleteAndEndsWhenTheyStop() {
        // The M31 kept writing while the S24's sending stalled for 10.06 s, then recovered.
        val now = 100 * s
        val writing = now - s / 2
        assertEquals(CallStallTolerance.Verdict.OK, CallStallTolerance.verdict(true, now, now - 2 * s, writing))
        assertEquals(CallStallTolerance.Verdict.POOR, CallStallTolerance.verdict(true, now, now - 3 * s, writing))
        assertEquals(
            "10 s: kept (the M31 ended this call at 8 s)",
            CallStallTolerance.Verdict.POOR,
            CallStallTolerance.verdict(true, now, now - 10 * s, writing),
        )
        assertEquals(CallStallTolerance.Verdict.END, CallStallTolerance.verdict(true, now, now - 14 * s, writing))
        // Our own writes stopped too (link down or both directions stalled): the 8 s rule.
        assertEquals(CallStallTolerance.Verdict.END, CallStallTolerance.verdict(true, now, now - 8 * s, now - 3 * s))
        assertEquals(CallStallTolerance.Verdict.POOR, CallStallTolerance.verdict(true, now, now - 7 * s, now - 3 * s))
        assertFalse(DebugTunables.callStallTolerance)
        assertEquals(8 * s, CallStallTolerance.SILENCE_LIMIT_NS)
    }

    @Test
    fun aLocalLivenessCloseHangsUpFirstOnlyInTheMediaPhases() {
        assertTrue(LivenessHangUp.applies(true, PrivateCallPhase.ACTIVE))
        assertTrue(LivenessHangUp.applies(true, PrivateCallPhase.NEGOTIATING_MEDIA))
        assertFalse(LivenessHangUp.applies(true, PrivateCallPhase.INCOMING_RINGING))
        assertFalse(LivenessHangUp.applies(true, PrivateCallPhase.OUTGOING_RINGING))
        assertFalse(LivenessHangUp.applies(true, null))
        assertFalse("control arm", LivenessHangUp.applies(false, PrivateCallPhase.ACTIVE))
        // An existing wire value: HANG_UP takes NORMAL or ERROR only.
        PrivateCallSignalMessage(java.util.UUID.randomUUID(), PrivateCallSignal.HANG_UP, PrivateCallReason.ERROR)

        assertTrue(DebugTunables.callLivenessHangUp)
    }

    @Test
    fun theReceiverOfAnErrorHangUpSaysWhoStoppedHearingWhom() {
        val now = 100 * s
        assertEquals(
            "F17's S24: its own sends had been stuck for 8 s",
            LivenessHangUp.OWN_SEND_STALLED_MESSAGE,
            LivenessHangUp.errorHangUpMessage(now, now - 8 * s),
        )
        assertEquals(LivenessHangUp.PEER_MEDIA_FAILURE_MESSAGE, LivenessHangUp.errorHangUpMessage(now, now - s / 2))
        assertEquals("nothing sent yet", LivenessHangUp.PEER_MEDIA_FAILURE_MESSAGE, LivenessHangUp.errorHangUpMessage(now, 0L))
    }

    @Test
    fun aVoiceCallWithoutAVideoSurfaceWaitsInsteadOfEnding() {
        // xiaomi-s24-voice300: "Call link lost: Call video surface is not ready" 0.1 s after answering from the lock screen.

        val wait = VideoSurfaceWait()
        assertEquals(VideoSurfaceWait.Action.SKIP_AND_REQUEST_KEY_FRAME, wait.onVideoPacket(surfaceReady = false, nowNs = 0L))
        assertEquals(VideoSurfaceWait.Action.SKIP, wait.onVideoPacket(false, s / 2))
        assertEquals("at most one key-frame request per second", VideoSurfaceWait.Action.SKIP_AND_REQUEST_KEY_FRAME, wait.onVideoPacket(false, s))
        assertEquals(VideoSurfaceWait.Action.DECODE, wait.onVideoPacket(true, s + 1))
        assertEquals(3L, wait.skipped)
    }
}
