package com.example.bluetoothtest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager

/** What PackageManager says about the installed copy (name, code and its current signing certificates). */
internal data class InstalledBuild(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    /** Colon-separated uppercase SHA-256 of each current signer certificate. */
    val signersSha256: List<String>,
) {
    fun facts(): InstalledFacts = InstalledFacts(packageName, versionCode, signersSha256)

    companion object {
        fun read(context: Context): InstalledBuild? = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers = info.signingInfo?.apkContentsSigners?.map { ReleaseSigner.sha256Colon(it.toByteArray()) }.orEmpty()
            InstalledBuild(context.packageName, info.versionName.orEmpty(), info.longVersionCode, signers)
        }.getOrNull()
    }
}

/**
 * Process-wide update settings: DEBUG test overrides (never read in a release build) and the build facts
 * the tag, the notice and the updater share. [debugRelease] lets a debug phone (versionCode 1, no release
 * number) take part in phone test T13.
 */
internal object UpdateRuntime {
    @Volatile var debugRelease: Int? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    @Volatile var debugReleaseKey: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    @Volatile var debugPinOwnSigner: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    @Volatile var debugLatestUrl: String? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /** DEBUG benchmark launches only: reads the `test_update_*` extras of the launch intent. */
    fun readDebugExtras(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        debugRelease = intent.getIntExtra(UpdateSettings.EXTRA_DEBUG_RELEASE_NUMBER, 0).takeIf { it in 1..ReleaseNumber.MAX }
        debugReleaseKey = intent.getBooleanExtra(UpdateSettings.EXTRA_DEBUG_RELEASE_KEY, false)
        debugPinOwnSigner = intent.getBooleanExtra(UpdateSettings.EXTRA_DEBUG_PIN_OWN_SIGNER, false)
        debugLatestUrl = intent.getStringExtra(UpdateSettings.EXTRA_DEBUG_LATEST_URL)
    }

    /** The release number the tag and the notice rules use: the build's own, or the DEBUG override; 0 for none. */
    fun release(build: InstalledBuild): Int = debugRelease ?: ReleaseNumber.fromVersionCode(build.versionCode) ?: 0

    /** Whether this copy counts as carrying the project release key (the DEBUG override can say yes). */
    fun hasReleaseKey(build: InstalledBuild): Boolean =
        if (debugRelease != null) debugReleaseKey else ReleaseSigner.isPinnedSingleSigner(build.signersSha256)

    /** The certificates the online update must carry: the pin, or (DEBUG test only) this build's own signer. */
    fun pinned(build: InstalledBuild): Set<String> =
        if (debugPinOwnSigner && BuildConfig.DEBUG) build.signersSha256.toSet() else ReleaseSigner.PINNED

    /** True when the installed copy may use the online check: the pinned certificate (or the DEBUG test pin). */
    fun onlineCheckOffered(build: InstalledBuild): Boolean =
        BuildConfig.UPDATER_AVAILABLE &&
            (ReleaseSigner.isPinnedSingleSigner(build.signersSha256) ||
                (debugPinOwnSigner && build.signersSha256.size == 1))

    fun source(): UpdateSource {
        val override = debugLatestUrl ?: return UpdateSource.GITHUB
        // DEBUG only (T13): a test server standing in for GitHub; its host is the only redirect target.
        val base = override.substringBeforeLast('/', override) + "/"
        val host = runCatching { java.net.URI(override).host }.getOrNull().orEmpty()
        return UpdateSource(
            latestUrl = override,
            assetUrlPrefix = base,
            pageUrlPrefix = base,
            releasesPage = base,
            redirectHosts = listOf(host),
            allowCleartext = override.startsWith("http://"),
        )
    }
}

/**
 * The user's yes to "Check for updates when online?", kept with a marker file in `noBackupFilesDir`.
 * The switch itself is a normal preference (Settings > Features), and Android may restore preferences from a
 * backup onto another phone; the marker is not restored, so a restored "yes" without it is dropped and the
 * question is asked again (security review F10). An emergency wipe removes both.
 */
internal object UpdateConsent {
    private const val MARKER = "update-consent"

    /** The rules without Android types (JVM-tested): the yes counts only together with its marker. */
    fun grantedFor(prefYes: Boolean, markerExists: Boolean): Boolean = prefYes && markerExists

    /** A yes without a marker was restored from a backup (or the marker was lost): forget it and ask again. */
    fun mustForget(prefYes: Boolean, markerExists: Boolean): Boolean = prefYes && !markerExists

    private fun marker(context: Context) = java.io.File(context.noBackupFilesDir, MARKER)

    fun granted(context: Context, prefs: SharedPreferences): Boolean =
        grantedFor(prefs.getBoolean(UpdateSettings.PREF_CHECK_FOR_UPDATES, false), marker(context).exists())

    /** Records the answer: the preference, the marker (yes only) and that the question was answered. */
    fun set(context: Context, prefs: SharedPreferences, yes: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (yes) {
            runCatching { marker(context).apply { parentFile?.mkdirs(); createNewFile() } }
        } else {
            marker(context).delete()
        }
        prefs.edit().putBoolean(UpdateSettings.PREF_CHECK_FOR_UPDATES, yes).apply()
        UpdateCheckStore(PrefsUpdateStore(prefs)).markAsked(nowMs)
    }

    /**
     * The "Check for updates" switch in Settings changed (its preference already holds the new value): the choice counts
     * as the answer to the question, and turning it off also forgets what the check learned.
     */
    fun onSwitchChanged(context: Context, prefs: SharedPreferences) {
        val yes = prefs.getBoolean(UpdateSettings.PREF_CHECK_FOR_UPDATES, false)
        set(context, prefs, yes)
        if (!yes) prefs.edit().remove(UpdateCheckStore.LATEST).apply()
    }

    /**
     * A "yes" that arrived without its marker (restored from a backup, or a marker lost) is forgotten: the
     * preference is removed and the question will be asked again. Returns true when it did so.
     */
    fun reconcile(context: Context, prefs: SharedPreferences): Boolean {
        if (!mustForget(prefs.getBoolean(UpdateSettings.PREF_CHECK_FOR_UPDATES, false), marker(context).exists())) return false
        prefs.edit()
            .remove(UpdateSettings.PREF_CHECK_FOR_UPDATES)
            .putLong(UpdateCheckStore.ASKED, 0L)
            .putLong(UpdateCheckStore.ATTEMPT, 0L)
            .remove(UpdateCheckStore.LATEST)
            .apply()
        return true
    }
}

/** Preferences adapters for the pure stores. */
internal class PrefsNoticeStore(private val prefs: SharedPreferences) : NoticeKeyValue {
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)

    override fun putLongs(values: Map<String, Long>) {
        prefs.edit().apply { values.forEach { (k, v) -> putLong(k, v) } }.apply()
    }
}

internal class PrefsUpdateStore(private val prefs: SharedPreferences) : UpdateKeyValue {
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun put(longs: Map<String, Long>, strings: Map<String, String?>) {
        prefs.edit().apply {
            longs.forEach { (k, v) -> putLong(k, v) }
            strings.forEach { (k, v) -> if (v == null) remove(k) else putString(k, v) }
        }.apply()
    }
}

/** What each flavour supplies (src/github and src/fdroid each define [FlavourUpdater]). */
internal interface UpdaterBackend {
    /** True in the github flavour: the online check, download and install exist. */
    val available: Boolean

    /** Arms or cancels the daily background check to match the switch. */
    fun applyCheckSetting(context: Context, enabled: Boolean)

    /** Runs one automatic check now if the schedule says it is due; never blocks the caller. */
    fun maybeCheckNow(context: Context, onDone: () -> Unit)

    /** Opens the update screen. [nearbyRelease] > 0 when it comes from the home notice ("Newer version nearby"). */
    fun open(activity: Activity, nearbyRelease: Int)

    /** Shows the one-time question "Check for updates when online?" and reports the answer. */
    fun askOnce(activity: Activity, onAnswered: (Boolean) -> Unit)
}

internal object Updater {
    val backend: UpdaterBackend get() = FlavourUpdater
}
