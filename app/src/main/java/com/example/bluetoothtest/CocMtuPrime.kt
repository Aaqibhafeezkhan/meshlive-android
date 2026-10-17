package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Default ON where the registry entry applies (fresh-ACL CoC centrals, Android 12-13; Q49; DEBUG
 * `test_coc_mtu_prime=false` is the control arm; BACKLOG MTU-02): one ATT MTU exchange on the
 * ACL a fresh-ACL LE CoC client just opened, before any CoC traffic.
 *
 * Why. The stack raises the LE link-layer data length (up to 251 bytes) only from the ATT MTU
 * handlers (`gatt_cl.cc` client MTU response, `gatt_sr.cc` server MTU request; Android 14
 * `GATTC_UpdateUserAttMtuIfNeeded`, with the MTU the app asked for). No path raises it for a dynamic
 * L2CAP CoC channel until Android 16 QPR1 (`set_max_data_length_for_lecoc`). The fresh-ACL client
 * (BleCocClient) does its MTU exchange on the first ACL, then closes that GATT client so the ACL
 * drops (CONN-08: about 1 s after the last client or channel leaves), and connects the CoC on a new
 * ACL on which nobody ever exchanges an MTU. That link keeps the controller default payload size
 * (27 bytes), the likely cause of the 17-29 kbps CoC stages in the saved evidence.
 *
 * Where. After the CoC connect, not before: a GATT client connected before the CoC would bring back
 * the EATT stranded-channel race the fresh ACL avoids, and a client closed just before the CoC
 * connect would let the ACL idle out. With the CoC channel open the ACL stays up when this short
 * client closes (the dynamic channel is a holder), exactly as for the GATT control reattach that
 * follows. The exchange requests 517 (not 185/247: on Android 14+ the data length follows the
 * app's requested MTU).
 *
 * Evidence (lane 10): M31 central to an ASUS peer reached 632 kbps on 5/5 runs with the prime against about 325 kbps
 * on 5/5 without; an S24 peer was neutral. Stacks whose control GATT client stays open (Android 10-11, 14+)
 * never reach this path and log `skipped reason=control-link-alive`.
 *
 * Measurement. Android exposes neither the data length nor a hook for it. The log line states the
 * requested and effective MTU and the data length the stack should have requested
 * (min(251, mtu + 4)); the benefit is judged from CoC throughput per connection event. A failed
 * exchange never fails the CoC connect.
 *
 * JVM-testable: the GATT calls are injected into [execute].
 */
internal class CocMtuPrime(
    private val owner: String,
    private val requestedMtu: Int = REQUESTED_MTU,
    private val nowMs: () -> Long,
    private val log: (String) -> Unit,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val mtuTimeoutMs: Long = MTU_TIMEOUT_MS,
) {
    enum class Outcome(val label: String) {
        OK("ok"),
        CONNECT_REFUSED("connect_refused"),
        CONNECT_FAILED("connect_failed"),
        CONNECT_TIMEOUT("connect_timeout"),
        MTU_REFUSED("mtu_request_refused"),
        MTU_FAILED("mtu_failed"),
        MTU_TIMEOUT("mtu_timeout"),
        SKIPPED("skipped"),
    }

    data class Result(
        val outcome: Outcome,
        val requestedMtu: Int,
        val effectiveMtu: Int?,
        val gattStatus: Int?,
        val connectMs: Long,
        val mtuMs: Long,
    ) {
        /** What the stack should have requested from the controller (Android hides the real value). */
        val inferredDataLength: Int? get() = effectiveMtu?.let { minOf(MAX_DATA_LENGTH, it + L2CAP_HEADER_BYTES) }
    }

    private val connected = CountDownLatch(1)
    private val mtuDone = CountDownLatch(1)
    @Volatile private var connectStatus: Int? = null
    @Volatile private var connectedState = false
    @Volatile private var mtu: Int? = null
    @Volatile private var mtuStatus: Int? = null

    /** `onConnectionStateChange` for the primer's client. */
    fun onConnectionState(status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED && status == GATT_SUCCESS) {
            connectedState = true
            connectStatus = status
        } else if (connectStatus == null) {
            connectStatus = if (status != GATT_SUCCESS) status else CONNECT_LOST
        }
        connected.countDown()
        if (newState == BluetoothProfile.STATE_DISCONNECTED) mtuDone.countDown()
    }

    /** `onMtuChanged` for the primer's client. */
    fun onMtu(effective: Int, status: Int) {
        mtu = effective
        mtuStatus = status
        mtuDone.countDown()
    }

    /**
     * Runs the exchange: [startConnect] opens the client, [requestMtu] asks for [requestedMtu], and
     * [teardown] disconnects and closes it (always called once the client exists). Blocks the caller.
     */
    fun execute(startConnect: () -> Boolean, requestMtu: (Int) -> Boolean, teardown: () -> Unit): Result {
        val startedMs = nowMs()
        var connectMs = 0L
        var mtuStartedMs = startedMs
        fun finish(outcome: Outcome): Result {
            val now = nowMs()
            val result = Result(
                outcome = outcome,
                requestedMtu = requestedMtu,
                effectiveMtu = mtu?.takeIf { mtuStatus == GATT_SUCCESS },
                gattStatus = if (outcome == Outcome.MTU_FAILED || outcome == Outcome.MTU_TIMEOUT) mtuStatus else connectStatus,
                connectMs = connectMs,
                mtuMs = if (outcome == Outcome.OK || outcome == Outcome.MTU_FAILED || outcome == Outcome.MTU_TIMEOUT) now - mtuStartedMs else 0L,
            )
            log(logLine(owner, result))
            return result
        }
        if (!runCatching(startConnect).getOrDefault(false)) return finish(Outcome.CONNECT_REFUSED)
        try {
            val gotConnect = runCatching { connected.await(connectTimeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            connectMs = nowMs() - startedMs
            if (!gotConnect) return finish(Outcome.CONNECT_TIMEOUT)
            if (!connectedState) return finish(Outcome.CONNECT_FAILED)
            mtuStartedMs = nowMs()
            if (!runCatching { requestMtu(requestedMtu) }.getOrDefault(false)) return finish(Outcome.MTU_REFUSED)
            val gotMtu = runCatching { mtuDone.await(mtuTimeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            return when {
                !gotMtu -> finish(Outcome.MTU_TIMEOUT)
                mtuStatus == null -> finish(Outcome.MTU_FAILED)
                mtuStatus != GATT_SUCCESS -> finish(Outcome.MTU_FAILED)
                else -> finish(Outcome.OK)
            }
        } finally {
            runCatching(teardown)
        }
    }

    companion object {
        const val REQUESTED_MTU = 517
        const val CONNECT_TIMEOUT_MS = 4_000L
        const val MTU_TIMEOUT_MS = 2_500L

        /** Largest LE data length payload (Core 4.2+); the stack requests the ATT MTU plus the L2CAP header, capped here. */
        const val MAX_DATA_LENGTH = 251
        const val L2CAP_HEADER_BYTES = 4
        private const val GATT_SUCCESS = 0
        private const val CONNECT_LOST = -1

        /** The switch and the registry entry (Android 12-13 fresh-ACL centrals) must both allow the arm. */
        fun enabled(
            switchOn: Boolean = CocSwitches.mtuPrime,
            policy: () -> ResolvedDevicePolicy = CocPolicy::current,
        ): Boolean = switchOn && policy().has<CocMtuExchangeAfterConnect>()

        fun logLine(owner: String, result: Result): String =
            "BENCHMARK coc_mtu_prime: owner=$owner | placement=after_connect | requestedMtu=${result.requestedMtu} | " +
                "effectiveMtu=${result.effectiveMtu ?: "none"} | outcome=${result.outcome.label} | " +
                "gattStatus=${result.gattStatus ?: "none"} | connectMs=${result.connectMs} | mtuMs=${result.mtuMs} | " +
                "inferredDataLength=${result.inferredDataLength ?: "none"} | dataLengthObservable=false"

        /** The skipped line, for a caller that did not run the exchange (reason: main-thread, control-link-alive, ...). */
        fun skippedLine(owner: String, reason: String): String =
            "BENCHMARK coc_mtu_prime: owner=$owner | placement=after_connect | outcome=${Outcome.SKIPPED.label} | reason=$reason"

        /**
         * Runs the exchange on the ACL of [device] with a short GATT client. Never throws; a caller on
         * the main thread skips it (the exchange blocks for up to [CONNECT_TIMEOUT_MS] + [MTU_TIMEOUT_MS]).
         */
        @SuppressLint("MissingPermission")
        fun runOnAcl(context: Context, device: BluetoothDevice, owner: String): Result? {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Log.i("BluetoothVideo", skippedLine(owner, "main-thread"))
                return null
            }
            val prime = CocMtuPrime(
                owner,
                nowMs = android.os.SystemClock::elapsedRealtime,
                log = { Log.i("BluetoothVideo", it) },
            )
            val disconnected = CountDownLatch(1)
            var gatt: BluetoothGatt? = null
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    prime.onConnectionState(status, newState)
                    if (newState == BluetoothProfile.STATE_DISCONNECTED) disconnected.countDown()
                }

                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = prime.onMtu(mtu, status)

                /** Hidden framework callback (not in the SDK stubs); this short-lived client has no use for it. */
                @Suppress("unused")
                fun onConnectionUpdated(g: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) = Unit
            }
            return runCatching {
                prime.execute(
                    startConnect = {
                        EncoderBenchGuard.check("CocMtuPrime.connectGatt")
                        gatt = GattConnectCompat.connect(device, context, callback, BluetoothDevice.TRANSPORT_LE, owner = "coc-mtu-prime")
                        gatt != null
                    },
                    requestMtu = { mtu -> gatt?.requestMtu(mtu) == true },
                    teardown = {
                        val client = gatt ?: return@execute
                        runCatching { client.disconnect() }
                        // Closing before the stack reports the disconnect would overlap the GATT reattach that follows.
                        runCatching { disconnected.await(DISCONNECT_WAIT_MS, TimeUnit.MILLISECONDS) }
                        runCatching { client.close() }
                        GattResourceRegistry.shared.releaseObject(client)
                    },
                )
            }.onFailure { error ->
                Log.i("BluetoothVideo", skippedLine(owner, "error-${error.javaClass.simpleName}"))
            }.getOrNull()
        }

        private const val DISCONNECT_WAIT_MS = 1_000L
    }
}
