package com.example.bluetoothtest

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File

/**
 * Experimental SIMD safe mode for the software AV1 encoder (batch T, item T-05;
 * `test_native_simd_safe_mode=true`, DEBUG only, default off). The risk it covers is theoretical: libaom
 * selects its Armv8 kernels at run time from getauxval(AT_HWCAP), and the NDK documents AT_HWCAP as
 * possibly wrong on some old devices (Go issue 28431 is the precedent: Exynos atomics). No libaom dotprod
 * SIGILL report on a phone was found. So the net stays off, never keyed on a SoC or model name, and only
 * reacts to the app's own evidence: the previous process left a marker at encoder initialisation, never
 * cleared it, and ended with a native crash within [CRASH_WINDOW_MS] of that marker.
 *
 * The pinned libaom reads `AOM_SIMD_CAPS_MASK` in aom_arm_cpu_caps()
 * (aom_ports/aarch64_cpudetect.c: flags = arm_get_cpu_caps() & arm_cpu_env_mask(), the mask parsed by
 * strtol in arm_cpudetect.h), with HAS_NEON = 1, HAS_ARM_CRC32 = 2, HAS_NEON_DOTPROD = 4, HAS_NEON_I8MM = 8,
 * HAS_SVE = 16, HAS_SVE2 = 32 (aom_ports/arm.h). [CAPS_MASK] keeps NEON and CRC32 and clears the rest. The
 * variable must be set with [Os.setenv] before the first encoder or decoder initialisation, because the
 * dispatch table is built once.
 *
 * Safety against a wrong attribution: a crash while safe mode was already on proves SIMD is not the cause;
 * safe mode is then cleared and not re-entered (the state file keeps "exonerated").
 */
internal object NativeSimdSafeMode {
    const val ENV_MASK = "AOM_SIMD_CAPS_MASK"

    /** HAS_NEON | HAS_ARM_CRC32 of the pinned libaom: dotprod, i8mm, SVE and SVE2 are cleared. */
    const val CAPS_MASK = 0x3

    /** A native crash this soon after the marker was written counts; the marker is cleared after this long without one. */
    const val CRASH_WINDOW_MS = 10_000L

    const val SIGILL = 4
    const val SIGBUS = 7
    const val SIGSEGV = 11

    /** What the app wrote when the encoder started initialising. */
    data class Marker(val startedAtMs: Long, val pid: Int)

    /** The previous process's exit, from ApplicationExitInfo. */
    data class ExitFacts(val reason: Int, val signal: Int, val timestampMs: Long)

    data class State(val safe: Boolean = false, val exonerated: Boolean = false)

    enum class Decision(val label: String) {
        OFF("off"),
        NORMAL("normal"),
        ENTER_SAFE("enter_safe"),
        STAY_SAFE("stay_safe"),
        EXONERATED("exonerated_leave_safe"),
        UNVERIFIABLE("unverifiable_no_exit_info"),
    }

    /**
     * Decides the mode for this launch. [exit] is null when there is no marker, no exit record for the
     * marker's pid (also API 29, which has no ApplicationExitInfo), or the record is not a native crash.
     */
    fun decide(
        switchOn: Boolean,
        arm64: Boolean,
        state: State,
        marker: Marker?,
        exit: ExitFacts?,
    ): Decision {
        if (!switchOn || !arm64) return Decision.OFF
        val crashedAtInit = marker != null && exit != null &&
            exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE &&
            exit.signal in CRASH_SIGNALS &&
            exit.timestampMs - marker.startedAtMs in 0..CRASH_WINDOW_MS
        return when {
            state.safe && crashedAtInit -> Decision.EXONERATED
            state.safe -> Decision.STAY_SAFE
            state.exonerated -> Decision.NORMAL
            crashedAtInit -> Decision.ENTER_SAFE
            marker != null && exit == null -> Decision.UNVERIFIABLE
            else -> Decision.NORMAL
        }
    }

    val CRASH_SIGNALS = setOf(SIGILL, SIGBUS, SIGSEGV)

    fun stateAfter(decision: Decision, state: State): State = when (decision) {
        Decision.ENTER_SAFE, Decision.STAY_SAFE -> State(safe = true, exonerated = false)
        Decision.EXONERATED -> State(safe = false, exonerated = true)
        else -> state
    }

    fun safeModeActive(decision: Decision): Boolean = decision == Decision.ENTER_SAFE || decision == Decision.STAY_SAFE

    /** Persistence behind the logic; the production one uses two small files in the app's files directory. */
    interface Store {
        fun readMarker(): Marker?
        fun writeMarker(marker: Marker)
        fun clearMarker()
        fun readState(): State
        fun writeState(state: State)
    }

    class FileStore(private val dir: File) : Store {
        private val markerFile get() = File(dir, "native_simd_probe.txt")
        private val stateFile get() = File(dir, "native_simd_state.txt")

        override fun readMarker(): Marker? = runCatching {
            val parts = markerFile.readText().trim().split(',')
            Marker(parts[0].toLong(), parts[1].toInt())
        }.getOrNull()

        override fun writeMarker(marker: Marker) {
            runCatching { markerFile.writeText("${marker.startedAtMs},${marker.pid}") }
        }

        override fun clearMarker() {
            runCatching { markerFile.delete() }
        }

        override fun readState(): State = runCatching {
            val text = stateFile.readText()
            State(safe = "safe=1" in text, exonerated = "exonerated=1" in text)
        }.getOrDefault(State())

        override fun writeState(state: State) {
            runCatching { stateFile.writeText("safe=${if (state.safe) 1 else 0}\nexonerated=${if (state.exonerated) 1 else 0}\n") }
        }
    }

    /**
     * Run once per process, before the first native encoder or decoder initialisation. Applies the mask
     * when safe mode is decided and returns the decision for the log line. Never throws.
     */
    fun prepare(
        store: Store,
        switchOn: Boolean,
        arm64: Boolean,
        exitFor: (Marker) -> ExitFacts?,
        setEnv: (String, String) -> Unit = { name, value -> Os.setenv(name, value, true) },
    ): Decision {
        val marker = store.readMarker()
        val state = store.readState()
        val exit = marker?.let { runCatching { exitFor(it) }.getOrNull() }
        val decision = decide(switchOn, arm64, state, marker, exit)
        if (marker != null && decision != Decision.OFF) store.clearMarker()
        val next = stateAfter(decision, state)
        if (next != state) store.writeState(next)
        if (safeModeActive(decision)) runCatching { setEnv(ENV_MASK, "0x" + Integer.toHexString(CAPS_MASK)) }
        return decision
    }

    /** Call when the native encoder (or decoder) is about to initialise; no-op unless the switch is on. */
    fun markInit(store: Store, switchOn: Boolean, nowMs: Long, pid: Int) {
        if (switchOn) store.writeMarker(Marker(nowMs, pid))
    }

    /** Call once the encoder has run [CRASH_WINDOW_MS] without a crash: the marker is no longer evidence. */
    fun markStable(store: Store) = store.clearMarker()

    fun logLine(decision: Decision, state: State, masked: Boolean): String =
        "BENCHMARK native_simd_safe_mode: decision=${decision.label} | safeMode=${safeModeActive(decision)} | " +
            "persistedSafe=${state.safe} | exonerated=${state.exonerated} | maskEnv=" +
            (if (masked) "$ENV_MASK=0x${Integer.toHexString(CAPS_MASK)}" else "unset")

    /** ApplicationExitInfo of the process that wrote [marker] (API 30+); null when none is recorded. */
    fun platformExitFor(context: Context, marker: Marker): ExitFacts? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val info = manager.getHistoricalProcessExitReasons(context.packageName, marker.pid, 1).firstOrNull() ?: return null
        return ExitFacts(info.reason, info.status, info.timestamp)
    }
}
