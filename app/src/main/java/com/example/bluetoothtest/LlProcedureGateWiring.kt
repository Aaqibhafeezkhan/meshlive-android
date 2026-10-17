package com.example.bluetoothtest

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Call-site helpers for [LlProcedureGate] (integration of batch P into the GATT/CoC control and video clients). The
 * gate is a pass-through unless the Experimental `test_ll_gate` is on, so wiring a link with one changes nothing by
 * default except that a throwing request now reads as "not started" (the callers already handle a false answer).
 */

/** True when the request was started (gate off) or is waiting for its turn (gate on). */
internal val GateAdmission.accepted: Boolean
    get() = this == GateAdmission.IMMEDIATE_OK || this == GateAdmission.QUEUED

/** One gate per link, timed on the main looper, logging through the app log, keyed by the authenticated hop when known. */
internal fun newLinkGate(link: String, role: LeLinkRole, address: String?): LlProcedureGate = LlProcedureGate(
    link = link,
    role = role,
    nowMs = SystemClock::elapsedRealtime,
    schedule = { delayMs, action -> Handler(Looper.getMainLooper()).postDelayed({ action() }, delayMs) },
    log = { line -> Log.i("BluetoothVideo", line) },
    peerKey = { AdaptivePhyMemory.peerKey(address) },
)
