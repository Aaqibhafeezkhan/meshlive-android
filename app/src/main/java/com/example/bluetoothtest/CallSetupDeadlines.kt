package com.example.bluetoothtest

/**
 * Bounds and wording for the hop handshake of an outgoing call link (2026-10-12). Measured hop authentication takes
 * 71-269 ms once the link is open (16 call attempts of the combined privacy session), so a link that has not finished
 * it after [HOP_AUTH_MS] belongs to an answerer that will never finish: for example a phone that does not speak this
 * handshake, or a silent stranger. The first LE attempt on 2M keeps its own, shorter stall guard
 * ([AdaptivePhyMemory.SETUP_STALL_MS]); every other attempt gets this one, so a call never waits in "Calling" for
 * minutes behind a link that carries nothing.
 */
internal object CallSetupDeadlines {
    const val HOP_AUTH_MS = 10_000L

    fun timeoutMessage(): String =
        "The other phone did not answer the secure handshake within ${HOP_AUTH_MS / 1000} seconds. It may run an older " +
            "version of this app, or it accepts calls only from phones it knows. Update both phones and try again."

    fun closedMessage(): String =
        "The other phone closed the connection before the secure handshake finished. It may run an older version of " +
            "this app, or it accepts calls only from phones it knows. Update both phones and try again."

    /** The answering phone's line for a failed or refused incoming handshake: a fixed reason, never an identifier. */
    fun incomingFailureLine(error: Throwable): String {
        val reason = when {
            error is Bmh3Failure -> error.reason.replace(' ', '_')
            else -> error.javaClass.simpleName
        }
        return "incoming_handshake: result=failed | reason=$reason"
    }

    /**
     * The answering phone's silent-stranger bound (Q29, 2026-10-14): an accepted link that has not finished both the hop
     * and the end-to-end handshake within [DualSetupBudget.INCOMING_HANDSHAKE_MS] is closed. It first covered dual
     * endpoints only; now it covers every CoC and GATT call endpoint. [allEndpoints] false (DEBUG
     * `test_call_handshake_deadline_all=false`) restores the dual-only rule. A relay helper keeps its own pre-setup
     * watchdog ([RelayedCalls.PRE_SETUP_WAIT_MS]).
     */
    fun incomingDeadlineApplies(dualEndpoint: Boolean, relayedCallsOn: Boolean, allEndpoints: Boolean): Boolean =
        !relayedCallsOn && (dualEndpoint || allEndpoints)

    /** The one line when the incoming deadline closes a link; `scope` says which rule applied. */
    fun incomingDeadlineLine(dualEndpoint: Boolean): String =
        "incoming_handshake: event=deadline | closed=true | deadlineMs=${DualSetupBudget.INCOMING_HANDSHAKE_MS} | " +
            "scope=${if (dualEndpoint) "dual" else "single"}"

    fun logLine(node: String, sinceOpenMs: Long): String =
        "call_setup: event=hop_auth_deadline | node=$node | sinceOpenMs=$sinceOpenMs | deadlineMs=$HOP_AUTH_MS | action=close"
}
