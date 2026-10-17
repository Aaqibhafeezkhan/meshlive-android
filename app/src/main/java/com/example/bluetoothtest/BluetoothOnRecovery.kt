package com.example.bluetoothtest

/**
 * Bluetooth ON recovery (2026-10-17, one switch: [DebugTunables.bluetoothOnRecovery], DEBUG `test_bluetooth_on_recovery=false` is
 * the control arm). Turning Bluetooth off and on destroys everything native: scans, advertising sets, GATT servers, CoC listeners.
 * [BluetoothEpoch] already tells the scan registry, but only the scanners it had paused came back: a scanner started while the
 * adapter was going down (or in BLE-only mode) stayed RUNNING in the registry with a dead stack registration, a live stream's
 * offer died with the adapter and disarmed the broadcast, and an idle call listener kept a GATT server without its video service.
 * This file holds the decisions (pure, JVM-tested) and the one log line; the owners call it from their own epoch consumers and
 * reuse their existing restart paths (scan registry resume, startStreamPresence, stopAvailability + openCallListener).
 */
internal object BluetoothOnRecovery {
    const val COMPONENT_SCANNER = "scanner"
    const val COMPONENT_STREAM_ADVERTISE = "stream_advertise"
    const val COMPONENT_CALL_LISTENER = "call_listener"
    const val COMPONENT_VIEWER_LINK = "viewer_link"

    val enabled: Boolean get() = DebugTunables.bluetoothOnRecovery

    /** `BENCHMARK bluetooth_on_recovery: component=<..> | action=<restarted|skipped> | reason=<..>` (the prefix is DEBUG only). */
    fun log(component: String, action: String, reason: String, detail: String = "") {
        scanLog(line(component, action, reason, detail))
    }

    internal fun line(component: String, action: String, reason: String, detail: String = ""): String =
        "bluetooth_on_recovery: component=$component | action=$action | reason=$reason" +
            if (detail.isEmpty()) "" else " | $detail"
}

internal data class BluetoothOnDecision(val restart: Boolean, val reason: String)

internal object BluetoothOnRecoveryPolicy {
    /**
     * One registered scanner at the ON callback. [onEdgeMs] is the elapsed time the adapter reached STATE_ON (negative: unknown).
     * A scanner the off callback paused is resumed (as before); one still RUNNING was started while the adapter was not yet
     * ON, so the stack never held it: restart it. A timed probe has no restart of its own and is left alone.
     */
    fun scanner(state: ScanState, restartable: Boolean, startedAtMs: Long, onEdgeMs: Long): BluetoothOnDecision = when {
        state == ScanState.HALTED -> BluetoothOnDecision(false, "halted")
        !restartable -> BluetoothOnDecision(false, "not_restartable")
        state == ScanState.PAUSED -> BluetoothOnDecision(true, "paused_at_off")
        onEdgeMs < 0L || startedAtMs < onEdgeMs -> BluetoothOnDecision(true, "started_before_adapter_on")
        else -> BluetoothOnDecision(false, "started_after_adapter_on")
    }

    /** The live broadcast's offer (advertising set, title pages, viewer-request discovery) after Bluetooth came back. */
    fun streamOffer(armed: Boolean, cameraScreen: Boolean, broadcastDiscovery: Boolean, permissions: Boolean): BluetoothOnDecision = when {
        !armed -> BluetoothOnDecision(false, "stream_not_armed")
        !cameraScreen -> BluetoothOnDecision(false, "not_on_camera_screen")
        !broadcastDiscovery -> BluetoothOnDecision(false, "no_broadcast_discovery")
        !permissions -> BluetoothOnDecision(false, "permissions_missing")
        else -> BluetoothOnDecision(true, "offer_died_with_adapter")
    }

    /**
     * A stream offer that failed to start. While Bluetooth is down or its ON callbacks are pending the failure is the
     * adapter's, not the stream's: keep the broadcast armed (the encoder session stays) and let the ON callback advertise again.
     */
    fun keepStreamArmedAfterAdvertiseFailure(enabled: Boolean, recoveryPending: Boolean): Boolean =
        enabled && recoveryPending

    /** Whether an offer start should wait for the ON callback instead of failing against a dead stack. */
    fun deferStreamOfferStart(enabled: Boolean, recoveryPending: Boolean): Boolean = enabled && recoveryPending

    /**
     * A stream viewer when the adapter goes off: its parent link is dead in the stack whatever the Java objects say, so an
     * active link is closed at once and the normal link-loss route recovery runs. `restart` here means "close the link".
     */
    fun viewerLink(running: Boolean, activeLink: Boolean): BluetoothOnDecision = when {
        !running -> BluetoothOnDecision(false, "receiver_closed")
        !activeLink -> BluetoothOnDecision(false, "no_active_link")
        else -> BluetoothOnDecision(true, "adapter_off")
    }

    /**
     * The idle private-call listener (GATT server with its video service, CoC control server and listener, Classic socket and
     * advertiser) after Bluetooth came back. A call in progress lost its link with the adapter and ends through its own paths;
     * the activity restarts availability afterwards.
     */
    fun callListener(running: Boolean, idle: Boolean, listening: Boolean): BluetoothOnDecision = when {
        !running -> BluetoothOnDecision(false, "endpoint_closed")
        !idle -> BluetoothOnDecision(false, "call_active")
        !listening -> BluetoothOnDecision(false, "not_listening")
        else -> BluetoothOnDecision(true, "listener_died_with_adapter")
    }
}
