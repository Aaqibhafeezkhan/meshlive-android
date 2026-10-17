package com.example.bluetoothtest

import android.content.Context

/** Outcome of the dual-role probe. A pass is never assumed: it needs both links to have carried data in the same window. */
internal enum class DualRoleResult(val label: String) {
    UNPROVEN("unproven"),
    PASS("pass"),
    FAIL("fail"),
}

/**
 * Dual-role capability probe (BACKLOG P-03, controllers.md C-08/C-19).
 *
 * A relay is peripheral on its parent link and central on its child link at the same time. Android does
 * not expose the controller's supported LE states; Linux validates central+peripheral for some Qualcomm
 * parts only (hci_qca.c QCA_CAP_VALID_LE_STATES: QCA2066, WCN3991, WCN6750, WCN6855, WCN7850; WCN3990,
 * WCN3998, WCN3950, WCN3988, QCA6390 get HCI_QUIRK_BROKEN_LE_STATES, commit 547801380e), and says controllers
 * "have had trouble initiating a connectable advertisement despite the state combination being reported as
 * supported" (hci.h). So dual role is a capability to PROBE per phone, not to assume, and the answer is only
 * logged: this class changes no behaviour. It never widens the Exynos 9611 entry (C: no external evidence).
 *
 * The probe watches the lifecycle the relay already goes through: parent up -> child connect -> child data,
 * with the parent still alive. States and the rule for each answer:
 *  - FAIL: a child connect failed while the parent was up, and no child ever carried data concurrently;
 *  - PASS: a child link carried at least [minChildBytes] with the peer heard, over a window of at least
 *    [minConcurrentMs], the parent was up for the whole window and showed activity inside it;
 *  - UNPROVEN otherwise (including a relay whose parent never reported activity: nothing is credited).
 * The result is sticky once PASS (a later failure is logged as `mixed`, not turned into FAIL).
 *
 * Thread-safe; events may come from any thread. [onResult] runs outside the lock when the answer changes.
 */
internal class DualRoleProbe(
    private val nowMs: () -> Long,
    private val log: (String) -> Unit = {},
    private val minChildBytes: Long = 8_192L,
    private val minConcurrentMs: Long = 2_000L,
    private val activeWindowMs: Long = 5_000L,
    private val onResult: (DualRoleResult, Int) -> Unit = { _, _ -> },
) {
    private class Child {
        var connectStartedAt = -1L
        var connectedAt = -1L
        var firstDataAt = -1L
        var lastDataAt = -1L
        var bytes = 0L
        var peerHeard = false
        var failed: String? = null
    }

    private val lock = Any()
    private val children = HashMap<String, Child>()
    private var parentUpAt = -1L
    private var parentLostAt = -1L
    private var parentActivity = ArrayList<Long>()
    private var result = DualRoleResult.UNPROVEN
    private var maxConcurrent = 0
    private var failedWhileParentUp: String? = null
    private var lastEvaluatedAt = -1L

    private companion object {
        const val EVALUATE_EVERY_MS = 250L
    }

    val current: DualRoleResult get() = synchronized(lock) { result }

    /** The most children that carried data inside one [activeWindowMs] with the parent up. */
    val maxConcurrentChildren: Int get() = synchronized(lock) { maxConcurrent }

    fun onParentUp() {
        synchronized(lock) {
            parentUpAt = nowMs()
            parentLostAt = -1L
            parentActivity.clear()
        }
    }

    /** Data moved on the parent link (read from it, or written to it). */
    fun onParentActivity() {
        synchronized(lock) {
            if (parentUpAt < 0) return
            parentActivity += nowMs()
            if (parentActivity.size > 64) parentActivity.removeAt(0)
        }
    }

    fun onParentLost() {
        synchronized(lock) { parentLostAt = nowMs() }
    }

    fun onChildConnectStarted(child: String) {
        synchronized(lock) { children.getOrPut(child) { Child() }.connectStartedAt = nowMs() }
    }

    fun onChildConnected(child: String) {
        synchronized(lock) { children.getOrPut(child) { Child() }.connectedAt = nowMs() }
    }

    /** A child connect (or its first exchange) failed with [reason] (a status, a timeout); counted only while the parent is up. */
    fun onChildFailed(child: String, reason: String) {
        var change: DualRoleResult? = null
        synchronized(lock) {
            children.getOrPut(child) { Child() }.failed = reason
            if (parentUpAt >= 0 && parentLostAt < 0) {
                failedWhileParentUp = reason
            }
            change = evaluateLocked()
        }
        publish(change)
    }

    /** [bytes] handed to a child link; [peerHeard] is true when that child is also answering (data arriving from it). */
    fun onChildData(child: String, bytes: Int, peerHeard: Boolean) {
        var change: DualRoleResult? = null
        synchronized(lock) {
            val c = children.getOrPut(child) { Child() }
            val now = nowMs()
            if (c.firstDataAt < 0) c.firstDataAt = now
            c.lastDataAt = now
            c.bytes += bytes
            if (peerHeard) c.peerHeard = true
            // A write per packet is far more often than the answer can change: look at most every 250 ms.
            if (lastEvaluatedAt < 0 || now - lastEvaluatedAt >= EVALUATE_EVERY_MS) {
                lastEvaluatedAt = now
                change = evaluateLocked()
            }
        }
        publish(change)
    }

    /** `dual_role=<result>` plus the evidence, for the device_policy line. */
    fun summary(): String = synchronized(lock) {
        "dual_role=${result.label}(children=$maxConcurrent${failedWhileParentUp?.let { ",failed=$it" } ?: ""})"
    }

    private fun evaluateLocked(): DualRoleResult? {
        val now = nowMs()
        val parentAlive = parentUpAt >= 0 && parentLostAt < 0
        if (parentUpAt >= 0) {
            val active = children.values.count { it.lastDataAt >= 0 && now - it.lastDataAt <= activeWindowMs && it.firstDataAt >= parentUpAt }
            if (parentAlive && active > maxConcurrent) maxConcurrent = active
        }
        val proven = children.values.any { c ->
            c.bytes >= minChildBytes && c.peerHeard && c.firstDataAt >= parentUpAt && parentUpAt >= 0 &&
                c.lastDataAt - c.firstDataAt >= minConcurrentMs &&
                (parentLostAt < 0 || parentLostAt > c.lastDataAt) &&
                parentActivity.any { it in c.firstDataAt..c.lastDataAt }
        }
        val next = when {
            result == DualRoleResult.PASS -> DualRoleResult.PASS
            proven -> DualRoleResult.PASS
            failedWhileParentUp != null && maxConcurrent == 0 -> DualRoleResult.FAIL
            else -> DualRoleResult.UNPROVEN
        }
        if (next == result) return null
        result = next
        return next
    }

    private fun publish(change: DualRoleResult?) {
        if (change == null) return
        val (count, bytes) = synchronized(lock) { maxConcurrent to children.values.sumOf { it.bytes } }
        log(
            "BENCHMARK dual_role: result=${change.label} | maxConcurrentChildren=$count | childBytes=$bytes | " +
                "failedWhileParentUp=${synchronized(lock) { failedWhileParentUp } ?: "none"}",
        )
        runCatching { onResult(change, count) }
    }
}

/**
 * Child-link limit learned from this phone's own behaviour (BACKLOG P-03 "learned child-link limit"):
 * the highest number of children that carried data concurrently with the parent up ([provenOk]) and the
 * smallest number of open children at which one more connect failed with the parent up ([failedAt]). A
 * limit is learned only when [failedAt] is above [provenOk] (a contradiction clears the failure mark), and
 * it is `failedAt - 1`. Marks expire with the epoch (Bluetooth restart, security patch) or [ttlMs].
 * Advice only: nothing in this batch enforces it; admission (batch G) may read [learnedMax].
 */
internal class ChildLinkLimit(
    private val store: RollbackStore,
    private val epoch: () -> String,
    private val ttlMs: Long = LinkChangeRollback.TTL_MS,
) {
    private fun read(key: String, nowMs: Long): Int? {
        val raw = store.read(key) ?: return null
        val parts = raw.split('|')
        if (parts.size < 3) return null
        val at = parts[1].toLongOrNull() ?: return null
        if (parts[2] != epoch() || nowMs - at !in 0 until ttlMs) return null
        return parts[0].toIntOrNull()
    }

    fun provenOk(nowMs: Long): Int = read(OK, nowMs) ?: 0

    fun failedAt(nowMs: Long): Int? = read(FAIL, nowMs)

    /** [children] children carried data concurrently with the parent up. */
    fun recordSuccess(children: Int, nowMs: Long) {
        if (children <= 0) return
        if (children > provenOk(nowMs)) store.write(OK, "$children|$nowMs|${epoch()}")
        val failed = failedAt(nowMs)
        if (failed != null && failed <= children) store.remove(FAIL)
    }

    /** One more child failed to connect while [openChildren] were open and the parent was up. */
    fun recordFailure(openChildren: Int, nowMs: Long) {
        val failAt = openChildren + 1
        val existing = failedAt(nowMs)
        if (failAt <= provenOk(nowMs)) return
        if (existing == null || failAt < existing) store.write(FAIL, "$failAt|$nowMs|${epoch()}")
    }

    /** The largest child count believed to work, or null while nothing is learned. */
    fun learnedMax(nowMs: Long): Int? {
        val failed = failedAt(nowMs) ?: return null
        return (failed - 1).takeIf { it >= 1 && failed > provenOk(nowMs) }
    }

    private companion object {
        const val OK = "cl.ok"
        const val FAIL = "cl.fail"
    }
}

/** The app-wide probe and learned limit. Root hooks call the `on*` methods; the summary goes into the device_policy line. */
internal object DualRoleProbes {
    @Volatile private var enabledProvider: () -> Boolean = {
        PhyLinkPolicy.dualRoleProbeLogActive(DeviceFamilyPolicy.switchedOff)
    }

    private val probe = DualRoleProbe(
        nowMs = { System.nanoTime() / 1_000_000L },
        log = { line -> runCatching { android.util.Log.i("BluetoothVideo", line) } },
        onResult = { result, children -> learn(result, children) },
    )

    @Volatile private var limit: ChildLinkLimit? = null

    fun attach(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("child_link_limit", Context.MODE_PRIVATE)
        limit = ChildLinkLimit(PreferencesRollbackStore(prefs), { LinkChangeRollbacks.epochProvider() })
    }

    private fun learn(result: DualRoleResult, children: Int) {
        val l = limit ?: return
        val now = System.currentTimeMillis()
        if (result == DualRoleResult.PASS) l.recordSuccess(children, now)
    }

    fun summary(): String = if (enabledProvider()) probe.summary() else "dual_role=off"

    fun learnedMaxChildren(): Int? = limit?.learnedMax(System.currentTimeMillis())

    fun onParentUp() { if (enabledProvider()) probe.onParentUp() }
    fun onParentActivity() { if (enabledProvider()) probe.onParentActivity() }
    fun onParentLost() { if (enabledProvider()) probe.onParentLost() }
    fun onChildConnectStarted(child: String?) { if (enabledProvider() && child != null) probe.onChildConnectStarted(child) }
    fun onChildConnected(child: String?) { if (enabledProvider() && child != null) probe.onChildConnected(child) }

    /** A child connect failed while the parent link was up; [openChildren] children were already open. */
    fun onChildFailed(child: String?, reason: String, openChildren: Int) {
        if (!enabledProvider() || child == null) return
        probe.onChildFailed(child, reason)
        limit?.recordFailure(openChildren, System.currentTimeMillis())
    }

    fun onChildData(child: String?, bytes: Int, peerHeard: Boolean) {
        if (enabledProvider() && child != null) probe.onChildData(child, bytes, peerHeard)
    }
}
