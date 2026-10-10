package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A legacy (BLE 4.x format) advertisement whose low-latency -> balanced step keeps the same
 * advertising set, and therefore the same random address.
 *
 * Root cause (GATT call timeouts, voice-ptt-s24a15s-51cc8028 case 0004): the servers advertised at
 * low latency for 10 s and then called stopAdvertising + startAdvertising in balanced mode. Every new
 * advertising set gets a new resolvable private address. The S24 replaced ..:6a:97 with ..:b4:ba at
 * 15:09:36.005-.022; the A15s, which had just seen ..:6a:97, dialled it at 15:09:36.105 and its
 * direct connect never completed (await_timeout after 12 s). The step runs exactly 10 s after a
 * peer becomes available, so a call placed about then always raced it.
 *
 * Here the step disables the running set, changes its parameters and enables it again. Both stacks
 * keep the address: Android 10 `btm_ble_multi_adv.cc` regenerates it only on the rotation timer, and
 * the gd `le_advertising_manager.cc` `set_parameters` reuses `current_address`. Parameters equal what
 * BluetoothLeAdvertiser.startAdvertising builds for ADVERTISE_MODE_LOW_LATENCY/BALANCED with
 * TX_POWER_HIGH/MEDIUM. A step that fails or stalls falls back to the previous stop + restart; that
 * replacement is bounded (it must start within [REPLACEMENT_TIMEOUT_MS]) and a failure reaches the
 * owner through `onLost`, so no owner believes it advertises with nothing live.
 *
 * Scope (review 2026-10-09): a new set gets a new resolvable private address only when LE privacy
 * is on (Android 10 `btm_ble_multi_adv.cc:240-267`; every phone here advertises with an RPA), and
 * the race is the app's own stop/restart, not a stack regression. The same set still rotates its
 * address on the stack's privacy timer, so "same address" holds only until the next rotation.
 * DEBUG `test_stable_adv_address=false` always uses stop + restart (control arm).
 */
internal class StableAdvertisingSet(
    private val adapter: BluetoothAdapter,
    private val connectable: Boolean,
    private val data: AdvertiseData,
    private val scanResponse: AdvertiseData,
    private val label: String,
    private val onStatus: (String) -> Unit,
    /**
     * Called on the main thread when a fallback left nothing advertising (the replacement set
     * failed, threw or did not start within [REPLACEMENT_TIMEOUT_MS]). The owner must clear its
     * advertising state and may start again.
     */
    private val onLost: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var current: SetCallback? = null

    /**
     * Starts a new set and waits for the stack (call off the main thread). Returns the start status
     * (AdvertiseCallback codes; 0 is success) or null on timeout. Throws when advertising is unavailable.
     */
    @SuppressLint("MissingPermission")
    fun start(lowLatency: Boolean, timeoutSeconds: Long): Int? {
        val advertiser = adapter.bluetoothLeAdvertiser ?: throw IOException("BLE advertising is unavailable")
        val callback = SetCallback()
        synchronized(this) {
            stopLocked()
            current = callback
        }
        advertiser.startAdvertisingSet(parameters(lowLatency), data, scanResponse, null, null, callback)
        if (!callback.started.await(timeoutSeconds, TimeUnit.SECONDS)) {
            stop()
            logChurn("start_timeout", callback)
            return null
        }
        val status = callback.status
        logChurn(if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) "start" else "start_failed_$status", callback)
        if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) stop()
        return status
    }

    /**
     * Asks the running set to move to balanced parameters in place. Non-blocking; safe on the main
     * thread. False when there is no running set (the caller restarts, as before).
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun switchToBalanced(): Boolean {
        val callback = current ?: return false
        val set = callback.set ?: return false
        if (callback.stopped || !callback.steps.begin()) return false
        handler.postDelayed({ callback.onStepTimeout() }, SWITCH_TIMEOUT_MS)
        set.enableAdvertising(false, 0, 0)
        return true
    }

    @Synchronized
    fun stop() = stopLocked()

    @SuppressLint("MissingPermission")
    private fun stopLocked() {
        val callback = current ?: return
        current = null
        callback.stopped = true
        handler.removeCallbacksAndMessages(null)
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(callback) }
        logChurn("stop", callback)
    }

    /**
     * Phase 0 (rotating discovery IDs, DEBUG only): every set start/stop with its set identity, so a
     * second phone's `adv_address_seen` lines show whether each new set got a new address.
     */
    private fun logChurn(event: String, callback: SetCallback) {
        if (!BuildConfig.DEBUG) return
        android.util.Log.i(
            "BluetoothVideo",
            "BENCHMARK adv_set_churn: label=$label | event=$event | set=${Integer.toHexString(System.identityHashCode(callback))} | " +
                "replacement=${callback.replacement} | elapsedMs=${android.os.SystemClock.elapsedRealtime()} | " +
                "wallMs=${System.currentTimeMillis()}",
        )
    }

    /** The old step: a new balanced set (new address). Used only when the in-place step fails. */
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun restartBalanced(failed: SetCallback, reason: String) {
        if (current !== failed || failed.stopped) return
        onStatus("$label advertising: in-place balanced step failed ($reason); restarting the set")
        stopLocked()
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            lost("BLE advertising is unavailable")
            return
        }
        val replacement = SetCallback(replacement = true)
        current = replacement
        // Bounded: no start callback within the timeout counts as a failed replacement.
        handler.postDelayed({ onReplacementResult(replacement, null) }, REPLACEMENT_TIMEOUT_MS)
        runCatching {
            advertiser.startAdvertisingSet(parameters(false), data, scanResponse, null, null, replacement)
        }.onFailure { onReplacementResult(replacement, START_THREW) }
    }

    /** Settles a fallback replacement once: success keeps it; any failure stops it and reports [onLost]. */
    @Synchronized
    private fun onReplacementResult(replacement: SetCallback, status: Int?) {
        if (current !== replacement || replacement.settled) return
        replacement.settled = true
        val failure = replacementFailure(status)
        if (failure == null) {
            onStatus("$label advertising restarted in balanced mode (new set)")
            return
        }
        stopLocked()
        lost(failure)
    }

    private fun lost(reason: String) {
        onStatus("$label is not advertising: $reason")
        handler.post { onLost(reason) }
    }

    private fun parameters(lowLatency: Boolean): AdvertisingSetParameters =
        AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(connectable)
            .setScannable(true)
            .setInterval(
                if (lowLatency) AdvertisingSetParameters.INTERVAL_LOW else AdvertisingSetParameters.INTERVAL_MEDIUM,
            )
            .setTxPowerLevel(
                if (lowLatency) AdvertisingSetParameters.TX_POWER_HIGH else AdvertisingSetParameters.TX_POWER_MEDIUM,
            )
            .build()

    private inner class SetCallback(val replacement: Boolean = false) : AdvertisingSetCallback() {
        val started = CountDownLatch(1)
        @Volatile var status: Int = -1
        @Volatile var set: AdvertisingSet? = null
        @Volatile var stopped = false
        /** Replacement only: its outcome was handled (success, failure or timeout). */
        var settled = false
        val steps = BalancedSwitchSteps()

        @SuppressLint("MissingPermission")
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            set = advertisingSet
            this.status = status
            if (stopped && status == ADVERTISE_SUCCESS) {
                runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(this) }
            }
            started.countDown()
            if (replacement) onReplacementResult(this, status)
        }

        @SuppressLint("MissingPermission")
        override fun onAdvertisingEnabled(advertisingSet: AdvertisingSet?, enable: Boolean, status: Int) {
            if (stopped) return
            val success = status == ADVERTISE_SUCCESS && advertisingSet != null
            act(steps.onEnabled(enable, success), advertisingSet, "enable($enable) status $status")
            if (success && enable) logChurn("balanced_in_place", this)
        }

        @SuppressLint("MissingPermission")
        override fun onAdvertisingParametersUpdated(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            if (stopped) return
            val success = status == ADVERTISE_SUCCESS && advertisingSet != null
            act(steps.onParametersUpdated(success), advertisingSet, "parameters status $status")
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) = Unit

        fun onStepTimeout() {
            if (!stopped) act(steps.onTimeout(), set, "timeout")
        }

        @SuppressLint("MissingPermission")
        private fun act(action: BalancedSwitchSteps.Action, advertisingSet: AdvertisingSet?, detail: String) {
            when (action) {
                BalancedSwitchSteps.Action.SET_PARAMETERS -> advertisingSet?.setAdvertisingParameters(parameters(false))
                BalancedSwitchSteps.Action.ENABLE -> advertisingSet?.enableAdvertising(true, 0, 0)
                BalancedSwitchSteps.Action.DONE ->
                    onStatus("$label advertising is balanced (same advertising set and address)")
                BalancedSwitchSteps.Action.RESTART -> restartBalanced(this, detail)
                BalancedSwitchSteps.Action.NONE -> Unit
            }
        }
    }

    companion object {
        /** Each step is one HCI command; a missing callback must not leave the phone unadvertised. */
        private const val SWITCH_TIMEOUT_MS = 2_000L

        /** A replacement set must report started within this (the owners' start timeout). */
        const val REPLACEMENT_TIMEOUT_MS = 8_000L

        /** Pseudo status: startAdvertisingSet itself threw. */
        const val START_THREW = -2

        /** Why a fallback replacement left nothing advertising, or null when it started (null status: timed out). */
        fun replacementFailure(status: Int?): String? = when (status) {
            AdvertisingSetCallback.ADVERTISE_SUCCESS -> null
            null -> "replacement set did not start within $REPLACEMENT_TIMEOUT_MS ms"
            START_THREW -> "replacement set could not be requested"
            else -> "replacement set failed (code $status)"
        }
    }
}

/**
 * Order of the in-place balanced step: disable -> set parameters -> enable, each confirmed by its
 * callback. Any failure or a step that stalls past the timeout asks for the old restart.
 */
internal class BalancedSwitchSteps {
    enum class Step { IDLE, DISABLING, UPDATING, ENABLING, DONE, FAILED }
    enum class Action { NONE, SET_PARAMETERS, ENABLE, DONE, RESTART }

    @Volatile var step: Step = Step.IDLE
        private set

    /** Starts the step once per set; false when it already ran or is running. */
    @Synchronized
    fun begin(): Boolean {
        if (step != Step.IDLE) return false
        step = Step.DISABLING
        return true
    }

    @Synchronized
    fun onEnabled(enable: Boolean, success: Boolean): Action = when (step) {
        Step.DISABLING -> if (success && !enable) {
            step = Step.UPDATING
            Action.SET_PARAMETERS
        } else {
            fail()
        }
        Step.ENABLING -> if (success && enable) {
            step = Step.DONE
            Action.DONE
        } else {
            fail()
        }
        else -> Action.NONE
    }

    @Synchronized
    fun onParametersUpdated(success: Boolean): Action = when {
        step != Step.UPDATING -> Action.NONE
        success -> {
            step = Step.ENABLING
            Action.ENABLE
        }
        else -> fail()
    }

    @Synchronized
    fun onTimeout(): Action =
        if (step == Step.DISABLING || step == Step.UPDATING || step == Step.ENABLING) fail() else Action.NONE

    private fun fail(): Action {
        step = Step.FAILED
        return Action.RESTART
    }
}
