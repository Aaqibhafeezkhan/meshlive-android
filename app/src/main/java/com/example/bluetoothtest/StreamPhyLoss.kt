package com.example.bluetoothtest

import java.io.IOException

/**
 * Sender-side bad-2M memory for a direct GATT stream (2026-10-13, Experimental protection, default on; switch
 * `test_stream_adaptive_phy`, SWITCHES.md). A call dial and a message dial already remember a hop peer whose 2M link dies right
 * after setup ([AdaptivePhyMemory.isEarlyLoss]) so the next dial starts on 1M. A stream source did not: the A15s (OPPO CPH2179,
 * Android 10, MediaTek mt6765) as the GATT central to the ASUS never kept a 2M link in 11 completed attempts on build d8c7589c (9 ended with connection timeout, 2 by an app close after the link crawled)
 * (lane4-asus-a15s-m31-d8c7589c-20261013/b6-a15s-source-diag): LE PHY Update to 2M, data crawling or stopped within 0.4-12.7 s,
 * the A15s link layer reporting reason 0x08 (connection timeout, GATT status 8) 5 s after its last reception and the ASUS reporting
 * the same 5.0 s later; the viewer then re-requested the stream, the A15s dialed again on 2M and the cycle repeated for the whole
 * run (three of the attempts had not even started the encoder, so the encoder load is not the cause).
 *
 * The rule is the call rule applied to the source: a 2M single-link GATT stream whose peer -> source direction went silent within
 * [AdaptivePhyMemory.EARLY_LOSS_MS] of hop authentication marks the authenticated hop peer bad on 2M. Silence is read from the
 * connection-timeout status (the link layer stopped hearing the peer [supervisionMs] before it reported the loss) or from the
 * sender's own heartbeat timeout. A peer's departure (status 19), a local close, a relay-capable peer (its other hop shares the
 * radio) and a fan-out source ([NodeLinkLoad.sendLinks] of 2 or more) never count. Pure so the ordering is testable.
 */
internal object StreamPhyLoss {
    /** GATT status of a link-layer connection timeout (HCI error 0x08, Core Vol 1 Part F 1.3 "Connection Timeout"). */
    const val GATT_CONN_TIMEOUT = 8

    /** The sender's own message when the immediate child's periodic heartbeat stopped. */
    const val HEARTBEAT_TIMED_OUT = "Immediate child heartbeat timed out"

    /**
     * A link that is still not connected this long after it opened is a stalled setup. Healthy GATT setups reach the connected
     * stream in about 1 to 2 s (hop authentication p90 1.05 s on the slowest phone, [AdaptivePhyMemory.SETUP_STALL_MS] guard 3 s);
     * on the A15s to ASUS 2M link the same steps took 2.4 to 6.9 s, and 3 of 8 attempts in b8 B1 ended in the owner's 12 s
     * connection timeout with the hop handshake or the end-to-end handshake unfinished.
     */
    const val SETUP_STALL_MS = 5_000L

    enum class Cause {
        /** The link layer stopped hearing the peer for the supervision timeout. */
        SUPERVISION_TIMEOUT,

        /** The peer's authenticated heartbeat stopped for the sender's heartbeat timeout. */
        HEARTBEAT_TIMEOUT,

        /** The link was closed (typically by the broadcaster's connection timeout) before the stream connected, after [SETUP_STALL_MS]. */
        SETUP_TIMEOUT,

        /** Anything else: a departure, a local close, a stack error. Never link evidence. */
        OTHER,
    }

    fun cause(disconnectStatus: Int, failure: Throwable?, connected: Boolean = true, sinceOpenMs: Long = 0L): Cause = when {
        disconnectStatus == GATT_CONN_TIMEOUT -> Cause.SUPERVISION_TIMEOUT
        failure is IOException && failure.message == HEARTBEAT_TIMED_OUT -> Cause.HEARTBEAT_TIMEOUT
        !connected && sinceOpenMs >= SETUP_STALL_MS -> Cause.SETUP_TIMEOUT
        else -> Cause.OTHER
    }

    /**
     * When the peer -> source direction went silent, on the clock of [lostAtMs]. A connection timeout is reported
     * [supervisionMs] after the last packet; a heartbeat timeout counts from the last authenticated heartbeat.
     */
    fun silentSinceMs(cause: Cause, lostAtMs: Long, supervisionMs: Long, lastHeartbeatAtMs: Long): Long = when (cause) {
        Cause.SUPERVISION_TIMEOUT -> if (supervisionMs > 0L) (lostAtMs - supervisionMs).coerceAtLeast(0L) else lostAtMs
        Cause.HEARTBEAT_TIMEOUT -> if (lastHeartbeatAtMs in 1..lostAtMs) lastHeartbeatAtMs else lostAtMs
        Cause.SETUP_TIMEOUT, Cause.OTHER -> lostAtMs
    }

    data class Verdict(val record: Boolean, val reason: String)

    @Suppress("LongParameterList")
    fun verdict(
        enabled: Boolean,
        endedLocally: Boolean,
        gatt: Boolean,
        cause: Cause,
        authenticatedAtMs: Long,
        silentSinceMs: Long,
        wasTwoM: Boolean,
        sendLinks: Int,
        peerRelays: Boolean,
        peerKnown: Boolean = true,
    ): Verdict = when {
        !enabled -> Verdict(false, "disabled")
        endedLocally -> Verdict(false, "ended_locally")
        !gatt -> Verdict(false, "not_gatt")
        cause == Cause.OTHER -> Verdict(false, "not_link_loss")
        authenticatedAtMs <= 0L -> Verdict(false, "not_started")
        // A rotating advertised pseudonym that never authenticated names no peer the memory could find again.
        !peerKnown -> Verdict(false, "unknown_peer")
        !wasTwoM -> Verdict(false, "not_two_m")
        sendLinks >= 2 -> Verdict(false, "fan_out")
        peerRelays -> Verdict(false, "hop_relays")
        !AdaptivePhyMemory.isEarlyLoss(authenticatedAtMs, maxOf(silentSinceMs, authenticatedAtMs), wasTwoM, sendLinks) ->
            Verdict(false, "not_early")
        else -> Verdict(true, "early_loss")
    }
}
