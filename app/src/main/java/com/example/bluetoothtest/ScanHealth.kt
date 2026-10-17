package com.example.bluetoothtest

import android.os.Build

/** The suspected reason a scan that should see peers sees nothing (`BENCHMARK scan_stale: suspected=`). */
internal enum class ScanSuspect(val label: String) {
    LOCATION_OFF("location_off"),
    DOZE("doze"),

    /** The app's own start budget was full: the stack drops a start over its quota SILENTLY (no onScanFailed). */
    QUOTA_SILENT("scan_quota_silent"),
    TIMEOUT_WINDOW("timeout_window"),
    OEM_PAUSE("oem_pause"),
    BACKGROUND("background"),
    NONE("none"),
}

/** Everything the verdict reads, passed in so the logic is pure. */
internal data class ScanHealthInputs(
    val nowMs: Long,
    /** At least one continuous scanner is registered and running. */
    val scanning: Boolean,
    /** Age of the oldest running scanner (the stack's per-app scan clock). */
    val scanAgeMs: Long,
    val screenOn: Boolean,
    val deviceIdle: Boolean,
    /** [ScanEnvSnapshot.blockedReason] or null. */
    val blockedReason: String?,
    val sdk: Int,
    /** A known peer is expected within radio range (see [ScanHealth.peerExpected]). */
    val peerExpected: Boolean,
    /** The phone maker is one whose power manager is known to pause background Bluetooth search. */
    val oemAggressive: Boolean,
    /** A renewal's stop gap is in progress: the watchdog stays out of its way. */
    val renewalPending: Boolean,
    /** The scan-start gate has a free slot for a restart. */
    val gateHasSlot: Boolean,
    /** `test_scan_stale_restart`: a stale verdict may ask for a restart. */
    val restartEnabled: Boolean,
    /** The app's scan-start budget is full right now (another UID scanner may have used slots the stack counts). */
    val quotaWindowFull: Boolean = false,
)

internal sealed interface ScanHealthDecision {
    data object Healthy : ScanHealthDecision

    /** Silence is explained or nothing is expected; no event. */
    data class Waiting(val why: String) : ScanHealthDecision

    /** The scan has been silent past the threshold while a peer is expected. */
    data class Stale(
        val suspect: ScanSuspect,
        val silentMs: Long,
        val thresholdMs: Long,
        /** `requested` (one gated restart asked for), `logged_only`, `deferred_quota` or `deferred_renewal`. */
        val action: String,
    ) : ScanHealthDecision {
        val restart: Boolean get() = action == "requested"
    }
}

/**
 * Scan silence / stale-result watchdog (batch S-04), modelled on bitchat's scanner watchdog
 * (BluetoothGattClientManager.kt: a check every 30 s; if no result for SCAN_STALE_RESULT_MS = 120 s and the
 * scan itself is older than that, force one clean restart). Android can stop delivering results without
 * any onScanFailed (Doze, background throttling, OEM power managers, the scan-timeout downgrade, Location
 * switched off on Android 10-11). MeshLive additions: the verdict needs a peer that is expected nearby
 * (a quiet neighbourhood is not a fault), the threshold doubles after every unanswered stale verdict up to
 * ten minutes (so a dead zone costs few restarts), and a restart is only ever requested, through the
 * scan-start gate, by the caller. The thresholds are bitchat's and ours, not measured limits.
 */
internal class ScanHealth(
    private val staleMs: Long = DEFAULT_STALE_MS,
    private val maxBackoffMs: Long = MAX_BACKOFF_MS,
    private val expectWindowMs: Long = EXPECT_WINDOW_MS,
) {
    private var lastResultMs = NONE
    private var lastStartMs = NONE
    private var lastVerdictMs = NONE
    private var thresholdMs = staleMs

    /** The current silence threshold (doubles per unanswered verdict). */
    val currentThresholdMs: Long get() = thresholdMs

    @Synchronized
    fun onResult(nowMs: Long) {
        lastResultMs = nowMs
        thresholdMs = staleMs
        lastVerdictMs = NONE
    }

    /** A scan (re)started anywhere in the app; silence is measured from the later of this and the last result. */
    @Synchronized
    fun onScanStart(nowMs: Long) {
        lastStartMs = nowMs
    }

    @Synchronized
    fun reset() {
        lastResultMs = NONE
        lastStartMs = NONE
        lastVerdictMs = NONE
        thresholdMs = staleMs
    }

    /** True when some result arrived inside [expectWindowMs]: a peer was around recently. */
    @Synchronized
    fun sawPeerRecently(nowMs: Long): Boolean = lastResultMs != NONE && nowMs - lastResultMs <= expectWindowMs

    @Synchronized
    fun evaluate(i: ScanHealthInputs): ScanHealthDecision {
        if (!i.scanning) return ScanHealthDecision.Waiting("not_scanning")
        val reference = maxOf(lastResultMs, lastStartMs, lastVerdictMs)
        if (reference == NONE) return ScanHealthDecision.Waiting("no_reference")
        val silent = i.nowMs - reference
        if (silent < thresholdMs) return ScanHealthDecision.Healthy
        if (!i.peerExpected) return ScanHealthDecision.Waiting("no_peer_expected")
        val suspect = classify(i)
        val action = when {
            i.renewalPending -> "deferred_renewal"
            !i.restartEnabled -> "logged_only"
            !i.gateHasSlot -> "deferred_quota"
            else -> "requested"
        }
        if (action == "deferred_renewal" || action == "deferred_quota") {
            // Not acted on: ask again at the next check without growing the threshold.
            return ScanHealthDecision.Stale(suspect, silent, thresholdMs, action)
        }
        lastVerdictMs = i.nowMs
        val decision = ScanHealthDecision.Stale(suspect, silent, thresholdMs, action)
        thresholdMs = (thresholdMs * 2).coerceAtMost(maxBackoffMs)
        return decision
    }

    companion object {
        private const val NONE = Long.MIN_VALUE
        const val CHECK_INTERVAL_MS = 30_000L
        const val DEFAULT_STALE_MS = 120_000L
        const val MAX_BACKOFF_MS = 600_000L
        const val EXPECT_WINDOW_MS = 60 * 60_000L

        /** Phone makers whose power manager is documented (DKMA, Nordic DevZone) to pause background BLE search. */
        val AGGRESSIVE_MANUFACTURERS = setOf(
            "xiaomi", "redmi", "poco", "vivo", "iqoo", "oppo", "realme", "oneplus", "huawei", "honor",
            "tecno", "infinix", "itel", "transsion",
        )

        fun oemAggressive(manufacturer: String): Boolean = manufacturer.trim().lowercase() in AGGRESSIVE_MANUFACTURERS

        /** First matching cause, most specific first. */
        fun classify(i: ScanHealthInputs): ScanSuspect = when {
            i.blockedReason == ScanEnvironment.REASON_LOCATION_OFF -> ScanSuspect.LOCATION_OFF
            i.deviceIdle -> ScanSuspect.DOZE
            i.quotaWindowFull -> ScanSuspect.QUOTA_SILENT
            i.scanAgeMs >= ScanRenewalPolicy.windowMs(i.sdk) -> ScanSuspect.TIMEOUT_WINDOW
            !i.screenOn && i.oemAggressive -> ScanSuspect.OEM_PAUSE
            !i.screenOn -> ScanSuspect.BACKGROUND
            else -> ScanSuspect.NONE
        }

        /** The `BENCHMARK scan_stale` line for a stale verdict. */
        fun line(i: ScanHealthInputs, d: ScanHealthDecision.Stale): String =
            "scan_stale: suspected=${d.suspect.label} | silentMs=${d.silentMs} | thresholdMs=${d.thresholdMs} | " +
                "scanAgeMs=${i.scanAgeMs} | screenOn=${i.screenOn} | idle=${i.deviceIdle} | sdk=${i.sdk} | " +
                "location=${i.blockedReason ?: "ok"} | peerExpected=${i.peerExpected} | action=${d.action}"

        fun manufacturerNow(): String = Build.MANUFACTURER ?: ""
    }
}
