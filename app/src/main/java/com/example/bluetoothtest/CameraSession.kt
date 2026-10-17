package com.example.bluetoothtest

/*
 * How the camera pipeline is rebuilt when the surface set changes (viewfinder refresh after the app
 * was backgrounded, ear-proximity pause and resume, a template change). V-01.
 *
 * SESSION_ONLY is the former behaviour: close the session and create the next one on the same
 * CameraDevice at once. WAIT_SESSION_CLOSED waits for `CameraCaptureSession.StateCallback.onClosed`
 * first (CameraX `Camera2Quirks.shouldCloseCaptureSessionOnDisconnect`, b/277675483: HARDWARE qcom
 * with SDK <= 31). REOPEN_DEVICE also closes and reopens the CameraDevice (CameraX
 * `CloseCameraDeviceOnCameraGraphCloseQuirk`, b/282871038 and b/538140458); it costs 300-800 ms and
 * flickers the privacy indicator, so it is Experimental.
 */
// The mode enum itself is [CameraRebuildMode] (DevicePolicyAreaEffects.kt).

/** Entry effect: wait for the old session's onClosed before creating the next session. */
internal data object CameraWaitSessionClosed : DevicePolicyEffect {
    override fun describe(): String = "camera rebuild waits for CameraCaptureSession onClosed before the next session"
}

/** Proposed-entry effect: close and reopen the CameraDevice when the session is rebuilt (Experimental). */
internal data object CameraReopenDeviceOnRebuild : DevicePolicyEffect {
    override fun describe(): String = "camera rebuild closes and reopens the CameraDevice (Experimental switch)"
}

/** Diagnostic entry effect: ask isSessionConfigurationSupported before the session is created. */
internal data object CameraStreamCombinationCheck : DevicePolicyEffect {
    override fun describe(): String = "log isSessionConfigurationSupported and the hardware level for the requested streams"
}

internal object CameraRebuildPolicy {
    /**
     * The rebuild mode for [traits]. The device-reopen proposals only count when the Experimental
     * switch is on and their `test_device_policy_off` id is not set; wait-for-onClosed follows the
     * active registry entry.
     */
    fun modeFor(
        traits: DeviceTraits,
        reopenSwitch: Boolean = CameraSwitches.reopenDevice,
        disabled: Set<String> = DeviceFamilyPolicy.switchedOff,
    ): CameraRebuildMode {
        val resolved = CameraPolicy.resolve(traits, disabled)
        if (reopenSwitch && CameraPolicy.reopenProposalMatches(resolved, disabled)) return CameraRebuildMode.REOPEN_DEVICE
        return if (resolved.has<CameraWaitSessionClosed>()) CameraRebuildMode.WAIT_SESSION_CLOSED else CameraRebuildMode.SESSION_ONLY
    }
}

/** The pieces the rebuild state machine drives; the encoder implements them on its worker thread. */
internal interface CameraRebuildHost {
    fun hasSession(): Boolean
    /** `session.close()`; the host calls [CameraSessionRebuilder.onSessionClosed] from `onClosed`. */
    fun closeSession()
    /** `camera.close()`; the host calls [CameraSessionRebuilder.onDeviceClosed] from the device `onClosed`. */
    fun closeDevice()
    /** `CameraManager.openCamera`; the host calls [CameraSessionRebuilder.onDeviceOpened] from `onOpened`. */
    fun openDevice()
    fun createSession()
    fun log(message: String)
    /** A reopen never completed: the camera has no device, the caller reports a failure. */
    fun rebuildFailed(message: String)
}

internal interface CameraRebuildScheduler {
    fun postDelayed(delayMs: Long, action: Runnable)
    fun remove(action: Runnable)
}

/**
 * Session rebuild state machine. All calls come from the camera worker thread (the thread the camera
 * callbacks run on), so there is no locking. A rebuild requested while one is running is coalesced
 * and runs once after it. A callback that never arrives (a HAL that does not deliver onClosed) is
 * replaced by a timeout so the camera is not left without a session.
 */
internal class CameraSessionRebuilder(
    private val host: CameraRebuildHost,
    private val scheduler: CameraRebuildScheduler,
    private val nowMs: () -> Long,
    private val sessionCloseTimeoutMs: Long = SESSION_CLOSE_TIMEOUT_MS,
    private val deviceCloseTimeoutMs: Long = DEVICE_CLOSE_TIMEOUT_MS,
    private val deviceOpenTimeoutMs: Long = DEVICE_OPEN_TIMEOUT_MS,
    private val minReopenGapMs: Long = MIN_REOPEN_GAP_MS,
) {
    private enum class State { IDLE, AWAIT_SESSION_CLOSED, AWAIT_DEVICE_CLOSED, AWAIT_DEVICE_OPENED, DEFERRED }

    private var state = State.IDLE
    private var mode = CameraRebuildMode.SESSION_ONLY
    private var queuedMode: CameraRebuildMode? = null
    private var lastReopenAtMs = Long.MIN_VALUE
    private var timeout: Runnable? = null
    private var deferred: Runnable? = null

    /** True while a rebuild is waiting for a callback. */
    val busy: Boolean get() = state != State.IDLE

    /** True while the device was closed for a reopen and the next `onOpened` belongs to this rebuild. */
    val reopening: Boolean get() = state == State.AWAIT_DEVICE_CLOSED || state == State.AWAIT_DEVICE_OPENED

    fun rebuild(requested: CameraRebuildMode) {
        if (state != State.IDLE) {
            queuedMode = strongest(queuedMode, requested)
            host.log("camera rebuild $requested queued behind $mode ($state)")
            return
        }
        mode = requested
        when (requested) {
            CameraRebuildMode.SESSION_ONLY -> {
                if (host.hasSession()) host.closeSession()
                finish()
            }
            CameraRebuildMode.WAIT_SESSION_CLOSED -> {
                if (!host.hasSession()) {
                    finish()
                } else {
                    awaitSessionClosed()
                }
            }
            CameraRebuildMode.REOPEN_DEVICE -> startReopen()
        }
    }

    private fun startReopen() {
        val sinceLast = if (lastReopenAtMs == Long.MIN_VALUE) Long.MAX_VALUE else nowMs() - lastReopenAtMs
        if (sinceLast < minReopenGapMs) {
            // At most one reopen per minReopenGapMs: the request is postponed, not dropped.
            val wait = minReopenGapMs - sinceLast
            host.log("camera reopen postponed ${wait} ms (at most one per $minReopenGapMs ms)")
            state = State.DEFERRED
            val action = Runnable {
                deferred = null
                state = State.IDLE
                val queued = queuedMode
                queuedMode = null
                rebuild(strongest(queued, CameraRebuildMode.REOPEN_DEVICE))
            }
            deferred = action
            scheduler.postDelayed(wait, action)
            return
        }
        lastReopenAtMs = nowMs()
        if (host.hasSession()) awaitSessionClosed() else closeDevice()
    }

    private fun awaitSessionClosed() {
        state = State.AWAIT_SESSION_CLOSED
        arm(sessionCloseTimeoutMs) {
            host.log("camera session onClosed not delivered in $sessionCloseTimeoutMs ms; continuing")
            onSessionClosed()
        }
        host.closeSession()
    }

    fun onSessionClosed() {
        if (state != State.AWAIT_SESSION_CLOSED) return
        disarm()
        if (mode == CameraRebuildMode.REOPEN_DEVICE) closeDevice() else finish()
    }

    private fun closeDevice() {
        state = State.AWAIT_DEVICE_CLOSED
        arm(deviceCloseTimeoutMs) {
            host.log("camera device onClosed not delivered in $deviceCloseTimeoutMs ms; reopening")
            onDeviceClosed()
        }
        host.closeDevice()
    }

    fun onDeviceClosed() {
        if (state != State.AWAIT_DEVICE_CLOSED) return
        disarm()
        state = State.AWAIT_DEVICE_OPENED
        arm(deviceOpenTimeoutMs) {
            host.log("camera device did not reopen in $deviceOpenTimeoutMs ms")
            state = State.IDLE
            queuedMode = null
            host.rebuildFailed("Camera did not reopen in ${deviceOpenTimeoutMs} ms.")
        }
        host.openDevice()
    }

    /** Returns true when the open belonged to a rebuild (the session is created here, not by the caller). */
    fun onDeviceOpened(): Boolean {
        if (state != State.AWAIT_DEVICE_OPENED) return false
        disarm()
        finish()
        return true
    }

    /** The encoder is closing: drop every pending step. */
    fun cancel() {
        disarm()
        deferred?.let(scheduler::remove)
        deferred = null
        queuedMode = null
        state = State.IDLE
    }

    private fun finish() {
        state = State.IDLE
        host.createSession()
        runQueued()
    }

    private fun runQueued() {
        val next = queuedMode ?: return
        queuedMode = null
        rebuild(next)
    }

    private fun arm(delayMs: Long, action: () -> Unit) {
        disarm()
        val runnable = Runnable {
            timeout = null
            action()
        }
        timeout = runnable
        scheduler.postDelayed(delayMs, runnable)
    }

    private fun disarm() {
        timeout?.let(scheduler::remove)
        timeout = null
    }

    private fun strongest(a: CameraRebuildMode?, b: CameraRebuildMode): CameraRebuildMode =
        if (a == null || b.ordinal > a.ordinal) b else a

    companion object {
        const val SESSION_CLOSE_TIMEOUT_MS = 1_000L
        const val DEVICE_CLOSE_TIMEOUT_MS = 1_500L
        const val DEVICE_OPEN_TIMEOUT_MS = 5_000L
        const val MIN_REOPEN_GAP_MS = 3_000L
    }
}

/** One stream plan of the session: which targets it contains. */
internal data class CameraStreamPlan(val label: String, val preview: Boolean, val reference: Boolean) {
    val streams: String
        get() = listOfNotNull("preview".takeIf { preview }, "encoder", "reference".takeIf { reference }).joinToString("+")
}

/**
 * Stream sets in the order they are tried when a combination is unsupported (V-02, Experimental
 * `test_camera_session_configuration`): the full set, then without the YUV reference reader, then
 * without the preview. A further fallback, the GL capture path (GpuScaleCapture), belongs to the
 * caller. Dropping the reference reader removes a measurement stream: it is reported as a capability
 * failure (requested != effective), never hidden. The encoder input is always kept.
 */
internal object CameraStreamPlanner {
    fun plans(hasPreview: Boolean, hasReference: Boolean): List<CameraStreamPlan> = buildList {
        add(CameraStreamPlan("full", hasPreview, hasReference))
        if (hasReference) add(CameraStreamPlan("without_reference", hasPreview, false))
        if (hasPreview) add(CameraStreamPlan("without_preview", false, false))
    }.distinctBy { it.preview to it.reference }

    /** The first plan [supported] accepts; null when none is (the caller reports a failed request). */
    fun firstSupported(plans: List<CameraStreamPlan>, supported: (CameraStreamPlan) -> Boolean?): CameraStreamPlan? =
        plans.firstOrNull { plan -> supported(plan) != false }

    /** Requested vs effective, for the camera BENCHMARK line. */
    fun effectiveNote(requested: CameraStreamPlan, effective: CameraStreamPlan?): String = when {
        effective == null -> "requested=${requested.streams} effective=none (unsupported)"
        effective == requested -> "requested=${requested.streams} effective=${effective.streams}"
        else -> "requested=${requested.streams} effective=${effective.streams} (capability failure: " +
            "${requested.streams} not supported)"
    }
}
