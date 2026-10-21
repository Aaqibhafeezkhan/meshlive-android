package com.example.bluetoothtest

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/*
 * DEBUG-only harness for the BVMID2 Meet flow (phone check N5 and the Design QL lease test): two phones scan each other's
 * expiring codes without a camera, in a NORMAL (user-default) launch. Nothing here is reachable in a release build: every
 * entry point checks [DebugContactControl.allowed] (debug build type and a debuggable package).
 *
 * The extra deliberately does not start with `test_`: a `test_*` extra makes the launch a benchmark launch (FeatureCatalog,
 * call reachability, BMH3 all follow their test extras), and the lease test needs the user's defaults.
 *
 * 1. Open the screen (MainActivity must be on top, so the intent reaches the running activity):
 *      adb shell am start -n <package>/.MainActivity --es debug_meet open
 * 2. Drive the open screen with a command file in app-private storage (only adb run-as can write it, so it needs no
 *    token; the screen polls it every second while visible and deletes it when read):
 *      adb shell run-as <package> sh -c 'printf "show\n" > files/debug-meet-command'
 *      adb shell run-as <package> sh -c 'printf "scan\n<BVMID2 code>\nyes\n" > files/debug-meet-command'
 *    "show" switches to Show my code; while a code is live its text is in `files/debug-meet-code` (the same secret the
 *    screen shows; deleted when the screen leaves). "scan" injects the code as if the camera read it; line 3 is the scripted
 *    answer to "Is this X's screen you scanned?" (`yes` or `no`; anything else leaves the dialog to a human). The screen
 *    stays the same activity, so its visit (Design QL) continues across "scan" and "show", as when a user switches tabs.
 */
internal object DebugMeetControl {
    const val EXTRA = "debug_meet"
    const val COMMAND_FILE = "debug-meet-command"
    const val CODE_FILE = "debug-meet-code"
    private const val TAG = "BluetoothVideo"

    /** A parsed command: "show", or "scan" with the code text and the scripted answer (null: ask the human). */
    class Command(val mode: String, val code: String?, val confirm: Boolean?)

    /** Pure parse of the command file. Null when the mode is unknown or a scan's code is unusable. */
    internal fun parse(text: String?): Command? {
        val lines = text?.lines()?.map { it.trim() } ?: return null
        return when (lines.firstOrNull()) {
            "show" -> Command("show", null, null)
            "scan" -> {
                val code = lines.getOrNull(1).orEmpty()
                if (!Bvmid2.looksLikeBvmid2(code) || Bvmid2.tryParse(code) == null) return null
                val confirm = when (lines.getOrNull(2)?.lowercase()) {
                    "yes" -> true
                    "no" -> false
                    else -> null
                }
                Command("scan", code, confirm)
            }
            else -> null
        }
    }

    /** Handles `debug_meet=open` of [intent]: opens Add contact (the BVMID2 Meet screen when the switch is on). */
    fun handleIntent(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.getStringExtra(EXTRA) != "open") return
        if (!DebugContactControl.allowed(context)) return
        intent.removeExtra(EXTRA) // a later onNewIntent or recreation must not open it again
        Log.i(TAG, "BENCHMARK meet_debug result=opened")
        context.startActivity(
            VerifyContactActivity.intent(context).putExtra(VerifyContactActivity.EXTRA_DEBUG_MEET, true),
        )
    }

    /** The open Meet screen's poll: reads and deletes the command file (null when there is none or it is unusable). */
    fun takeCommand(context: Context): Command? {
        if (!BuildConfig.DEBUG || !DebugContactControl.allowed(context)) return null
        val file = File(context.filesDir, COMMAND_FILE)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()
        file.delete()
        val command = parse(text)
        Log.i(TAG, "BENCHMARK meet_debug command=${command?.mode ?: "rejected"}")
        return command
    }

    /** Displayer: the live code's text for adb (debug builds only). */
    fun writeCode(context: Context, code: String?) {
        if (!BuildConfig.DEBUG || !DebugContactControl.allowed(context)) return
        val file = File(context.filesDir, CODE_FILE)
        runCatching { if (code == null) file.delete() else file.writeText(code + "\n") }
    }
}
