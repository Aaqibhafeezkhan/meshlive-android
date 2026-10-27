package com.example.bluetoothtest

import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simplification pass A: [ConnectAttempts] must decide exactly as the inline retry chains of the GATT video and BLE
 * control client loops did before (the reference models below are those chains, copied from integration/20261027), for
 * every default and Experimental configuration; the unified arm is tested on its own.
 */
private fun giveUp(thrown: IOException, original: IOException, lines: List<String>): ConnectRetryPolicyTest.Outcome =
    ConnectRetryPolicyTest.Outcome.GiveUp(thrown.javaClass.simpleName, thrown.message, thrown === original, lines)

class ConnectRetryPolicyTest {
    private val retryable = setOf(BluetoothTransportPolicy.GATT_ERROR_133)

    private val messages = listOf(
        "GATT video connection failed (status 133)",
        "GATT video receiver disconnected (status 62)",
        "GATT video connection failed (status 8)",
        "GATT video connection failed (status 147)",
        "GATT video service discovery failed (status 133)",
        "Timed out connecting GATT video",
        "BLE control connection failed (status 133)",
        "BLE control connection failed (status 62)",
        "BLE control connection failed (status 8)",
        "BLE control connection failed (status 34)",
        BLE_CONTROL_READ_TIMEOUT,
        "Receiver returned no BLE video channel",
    )
    private val durations = listOf(250L, 450L, 1_400L, 1_600L, 4_800L, 5_900L, 6_100L, 11_500L, 12_100L, 30_000L)

    /** One setup context: the thread-local scopes the old code had open, and the SetupDeadline that replaces them. */
    private data class Context(
        val name: String,
        val leSetupBudgetAtMs: Long?,
        val stallAtMs: Long?,
        val fastAtMs: Long?,
        val deadline: SetupDeadline?,
    )

    private fun contexts(jitter: () -> Long): List<Context> = listOf(
        Context("unbounded", null, null, null, null),
        Context("first-dial-retry-room", 15_000L, null, null, SetupDeadline.forFirstDial(15_000L, boundsOrdinary = true, fastDials = false)),
        Context("first-dial-fast", 15_000L, null, 15_000L, SetupDeadline(15_000L, 15_000L, SetupDeadline.ExtraDials.FAST_FIRST_DIAL, jitter)),
        Context("first-dial-fast-no-retry", null, null, 15_000L, SetupDeadline(15_000L, null, SetupDeadline.ExtraDials.FAST_FIRST_DIAL, jitter)),
        Context("stall-retry-early-loss", null, 15_000L, null, SetupDeadline.forStallRetry(15_000L)),
        Context("stall-retry-own-budget", null, 10_000L, null, SetupDeadline.forStallRetry(10_000L)),
        Context("message-dial", 15_000L, null, null, SetupDeadline(15_000L)),
        // The CoC phase of a dual attempt nested in a first dial with fast dials: the budget narrows, the dial scope stays.
        Context(
            "coc-phase-in-fast-first-dial", 9_000L, null, 15_000L,
            SetupDeadline(15_000L, 15_000L, SetupDeadline.ExtraDials.FAST_FIRST_DIAL, jitter).narrowed(9_000L),
        ),
        Context("coc-phase-in-stall-retry", 9_000L, 15_000L, null, SetupDeadline.forStallRetry(15_000L).narrowed(9_000L)),
    )

    sealed interface Outcome {
        data class Dial(val delayMs: Long, val notice: String, val lines: List<String>) : Outcome
        data class GiveUp(val type: String, val message: String?, val sameError: Boolean, val lines: List<String>) : Outcome
    }

    /** The GATT video client's chain before the refactor (pool retry excluded: it stays in the loop). */
    private class OldGatt(val ctx: Context, establishBudget: Boolean, randomBelow: (Long) -> Long, val jitter: () -> Long) {
        var attempt = 1
        var extendedRetries = 0
        val budget = if (establishBudget) RelayEstablishBudget(0L, randomBelow = randomBelow) else null

        fun onFailure(error: IOException, attemptStartedMs: Long, nowMs: Long, retryable: Set<Int>): Outcome {
            val lines = mutableListOf<String>()
            val failureClass = leSetupStatus(error.message)?.let {
                ConnectDurationClass.classify(it, nowMs - attemptStartedMs, 12_000L)
            }
            if (budget != null) {
                val durationMs = nowMs - attemptStartedMs
                val decision = budget.onFailure(error.message, durationMs, nowMs)
                lines += RelayEstablishBudget.logLine("gatt", attempt, durationMs, error.message, decision, nowMs - 0L)
                if (decision.kind != RelayEstablishBudget.Kind.OTHER) {
                    val delayMs = decision.retryAfterMs
                        ?: return giveUp(EstablishmentBudgetExhausted(error.message, decision.attempt, decision.elapsedMs, error), error, lines)
                    attempt += 1
                    return Outcome.Dial(delayMs, leSetupRetryNotice("GATT video", error.message, "retrying in place"), lines)
                }
            }
            when (val stallDial = StallRetryDials.next(failureClass, attempt, nowMs, ctx.stallAtMs)) {
                is StallRetryDials.Verdict.Dial -> {
                    lines += "BENCHMARK stall_retry_dial: peer=...AA:BB | failedAttempt=$attempt | " +
                        "maxAttempts=${StallRetryDials.MAX_ATTEMPTS} | delayMs=${stallDial.delayMs} | remainingMs=${stallDial.remainingMs}"
                    attempt += 1
                    return Outcome.Dial(stallDial.delayMs, leSetupRetryNotice("GATT video", error.message, "retrying"), lines)
                }
                StallRetryDials.Verdict.Stop -> lines += "BENCHMARK stall_retry_dial: peer=...AA:BB | failedAttempt=$attempt | " +
                    "maxAttempts=${StallRetryDials.MAX_ATTEMPTS} | action=ordinary_rule"
                StallRetryDials.Verdict.NotApplicable -> Unit
            }
            val fastJitter = if (ctx.fastAtMs != null) jitter() else null
            when (val fastDial = FastEstablishDials.next(failureClass, attempt, nowMs, ctx.fastAtMs, fastJitter)) {
                is FastEstablishDials.Verdict.Dial -> {
                    lines += "BENCHMARK fast_0x3e_dial: peer=...AA:BB | failedAttempt=$attempt | " +
                        "maxAttempts=${FastEstablishDials.MAX_ATTEMPTS} | delayMs=${fastDial.delayMs} | remainingMs=${fastDial.remainingMs}"
                    attempt += 1
                    return Outcome.Dial(fastDial.delayMs, leSetupRetryNotice("GATT video", error.message, "retrying"), lines)
                }
                FastEstablishDials.Verdict.Stop -> {
                    lines += "BENCHMARK fast_0x3e_dial: peer=...AA:BB | failedAttempt=$attempt | " +
                        "maxAttempts=${FastEstablishDials.MAX_ATTEMPTS} | action=give_up"
                    return giveUp(error, error, lines)
                }
                FastEstablishDials.Verdict.NotApplicable -> Unit
            }
            val allowed = leSetupRetryAllowed(
                message = error.message,
                attemptsUsed = attempt,
                maxAttempts = 2,
                classifiedRetryable = isRetryableGattVideoFailure(error.message, retryable),
                extendedRetriesUsed = extendedRetries,
                nextAttemptMs = 500L + 12_000L,
                remainingBudgetMs = ctx.leSetupBudgetAtMs?.let { it - nowMs },
            )
            if (!allowed) return giveUp(error, error, lines)
            if (leSetupStatus(error.message)?.let { it != BluetoothTransportPolicy.GATT_ERROR_133 } == true) extendedRetries++
            attempt += 1
            return Outcome.Dial(500L, leSetupRetryNotice("GATT video", error.message, "retrying once"), lines)
        }
    }

    /** The BLE control client's chain before the refactor. */
    private class OldControl(val ctx: Context, establishBudget: Boolean, val fastFailRedialAll: Boolean, val randomBelow: (Long) -> Long) {
        var attempt = 1
        var extendedRetries = 0
        val budget = if (establishBudget) RelayEstablishBudget(0L, randomBelow = randomBelow) else null

        fun onFailure(error: IOException, attemptStartedMs: Long, nowMs: Long, retryable: Set<Int>): Outcome {
            val lines = mutableListOf<String>()
            if (budget != null) {
                val durationMs = nowMs - attemptStartedMs
                val decision = budget.onFailure(error.message, durationMs, nowMs)
                lines += RelayEstablishBudget.logLine("coc-control", attempt, durationMs, error.message, decision, nowMs - 0L)
                if (decision.kind != RelayEstablishBudget.Kind.OTHER) {
                    val delayMs = decision.retryAfterMs
                        ?: return giveUp(EstablishmentBudgetExhausted(error.message, decision.attempt, decision.elapsedMs, error), error, lines)
                    attempt += 1
                    return Outcome.Dial(delayMs, leSetupRetryNotice("BLE control", error.message, "retrying in place"), lines)
                }
            }
            val failureClass = leSetupStatus(error.message)?.let { ConnectDurationClass.classify(it, nowMs - attemptStartedMs, 6_000L) }
            FastFailRedial.nextDelayMs(
                fastFailRedialAll, failureClass, nowMs - 0L, ctx.leSetupBudgetAtMs?.let { it - nowMs },
                random = { from, until -> from + randomBelow(until - from) },
            )?.let { delayMs ->
                lines += FastFailRedial.line("ble-control", "AA:BB", attempt, delayMs, nowMs - 0L)
                attempt += 1
                return Outcome.Dial(delayMs, leSetupRetryNotice("BLE control", error.message, "retrying connection"), lines)
            }
            val allowed = leSetupRetryAllowed(
                message = error.message,
                attemptsUsed = attempt,
                maxAttempts = 3,
                classifiedRetryable = isRetryableBleControlFailure(error.message, retryable),
                extendedRetriesUsed = extendedRetries,
                nextAttemptMs = 500L + 6_000L,
                remainingBudgetMs = ctx.leSetupBudgetAtMs?.let { it - nowMs },
            )
            if (!allowed) return giveUp(error, error, lines)
            if (leSetupStatus(error.message)?.let { it != BluetoothTransportPolicy.GATT_ERROR_133 } == true) extendedRetries++
            attempt += 1
            val notice = if (error.message == BLE_CONTROL_READ_TIMEOUT) {
                "BLE control did not respond; retrying connection"
            } else {
                leSetupRetryNotice("BLE control", error.message, "retrying connection")
            }
            return Outcome.Dial(500L, notice, lines)
        }
    }

    private fun newOutcome(decision: ConnectAttempts.Decision, original: IOException): Outcome = when (decision) {
        is ConnectAttempts.Decision.Dial -> Outcome.Dial(decision.delayMs, decision.notice, decision.lines)
        is ConnectAttempts.Decision.GiveUp -> giveUp(decision.error, original, decision.lines)
    }

    /** Drives the old chain and [ConnectAttempts] through the same failures and clock; every decision must match. */
    private fun compare(link: ConnectAttempts.Link, seed: Int): Int {
        var compared = 0
        val scenarioRandom = Random(seed)
        for (establishBudget in listOf(false, true)) for (fastFailAll in listOf(false, true)) {
            for (ctxIndex in contexts { 0L }.indices) {
                val oldRandom = Random(seed * 31 + ctxIndex)
                val newRandom = Random(seed * 31 + ctxIndex)
                val oldJitter = Random(seed * 17 + ctxIndex)
                val newJitter = Random(seed * 17 + ctxIndex)
                val oldCtx = contexts { oldJitter.nextLong(0L, FastEstablishDials.JITTER_MS + 1) }[ctxIndex]
                val newCtx = contexts { newJitter.nextLong(0L, FastEstablishDials.JITTER_MS + 1) }[ctxIndex]
                val config = ConnectRetryConfig.DEFAULT.copy(fastFailRedialControl = fastFailAll, fastFirstDial = newCtx.fastAtMs != null)
                val attempts = ConnectAttempts(link, config, newCtx.deadline, 0L, establishBudget, "AA:BB") { bound -> newRandom.nextLong(bound) }
                val oldGatt = OldGatt(oldCtx, establishBudget, { bound -> oldRandom.nextLong(bound) }) { oldJitter.nextLong(0L, FastEstablishDials.JITTER_MS + 1) }
                val oldControl = OldControl(oldCtx, establishBudget, fastFailAll) { bound -> oldRandom.nextLong(bound) }
                var now = scenarioRandom.nextLong(0L, 3_000L)
                for (step in 0 until 14) {
                    val attemptStartedMs = now
                    now += durations[scenarioRandom.nextInt(durations.size)]
                    val error = IOException(messages[scenarioRandom.nextInt(messages.size)])
                    val old = if (link == ConnectAttempts.Link.GATT_VIDEO) {
                        oldGatt.onFailure(error, attemptStartedMs, now, retryable)
                    } else {
                        oldControl.onFailure(error, attemptStartedMs, now, retryable)
                    }
                    val classifiedRetryable = if (link == ConnectAttempts.Link.GATT_VIDEO) {
                        isRetryableGattVideoFailure(error.message, retryable)
                    } else {
                        isRetryableBleControlFailure(error.message, retryable)
                    }
                    val new = newOutcome(
                        attempts.onFailure(
                            error, attempts.classify(error.message, now - attemptStartedMs), attemptStartedMs, now,
                            classifiedRetryable, linkReached = false,
                        ),
                        error,
                    )
                    assertEquals("${link.name} ${newCtx.name} budget=$establishBudget fastAll=$fastFailAll seed=$seed", old, new)
                    compared++
                    if (old !is Outcome.Dial) break
                    now += old.delayMs
                }
            }
        }
        return compared
    }

    @Test
    fun theGattVideoChainDecidesExactlyAsBefore() {
        val compared = (1..60).sumOf { compare(ConnectAttempts.Link.GATT_VIDEO, it) }
        assertTrue(compared > 2_000)
    }

    @Test
    fun theBleControlChainDecidesExactlyAsBefore() {
        val compared = (1..60).sumOf { compare(ConnectAttempts.Link.BLE_CONTROL, it) }
        assertTrue(compared > 2_000)
    }

    @Test
    fun defaultConfigNeverClipsAnAttemptOrBoundsTheConnectPhase() {
        val deadline = SetupDeadline(1_000L)
        for (link in ConnectAttempts.Link.values()) {
            val attempts = ConnectAttempts(link, ConnectRetryConfig.DEFAULT, deadline, 0L, false, "AA:BB")
            assertEquals(link.attemptTimeoutMs, attempts.attemptTimeoutMs(900L))
            assertNull(attempts.connectPhaseMs())
        }
        assertEquals(12_000L, ConnectAttempts.Link.GATT_VIDEO.attemptTimeoutMs)
        assertEquals(6_000L, ConnectAttempts.Link.BLE_CONTROL.attemptTimeoutMs)
    }

    @Test
    fun oneDeadlineCarriesWhatTheThreeScopesCarried() {
        assertNull(SetupDeadline.forFirstDial(15_000L, boundsOrdinary = false, fastDials = false))
        val bounded = SetupDeadline.forFirstDial(15_000L, boundsOrdinary = true, fastDials = false)!!
        assertEquals(14_000L, bounded.ordinaryRemainingMs(1_000L))
        assertEquals(SetupDeadline.ExtraDials.NONE, bounded.extraDials)
        val fastOnly = SetupDeadline.forFirstDial(15_000L, boundsOrdinary = false, fastDials = true)!!
        assertNull(fastOnly.ordinaryRemainingMs(1_000L))
        assertEquals(SetupDeadline.ExtraDials.FAST_FIRST_DIAL, fastOnly.extraDials)
        val stall = SetupDeadline.forStallRetry(25_000L)
        assertNull(stall.ordinaryRemainingMs(1_000L))
        assertEquals(24_000L, stall.remainingMs(1_000L))
        // A CoC phase narrows the ordinary budget (the old nested LeSetupBudget.within) and keeps the dial scope.
        val phase = stall.narrowed(10_000L)
        assertEquals(9_000L, phase.ordinaryRemainingMs(1_000L))
        assertEquals(SetupDeadline.ExtraDials.STALL_RETRY, phase.extraDials)
        // ... but the dial scope keeps its own deadline (the equivalence test found this when narrowed() moved both).
        assertEquals(25_000L, phase.atMs)
        assertEquals(10_000L, phase.endMs)
        assertEquals(10_000L, DualSetupBudget.cocDeadline(1_000L, bounded.ordinaryRemainingMs(1_000L)?.let { 1_000L + it }))
    }

    @Test
    fun oneFastEstablishmentThresholdForEveryRule() {
        for (status in listOf(null, 8, 62, 133, 147)) for (elapsed in listOf(0L, 300L, 1_499L, 1_500L, 1_501L, 5_000L)) {
            val old = status != null && elapsed <= 1_500L && (status == 133 || status == 62)
            assertEquals(old, ConnectDurationClass.isFastEstablishment(status, elapsed))
            assertEquals(old, ConnectDurationClass.classify(status, elapsed, 12_000L) == ConnectDurationClass.FAST_ESTABLISH_FAILURE)
            val message = status?.let { "GATT video connection failed (status $it)" }
            assertEquals(old, RelayEstablishBudget.classify(message, elapsed) == RelayEstablishBudget.Kind.FAST_ESTABLISHMENT)
        }
    }

    @Test
    fun unifiedRedialsFastFailuresWithJitterUntilTheBudgetEnds() {
        val config = ConnectRetryConfig.DEFAULT.copy(unified = true)
        for (link in ConnectAttempts.Link.values()) {
            val attempts = ConnectAttempts(link, config, null, 0L, false, "AA:BB") { 0L }
            var now = 0L
            var dials = 0
            while (true) {
                val started = now
                now += 400L
                val error = IOException("${link.notice} connection failed (status 133)")
                val decision = attempts.onFailure(error, attempts.classify(error.message, now - started), started, now, true, false)
                if (decision is ConnectAttempts.Decision.GiveUp) {
                    assertSame(error, decision.error)
                    assertTrue(decision.lines.single().contains("action=give_up"))
                    break
                }
                decision as ConnectAttempts.Decision.Dial
                assertEquals(FastEstablishDials.BASE_SPACING_MS, decision.delayMs)
                now += decision.delayMs
                dials++
            }
            // More than any old rule's 4 dials, and every dial started with room for a connect inside the 11 s budget.
            assertTrue("$link dials=$dials", dials > 4)
            assertTrue(now <= UnifiedConnectRule.BUDGET_MS + 400L)
        }
    }

    @Test
    fun unifiedRespectsTheCallersDeadlineAndKeepsTheOrdinaryRuleForSlowFailures() {
        val config = ConnectRetryConfig.DEFAULT.copy(unified = true)
        val attempts = ConnectAttempts(ConnectAttempts.Link.GATT_VIDEO, config, SetupDeadline(3_000L), 0L, false, "AA:BB") { 0L }
        // A slow absent-peer failure (connect phase ran out): the ordinary rule, inside the deadline.
        val slow = IOException("Timed out connecting GATT video")
        val first = attempts.onFailure(slow, null, 0L, 1_000L, true, false)
        assertTrue(first is ConnectAttempts.Decision.Dial)
        val second = attempts.onFailure(slow, null, 1_500L, 2_500L, true, false)
        assertTrue(second is ConnectAttempts.Decision.GiveUp)
        // Attempts are clipped to the deadline (at least the minimum attempt) and get the connect-phase bound.
        assertEquals(UnifiedConnectRule.MIN_ATTEMPT_MS, attempts.attemptTimeoutMs(2_500L))
        assertEquals(UnifiedConnectRule.CONNECT_PHASE_MS, attempts.connectPhaseMs())
        val unbounded = ConnectAttempts(ConnectAttempts.Link.GATT_VIDEO, config, null, 0L, false, "AA:BB")
        // Without a caller deadline the connect's own 11 s budget clips the 12 s GATT attempt.
        assertEquals(UnifiedConnectRule.BUDGET_MS, unbounded.attemptTimeoutMs(0L))
        assertEquals(5_000L, unbounded.attemptTimeoutMs(6_000L))
        assertEquals(7_000L, UnifiedConnectRule.clipMs(0L, 10_000L, 3_000L))
    }

    @Test
    fun unifiedStillHonoursARequestedRelayEstablishBudget() {
        val config = ConnectRetryConfig.DEFAULT.copy(unified = true)
        val attempts = ConnectAttempts(ConnectAttempts.Link.BLE_CONTROL, config, null, 0L, true, "AA:BB") { 0L }
        val error = IOException("BLE control connection failed (status 133)")
        val decision = attempts.onFailure(error, ConnectDurationClass.FAST_ESTABLISH_FAILURE, 0L, 400L, true, false)
        decision as ConnectAttempts.Decision.Dial
        assertEquals(RelayEstablishBudget.MIN_DELAY_MS, decision.delayMs)
        assertTrue(decision.lines.single().startsWith("BENCHMARK relay_child_connect:"))
    }

    @Test
    fun unifiedHelperHopHasOneBudgetedLayer() {
        assertNull(RelayHopPolicy.unifiedHopDeadline(false, 0L, 20_000, 0L, 100_000L))
        val deadline = RelayHopPolicy.unifiedHopDeadline(true, 0L, 20_000, 5_000_000_000L, 100_000L)!!
        assertEquals(100_000L + 15_000L - UnifiedConnectRule.HELPER_REPLY_RESERVE_MS, deadline.atMs)
        assertTrue(RelayHopPolicy.phyRetryAfter(unified = false, connected = false))
        assertFalse(RelayHopPolicy.phyRetryAfter(unified = true, connected = false))
        assertTrue(RelayHopPolicy.phyRetryAfter(unified = true, connected = true))
    }

    @Test
    fun controlSetupWaitEndsAtTheConnectPhaseOnlyWhenNotConnected() {
        val never = CountDownLatch(1)
        val started = System.nanoTime()
        assertFalse(BleL2capPsmReader.awaitControlSetup(never, 2_000L, 50L) { false })
        assertTrue((System.nanoTime() - started) / 1_000_000L < 1_500L)
        val done = CountDownLatch(0)
        assertTrue(BleL2capPsmReader.awaitControlSetup(done, 2_000L, null) { false })
        assertTrue(BleL2capPsmReader.awaitControlSetup(done, 2_000L, 50L) { false })
        // Connected by the phase end: the wait continues to the attempt bound.
        val laterStart = System.nanoTime()
        assertFalse(BleL2capPsmReader.awaitControlSetup(never, 200L, 50L) { true })
        assertTrue((System.nanoTime() - laterStart) / 1_000_000L >= 190L)
    }
}
