package com.example.bluetoothtest

/** Camera open error classes (CameraDevice.StateCallback.ERROR_* codes). */
internal enum class CameraOpenErrorKind { IN_USE, MAX_IN_USE, DISABLED, DEVICE, SERVICE, UNKNOWN }

/** What to do after a camera error. [retryDelayMs] null means report the failure now. */
internal data class CameraOpenDecision(
    val kind: CameraOpenErrorKind,
    val retryDelayMs: Long?,
    /** Close the camera fully before the reopen (device and service errors). */
    val closeFirst: Boolean,
    val message: String,
)

/**
 * Camera open retry policy (V-05), the shape WebRTC's CameraCapturer uses: 3 attempts, 500 ms apart,
 * inside a 10 s budget, for the transient "another client holds the camera" errors. Device and
 * service errors close fully, back off 1 s (a Samsung HAL wedged by ERROR_CAMERA_DEVICE is never
 * retried in a tight loop) and reopen once. A disabled camera (policy or the privacy toggle) is not
 * retried and is explained. The attempt counters are per error class and reset when a camera opens.
 */
internal object CameraOpenPolicy {
    const val ERROR_CAMERA_IN_USE = 1
    const val ERROR_MAX_CAMERAS_IN_USE = 2
    const val ERROR_CAMERA_DISABLED = 3
    const val ERROR_CAMERA_DEVICE = 4
    const val ERROR_CAMERA_SERVICE = 5

    const val IN_USE_RETRIES = 3
    const val IN_USE_RETRY_DELAY_MS = 500L
    const val DEVICE_RETRIES = 1
    const val DEVICE_RETRY_DELAY_MS = 1_000L
    const val BUDGET_MS = 10_000L

    fun classify(code: Int): CameraOpenErrorKind = when (code) {
        ERROR_CAMERA_IN_USE -> CameraOpenErrorKind.IN_USE
        ERROR_MAX_CAMERAS_IN_USE -> CameraOpenErrorKind.MAX_IN_USE
        ERROR_CAMERA_DISABLED -> CameraOpenErrorKind.DISABLED
        ERROR_CAMERA_DEVICE -> CameraOpenErrorKind.DEVICE
        ERROR_CAMERA_SERVICE -> CameraOpenErrorKind.SERVICE
        else -> CameraOpenErrorKind.UNKNOWN
    }

    /**
     * [attemptsSoFar]: how many retries of this error class already ran; [elapsedMs]: time since the
     * first failure of the streak. The final failure keeps the former "Camera error N" prefix.
     */
    fun decide(code: Int, attemptsSoFar: Int, elapsedMs: Long, enabled: Boolean = true): CameraOpenDecision {
        val kind = classify(code)
        val base = "Camera error $code"
        if (!enabled) return CameraOpenDecision(kind, null, false, base)
        return when (kind) {
            CameraOpenErrorKind.IN_USE, CameraOpenErrorKind.MAX_IN_USE ->
                if (attemptsSoFar < IN_USE_RETRIES && elapsedMs + IN_USE_RETRY_DELAY_MS <= BUDGET_MS) {
                    CameraOpenDecision(kind, IN_USE_RETRY_DELAY_MS, false, "$base (camera in use by another app); retrying")
                } else {
                    CameraOpenDecision(kind, null, false, "$base: the camera is in use by another app or too many cameras are open.")
                }
            CameraOpenErrorKind.DISABLED ->
                CameraOpenDecision(kind, null, false, "$base: camera access is blocked by device policy or the camera privacy toggle.")
            CameraOpenErrorKind.DEVICE, CameraOpenErrorKind.SERVICE ->
                if (attemptsSoFar < DEVICE_RETRIES && elapsedMs + DEVICE_RETRY_DELAY_MS <= BUDGET_MS) {
                    CameraOpenDecision(kind, DEVICE_RETRY_DELAY_MS, true, "$base (camera ${kind.name.lowercase()} fault); reopening once")
                } else {
                    CameraOpenDecision(kind, null, true, "$base: the camera reported a ${kind.name.lowercase()} fault.")
                }
            CameraOpenErrorKind.UNKNOWN -> CameraOpenDecision(kind, null, false, base)
        }
    }

    /** The BENCHMARK line for one decision. */
    fun benchmarkLine(decision: CameraOpenDecision, code: Int, attempt: Int, elapsedMs: Long): String =
        "BENCHMARK camera_open_retry: code=$code | kind=${decision.kind} | attempt=$attempt | elapsedMs=$elapsedMs | " +
            "action=${if (decision.retryDelayMs != null) "retry_in_${decision.retryDelayMs}ms" else "report"} | " +
            "closeFirst=${decision.closeFirst}"
}

/** Counts retries per error class for one camera start; reset when a device opens. */
internal class CameraOpenRetryState(private val nowMs: () -> Long) {
    private val attempts = mutableMapOf<CameraOpenErrorKind, Int>()
    private var streakStartedMs = Long.MIN_VALUE

    fun next(code: Int, enabled: Boolean = true): CameraOpenDecision {
        val now = nowMs()
        if (streakStartedMs == Long.MIN_VALUE) streakStartedMs = now
        val kind = CameraOpenPolicy.classify(code)
        val used = attempts[kind] ?: 0
        val decision = CameraOpenPolicy.decide(code, used, now - streakStartedMs, enabled)
        if (decision.retryDelayMs != null) attempts[kind] = used + 1
        return decision
    }

    fun attemptOf(code: Int): Int = attempts[CameraOpenPolicy.classify(code)] ?: 0

    val elapsedMs: Long get() = if (streakStartedMs == Long.MIN_VALUE) 0L else nowMs() - streakStartedMs

    fun reset() {
        attempts.clear()
        streakStartedMs = Long.MIN_VALUE
    }
}
