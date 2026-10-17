package com.example.bluetoothtest

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager

/**
 * Experimental ADPF performance-hint session for the visible call (batch T, item T-04;
 * `test_adpf_hint=true`, DEBUG only, default off). Documented API: PerformanceHintManager, API 31+
 * (https://developer.android.com/games/optimize/adpf/performance-hint-api). The platform pauses a session
 * when the app's process state is below IMPORTANT_FOREGROUND (AOSP HintManagerService), so a session is
 * created only while the process shows an activity ([sessionAllowed]) and is closed when it
 * drops. `createHintSession` returns null on phones without vendor support; that is the supported-or-not
 * test, nothing is assumed from the SoC. The benefit for the software AV1 encoder threads is unmeasured.
 * Session methods are not thread-safe: report from the one encode thread.
 */
internal object AdpfHint {
    /**
     * "Visible call only": the process shows an activity (IMPORTANCE_FOREGROUND = TOP, IMPORTANCE_VISIBLE =
     * IMPORTANT_FOREGROUND). A foreground-service-only process (importance 125, the screen-off relay or call)
     * is excluded on purpose: the research note (cpu-power.md section 5) found the hint gets no benefit there
     * and the service pauses sessions below IMPORTANT_FOREGROUND.
     */
    fun importanceShowsActivity(importance: Int): Boolean =
        importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE

    fun sessionAllowed(switchOn: Boolean, sdkInt: Int, importance: Int): Boolean =
        switchOn && sdkInt >= Build.VERSION_CODES.S && importanceShowsActivity(importance)

    /** Target work duration for one frame at [fps], in nanoseconds. */
    fun targetNanos(fps: Int): Long = 1_000_000_000L / fps.coerceIn(1, 120)

    /** The importance of this process now, or null when it cannot be read. */
    fun currentImportance(): Int? = runCatching {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance
    }.getOrNull()
}

/** One live hint session. */
internal interface HintSessionHandle {
    fun updateTargetNanos(targetNs: Long)
    fun reportActualNanos(actualNs: Long)
    fun close()
}

/** Creates sessions; returns null when the device or OEM does not support them. */
internal fun interface HintSessionBackend {
    fun create(threadIds: IntArray, targetNs: Long): HintSessionHandle?
}

/**
 * Owns at most one session. [update] is called from the encode thread with the thread ids, the frame rate
 * and the last frame's work duration; it creates, reports to or closes the session as the rule dictates.
 */
internal class AdpfHintController(
    private val backend: HintSessionBackend,
    private val switchOn: () -> Boolean,
    private val importance: () -> Int?,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    private var session: HintSessionHandle? = null
    private var sessionFps = 0
    private var createFailed = false

    var reports = 0L
        private set

    val hasSession: Boolean get() = session != null

    /** True once `createHintSession` returned null: no retry in this process (unsupported or refused). */
    val unsupported: Boolean get() = createFailed

    fun update(threadIds: IntArray, fps: Int, actualWorkNs: Long) {
        val allowed = AdpfHint.sessionAllowed(switchOn(), sdkInt, importance() ?: Int.MAX_VALUE)
        if (!allowed) {
            close()
            return
        }
        if (createFailed || threadIds.isEmpty()) return
        var current = session
        if (current == null) {
            current = runCatching { backend.create(threadIds, AdpfHint.targetNanos(fps)) }.getOrNull()
            if (current == null) {
                createFailed = true
                return
            }
            session = current
            sessionFps = fps
        } else if (fps != sessionFps) {
            runCatching { current.updateTargetNanos(AdpfHint.targetNanos(fps)) }
            sessionFps = fps
        }
        if (actualWorkNs > 0L) {
            runCatching { current.reportActualNanos(actualWorkNs) }
            reports++
        }
    }

    fun close() {
        val current = session ?: return
        session = null
        runCatching { current.close() }
    }

    fun benchmarkLine(): String =
        "BENCHMARK adpf_hint: switchOn=${switchOn()} | session=$hasSession | unsupported=$unsupported | reports=$reports | fps=$sessionFps"

    companion object {
        /** The platform backend (API 31+); null below. */
        fun platformBackend(context: Context): HintSessionBackend? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            val manager = context.getSystemService(PerformanceHintManager::class.java) ?: return null
            return HintSessionBackend { tids, targetNs ->
                val created = manager.createHintSession(tids, targetNs) ?: return@HintSessionBackend null
                object : HintSessionHandle {
                    override fun updateTargetNanos(targetNs: Long) = created.updateTargetWorkDuration(targetNs)
                    override fun reportActualNanos(actualNs: Long) = created.reportActualWorkDuration(actualNs)
                    override fun close() = created.close()
                }
            }
        }
    }
}
