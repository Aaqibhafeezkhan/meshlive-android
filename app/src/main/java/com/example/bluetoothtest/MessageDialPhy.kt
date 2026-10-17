package com.example.bluetoothtest

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Adaptive PHY for a store-and-forward message-exchange dial (2026-10-13; Experimental switch
 * `test_message_adaptive_phy`, default on, SWITCHES.md). A message exchange dials with the same GATT/CoC client as a
 * private call, but until now it had none of the call's 2M protections ([AdaptivePhyMemory.SETUP_STALL_MS]): the A15s
 * (OPPO, Android 10) dialing the ASUS lost the ASUS -> A15s direction right after LE PHY Update Complete (2M), so the
 * link hello was never answered, the A15s timed out about 5 s later (EOFException) and the ASUS 5 s after that. Six of
 * seven A15s-initiated exchanges failed that way on builds f0552884 and f0a89e03 (lane3-asus-a15s-m31-20261013), while
 * the ASUS-initiated ones and every call that used the guard worked. The same signature was captured earlier with HCI snoop
 * (AdaptivePhyMemory.SETUP_STALL_MS: only the first 251-octet fragment of the A15s hello reached the ASUS, the same request
 * on 1M went ACTIVE in 2.5 s).
 *
 * Same rules as the call: the first 2M attempt is closed when hop authentication has not finished after the stall guard, one
 * bounded 1M retry (to the peer's current advertisement) follows inside [AdaptivePhyMemory.EARLY_LOSS_MS], and a peer that
 * needed the retry, or whose 2M exchange died early without any answer, is remembered as bad on 2M so the next dial starts
 * on 1M. Pure and injectable so the ordering is testable without a radio.
 */
internal object MessageDialPhy {
    /** The 1M retry after a failed first 2M link: LE only, direct, the first attempt really on 2M, inside the early-loss window. */
    fun retryEligible(enabled: Boolean, running: Boolean, le: Boolean, firstPhy: BlePhyPreference, elapsedMs: Long): Boolean =
        adaptivePhySetupRetryEligible(
            enabled = enabled,
            running = running,
            le = le,
            direct = true,
            firstOnTwoM = firstPhy == BlePhyPreference.TWO_M,
            elapsedMs = elapsedMs,
        )

    class Dialed<C, L>(
        val connection: C,
        val link: L,
        /** The PHY the authenticated link was requested on. */
        val phy: BlePhyPreference,
        /** True when the first (2M) attempt failed and this link is the 1M retry. */
        val retried: Boolean,
        val firstError: IOException?,
    )

    /**
     * Runs [attempt] on [firstPhy]; when it fails with an IOException and [retryAllowed] still permits (given the elapsed
     * time), calls [beforeRetry] (close the dead link, find the peer's new advertisement) and runs [attempt] once more on 1M.
     * [attempt] gets the stall guard to use: [stallGuardMs] for a first attempt that can still retry, otherwise 0.
     */
    fun <C, L> dial(
        firstPhy: BlePhyPreference,
        stallGuardMs: Long,
        retryAllowed: (elapsedMs: Long) -> Boolean,
        elapsedMs: () -> Long,
        attempt: (phy: BlePhyPreference, stallGuardMs: Long) -> Pair<C, L>,
        beforeRetry: (IOException) -> Unit,
    ): Dialed<C, L> {
        val guard = if (retryAllowed(0L)) stallGuardMs else 0L
        try {
            val first = attempt(firstPhy, guard)
            return Dialed(first.first, first.second, firstPhy, retried = false, firstError = null)
        } catch (error: IOException) {
            if (!retryAllowed(elapsedMs())) throw error
            beforeRetry(error)
            val second = attempt(BlePhyPreference.ONE_M_ADAPTIVE, 0L)
            return Dialed(second.first, second.second, BlePhyPreference.ONE_M_ADAPTIVE, retried = true, firstError = error)
        }
    }

    /**
     * Opens one link and authenticates it ([authenticate] is the hop handshake). With [stallGuardMs] > 0 a link whose hop
     * authentication has not finished that long after it opened is closed, so a link that carries nothing fails fast
     * (as a [CallSetupStalledException]) into the 1M retry. Exactly one side wins a handshake that completes at the deadline
     * ([CallSetupStallState]): it is kept whole or closed and retried, never both.
     */
    fun <C, L> openAndAuthenticate(
        stallGuardMs: Long,
        open: () -> C,
        close: (C) -> Unit,
        authenticate: (C) -> L,
        running: () -> Boolean,
        nowMs: () -> Long,
        onStall: (sinceOpenMs: Long) -> Unit = {},
    ): Pair<C, L> {
        val connection = open()
        val state = CallSetupStallState()
        val settled = CountDownLatch(1)
        val openedAtMs = nowMs()
        if (stallGuardMs > 0L) {
            thread(name = "MessageSyncSetupStall", isDaemon = true) {
                val settledInTime = try {
                    settled.await(stallGuardMs, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return@thread
                }
                val sinceOpenMs = nowMs() - openedAtMs
                if (running() && adaptivePhySetupStalled(stallGuardMs, settledInTime, sinceOpenMs) && state.claimStall()) {
                    onStall(sinceOpenMs)
                    runCatching { close(connection) }
                }
            }
        }
        try {
            val link = authenticate(connection)
            if (!state.authenticated()) throw CallSetupStalledException("LE message setup stalled")
            return connection to link
        } catch (error: Exception) {
            runCatching { close(connection) }
            if (state.stalled && error !is CallSetupStalledException) {
                throw CallSetupStalledException("LE message setup stalled", error)
            }
            throw error
        } finally {
            state.ended()
            settled.countDown()
        }
    }

    /**
     * An authenticated 2M exchange that died early without any answer: no chunk moved either way, no card arrived (so the
     * peer's HELLO never did), and it ended within [AdaptivePhyMemory.EARLY_LOSS_MS] of authentication. A suspend, a rate
     * limit, a protocol refusal or a finished exchange is never link evidence.
     */
    fun earlyLossWithoutAnswer(
        outcome: StoreForwardSyncOutcome,
        chunksSent: Int,
        chunksReceived: Int,
        cardReceived: Boolean,
        sinceAuthMs: Long,
        wasTwoM: Boolean,
        sendLinks: Int,
    ): Boolean {
        val dead = outcome == StoreForwardSyncOutcome.INTERRUPTED || outcome == StoreForwardSyncOutcome.TIMEOUT
        return dead && chunksSent == 0 && chunksReceived == 0 && !cardReceived &&
            AdaptivePhyMemory.isEarlyLoss(1L, sinceAuthMs + 1L, wasTwoM, sendLinks)
    }
}
