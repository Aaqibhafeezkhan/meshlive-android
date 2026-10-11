package com.example.bluetoothtest

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File
import java.security.MessageDigest

/*
 * DEBUG-only benchmark harness controls for the rotating-ID phone test P3 (mutual verified contacts ->
 * presence leases). Nothing here is reachable in a release build: every entry point checks
 * `BuildConfig.DEBUG` and the package's FLAG_DEBUGGABLE, and the release build ignores the extras.
 *
 * Verified contact without two humans scanning QR codes (same pattern as the debug emergency wipe,
 * EmergencyWipe.consumeDebugWipeToken). The launcher activity is exported, so an extra alone is never
 * enough; it must carry a one-shot token that adb wrote into app-private storage with run-as:
 *
 *   adb shell run-as <package> sh -c 'printf "<token>\n<64 hex fingerprint>\n" > files/debug-verify-contact'
 *   adb shell am start -n <package>/.MainActivity --es test_verify_contact <token> ...
 *
 * The app reads the file at launch (and on a new intent), compares the token in constant time, deletes the
 * file when it matches (one shot), and marks the fingerprint as a verified contact in the same
 * `VerifiedContactStore` the QR flow writes (same Keystore-sealed file, method QR_SCAN). The optional third
 * file line is the contact's display name. `test_forget_contact` with `files/debug-forget-contact` removes
 * a contact through the facade's `IdentityVerification.forget`, exactly as the Forget action would
 * (including the presence-lease revocation when the experiment is on).
 *
 * `test_presence_day_offset=<n>` (cold start only) moves the discovery wall clock forward by n whole days.
 * See [DiscoveryWallClock].
 *
 * `test_rotid_epoch_s=<60..900>` (cold start only) shortens the rotating-ID epoch. See [DiscoveryEpochLength].
 *
 * Tools: tools/debug_verify_contacts.py writes these files for a pair of phones.
 */
internal object DebugContactControl {
    const val VERIFY_EXTRA = "test_verify_contact"
    const val FORGET_EXTRA = "test_forget_contact"
    const val DAY_OFFSET_EXTRA = "test_presence_day_offset"
    const val VERIFY_FILE = "debug-verify-contact"
    const val FORGET_FILE = "debug-forget-contact"

    private val TOKEN = Regex("[0-9a-f]{32,128}")
    private val FINGERPRINT = Regex("[0-9a-f]{64}")
    private const val TAG = "BluetoothVideo"

    /** A parsed one-shot request: the fingerprint and the display name to store. */
    class Request(val fingerprint: IdentityFingerprint, val displayName: String)

    /** Both gates: a debug build type and a debuggable package. Release builds are never allowed. */
    fun allowed(context: Context): Boolean =
        isAllowed(BuildConfig.DEBUG, (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)

    internal fun isAllowed(buildDebug: Boolean, debuggable: Boolean): Boolean = buildDebug && debuggable

    /**
     * Pure parse of the file text against the presented token: line 1 the token (32-128 lowercase hex), line 2
     * the full fingerprint (64 lowercase hex), optional line 3 the display name. Null when the token is missing,
     * malformed or different, or the fingerprint is malformed.
     */
    internal fun parse(stored: String?, presented: String?): Request? {
        if (!tokenMatches(stored, presented)) return null
        val lines = stored!!.lines().map { it.trim() }
        val fingerprintHex = lines.getOrNull(1).orEmpty()
        if (!FINGERPRINT.matches(fingerprintHex)) return null
        val fingerprint = runCatching { IdentityFingerprint.fromHex(fingerprintHex) }.getOrNull() ?: return null
        val name = lines.getOrNull(2)?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { IdentityQrCodec.normalizeName(it) }.getOrNull() }
            ?: "Debug contact ${fingerprint.nodePrefixHex.take(6)}"
        return Request(fingerprint, name)
    }

    /** True when the stored first line is a well-formed token equal to the presented one (constant-time compare). */
    internal fun tokenMatches(stored: String?, presented: String?): Boolean {
        val expected = stored?.lineSequence()?.firstOrNull()?.trim() ?: return false
        val offered = presented?.trim() ?: return false
        if (!TOKEN.matches(expected)) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), offered.toByteArray(Charsets.US_ASCII))
    }

    /**
     * One shot: returns the request when [allowed] and the token matches, and deletes the file in that case (also
     * when the fingerprint line is malformed, so a bad file cannot be retried). A mismatch leaves the file and
     * returns null. Nothing is read when not allowed.
     */
    internal fun consume(directory: File, fileName: String, presented: String?, allowed: Boolean): Request? {
        if (!allowed || presented == null) return null
        val file = File(directory, fileName)
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        if (!tokenMatches(text, presented)) return null
        val request = parse(text, presented)
        file.delete()
        return request
    }

    /**
     * Marks the fingerprint verified in [store], as the QR flow does after a scan (the scan itself is the
     * confirmation, so the confirmed fingerprint is also the "authenticated" one; the live handshake
     * requirement of the UI path is the part a benchmark cannot supply). This phone's own fingerprint is refused.
     */
    internal fun verify(
        store: VerifiedContactStore,
        own: IdentityFingerprint?,
        request: Request,
    ): String {
        if (own != null && own == request.fingerprint) return "self"
        return store.markVerified(request.fingerprint, request.fingerprint, request.displayName, VerificationMethod.QR_SCAN)
            .name.lowercase()
    }

    /** Handles `test_verify_contact` / `test_forget_contact` of [intent]; call after IdentityVerification.attach. */
    fun handleIntent(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val allowed = allowed(context)
        val verifyToken = intent.getStringExtra(VERIFY_EXTRA)
        val forgetToken = intent.getStringExtra(FORGET_EXTRA)
        if (verifyToken == null && forgetToken == null) return
        val directory = context.filesDir
        var changed = false
        consume(directory, VERIFY_FILE, verifyToken, allowed)?.let { request ->
            val verification = IdentityVerification.get(context)
            val own = runCatching { verification.ownFingerprint() }.getOrNull()
            val result = runCatching { verify(verification.store, own, request) }.getOrElse { "error:${it.javaClass.simpleName}" }
            val prefix = request.fingerprint.nodePrefixHex
            if (result == "verified") {
                Log.i(TAG, "BENCHMARK contact_verified source=debug fingerprint_prefix=$prefix")
                changed = true
            } else {
                Log.w(TAG, "BENCHMARK contact_verify_rejected source=debug fingerprint_prefix=$prefix reason=$result")
            }
        }
        consume(directory, FORGET_FILE, forgetToken, allowed)?.let { request ->
            val forgotten = runCatching { IdentityVerification.get(context).forget(request.fingerprint) }.getOrDefault(false)
            Log.i(
                TAG,
                "BENCHMARK contact_forgotten source=debug fingerprint_prefix=${request.fingerprint.nodePrefixHex} " +
                    "result=${if (forgotten) "forgotten" else "not_found"}",
            )
            changed = changed || forgotten
        }
        if (changed && RotatingDiscoveryFeature.enabled) RotatingDiscovery.requestRecognitionRebuild()
    }

    /**
     * Logs this phone's full identity fingerprint for the harness (public information: it is what the identity QR
     * code carries). Runs off the caller's thread because the identity may need the Keystore.
     */
    fun logOwnIdentity(context: Context) {
        if (!BuildConfig.DEBUG) return
        val app = context.applicationContext
        Thread {
            runCatching {
                val fingerprint = IdentityVerification.get(app).ownFingerprint()
                Log.i(TAG, "BENCHMARK identity fingerprint=${fingerprint.hex} node=${fingerprint.nodeId}")
            }
        }.apply { name = "DebugIdentityLog"; isDaemon = true }.start()
    }

    /**
     * Reads `test_rotid_epoch_s=<60..900>` of a cold-start intent (DEBUG only; call before rotating discovery starts) and
     * logs what was applied. Release builds and launches without the extra keep the 900 s epoch.
     */
    fun applyEpochLength(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || !intent.hasExtra(DiscoveryEpochLength.EXTRA)) return
        val requested = intent.getIntExtra(DiscoveryEpochLength.EXTRA, 0)
        val applied = DiscoveryEpochLength.set(requested, BuildConfig.DEBUG, allowed(context), RotatingDiscovery.isStarted())
        Log.i(TAG, "BENCHMARK rotid_epoch_s: requested=$requested | epochSeconds=${RotatingDiscoveryIds.epochSeconds} | applied=$applied")
    }

    /** Reads `test_presence_day_offset` of a cold-start intent (DEBUG only; call before rotating discovery starts). */
    fun applyDayOffset(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || !intent.hasExtra(DAY_OFFSET_EXTRA)) return
        val requested = intent.getIntExtra(DAY_OFFSET_EXTRA, 0)
        val applied = DiscoveryWallClock.setDayOffset(requested, BuildConfig.DEBUG, allowed(context))
        Log.i(TAG, "BENCHMARK presence_day_offset: requested=$requested | days=${DiscoveryWallClock.dayOffset} | applied=$applied")
    }
}

/**
 * The wall clock of rotating discovery (epoch ticks, the selection-check tolerance, recognition-table
 * rebuilds, the Visible-by-handle window and lease received-times). It equals `System.currentTimeMillis()`
 * unless a DEBUG launch asked for `test_presence_day_offset=<n>`, which adds n whole days. Nothing else
 * (certificates, call timing, media) reads it.
 *
 * Use the same offset on both phones of a pair: a selected rotating advert is accepted only within two
 * epochs of the verifier's own clock (RD4), so a one-sided offset makes that pair's rotating call fail as
 * stale, by design. Forward only (0..[MAX_DAYS]); a later launch without the extra returns to real time and the
 * persisted epoch counter is then ahead of the wall (RV4 forward-jump recovery starts a new presence
 * generation after an hour of consistent wall time), so clear app data after a day-roll run.
 */
object DiscoveryWallClock {
    const val MAX_DAYS = 28
    private const val DAY_MS = 86_400_000L

    @Volatile private var offsetMs = 0L

    val dayOffset: Int get() = (offsetMs / DAY_MS).toInt()

    fun nowMs(): Long = System.currentTimeMillis() + offsetMs

    /** Applies [days] only for a debug build type of a debuggable package; returns whether it was applied. */
    internal fun setDayOffset(days: Int, buildDebug: Boolean, debuggable: Boolean): Boolean {
        if (!DebugContactControl.isAllowed(buildDebug, debuggable) || days !in 0..MAX_DAYS) return false
        offsetMs = days * DAY_MS
        return true
    }

    internal fun resetForTest() {
        offsetMs = 0L
    }
}

/**
 * DEBUG harness `test_rotid_epoch_s=<60..900>` (cold start only): shortens the rotating-ID epoch so a functional phone test
 * sees three boundaries in minutes (60 s epochs: about 3 min). Gates: a debug build type of a debuggable package, a value in
 * 60..900, and rotating discovery not yet started in this process (the length is set once, before the epoch clock loads its
 * stored state). Everything else is unchanged: the wire format, the day numbering (presence-lease days stay real days, see
 * [PresenceLeases.dayOf]) and the ordered-encryption and admission rules. Give BOTH phones the same value: a selected rotating
 * advert is accepted only within two epochs of the verifier's own wall epoch (RD4), and epoch numbers are `unix / epochSeconds`,
 * so unequal lengths make rotating adverts fail as stale by design. A later launch without the extra returns to 900 s; the
 * epoch clock then restarts its stored counter (its length is stored beside it), so nothing needs clearing for RV4, but contacts'
 * stored epoch skews (counted in the old length) are only corrected by the next lease exchange. The gate
 * takes its flags as parameters so JVM tests reach it.
 */
object DiscoveryEpochLength {
    const val EXTRA = "test_rotid_epoch_s"
    const val MIN_SECONDS = 60
    const val MAX_SECONDS = 900

    /** Applies [seconds]; returns whether it is in force afterwards because of this call (or already was, unchanged). */
    internal fun set(seconds: Int, buildDebug: Boolean, debuggable: Boolean, started: Boolean): Boolean {
        if (!DebugContactControl.isAllowed(buildDebug, debuggable) || seconds !in MIN_SECONDS..MAX_SECONDS) return false
        if (started) return seconds.toLong() == RotatingDiscoveryIds.epochSeconds
        RotatingDiscoveryIds.epochSeconds = seconds.toLong()
        return true
    }

    internal fun resetForTest() {
        RotatingDiscoveryIds.epochSeconds = RotatingDiscoveryIds.EPOCH_SECONDS
    }
}
