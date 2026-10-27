package com.example.bluetoothtest

/**
 * Experimental (off), connection review T2/A11 (2026-10-27): the fast-failure redial rule for the BLE control client (CoC
 * calls, streams and relay hops), DEBUG `test_fast_fail_redial_all=true`. The GATT video client's arm of the same rule is
 * `test_fast_0x3e_dials` ([FastEstablishDials]): same classifier ([ConnectDurationClass]) and the same backoff, taken from
 * [FastEstablishDials.delayMs] (300 ms + random 0-500 ms). The two rules differ only in their window: the call's first GATT
 * dial is capped at [FastEstablishDials.MAX_ATTEMPTS] dials inside the call's early-loss window, this client at [BUDGET_MS]
 * from its first connect (and any caller [SetupDeadline]). They run on different clients, so a launch with both arms on
 * never applies both rules to the same failure.
 *
 * Why: a GATT 133/62 within 1.5 s ([ConnectDurationClass.FAST_ESTABLISH_FAILURE]) is the controller's HCI 0x3E: the peer
 * did not answer the CONNECT_IND in the first connection events (profiling-20261026: 2 of 19 direct calls, the callee's
 * snoop shows no connection at all). It says nothing about range. The ordinary rule gives 2 dials 500 ms apart, and only
 * the stall retry had more ([StallRetryDials]); every other connect gave up ~1.1 s into a 10 s budget.
 *
 * Rule: such a failure is redialed after 300 ms + a random 0-500 ms (decorrelates two phones that retry together) while
 * elapsed + delay + [CONNECT_ROOM_MS] still fits [BUDGET_MS] (and any caller [SetupDeadline]), on any PHY. A slow failure
 * (own deadline, the stack's 30 s timeout: out of range) still ends at once through the ordinary rule.
 */
internal object FastFailRedial {
    const val BUDGET_MS = 11_000L
    const val MIN_DELAY_MS = FastEstablishDials.BASE_SPACING_MS
    const val MAX_DELAY_MS = FastEstablishDials.BASE_SPACING_MS + FastEstablishDials.JITTER_MS
    /** The shortest useful connect attempt after the delay (a fast failure takes 250-450 ms). */
    const val CONNECT_ROOM_MS = 600L

    /** The delay before the next dial, or null when the ordinary rule decides. */
    fun nextDelayMs(
        enabled: Boolean,
        failureClass: ConnectDurationClass?,
        elapsedMs: Long,
        outerRemainingMs: Long?,
        random: (Long, Long) -> Long = { from, until -> kotlin.random.Random.nextLong(from, until) },
    ): Long? {
        if (!enabled || failureClass != ConnectDurationClass.FAST_ESTABLISH_FAILURE) return null
        val delayMs = FastEstablishDials.delayMs(random(0L, FastEstablishDials.JITTER_MS + 1))
        if (elapsedMs + delayMs + CONNECT_ROOM_MS > BUDGET_MS) return null
        if (outerRemainingMs != null && delayMs + CONNECT_ROOM_MS > outerRemainingMs) return null
        return delayMs
    }

    fun line(link: String, peerTail: String, attempt: Int, delayMs: Long, elapsedMs: Long): String =
        "BENCHMARK fast_fail_redial: link=$link | peer=...$peerTail | failedAttempt=$attempt | delayMs=$delayMs | " +
            "elapsedMs=$elapsedMs | budgetMs=$BUDGET_MS"
}

/**
 * Experimental (off), connection review A10 (2026-10-27), DEBUG `test_stall_retry_own_budget=true`: the 1M retry after a
 * setup stall gets its own window, [WINDOW_MS] from the retry's start, instead of what was left of the first attempt's
 * 15 s early-loss window, and skips the ACL wait (the stall-retry dials already absorb a lingering ACL's fast failures).
 */
internal object StallRetryOwnBudget {
    const val WINDOW_MS = 10_000L

    fun deadlineMs(enabled: Boolean, retryStartMs: Long, earlyLossDeadlineMs: Long): Long =
        if (enabled) retryStartMs + WINDOW_MS else earlyLossDeadlineMs

    fun waitsForAcl(enabled: Boolean): Boolean = !enabled
}
