package com.example.bluetoothtest

import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser

/**
 * When to restart an advertising set that stopped without being asked (BACKLOG A-03; oem-bt.md C7;
 * bluetooth.md `adv-set-terminated-on-connection-and-reenable`, `adv-set-callbacks-lost-on-service-down`).
 *
 * Two signals: the stack reports `onAdvertisingSetStopped` for a set the app did not stop, or it reports
 * `onAdvertisingEnabled(false)` and no `enabled(true)` follows within [graceMs]. The second one needs the grace
 * because the stack itself disables a connectable set to rotate its random address every 7 to 15 minutes
 * (ADV-04) and enables it again within milliseconds; that gap is also the only observable trace of the rotation,
 * so [onEnabled] returns its length for the log. Restarts back off ([backoffMs]) and stop after [maxRestarts]
 * inside [windowMs]; then the owner's own lost-handling takes over. Pure: the clock is injected.
 */
internal class AdvertisingWatchdog(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxRestarts: Int = 5,
    private val windowMs: Long = 10 * 60_000L,
    private val backoffMs: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L),
    val graceMs: Long = GRACE_MS,
) {
    sealed interface Decision {
        data object None : Decision
        data class WaitGrace(val ms: Long) : Decision
        data class Restart(val delayMs: Long, val reason: String) : Decision
        data class GiveUp(val reason: String) : Decision
    }

    private val restarts = ArrayDeque<Long>()
    private var disabledAtMs = 0L

    @Synchronized
    fun onUnexpectedStop(): Decision = restartDecision("set_stopped")

    /** The set was disabled and nobody asked: wait [graceMs] for the stack's own enable before judging it dead. */
    @Synchronized
    fun onUnexpectedDisable(): Decision {
        if (disabledAtMs == 0L) disabledAtMs = clock().coerceAtLeast(1L)
        return Decision.WaitGrace(graceMs)
    }

    /** The set was enabled again; returns how long it was off when that followed an unexpected disable. */
    @Synchronized
    fun onEnabled(): Long? {
        if (disabledAtMs == 0L) return null
        val gap = clock() - disabledAtMs
        disabledAtMs = 0L
        return gap
    }

    @Synchronized
    fun onGraceElapsed(): Decision {
        if (disabledAtMs == 0L) return Decision.None
        disabledAtMs = 0L
        return restartDecision("disabled_for_${graceMs}ms")
    }

    @Synchronized
    fun onRestartFailed(): Decision = restartDecision("restart_failed")

    private fun restartDecision(reason: String): Decision {
        val now = clock()
        while (restarts.isNotEmpty() && now - restarts.first() > windowMs) restarts.removeFirst()
        if (restarts.size >= maxRestarts) return Decision.GiveUp("$reason; $maxRestarts restarts within ${windowMs / 60_000} min")
        val delay = backoffMs[restarts.size.coerceAtMost(backoffMs.lastIndex)]
        restarts.addLast(now)
        return Decision.Restart(delay, reason)
    }

    companion object {
        /** Longer than the stack's disable -> new address -> enable window by a wide margin. */
        const val GRACE_MS = 4_000L

        /**
         * Wait before the next start of an optional set after [failures] failed ones: 2 s doubling to 60 s; a
         * TOO_MANY refusal waits the full minute (the budget does not change in seconds). Before this, a failed reach
         * start was retried at every page tick.
         */
        fun startFailureBackoffMs(failures: Int, tooMany: Boolean): Long {
            if (tooMany) return 60_000L
            val shift = (failures - 1).coerceIn(0, 5)
            return (2_000L shl shift).coerceAtMost(60_000L)
        }
    }
}

/**
 * Starting an advertising set without throwing (BACKLOG A-01; bluetooth.md `adv-set-oversize-throws-not-callbacks`).
 *
 * `BluetoothLeAdvertiser.startAdvertisingSet` validates synchronously: an oversize legacy payload or an
 * unsupported parameter throws `IllegalArgumentException`, and a start while the adapter is off throws
 * `IllegalStateException("BT Adapter is not turned ON")` (BluetoothLeUtils.checkAdapterStateOn). A thrown
 * exception from a delayed restart path would end the process. [request] turns each into a status the callers
 * already treat as a failed start; the platform's own callback codes are kept as they are. One
 * `AdvertisingSetCallback` object serves one set (the platform refuses to reuse it), and the stable connectable
 * set is always started with duration 0 and no event limit, because a limited set is not re-enabled after a
 * connection (`adv-set-terminated-on-connection-and-reenable`).
 */
internal object AdvertisingStart {
    /** The start call itself threw something other than the cases below (permission, dead binder). */
    const val STATUS_THREW = -2

    /** The adapter is off or turning off: not a refusal of the parameters, nothing to remember. */
    const val STATUS_ADAPTER_OFF = -3

    /** The platform rejected the arguments for a reason other than the payload size. */
    const val STATUS_BAD_ARGUMENT = -4

    /** `duration` and `maxExtendedAdvertisingEvents` of a set that must stay on air until stopped. */
    const val UNLIMITED_DURATION = 0
    const val UNLIMITED_EVENTS = 0

    /** A status that never came from the stack: the start was not even requested. */
    fun isLocal(status: Int): Boolean = status <= STATUS_THREW

    fun classify(error: Throwable): Int = when {
        error is IllegalStateException -> STATUS_ADAPTER_OFF
        error is IllegalArgumentException && looksOversize(error.message) -> AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE
        error is IllegalArgumentException -> STATUS_BAD_ARGUMENT
        else -> STATUS_THREW
    }

    private fun looksOversize(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return "too big" in text || "too large" in text || "too long" in text
    }

    /** The AOSP range check, and the app's rule that the stable set never limits itself. */
    fun timingProblem(duration: Int, maxEvents: Int): String? = when {
        duration !in 0..65535 -> "duration $duration outside 0..65535"
        maxEvents !in 0..255 -> "maxExtendedAdvertisingEvents $maxEvents outside 0..255"
        else -> null
    }

    /** True for a set that must be re-enabled by the stack after every connection. */
    fun isUnlimited(duration: Int, maxEvents: Int): Boolean = duration == 0 && maxEvents == 0

    /** Plain words for a start status (the user-facing and log text). */
    fun describe(status: Int?): String = when (status) {
        null -> "timed out"
        AdvertisingSetCallback.ADVERTISE_SUCCESS -> "started"
        AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "the advertisement is too large"
        AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "no free advertising slots"
        AdvertisingSetCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
        AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "the phone's Bluetooth reported an internal error"
        AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "not supported by this phone's Bluetooth"
        STATUS_THREW -> "the start request failed"
        STATUS_ADAPTER_OFF -> "Bluetooth is off"
        STATUS_BAD_ARGUMENT -> "the phone's Bluetooth rejected the settings"
        else -> "code $status"
    }

    /**
     * Requests a set. Returns null when the request was made (the callback reports the outcome) or a local failure
     * status when it could not be made. [duration] and [maxEvents] default to unlimited.
     */
    fun request(
        advertiser: BluetoothLeAdvertiser,
        parameters: AdvertisingSetParameters,
        data: AdvertiseData,
        scanResponse: AdvertiseData?,
        callback: AdvertisingSetCallback,
        duration: Int = UNLIMITED_DURATION,
        maxEvents: Int = UNLIMITED_EVENTS,
    ): Int? {
        timingProblem(duration, maxEvents)?.let { return STATUS_BAD_ARGUMENT }
        return try {
            @Suppress("MissingPermission")
            advertiser.startAdvertisingSet(parameters, data, scanResponse, null, null, duration, maxEvents, callback)
            null
        } catch (error: RuntimeException) {
            classify(error)
        }
    }
}
