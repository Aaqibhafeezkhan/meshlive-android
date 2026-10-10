package com.example.bluetoothtest

/**
 * Experimental (`test_coc_single_initiator`, default off): one deterministic initiator per direct peer
 * pair. The phone whose node-ID prefix sorts lower (unsigned bytes) dials at once; the other holds its
 * outgoing direct call for [HOLDOFF_MS] and yields when the peer's own call reaches it first, so two
 * phones calling each other at the same moment do not cross two CoC connects.
 *
 * Not a DeviceFamilyPolicy entry: the source review of 2026-10-10 found no official basis for a
 * stack-version rule. AOSP 4dc8b0421f0258d196f6947fe5bb7f404b0d981d ("l2cap: Notify upper layer about
 * credit based conn request collision", 2022-07, b/236216322) changes the enhanced (ECFC,
 * L2CAP_CMD_CREDIT_BASED_CONN_REQ) case, which EATT uses, and only notifies for the EATT PSM; app LE CoC
 * sockets use L2CAP_CMD_BLE_CREDIT_BASED_CONN_REQ, which android11-release l2c_ble.cc:558-635 accepts
 * without a pending-request check. The experiment therefore tests the app-level glare hypothesis only.
 */
internal object CocSingleInitiator {
    const val HOLDOFF_MS = 1_500L
    const val POLL_MS = 50L

    fun enabled(): Boolean = DebugTunables.cocSingleInitiator

    /** True when [local] is the designated initiator towards [peer]: lower unsigned prefix, ties initiate. */
    fun initiates(local: ByteArray, peer: ByteArray): Boolean {
        val n = minOf(local.size, peer.size)
        for (i in 0 until n) {
            val a = local[i].toInt() and 0xff
            val b = peer[i].toInt() and 0xff
            if (a != b) return a < b
        }
        return local.size <= peer.size
    }

    /** Holdoff before an outgoing direct call; 0 when off, relayed, or this phone is the initiator. */
    fun holdoffMs(enabled: Boolean, local: ByteArray, peer: ByteArray, direct: Boolean): Long =
        if (enabled && direct && !initiates(local, peer)) HOLDOFF_MS else 0L

    /**
     * Waits up to [holdoffMs] in [POLL_MS] steps; returns true (yield, do not dial) as soon as
     * [peerCallArrived] holds. [sleep] is injected for JVM tests.
     */
    fun awaitPeerCall(holdoffMs: Long, peerCallArrived: () -> Boolean, sleep: (Long) -> Unit = Thread::sleep): Boolean {
        var waited = 0L
        while (waited < holdoffMs) {
            if (peerCallArrived()) return true
            val step = minOf(POLL_MS, holdoffMs - waited)
            sleep(step)
            waited += step
        }
        return peerCallArrived()
    }

    fun line(role: String, peer: String, holdoffMs: Long, outcome: String): String =
        "BENCHMARK coc_single_initiator: role=$role | peer=$peer | holdoffMs=$holdoffMs | outcome=$outcome"
}
