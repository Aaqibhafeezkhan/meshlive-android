package com.example.bluetoothtest

/**
 * Experimental (default off, DEBUG `test_fast_0x3e_dials`): more, jittered redials of a call's FIRST GATT dial after a fast
 * HCI 0x3E failure ([ConnectDurationClass.FAST_ESTABLISH_FAILURE]: GATT 133 or 62 within 1.5 s, no link formed).
 *
 * Why (2026-10-26, S22 and ASUS profiling with HCI snoop on both phones): about 1 call in 10 failed this way. The caller's
 * controller reported Connection Complete, then disconnected with 0x3E 250-440 ms later, and the callee's snoop showed no
 * connection at all: the CONNECT_IND was never received. The ordinary rule redials once, 500 ms later, so two such
 * failures in a row end the call. Today only the 1M retry after a 2M stall gets more dials ([StallRetryDials]). A missed
 * CONNECT_IND is a per-event radio miss, not an absent peer. So: up to [MAX_ATTEMPTS] dials, each [BASE_SPACING_MS] plus
 * a random 0..[JITTER_MS] apart (the jitter keeps redials from repeating the same timing against the callee's own
 * scanning and advertising), while the next dial still starts before the call's early-loss deadline. Every other failure
 * class and every other connect path keep the ordinary rule. No wire change. Off: the ordinary rule.
 */
internal object FastEstablishDials {
    const val MAX_ATTEMPTS = 4
    const val BASE_SPACING_MS = 300L
    const val JITTER_MS = 500L

    /**
     * The one fast-0x3E backoff: [BASE_SPACING_MS] plus [jitterMs] clamped to 0..[JITTER_MS]. [FastFailRedial] (the BLE
     * control client's arm) uses the same helper, so both fast-failure redial rules space their dials identically.
     */
    fun delayMs(jitterMs: Long): Long = BASE_SPACING_MS + jitterMs.coerceIn(0L, JITTER_MS)

    sealed interface Verdict {
        /** No scope or another failure class: the ordinary rule decides. */
        data object NotApplicable : Verdict

        /** Out of dials or out of the window: surface the failure. */
        data object Stop : Verdict

        data class Dial(val delayMs: Long, val remainingMs: Long) : Verdict
    }

    /**
     * After dial [attemptsUsed] failed with [failureClass]. [deadlineMs] is the first dial's [SetupDeadline]
     * ([SetupDeadline.ExtraDials.FAST_FIRST_DIAL]), null on every other connect; [jitterMs] its jitter draw.
     */
    fun next(
        failureClass: ConnectDurationClass?,
        attemptsUsed: Int,
        nowMs: Long,
        deadlineMs: Long?,
        jitterMs: Long?,
    ): Verdict {
        if (deadlineMs == null || failureClass != ConnectDurationClass.FAST_ESTABLISH_FAILURE) return Verdict.NotApplicable
        val delayMs = delayMs(jitterMs ?: 0L)
        val remainingMs = deadlineMs - nowMs
        if (attemptsUsed >= MAX_ATTEMPTS || remainingMs <= delayMs) return Verdict.Stop
        return Verdict.Dial(delayMs, remainingMs)
    }
}
