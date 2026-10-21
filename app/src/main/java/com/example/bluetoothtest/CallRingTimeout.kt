package com.example.bluetoothtest

/**
 * F19 (2026-10-20, ql-035b5235 asus-a15s / s22-xiaomi): an unanswered direct call rang for 6 min 6 s, until the test
 * driver hung up. Neither phone could be found by anyone else meanwhile (a phone holding a call has no call advert,
 * CF-CALL-02/03). Direct calls had no ring timer at all (CF-CALL-09); only relayed calls had a 60 s callee cap.
 *
 * Default on (DEBUG control arm `test_call_ring_timeout=false`); no wire change:
 * - the caller cancels [CALLER_MS] after the callee's Ringing, with the existing Cancel, and shows [NO_ANSWER_MESSAGE];
 * - the callee declines (existing Rejected) after [CALLEE_MS] of ringing, longer than the caller's limit so a current
 *   caller's Cancel arrives first; it only ends a call from an older caller or one whose Cancel was lost. Design decision
 *   2026-10-21 (all platforms): 65 s, so a lost Cancel ends the ringing within 5 s of the caller's 60 s instead of 15;
 * - a callee whose caller cancelled after about the caller's limit shows [MISSED_MESSAGE], not "Caller cancelled".
 */
internal object CallRingTimeout {
    const val CALLER_MS = 60_000L
    const val CALLEE_MS = 65_000L
    const val NO_ANSWER_MESSAGE = "No answer"
    const val MISSED_MESSAGE = "Missed call"
    const val CALLER_CANCELLED_MESSAGE = "Caller cancelled"

    /** A Cancel this close to the caller's limit is the caller's ring timeout, not the user's choice. */
    private const val MISSED_MARGIN_MS = 5_000L

    fun callerExpired(enabled: Boolean, phase: PrivateCallPhase?, ringingSinceMs: Long, nowMs: Long): Boolean =
        enabled && phase == PrivateCallPhase.OUTGOING_RINGING && nowMs - ringingSinceMs >= CALLER_MS

    fun calleeExpired(enabled: Boolean, phase: PrivateCallPhase?, ringingSinceMs: Long, nowMs: Long): Boolean =
        enabled && phase == PrivateCallPhase.INCOMING_RINGING && nowMs - ringingSinceMs >= CALLEE_MS

    /** What the callee shows when the caller cancels after ringing for [rangMs]. */
    fun cancelledMessage(enabled: Boolean, rangMs: Long?): String =
        if (enabled && rangMs != null && rangMs >= CALLER_MS - MISSED_MARGIN_MS) MISSED_MESSAGE else CALLER_CANCELLED_MESSAGE
}
