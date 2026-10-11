package com.example.bluetoothtest

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** A candidate APK that passed every check and sits in the private directory, waiting for the user's tap. */
internal data class PreparedUpdate(
    val file: File,
    /** SHA-256 (lowercase hex) of [file], computed while it was written and again when it is installed. */
    val sha256: String,
    val versionName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    /** "github" (the release page) or "nearby" (a file the user picked). */
    val origin: String,
)

internal sealed class PrepareResult {
    data class Ready(val update: PreparedUpdate) : PrepareResult()

    /** Nothing newer is published. */
    data class UpToDate(val tag: String) : PrepareResult()

    /** [kind] picks the sentence shown to the user; [log] is for the DEBUG log only. */
    data class Failed(val kind: Kind, val log: String, val refusal: UpdateRefusal? = null) : PrepareResult()

    enum class Kind { OFFLINE, SERVER, RATE_LIMITED, NO_FILE, NO_HASH, HASH_MISMATCH, REFUSED, STORAGE, CANCELLED, UNREADABLE, CHECKS_OFF }
}

internal sealed class CheckResult {
    object Skipped : CheckResult()
    object NotOffered : CheckResult()
    data class TooSoon(val waitMs: Long) : CheckResult()
    object Offline : CheckResult()
    data class Answered(val latestTag: String?, val newer: Boolean) : CheckResult()
    data class Failed(val log: String) : CheckResult()
}

/**
 * The updater's work (github flavour): the daily check, the download, the verification and the install.
 * Every decision is in the pure classes ([UpdateSchedule], [UpdateFlow], [UpdateVerifier], [HashedCopy]);
 * this object connects them to the network, the private directory and PackageInstaller.
 */
internal object UpdateEngine {
    private const val TAG = "BluetoothVideo"

    /** A picked file must be read within this time. */
    private const val IMPORT_BUDGET_MS = 3 * 60_000L

    // ---- private storage ----

    fun directory(context: Context): File = File(context.noBackupFilesDir, "updates").also { it.mkdirs() }

    /** One file per origin, so a picked file and a download never overwrite each other (security review F6). */
    private fun candidate(context: Context, origin: String) = File(directory(context), "candidate-$origin.apk")

    private fun partial(context: Context, origin: String) = File(directory(context), "candidate-$origin.part")

    /** Deletes leftovers (an installed update's file, an abandoned download). Safe at any time but during an install. */
    fun sweep(context: Context, keepIfNewerThanMs: Long = 0) {
        val dir = File(context.noBackupFilesDir, "updates")
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { if (keepIfNewerThanMs == 0L || now - it.lastModified() > keepIfNewerThanMs) it.delete() }
        abandonSessions(context)
    }

    /** Installer sessions this app left open (a refused tap, a crash): abandoned, so none holds a copy of an APK. */
    fun abandonSessions(context: Context) {
        runCatching {
            val installer = context.packageManager.packageInstaller
            installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        }
    }

    // ---- the daily check ----

    private fun online(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * One `releases/latest` request if the schedule allows it. Records the attempt either way, so
     * "at most one request a day" holds across the app, the background job and a manual tap.
     */
    fun check(context: Context, manual: Boolean): CheckResult = synchronized(checkLock) { checkLocked(context, manual) }

    /** The schedule is read, used and saved under one lock: the job, the home resume and a tap share the process (review F5). */
    private val checkLock = Any()

    private fun checkLocked(context: Context, manual: Boolean): CheckResult {
        val prefs = context.getSharedPreferences(UpdateSettings.PREFERENCES_FILE, Context.MODE_PRIVATE)
        val store = UpdateCheckStore(PrefsUpdateStore(prefs))
        val build = InstalledBuild.read(context) ?: return CheckResult.Failed("installed build unreadable")
        if (!UpdateRuntime.onlineCheckOffered(build)) return CheckResult.NotOffered
        // No request of any kind unless the user said yes (also for a tap on "Check now").
        if (!UpdateConsent.granted(context, prefs)) return CheckResult.Skipped
        val now = System.currentTimeMillis()
        val state = store.load()
        if (manual) {
            if (!UpdateSchedule.manualAllowed(state, now)) return CheckResult.TooSoon(UpdateSchedule.manualWaitMs(state, now))
        } else if (!UpdateSchedule.automaticDue(true, state, now)) {
            return CheckResult.Skipped
        }
        if (!online(context)) return CheckResult.Offline
        val source = UpdateRuntime.source()
        return try {
            val answer = UpdateHttp.get(source.latestUrl, source, GitHubReleases.MAX_LATEST_BYTES, now)
            val outcome = if (answer.status == 200) {
                UpdateFlow.evaluate(String(answer.body, Charsets.UTF_8), build.versionName, build.versionCode, Build.SUPPORTED_ABIS.toList(), source)
            } else {
                CheckOutcome.Unusable("HTTP ${answer.status}")
            }
            val tag = when (outcome) {
                is CheckOutcome.UpToDate -> outcome.tag
                is CheckOutcome.Available -> outcome.release.tag
                is CheckOutcome.NoFileForThisPhone -> outcome.release.tag
                is CheckOutcome.Unusable -> null
            }
            store.save(UpdateSchedule.afterAnswer(state, System.currentTimeMillis(), tag, answer.rateLimitedUntilMs))
            if (BuildConfig.DEBUG) Log.i(TAG, "BENCHMARK update_check: manual=$manual | status=${answer.status} | outcome=${outcome::class.java.simpleName} | tag=$tag")
            when (outcome) {
                is CheckOutcome.Available, is CheckOutcome.NoFileForThisPhone -> CheckResult.Answered(tag, newer = true)
                is CheckOutcome.UpToDate -> CheckResult.Answered(tag, newer = false)
                is CheckOutcome.Unusable -> CheckResult.Failed(outcome.detail)
            }
        } catch (e: UpdateHttp.TransportException) {
            store.save(UpdateSchedule.afterNetworkFailure(state, System.currentTimeMillis()))
            if (BuildConfig.DEBUG) Log.i(TAG, "BENCHMARK update_check: manual=$manual | transport failure: ${e.message}")
            CheckResult.Offline
        }
    }

    // ---- download and verify ----

    /**
     * The user tapped "Download": reads `releases/latest` again (the answer is not stored), downloads the
     * file for this phone into the private directory, checks its SHA-256 against the release, reads its
     * package facts and verifies them. Returns [PrepareResult.Ready] only if every rule held.
     */
    fun downloadAndVerify(
        context: Context,
        isCancelled: () -> Boolean,
        onProgress: (done: Long, total: Long) -> Unit,
    ): PrepareResult {
        val build = InstalledBuild.read(context)
            ?: return PrepareResult.Failed(PrepareResult.Kind.UNREADABLE, "installed build unreadable")
        if (!UpdateRuntime.onlineCheckOffered(build)) {
            return PrepareResult.Failed(PrepareResult.Kind.REFUSED, "online update not offered", UpdateRefusal.ONLINE_UPDATE_NOT_OFFERED)
        }
        val prefs = context.getSharedPreferences(UpdateSettings.PREFERENCES_FILE, Context.MODE_PRIVATE)
        if (!UpdateConsent.granted(context, prefs)) return PrepareResult.Failed(PrepareResult.Kind.CHECKS_OFF, "update checks are off")
        if (!online(context)) return PrepareResult.Failed(PrepareResult.Kind.OFFLINE, "offline")
        val source = UpdateRuntime.source()
        try {
            val answer = UpdateHttp.get(source.latestUrl, source, GitHubReleases.MAX_LATEST_BYTES)
            if (answer.rateLimitedUntilMs != null) return PrepareResult.Failed(PrepareResult.Kind.RATE_LIMITED, "HTTP ${answer.status}")
            if (answer.status != 200) return PrepareResult.Failed(PrepareResult.Kind.SERVER, "HTTP ${answer.status}")
            val outcome = UpdateFlow.evaluate(String(answer.body, Charsets.UTF_8), build.versionName, build.versionCode, Build.SUPPORTED_ABIS.toList(), source)
            val offer = when (outcome) {
                is CheckOutcome.UpToDate -> return PrepareResult.UpToDate(outcome.tag)
                is CheckOutcome.NoFileForThisPhone -> return PrepareResult.Failed(PrepareResult.Kind.NO_FILE, "no file for this phone in ${outcome.release.tag}")
                is CheckOutcome.Unusable -> return PrepareResult.Failed(PrepareResult.Kind.SERVER, outcome.detail)
                is CheckOutcome.Available -> outcome
            }
            val sumsText = if (offer.needsSums && offer.sums != null) {
                val sums = UpdateHttp.get(offer.sums.url, source, GitHubReleases.MAX_SUMS_BYTES)
                if (sums.status == 200) String(sums.body, Charsets.UTF_8) else null
            } else {
                null
            }
            val published = UpdateFlow.publishedSha256(offer, sumsText)
                ?: return PrepareResult.Failed(PrepareResult.Kind.NO_HASH, "no published SHA-256 for ${offer.apk.name}")

            val part = partial(context, "github")
            part.delete()
            val result = try {
                FileOutputStream(part).use { out ->
                    UpdateHttp.download(offer.apk.url, source, out, GitHubReleases.MAX_APK_BYTES, offer.apk.size, isCancelled) {
                        onProgress(it, offer.apk.size)
                    }
                }
            } catch (e: UpdateHttp.TransportException) {
                part.delete()
                val cancelled = e.message == "Cancelled"
                return PrepareResult.Failed(if (cancelled) PrepareResult.Kind.CANCELLED else PrepareResult.Kind.OFFLINE, e.message.orEmpty())
            } catch (e: IOException) {
                part.delete()
                return PrepareResult.Failed(PrepareResult.Kind.STORAGE, e.message.orEmpty())
            } catch (e: IllegalStateException) {
                part.delete()
                return PrepareResult.Failed(PrepareResult.Kind.SERVER, e.message.orEmpty())
            }
            if (!UpdateVerifier.sameBytes(published, result.sha256)) {
                part.delete()
                return PrepareResult.Failed(PrepareResult.Kind.HASH_MISMATCH, "got ${result.sha256}, release lists $published")
            }
            val file = candidate(context, "github")
            file.delete()
            if (!part.renameTo(file)) return PrepareResult.Failed(PrepareResult.Kind.STORAGE, "rename failed")
            return verifyFile(context, file, result.sha256, build, requirePinned = true, origin = "github")
        } catch (e: UpdateHttp.TransportException) {
            return PrepareResult.Failed(PrepareResult.Kind.OFFLINE, e.message.orEmpty())
        }
    }

    /**
     * A file the user picked (an APK received from a nearby phone, any way it arrived): copied once into the
     * private directory while hashing it, then verified like a download except that it needs the *installed*
     * signer, not the pinned release key.
     */
    fun importAndVerify(context: Context, uri: Uri, isCancelled: () -> Boolean): PrepareResult {
        val build = InstalledBuild.read(context)
            ?: return PrepareResult.Failed(PrepareResult.Kind.UNREADABLE, "installed build unreadable")
        val part = partial(context, "nearby")
        part.delete()
        val deadline = System.currentTimeMillis() + IMPORT_BUDGET_MS
        val result = try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                // A provider that hangs or trickles must not hold the screen forever (review F13).
                val guarded = object : java.io.FilterInputStream(raw) {
                    private fun check() {
                        if (isCancelled()) throw IOException("Cancelled")
                        if (System.currentTimeMillis() > deadline) throw IOException("The file took too long to read")
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        check()
                        return super.read(b, off, len)
                    }
                }
                FileOutputStream(part).use { out -> HashedCopy.copy(guarded, out, GitHubReleases.MAX_APK_BYTES) }
            } ?: return PrepareResult.Failed(PrepareResult.Kind.UNREADABLE, "cannot open the file")
        } catch (e: IllegalStateException) {
            part.delete()
            return PrepareResult.Failed(PrepareResult.Kind.REFUSED, e.message.orEmpty(), UpdateRefusal.UNREADABLE)
        } catch (e: IOException) {
            part.delete()
            val kind = if (e.message == "Cancelled") PrepareResult.Kind.CANCELLED else PrepareResult.Kind.STORAGE
            return PrepareResult.Failed(kind, e.message.orEmpty())
        } catch (e: SecurityException) {
            part.delete()
            return PrepareResult.Failed(PrepareResult.Kind.UNREADABLE, "no permission to read the file")
        }
        val file = candidate(context, "nearby")
        file.delete()
        if (!part.renameTo(file)) return PrepareResult.Failed(PrepareResult.Kind.STORAGE, "rename failed")
        return verifyFile(context, file, result.sha256, build, requirePinned = false, origin = "nearby")
    }

    private fun verifyFile(
        context: Context,
        file: File,
        sha256: String,
        build: InstalledBuild,
        requirePinned: Boolean,
        origin: String,
    ): PrepareResult {
        val facts = readApkFacts(context, file)
        val verdict = UpdateVerifier.verify(facts, build.facts(), requirePinned, UpdateRuntime.pinned(build))
        if (verdict is UpdateVerdict.Refused) {
            file.delete()
            return PrepareResult.Failed(PrepareResult.Kind.REFUSED, verdict.detail, verdict.reason)
        }
        val info = checkNotNull(facts)
        val versionName = runCatching {
            context.packageManager.getPackageArchiveInfo(file.path, 0)?.versionName
        }.getOrNull().orEmpty()
        return PrepareResult.Ready(PreparedUpdate(file, sha256, versionName, info.versionCode, file.length(), origin))
    }

    /**
     * Android 10 collects an archive's certificates only when GET_SIGNATURES is set (the flag was merged into
     * GET_SIGNING_CERTIFICATES in Android 11), and without them `signingInfo` is null: every file would look
     * unsigned (security review F1, AOSP android10-release PackageManager.getPackageArchiveInfo).
     */
    @Suppress("DEPRECATION")
    private fun archiveFlags(): Int =
        if (Build.VERSION.SDK_INT >= 30) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        }

    private fun readApkFacts(context: Context, file: File): ApkFacts? = runCatching {
        val info = context.packageManager.getPackageArchiveInfo(file.path, archiveFlags()) ?: return null
        val signing = info.signingInfo ?: return null
        val multiple = signing.hasMultipleSigners()
        ApkFacts(
            packageName = info.packageName.orEmpty(),
            versionCode = info.longVersionCode,
            signersSha256 = signing.apkContentsSigners.map { ReleaseSigner.sha256Colon(it.toByteArray()) },
            multipleSigners = multiple,
            lineageSha256 = if (multiple) {
                emptyList()
            } else {
                signing.signingCertificateHistory.orEmpty().map { ReleaseSigner.sha256Colon(it.toByteArray()) }
            },
        )
    }.getOrNull()

    // ---- install ----

    /**
     * Writes [update] into a PackageInstaller session and commits it. The session bytes are hashed in
     * transit and must equal the digest of the verified file ([HashedCopy.copyBoundTo]); Android then asks
     * the user to confirm (`USER_ACTION_REQUIRED`: never silent). Throws on any failure after abandoning
     * the session.
     */
    fun install(context: Context, update: PreparedUpdate): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(update.file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        abandonSessions(context)
        val id = installer.createSession(params)
        var session: PackageInstaller.Session? = null
        try {
            session = installer.openSession(id)
            FileInputStream(update.file).use { input ->
                session!!.openWrite("base.apk", 0, update.file.length()).use { out ->
                    HashedCopy.copyBoundTo(input, out, update.sha256, GitHubReleases.MAX_APK_BYTES)
                    session.fsync(out)
                }
            }
            val callback = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
            return id
        } catch (e: Exception) {
            runCatching { session?.abandon() ?: installer.abandonSession(id) }
            throw e
        } finally {
            runCatching { session?.close() }
        }
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()
}
