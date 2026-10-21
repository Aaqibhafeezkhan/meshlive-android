package com.example.bluetoothtest

/*
 * F17 (2026-10-20, longcall-8143c157 m31-s24-voice300): a 5-minute M31 -> S24 call ended at 259 s. The S24's sending
 * stalled for 10.06 s (its host queue held ~200 packets, `Link congestion ... xmit_hold_q.count: 201`) while its
 * receiving and the link-layer ACKs kept working, then recovered by itself. The M31 still wrote, heard nothing for 8 s
 * and ended the call ("Call peer stopped responding"); the S24 found out only through its own 8 s timer.
 */

/**
 * Stage A (Experimental, default off, `test_call_stall_tolerance`): how long this phone keeps a call whose peer has gone
 * quiet. Off, the rule is CF-CALL-10's: 8 s without any record ends the call. On, a call whose own writes still complete
 * (so the link is up) is kept for up to [TOLERANCE_NS], with "Poor connection" shown from [POOR_AFTER_NS]; a peer that
 * stops reading as well, or a link that fails, ends it as before. Only this side's timer changes: the peer, which still
 * receives, keeps its own 8 s rule. Promoting it needs a CF-CALL-10 amendment (the 8 s is a wire rule).
 */
internal object CallStallTolerance {
    /** CF-CALL-10. */
    const val SILENCE_LIMIT_NS = 8_000_000_000L

    /** "Poor connection" after this much silence (tolerance on only). */
    const val POOR_AFTER_NS = 3_000_000_000L

    /** The longest kept silence while this phone's own writes still complete (F17's stall lasted 10.06 s). */
    const val TOLERANCE_NS = 14_000_000_000L

    /**
     * Own writes count as completing when one finished this recently. The writer flushes at least once per heartbeat
     * (1 s); a blocked write stops it.
     */
    const val SEND_FRESH_NS = 2_000_000_000L

    enum class Verdict { OK, POOR, END }

    fun verdict(
        enabled: Boolean,
        nowNs: Long,
        lastReceivedNs: Long,
        lastSendProgressNs: Long,
    ): Verdict {
        val silentNs = nowNs - lastReceivedNs
        if (!enabled) return if (silentNs >= SILENCE_LIMIT_NS) Verdict.END else Verdict.OK
        if (silentNs < POOR_AFTER_NS) return Verdict.OK
        if (silentNs < SILENCE_LIMIT_NS) return Verdict.POOR
        val sending = nowNs - lastSendProgressNs < SEND_FRESH_NS
        return if (sending && silentNs < TOLERANCE_NS) Verdict.POOR else Verdict.END
    }

    /** One `call_stall` line per phase change. */
    fun line(phase: String, silentMs: Long, sendAgeMs: Long, callId: String?): String =
        "BENCHMARK call_stall: phase=$phase | silentMs=$silentMs | sendAgeMs=$sendAgeMs | " +
            "toleranceMs=${TOLERANCE_NS / 1_000_000L} | callId=${callId ?: "?"}"
}

/**
 * The best-effort hang-up before a local liveness close (default on; control arm `test_call_liveness_hang_up=false`).
 * F17: the side that stopped hearing its peer closed silently, and the peer, which could still hear, waited its own 8 s.
 * The HANG_UP (reason ERROR, an existing wire value) is written from a helper thread for at most [WAIT_MS], so a
 * blocked link never delays the close by more.
 */
internal object LivenessHangUp {
    const val WAIT_MS = 300L

    /** HANG_UP is a signal of the media phases only (CF-CALL-20); ringing ends by silence as before. */
    fun applies(enabled: Boolean, phase: PrivateCallPhase?): Boolean =
        enabled && (phase == PrivateCallPhase.NEGOTIATING_MEDIA || phase == PrivateCallPhase.ACTIVE)

    /**
     * What the receiver of HANG_UP(ERROR) shows. The wire cannot say why; when this phone's own sends have not
     * completed for [OWN_SEND_STALL_NS] the peer most likely stopped hearing us (F17's S24), not a peer media failure.
     */
    fun errorHangUpMessage(nowNs: Long, lastSendProgressNs: Long): String =
        if (lastSendProgressNs > 0L && nowNs - lastSendProgressNs >= OWN_SEND_STALL_NS) {
            OWN_SEND_STALLED_MESSAGE
        } else {
            PEER_MEDIA_FAILURE_MESSAGE
        }

    const val OWN_SEND_STALL_NS = 3_000_000_000L
    const val OWN_SEND_STALLED_MESSAGE = "Call ended: this phone's sound and video stopped reaching the other phone"
    const val PEER_MEDIA_FAILURE_MESSAGE = "Peer's phone ended the call after a local media failure"
}

/**
 * F17 second instance (2026-10-20, longcall-ee3558f9 xiaomi-s24-voice300): a voice call answered from the lock screen
 * ended 0.1 s after the answer with "Call link lost: Call video surface is not ready". The call's remote TextureView had
 * no surface yet, and the receiver asked for one as soon as the peer's stream header arrived, although a voice call never
 * decodes video. The receiver now asks only when a video packet needs a decoder; until the surface exists video is
 * skipped and a key frame is requested (at most once per [KEY_FRAME_REQUEST_INTERVAL_NS]), so the picture starts at the
 * next key frame.
 */
internal class VideoSurfaceWait {
    enum class Action { DECODE, SKIP, SKIP_AND_REQUEST_KEY_FRAME }

    private var lastRequestNs = Long.MIN_VALUE
    var skipped = 0L
        private set

    fun onVideoPacket(surfaceReady: Boolean, nowNs: Long): Action {
        if (surfaceReady) return Action.DECODE
        skipped++
        if (lastRequestNs != Long.MIN_VALUE && nowNs - lastRequestNs < KEY_FRAME_REQUEST_INTERVAL_NS) return Action.SKIP
        lastRequestNs = nowNs
        return Action.SKIP_AND_REQUEST_KEY_FRAME
    }

    companion object {
        const val KEY_FRAME_REQUEST_INTERVAL_NS = 1_000_000_000L
    }
}
