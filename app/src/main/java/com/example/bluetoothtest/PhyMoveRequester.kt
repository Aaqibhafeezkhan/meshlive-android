package com.example.bluetoothtest

/**
 * Sends one PHY request for a central link and follows it to its end (BACKLOG P-01 and CONN-09), without any
 * Android type, so the sequencing is JVM-testable. [AdaptivePhyLink] builds one for its move to 1M.
 *
 *  - [gate] null: the request goes out at once (the behaviour before batch P).
 *  - [gate] set: the request is a PHY step of the [LlProcedureGate] (when the gate is off the step runs at once).
 *  - [plan] set (Android 13): after the request was queued, readPhy is polled per the plan until a report shows
 *    [targetTx] or the plan ends unconfirmed ([PhyRequestFollower]); the gate's PHY step is bound to the plan.
 *
 * [requestPhy] is the setPreferredPhy call (plus the immediate readPhy the app always made); it may throw.
 */
internal class PhyMoveRequester(
    private val link: String,
    private val requestPhy: () -> Unit,
    private val readPhy: () -> Boolean,
    private val targetTx: Int,
    private val gate: LlProcedureGate?,
    private val plan: PhyWaitPlan?,
    private val scheduleAt: (delayMs: Long, action: () -> Unit) -> Unit,
    private val nowMs: () -> Long,
    private val log: (String) -> Unit,
) {
    @Volatile private var follower: PhyRequestFollower? = null

    /** True when the request was queued by the stack, or admitted to the gate. */
    fun move(): Boolean {
        val start: () -> Boolean = {
            val ok = runCatching(requestPhy).isSuccess
            if (ok && plan != null) {
                PhyRequestFollower(
                    link = link,
                    expectedTx = targetTx,
                    plan = plan,
                    readPhy = readPhy,
                    scheduleAt = scheduleAt,
                    log = log,
                    nowMs = nowMs,
                ).also { follower = it }.start()
            }
            ok
        }
        val gate = gate ?: return start()
        val bound = plan?.let { it.deadlineMs + PhyRequestFollower.RESULT_GRACE_MS + 500L }
        return when (gate.submit(LlProcedure.PHY, "move_to_${lePhyLabel(targetTx).lowercase()}", expectedTx = targetTx, timeoutMs = bound, start = start)) {
            GateAdmission.IMMEDIATE_OK, GateAdmission.QUEUED -> true
            GateAdmission.IMMEDIATE_FAILED, GateAdmission.DROPPED_STUCK, GateAdmission.DROPPED_CLOSED -> false
        }
    }

    /** onPhyUpdate. */
    fun onUpdate(tx: Int, status: Int) {
        follower?.onUpdate(tx, status)
        gate?.onPhyUpdate(tx, status)
    }

    /** onPhyRead, or a report whose source is unknown: counts only when it shows the requested PHY. */
    fun onRead(tx: Int, status: Int) {
        follower?.onRead(tx, status)
        gate?.onPhyRead(tx, status)
    }
}
