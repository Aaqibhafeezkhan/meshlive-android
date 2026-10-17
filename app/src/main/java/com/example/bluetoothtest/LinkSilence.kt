package com.example.bluetoothtest

import android.os.SystemClock

/**
 * One classifier for "the link is up but the peer says nothing" (G-05), instead of a separate timeout per call site.
 * The same signature appears in several reports: Android 13/14 OnePlus and Samsung S22 connect, negotiate MTU, then
 * service discovery never completes (Android-BLE-Library#448); an OxygenOS stack ignoring Service Changed hangs at
 * ATT MTU 23 (rgb-sunglasses#115); a phone out of GATT resources keeps an incoming link up with no ATT bearer until its
 * own timeout (AOSP c77ceb7f30, fixed in android14-qpr3). The classifier only observes: it is fed the setup stages a
 * GATT or CoC-control open already goes through, and reports the first verdict for each. What the caller does with a
 * verdict is a separate decision ([RecoveryStep]); the settle delay before the first operation is the Experimental
 * `test_gatt_settle_ms`.
 */
internal class LinkSilence(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    enum class Stage(val label: String, val limitMs: Long) {
        MTU("mtu", MTU_LIMIT_MS),
        DISCOVERY("discovery", DISCOVERY_LIMIT_MS),
        SUBSCRIBE("subscribe", SUBSCRIBE_LIMIT_MS),
        PSM_READ("psm_read", PSM_READ_LIMIT_MS),
        FIRST_NOTIFICATION("first_notification", FIRST_NOTIFICATION_LIMIT_MS),
    }

    enum class Kind(val label: String) {
        /** Connected, and no ATT response of any kind to the first request: the remote probably has no GATT block left. */
        REMOTE_GATT_FULL("remote_gatt_full"),

        /** A setup stage ran past its limit although other ATT traffic worked before it. */
        STAGE_HANG("stage_hang"),

        /** Notifications were enabled and none arrived in time. */
        NOTIFICATION_SILENT("notification_silent"),
    }

    data class Verdict(val kind: Kind, val stage: Stage, val waitedMs: Long, val connectedForMs: Long) {
        fun describe(): String =
            "kind=${kind.label} | stage=${stage.label} | waitedMs=$waitedMs | connectedForMs=$connectedForMs"
    }

    private var connectedAtMs = NOT_SET
    private var firstAttRequestAtMs = NOT_SET
    private var attResponses = 0
    private val started = LinkedHashMap<Stage, Long>()
    private val reported = HashSet<Stage>()
    private var remoteFullReported = false

    @Synchronized
    fun onConnected() {
        connectedAtMs = clock()
    }

    /** The link was lost or replaced: forget everything. */
    @Synchronized
    fun reset() {
        connectedAtMs = NOT_SET
        firstAttRequestAtMs = NOT_SET
        attResponses = 0
        started.clear()
        reported.clear()
        remoteFullReported = false
    }

    @Synchronized
    fun begin(stage: Stage) {
        val now = clock()
        started[stage] = now
        if (stage != Stage.FIRST_NOTIFICATION && firstAttRequestAtMs == NOT_SET) firstAttRequestAtMs = now
    }

    /** The stage's response arrived (callback or notification). */
    @Synchronized
    fun complete(stage: Stage) {
        started.remove(stage)
        attResponses++
    }

    /**
     * The first verdict not yet reported, or null. Call from the setup poll loop; each verdict is returned once.
     * [remoteFullAfterMs] is the ATT-silence window before a link is called remote-full (default 2 s, backlog entry 24).
     */
    @Synchronized
    fun check(remoteFullAfterMs: Long = REMOTE_FULL_AFTER_MS): Verdict? {
        val now = clock()
        val connectedFor = if (connectedAtMs == NOT_SET) 0L else now - connectedAtMs
        if (connectedAtMs == NOT_SET) return null
        if (!remoteFullReported && attResponses == 0 && firstAttRequestAtMs != NOT_SET &&
            now - firstAttRequestAtMs >= remoteFullAfterMs
        ) {
            remoteFullReported = true
            val stage = started.keys.firstOrNull() ?: Stage.MTU
            return Verdict(Kind.REMOTE_GATT_FULL, stage, now - firstAttRequestAtMs, connectedFor)
        }
        for ((stage, startedAt) in started) {
            if (stage in reported) continue
            val waited = now - startedAt
            if (waited < stage.limitMs) continue
            reported += stage
            val kind = when {
                stage == Stage.FIRST_NOTIFICATION -> Kind.NOTIFICATION_SILENT
                attResponses == 0 -> if (remoteFullReported) continue else Kind.REMOTE_GATT_FULL
                else -> Kind.STAGE_HANG
            }
            return Verdict(kind, stage, waited, connectedFor)
        }
        return null
    }

    /** What to do after a verdict, in order. The caller escalates one step per failed recovery of the same peer. */
    enum class RecoveryStep(val label: String) {
        /** Close the client, wait the settle delay, reconnect the same way. */
        CLOSE_AND_RECONNECT("close_wait_reconnect"),

        /** Close, wait, reconnect asking for no 2M PHY (Android-BLE-Library#448 maintainer advice). */
        RECONNECT_WITHOUT_2M("reconnect_without_2m"),

        /** Stop retrying and tell the user (B2 text). */
        REPORT("report"),
    }

    companion object {
        const val REMOTE_FULL_AFTER_MS = 2_000L
        const val MTU_LIMIT_MS = 3_000L
        const val DISCOVERY_LIMIT_MS = 10_000L
        const val SUBSCRIBE_LIMIT_MS = 5_000L
        const val PSM_READ_LIMIT_MS = 5_000L
        const val FIRST_NOTIFICATION_LIMIT_MS = 5_000L

        /** Start comparator for the settle delay (Gadgetbridge: 300 ms; 1000 ms on Android 15+ for bonded devices). */
        const val SETTLE_UNBONDED_MS = 300L
        const val SETTLE_BONDED_ANDROID15_MS = 1_000L

        private const val NOT_SET = Long.MIN_VALUE

        fun recoveryStep(failuresForPeer: Int): RecoveryStep = when {
            failuresForPeer <= 0 -> RecoveryStep.CLOSE_AND_RECONNECT
            failuresForPeer == 1 -> RecoveryStep.RECONNECT_WITHOUT_2M
            else -> RecoveryStep.REPORT
        }

        /**
         * `requestConnectionPriority` sends a supervision timeout of 5 s on Android 10+ and 20 s on Android 8-9 (AOSP
         * GattService connectionParameterUpdate, d32b2d461; aosp-bt-versions CONN-01). Every HIGH or BALANCED request
         * therefore arms a 5 s silence limit.
         */
        fun prioritySupervisionMs(sdk: Int): Long = if (sdk in 26..28) 20_000L else 5_000L

        /** A status-8 drop after a silence within half a second of the priority supervision timeout is that timer, not a stack fault. */
        fun status8MatchesPrioritySupervision(status: Int, silentMs: Long, sdk: Int): Boolean =
            status == 8 && kotlin.math.abs(silentMs - prioritySupervisionMs(sdk)) <= 500L

        /** 129 (GATT_INTERNAL_ERROR), 133 (GATT_ERROR) and 8 (supervision timeout) need a full teardown and a wait, not an in-place reconnect. */
        fun needsFullTeardown(status: Int): Boolean = status == 129 || status == 133 || status == 8

        /**
         * The post-CONNECTED wait before the first GATT operation. MeshLive does not bond, so 300 ms is the comparator;
         * [override] is the Experimental `test_gatt_settle_ms` (0 = none, which is the shipped behaviour).
         */
        fun settleDelayMs(sdk: Int, bonded: Boolean, override: Int): Long =
            if (override > 0) override.toLong()
            else if (bonded && sdk >= 35) SETTLE_BONDED_ANDROID15_MS else SETTLE_UNBONDED_MS

        /** The comparator the settle delay would use, as a log field when the shipped behaviour (no delay) runs. */
        fun fingerprint(traits: DeviceTraits): String =
            "sdk=${traits.sdkInt} | patch=${traits.securityPatch.ifEmpty { "?" }} | manufacturer=${traits.manufacturer.lowercase()} | " +
                "soc=${traits.socFamily.name} | skin=${traits.oemSkin.name}"

        fun logLine(verdict: Verdict, link: String, peerTail: String?, status: Int?, fingerprint: String, sdk: Int = 0): String =
            "BENCHMARK gatt_link_silence: link=$link | peer=...${peerTail ?: "?"} | ${verdict.describe()} | " +
                "lastStatus=${status ?: "-"} | fullTeardown=${status?.let(::needsFullTeardown) ?: false} | " +
                "prioritySupervisionMs=${prioritySupervisionMs(sdk)} | $fingerprint"
    }
}

/**
 * What a failed direct connect's duration says (aosp-bt-versions CONN-04). The stack ends a direct connect after 30 s
 * (L2CAP_BLE_LINK_CONNECT_TIMEOUT_MS, GD kCreateConnectionTimeoutMs) with GATT 133, or 147 (0x93) from Android 14 QPR3;
 * 0.4-1 s is HCI 0x3E (failed to establish). There is no Samsung-specific 5 s timer in AOSP: a failure at 5 s is
 * classified by its duration like any other, and a 5 s cut on one OEM would be an OEM stack change, not a known rule.
 */
internal enum class ConnectDurationClass(val label: String) {
    FAST_ESTABLISH_FAILURE("fast_0x3e"),
    OWN_DEADLINE("own_deadline"),
    STACK_30S_TIMEOUT("stack_30s_timeout"),
    OTHER("other");

    companion object {
        fun classify(status: Int?, elapsedMs: Long, ownDeadlineMs: Long): ConnectDurationClass = when {
            status == null -> OTHER
            elapsedMs in 28_000L..32_500L && (status == 133 || status == 147) -> STACK_30S_TIMEOUT
            elapsedMs <= 1_500L && (status == 133 || status == 62) -> FAST_ESTABLISH_FAILURE
            elapsedMs >= ownDeadlineMs - 1_000L && elapsedMs <= ownDeadlineMs + 1_500L -> OWN_DEADLINE
            else -> OTHER
        }
    }
}
