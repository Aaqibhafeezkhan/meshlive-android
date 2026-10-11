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

    fun logLine(node: String, sinceOpenMs: Long): String =
        "call_setup: event=hop_auth_deadline | node=$node | sinceOpenMs=$sinceOpenMs | deadlineMs=$HOP_AUTH_MS | action=close"
}
