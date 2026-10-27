package com.example.bluetoothtest

import android.os.SystemClock
import android.util.Log

/**
 * The 1M call-setup retry after a 2M setup stall waits for the stalled link's ACL to drop first (default on; DEBUG
 * `test_setup_retry_acl_wait=false` is the control arm).
 *
 * The A15s (MediaTek, Android 10) calling the ASUS stalls right after the 2M PHY update; the stall guard closes the link at
 * 3.0 s, but the ACL to the ASUS lingers 1.1-3.7 s. The ASUS re-advertises about 1.6 s after its cancel, so the retry finds a
 * fresh address in 0.13 s and dials into the lingering ACL: HCI 0x3E, then 0x0B (connection already exists), GATT 133 twice,
 * and the call failed with about 10 s of the early-loss window unused (longcall-035b5235 lc-U8UOWKQKPZIJFM8D-...-080235). The
 * retry targets a new address, so [GattLinkLifetime]'s per-address redial wait never applies; this waits on the OLD device's
 * ACL, armed before the stall guard closes the link so the broadcast cannot be missed. The stack's GATT connected list is
 * not consulted: the A15s reports the peer disconnected while its ACL is still up.
 */
internal object SetupRetryAclWait {
    /** Longest wait; the measured linger was 1.1-3.7 s. */
    const val BOUND_MS = 5_000L

    /**
     * Review T1 (2026-10-27): Android 10/11 stacks never deliver the ACL broadcast for an unnamed random-address peer
     * (RemoteDevices drops it before the intent), so the wait always ran its full bound: 9 of 9 retries on the A15s
     * logged `waitedMs=5003 | dropped=false`. There it is a short fixed pause instead, run while the retry rescans.
     */
    const val LEGACY_STACK_BOUND_MS = 1_500L
    const val LEGACY_STACK_MAX_SDK = 30

    fun boundMs(sdkInt: Int): Long = if (sdkInt <= LEGACY_STACK_MAX_SDK) LEGACY_STACK_BOUND_MS else BOUND_MS

    /** Waits for [watch] (always released); logs `BENCHMARK setup_retry_acl_wait`. True when the ACL dropped. */
    fun await(
        watch: AclDownWatch,
        boundMs: Long = BOUND_MS,
        nowMs: () -> Long = SystemClock::elapsedRealtime,
        log: (String) -> Unit = { runCatching { Log.i("BluetoothVideo", it) } },
    ): Boolean {
        val startedMs = nowMs()
        val dropped = GattLinkLifetime.awaitAclDown(
            watch = watch,
            timeoutMs = boundMs,
            mayBlock = true,
            stillUp = { true },
            nowMs = nowMs,
            log = log,
            reason = "setup_retry",
        )
        log("BENCHMARK setup_retry_acl_wait: waitedMs=${nowMs() - startedMs} | dropped=$dropped | boundMs=$boundMs")
        return dropped
    }
}

/**
 * More dials for the 1M call-setup retry after a 2M stall (default on; DEBUG `test_stall_retry_dials=false` is the control
 * arm). A fast HCI 0x3E failure (GATT 133 within 1.5 s, [ConnectDurationClass.FAST_ESTABLISH_FAILURE]) right after a
 * stall is the peer's link still being torn down, not an absent peer; the ordinary rule (2 GATT open attempts 500 ms apart)
 * gave up 1.1 s into a 10 s remainder. Inside the stall retry's [SetupDeadline] such a failure is redialed up to [MAX_ATTEMPTS] dials in total,
 * [SPACING_MS] apart, while the next dial still starts before the early-loss deadline. Every other failure, and every other
 * connect path, keeps the ordinary rule ([ConnectAttempts] applies it).
 */
internal object StallRetryDials {
    const val MAX_ATTEMPTS = 4
    const val SPACING_MS = 1_500L

    sealed interface Verdict {
        /** Not a stall retry or not a fast 0x3E failure: the ordinary retry rule decides. */
        data object NotApplicable : Verdict

        /** Out of dials or out of the window: surface the failure. */
        data object Stop : Verdict

        data class Dial(val delayMs: Long, val remainingMs: Long) : Verdict
    }

    /**
     * After dial [attemptsUsed] failed with [failureClass]: what the stall-retry budget says. [deadlineMs] is the stall
     * retry's [SetupDeadline] ([SetupDeadline.ExtraDials.STALL_RETRY]), null on every other connect.
     */
    fun next(
        failureClass: ConnectDurationClass?,
        attemptsUsed: Int,
        nowMs: Long,
        deadlineMs: Long?,
    ): Verdict {
        if (deadlineMs == null || failureClass != ConnectDurationClass.FAST_ESTABLISH_FAILURE) return Verdict.NotApplicable
        val remainingMs = deadlineMs - nowMs
        if (attemptsUsed >= MAX_ATTEMPTS || remainingMs <= SPACING_MS) return Verdict.Stop
        return Verdict.Dial(SPACING_MS, remainingMs)
    }
}
