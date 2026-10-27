package com.example.bluetoothtest

import java.io.IOException

/**
 * Relay child establishment budget (Experimental, default off; DEBUG launch extra
 * `test_relay_establish_budget=true`, the control arm is today's behaviour: 2 connect attempts per
 * cycle, then one failure counted per cycle).
 *
 * Why (demo-f22f533d-relay/-relay2, 2026-10-12): a relay that is LE peripheral to its parent and LE
 * central to its child loses about half of its child connect attempts with HCI 0x3E (the stack
 * reports GATT status 133 after 0.3-0.5 s): six of nine failed attempts were connect requests the
 * child never received, three left both ends connected with no first anchor. The attempts look like
 * independent draws (about 0.5 each), yet the relay gave up after 4 attempts / 3.5 s, marked the child
 * for 5 minutes and so also refused the child's own retry, although the child kept advertising for
 * 15 s. With p = 0.5, 4 attempts fail 6 %, 10 attempts 0.1 %.
 *
 * Rule: a FAST establishment failure (status 133 or 62 within [FAST_MAX_MS] of the connect request)
 * is retried in place with [MIN_DELAY_MS]..[MAX_DELAY_MS] random spacing while the whole next attempt
 * still fits inside [BUDGET_MS] from the first connect request. A TARGET_ABSENT failure (the stack's
 * own ~5 s create-connection timeout) is retried at most once: the child is probably gone or has a
 * new address. Any other failure keeps the old per-cycle rule. When the budget is exhausted the
 * caller throws [EstablishmentBudgetExhausted], which counts as ONE establishment failure.
 */
internal class RelayEstablishBudget(
    private val startedAtMs: Long,
    private val budgetMs: Long = BUDGET_MS,
    private val randomBelow: (Long) -> Long = { bound -> java.util.concurrent.ThreadLocalRandom.current().nextLong(bound) },
) {
    enum class Kind { FAST_ESTABLISHMENT, TARGET_ABSENT, OTHER }

    /** One decision: [retryAfterMs] is null when no further attempt is allowed. */
    data class Decision(val kind: Kind, val retryAfterMs: Long?, val attempt: Int, val elapsedMs: Long)

    private var attempts = 0
    private var absentRetries = 0

    val attemptCount: Int get() = attempts

    /** Records a failed attempt that took [durationMs] and decides on the next one, at wall time [nowMs]. */
    fun onFailure(message: String?, durationMs: Long, nowMs: Long): Decision {
        attempts += 1
        val elapsed = (nowMs - startedAtMs).coerceAtLeast(0L)
        val kind = classify(message, durationMs)
        if (kind == Kind.OTHER) return Decision(kind, null, attempts, elapsed)
        if (kind == Kind.TARGET_ABSENT && absentRetries >= MAX_ABSENT_RETRIES) return Decision(kind, null, attempts, elapsed)
        val delay = MIN_DELAY_MS + randomBelow(MAX_DELAY_MS - MIN_DELAY_MS)
        // The next attempt is expected to take as long as this one did (never less than a fast one).
        val expectedMs = durationMs.coerceAtLeast(EXPECTED_FAST_ATTEMPT_MS)
        if (elapsed + delay + expectedMs > budgetMs) return Decision(kind, null, attempts, elapsed)
        if (kind == Kind.TARGET_ABSENT) absentRetries += 1
        return Decision(kind, delay, attempts, elapsed)
    }

    companion object {
        const val BUDGET_MS = 12_000L
        const val MIN_DELAY_MS = 300L
        const val MAX_DELAY_MS = 900L
        const val FAST_MAX_MS = ConnectDurationClass.FAST_MAX_MS
        const val ABSENT_MIN_MS = 4_500L
        const val ABSENT_MAX_MS = 6_500L
        const val EXPECTED_FAST_ATTEMPT_MS = 600L
        const val MAX_ABSENT_RETRIES = 1

        /** The duration rule is [ConnectDurationClass]'s; this adds only the connect-stage message check and the absent band. */
        fun classify(message: String?, durationMs: Long): Kind {
            val status = leSetupStatus(message) ?: return Kind.OTHER
            if (status !in ConnectDurationClass.ESTABLISHMENT_STATUSES) return Kind.OTHER
            // Post-link failures (service discovery, MTU, write) carry other messages and are not
            // "connection failed" / "disconnected before use" statuses.
            if (message == null || !(CONNECT_STAGE_PREFIXES.any { message.startsWith(it) })) return Kind.OTHER
            return when {
                ConnectDurationClass.isFastEstablishment(status, durationMs) -> Kind.FAST_ESTABLISHMENT
                durationMs in ABSENT_MIN_MS..ABSENT_MAX_MS -> Kind.TARGET_ABSENT
                else -> Kind.OTHER
            }
        }

        private val CONNECT_STAGE_PREFIXES = listOf(
            "GATT video connection failed (status ",
            "GATT video receiver disconnected (status ",
            "BLE control connection failed (status ",
        )

        /** Single BENCHMARK line per attempt (GATT video and CoC control setup). */
        fun logLine(
            link: String,
            attempt: Int,
            durationMs: Long,
            message: String?,
            decision: Decision?,
            elapsedMs: Long,
        ): String {
            val status = leSetupStatus(message)
            val result = when {
                message == null -> "ok"
                decision?.retryAfterMs != null -> "retry"
                decision != null && decision.kind != Kind.OTHER -> "exhausted"
                else -> "legacy"
            }
            return "BENCHMARK relay_child_connect: link=$link | attempt=$attempt | durationMs=$durationMs | " +
                "status=${status ?: "none"} | kind=${decision?.kind?.name?.lowercase() ?: "none"} | result=$result | " +
                "delayMs=${decision?.retryAfterMs ?: 0} | elapsedMs=$elapsedMs | budgetMs=$BUDGET_MS"
        }
    }
}

/** The budget ran out on fast/absent establishment failures: counts as ONE establishment failure. */
internal class EstablishmentBudgetExhausted(
    message: String?,
    val attempts: Int,
    val elapsedMs: Long,
    cause: Throwable?,
) : IOException(message, cause)

/**
 * A relay child setup failure that happened after a link to the child existed: its GATT or control
 * connection had been established (the 2M PHY update was requested or in flight, DebugTunables.relayEstablishKeep2M),
 * then the setup failed. Only created with that Experimental switch on, with the original message and cause
 * so the message-based retry rules are unchanged. A setup failure without this type never got a connection:
 * it is an establishment failure (the connect request itself failed, which runs on the advertising PHY).
 */
internal class LinkReachedSetupFailure(cause: IOException) : IOException(cause.message, cause)

/** [error] as seen by the caller: marked as post-connection when [reached] (switch on), unchanged otherwise. */
internal fun carryLinkReached(error: IOException, reached: Boolean): IOException =
    if (DebugTunables.relayEstablishKeep2M && reached &&
        error !is LinkReachedSetupFailure && error !is EstablishmentBudgetExhausted
    ) {
        LinkReachedSetupFailure(error)
    } else {
        error
    }

/** Runs a connect step whose IOException should be marked post-connection when [reached] says a link existed. */
internal inline fun <T> markLinkReached(reached: () -> Boolean, block: () -> T): T {
    if (!DebugTunables.relayEstablishKeep2M) return block()
    try {
        return block()
    } catch (error: IOException) {
        throw carryLinkReached(error, reached())
    }
}
