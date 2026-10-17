package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/*
 * Small read-only probes of the reliability batch (device-family backlog B-13, B-14): "the phone updated or
 * restarted since the app last ran", the incoming-call screen watch, and the Experimental Xiaomi pop-up
 * permission probe. None of them changes anything on the phone.
 */

/** What identifies one phone software state. */
internal data class PhoneStamp(val fingerprint: String, val securityPatch: String, val bootCount: Int?)

/** The stored stamp plus when a change was last seen (0 = never). */
internal data class PhoneUpdateRecord(val stamp: PhoneStamp?, val updatedAtMs: Long = 0L, val restartedAtMs: Long = 0L)

/** Pure rules for [PhoneUpdateTracker] (unit-tested). */
internal object PhoneUpdatePolicy {
    const val UPDATED_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    const val RESTART_WINDOW_MS = 2L * 24 * 60 * 60 * 1000

    /** The record after an app start. The first start only stores the stamp: there is nothing to compare with. */
    fun next(previous: PhoneUpdateRecord, current: PhoneStamp, nowMs: Long): PhoneUpdateRecord {
        val old = previous.stamp ?: return previous.copy(stamp = current)
        val updated = old.fingerprint != current.fingerprint || old.securityPatch != current.securityPatch
        val restarted = old.bootCount != null && current.bootCount != null && old.bootCount != current.bootCount
        return PhoneUpdateRecord(
            stamp = current,
            updatedAtMs = if (updated) nowMs else previous.updatedAtMs,
            restartedAtMs = if (restarted) nowMs else previous.restartedAtMs,
        )
    }

    fun updatedRecently(record: PhoneUpdateRecord, nowMs: Long): Boolean =
        record.updatedAtMs > 0L && nowMs - record.updatedAtMs in 0..UPDATED_WINDOW_MS

    fun restartedRecently(record: PhoneUpdateRecord, nowMs: Long): Boolean =
        record.restartedAtMs > 0L && nowMs - record.restartedAtMs in 0..RESTART_WINDOW_MS
}

/**
 * Remembers Build.FINGERPRINT, the security patch and the boot count at every app start so the reliability card can say
 * "your phone updated, battery settings may have reset" for a week (Xiaomi: after a restart, for two days). Three short
 * values in the UI preferences file (which the emergency wipe clears); nothing leaves the phone.
 */
internal object PhoneUpdateTracker {
    private const val KEY_FINGERPRINT = "bg_stamp_fingerprint"
    private const val KEY_PATCH = "bg_stamp_patch"
    private const val KEY_BOOT = "bg_stamp_boot_count"
    private const val KEY_UPDATED_AT = "bg_stamp_updated_at_ms"
    private const val KEY_RESTARTED_AT = "bg_stamp_restarted_at_ms"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(OnboardingActivity.PREFERENCES, Context.MODE_PRIVATE)

    private fun load(context: Context): PhoneUpdateRecord {
        val p = prefs(context)
        val fingerprint = p.getString(KEY_FINGERPRINT, null)
        val stamp = fingerprint?.let {
            PhoneStamp(it, p.getString(KEY_PATCH, "").orEmpty(), p.getInt(KEY_BOOT, -1).takeIf { boot -> boot >= 0 })
        }
        return PhoneUpdateRecord(stamp, p.getLong(KEY_UPDATED_AT, 0L), p.getLong(KEY_RESTARTED_AT, 0L))
    }

    private fun currentStamp(context: Context) = PhoneStamp(
        fingerprint = android.os.Build.FINGERPRINT.orEmpty(),
        securityPatch = android.os.Build.VERSION.SECURITY_PATCH.orEmpty(),
        bootCount = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull(),
    )

    /** Call once per process start ([BackgroundReliability.logStatus] does). */
    fun recordAtStart(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val next = PhoneUpdatePolicy.next(load(context), currentStamp(context), nowMs)
        val stamp = next.stamp ?: return
        prefs(context).edit()
            .putString(KEY_FINGERPRINT, stamp.fingerprint)
            .putString(KEY_PATCH, stamp.securityPatch)
            .putInt(KEY_BOOT, stamp.bootCount ?: -1)
            .putLong(KEY_UPDATED_AT, next.updatedAtMs)
            .putLong(KEY_RESTARTED_AT, next.restartedAtMs)
            .apply()
    }

    fun updatedRecently(context: Context): Boolean =
        runCatching { PhoneUpdatePolicy.updatedRecently(load(context), System.currentTimeMillis()) }.getOrDefault(false)

    fun restartedRecently(context: Context): Boolean =
        runCatching { PhoneUpdatePolicy.restartedRecently(load(context), System.currentTimeMillis()) }.getOrDefault(false)
}

/** Reads an AppOps mode; any failure is null (unknown). A JVM test injects a fake. */
internal fun interface AppOpsReader {
    fun mode(op: Int, uid: Int, packageName: String): Int?
}

/**
 * EXPERIMENTAL, default off (`test_miui_appops_probe=true`, DEBUG only): Xiaomi exposes its "Display pop-up windows while
 * running in the background" permission only through hidden AppOps. This calls the hidden
 * `AppOpsManager.checkOpNoThrow(int, int, String)` by reflection, so it can break with any Android or MIUI update; it
 * fails closed (unknown) and is never used for a decision. The operation id 10021 comes from community code (Telegram's
 * XiaomiUtilities) and is UNVERIFIED on HyperOS. Backlog B-17 (hidden-API autostart detection) is deliberately not built.
 */
internal object MiuiAppOpsProbe {
    const val OP_BACKGROUND_START_ACTIVITY = 10021

    /** true allowed, false denied, null unknown (default mode, reflection failure, odd value). */
    fun interpret(mode: Int?): Boolean? = when (mode) {
        AppOpsManager.MODE_ALLOWED -> true
        AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> false
        else -> null
    }

    fun backgroundPopupGranted(context: Context): Boolean? =
        backgroundPopupGranted(context.packageName, android.os.Process.myUid(), reflectionReader(context))

    /** Pure core (a test injects the reader): any failure of [reader] is unknown. */
    fun backgroundPopupGranted(packageName: String, uid: Int, reader: AppOpsReader): Boolean? =
        runCatching { interpret(reader.mode(OP_BACKGROUND_START_ACTIVITY, uid, packageName)) }.getOrNull()

    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    private fun reflectionReader(context: Context) = AppOpsReader { op, uid, packageName ->
        runCatching {
            val manager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
            )
            method.invoke(manager, op, uid, packageName) as? Int
        }.getOrNull()
    }
}

/**
 * Did the incoming-call screen come up after its full-screen intent was posted? (B-14.) On Xiaomi, vivo and OPPO-family
 * software a missing background pop-up permission leaves only a heads-up notification. Root hook: call
 * [onFullScreenIntentPosted] where the incoming-call notification is posted and [onCallScreenShown] from the call
 * screen's onResume; the verdict is `BENCHMARK incoming_ui: result=fullscreen|heads_up_only` and
 * [BackgroundReliability.recordIncomingUi].
 */
internal class IncomingUiWatch(
    private val waitMs: Long = DEFAULT_WAIT_MS,
    private val clock: () -> Long,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val report: (result: String, waitedMs: Long) -> Unit,
) {
    private var postedAtMs = NONE
    private var generation = 0

    fun onFullScreenIntentPosted() {
        postedAtMs = clock()
        val mine = ++generation
        schedule(waitMs) { check(mine) }
    }

    /** The alert ended without the screen being needed (answered, declined or missed): no verdict. */
    fun cancel() {
        postedAtMs = NONE
        generation++
    }

    fun onCallScreenShown() {
        if (postedAtMs == NONE) return
        val waited = clock() - postedAtMs
        postedAtMs = NONE
        generation++
        report("fullscreen", waited)
    }

    internal fun check(forGeneration: Int) {
        if (forGeneration != generation || postedAtMs == NONE) return
        val waited = clock() - postedAtMs
        postedAtMs = NONE
        report("heads_up_only", waited)
    }

    companion object {
        private const val NONE = Long.MIN_VALUE
        const val DEFAULT_WAIT_MS = 4_000L

        fun line(result: String, waitedMs: Long): String = "incoming_ui: result=$result | waitedMs=$waitedMs"

        /** The app-wide instance on the main looper. */
        val shared: IncomingUiWatch by lazy {
            val handler = Handler(Looper.getMainLooper())
            IncomingUiWatch(
                clock = android.os.SystemClock::elapsedRealtime,
                schedule = { delayMs, action -> handler.postDelayed(action, delayMs) },
                report = { result, waited ->
                    BackgroundReliability.recordIncomingUi(result == "heads_up_only")
                    try { Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + line(result, waited)) } catch (_: RuntimeException) {}
                },
            )
        }
    }
}
