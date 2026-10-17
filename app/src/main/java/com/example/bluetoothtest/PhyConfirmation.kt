package com.example.bluetoothtest

/**
 * Batch P (BACKLOG P-04 residue, CONN-09): what counts as a confirmed PHY, how long to wait for the
 * stack to say so, and which PHY mask a connection attempt carries. Pure functions and constants, so
 * they are JVM-testable; the GATT calls stay with the callers.
 *
 * `BluetoothDevice.PHY_LE_*` values are 1/2/3 (the PHY a callback reports) and the `*_MASK` values are
 * 1/2/4 (the PHYs a request allows). Spelled out here so tests need no Android class.
 */
internal object LePhy {
    const val ONE_M = 1
    const val TWO_M = 2
    const val CODED = 3
    const val MASK_1M = 1
    const val MASK_2M = 2
    const val MASK_CODED = 4

    /** The single PHY a one-bit [mask] names, or null for an empty or multi-PHY mask. */
    fun valueForMask(mask: Int): Int? = when (mask) {
        MASK_1M -> ONE_M
        MASK_2M -> TWO_M
        MASK_CODED -> CODED
        else -> null
    }

    /**
     * The PHY mask to hand `connectGatt(..., phy)`: always includes 1M. The M31 (Android 12, legacy
     * stack) needs 1M in the mask (root's phone finding; legacy advertising and so the first
     * connection are 1M anyway); a 2M or Coded request is made after the connection with
     * setPreferredPhy. Android 14+ gd ignores the argument and initiates on every PHY the controller
     * supports (a14 le_impl.h; AOSP 70a29b679b removed the parameter), so adding 1M there changes
     * nothing. The app still calls the four-argument connectGatt; a call site that passes a mask uses this.
     */
    fun connectGattMask(requestedMask: Int): Int = (requestedMask and (MASK_1M or MASK_2M or MASK_CODED)) or MASK_1M
}

/**
 * Coded PHY is confirmed only by a PHY the stack REPORTED (onPhyUpdate, or onPhyRead, status 0, tx ==
 * Coded). A request, `isLeCodedPhySupported`, a queued `setPreferredPhy` or a non-error return never
 * is: the flag reflects a feature bit (Nordic Android-BLE-Library #166, AOSP
 * LeScanningManager::configure_scan scans 1M only before Android 15), and the maintainers report that
 * the option is a hint ("Android just prefers LE 2M if the other side supports it").
 */
internal fun codedPhyConfirmed(reportedTx: Int?, status: Int?): Boolean =
    reportedTx == LePhy.CODED && status == 0

/**
 * The experimental PHY whose capacity prior may seed a pacer: a non-Coded arm always (LE 1M is where
 * every link starts), a Coded arm only after the link reported Coded ([confirmedTx], the last tx PHY
 * the stack reported with status 0). An unconfirmed Coded request returns null, so the prior of the
 * PHY the link really runs on applies. Hook: LinkCapacity / LinkSelfMeasurement pass the result.
 */
internal fun trustedExperimentalPhy(requested: ExperimentalBlePhy?, confirmedTx: Int?): ExperimentalBlePhy? =
    when {
        requested == null -> null
        !requested.coded -> requested
        confirmedTx == LePhy.CODED -> requested
        else -> null
    }

/**
 * How a PHY request is followed up when the stack may never call onPhyUpdate.
 *
 * Android 13 base does not appear to forward the PHY-update event to GATT (the GD ACL shim wired it
 * in 13 QPR1, e0fd13ef08, "Pass PHY change event up to GATT"; aosp-bt-versions CONN-09, derived from
 * the diff, MEDIUM). Nordic's library waits 1 s and then calls readPhy (BleManagerHandler,
 * 46d17813); Blessed gives up on the callback after 200 ms on Android 13 so its queue is not blocked.
 * [readPhy] is a read of the controller state, it starts no LL procedure, so polling it is safe.
 *
 * Polls happen at [firstPollMs], then every [pollIntervalMs] until [deadlineMs] after the request.
 * A poll that reports the requested PHY ends the wait; the deadline ends it with "unconfirmed".
 */
internal data class PhyWaitPlan(
    val firstPollMs: Long,
    val pollIntervalMs: Long,
    val deadlineMs: Long,
) {
    init {
        require(firstPollMs > 0 && pollIntervalMs > 0 && deadlineMs >= firstPollMs)
    }

    /** Offsets (ms after the request) at which readPhy is called. */
    fun pollOffsets(): List<Long> {
        val offsets = ArrayList<Long>()
        var at = firstPollMs
        while (at <= deadlineMs) {
            offsets += at
            at += pollIntervalMs
        }
        return offsets
    }

    companion object {
        /** Nordic's behaviour: one readPhy 1 s after the request, then up to 500 ms for its result. */
        val NORDIC = PhyWaitPlan(firstPollMs = 1_000L, pollIntervalMs = 500L, deadlineMs = 1_000L)

        /** Android 13: first look at 200 ms (Blessed), then every 400 ms, give up at 2 s. */
        val ANDROID_13 = PhyWaitPlan(firstPollMs = 200L, pollIntervalMs = 400L, deadlineMs = 2_000L)

        /** The plan for this phone: [ANDROID_13] where the backstop entry applies, else [NORDIC]. */
        fun forDevice(sdkInt: Int, disabledPolicies: Set<String>): PhyWaitPlan =
            if (PhyLinkPolicy.phyCallbackBackstopActive(sdkInt, disabledPolicies)) ANDROID_13 else NORDIC

        /** Runtime form of [forDevice]; needs no registry membership of the entry. */
        fun current(): PhyWaitPlan = forDevice(android.os.Build.VERSION.SDK_INT, DeviceFamilyPolicy.switchedOff)
    }
}

/** Where a PHY request ended. */
internal enum class PhyRequestEnd(val label: String) {
    /** onPhyUpdate reported (status 0) the requested PHY. */
    UPDATE("update"),

    /** onPhyUpdate reported a different PHY or a non-zero status. */
    UPDATE_OTHER("update_other"),

    /** No update; a readPhy poll reported the requested PHY. */
    READ("read"),

    /** No update and no poll saw the requested PHY by the deadline: the PHY is unknown. */
    UNCONFIRMED("unconfirmed"),
}

/**
 * Follows one PHY request to its end: report callbacks in, polls out. [scheduleAt] runs the action
 * [delayMs] later (a Handler on a phone, a fake in tests); a poll after the request ended is a no-op.
 * Thread-safe; [onEnd] runs once, outside the lock.
 */
internal class PhyRequestFollower(
    private val link: String,
    private val expectedTx: Int,
    private val plan: PhyWaitPlan,
    private val readPhy: () -> Boolean,
    private val scheduleAt: (delayMs: Long, action: () -> Unit) -> Unit,
    private val log: (String) -> Unit,
    private val nowMs: () -> Long,
    private val onEnd: (PhyRequestEnd, Int?) -> Unit = { _, _ -> },
) {
    private var startedAtMs = -1L
    private var ended: PhyRequestEnd? = null
    private var polls = 0
    private var lastReportedTx: Int? = null

    companion object {
        /** Nordic waits up to 500 ms for the readPhy result (BleManagerHandler). */
        const val RESULT_GRACE_MS = 500L
    }

    val end: PhyRequestEnd? @Synchronized get() = ended

    /** Call right after the PHY request was handed to the stack. */
    fun start() {
        synchronized(this) {
            if (startedAtMs >= 0) return
            startedAtMs = nowMs()
        }
        plan.pollOffsets().forEach { offset -> scheduleAt(offset) { poll() } }
        // The last poll's result arrives asynchronously: the request ends RESULT_GRACE_MS after the deadline.
        scheduleAt(plan.deadlineMs + RESULT_GRACE_MS) { finish(PhyRequestEnd.UNCONFIRMED, null) }
    }

    /** onPhyUpdate. */
    fun onUpdate(tx: Int, status: Int) {
        if (status == 0 && tx == expectedTx) finish(PhyRequestEnd.UPDATE, tx) else finish(PhyRequestEnd.UPDATE_OTHER, tx)
    }

    /** onPhyRead (the result of a poll or of any other readPhy). */
    fun onRead(tx: Int, status: Int) {
        if (status != 0) return
        synchronized(this) { lastReportedTx = tx }
        if (tx == expectedTx) finish(PhyRequestEnd.READ, tx)
    }

    private fun poll() {
        synchronized(this) {
            if (ended != null) return
            polls++
        }
        runCatching(readPhy)
    }

    private fun finish(end: PhyRequestEnd, tx: Int?) {
        val sinceMs: Long
        val pollCount: Int
        val reported: Int?
        synchronized(this) {
            if (ended != null) return
            ended = end
            sinceMs = nowMs() - startedAtMs
            pollCount = polls
            reported = tx ?: lastReportedTx
        }
        log(
            "BENCHMARK phy_follow: link=$link | expected=${lePhyLabel(expectedTx)} | end=${end.label} | " +
                "reported=${reported?.let(::lePhyLabel) ?: "none"} | polls=$pollCount | waitedMs=$sinceMs",
        )
        runCatching { onEnd(end, reported) }
    }
}
