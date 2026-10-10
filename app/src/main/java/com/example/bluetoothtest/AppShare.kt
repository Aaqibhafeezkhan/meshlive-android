package com.example.bluetoothtest

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.concurrent.thread

/**
 * Offline app sharing (research catalog P1, 2026-10-10; a Briar-style pattern, no code copied).
 *
 * A phone with the app can hand it to a phone without internet or an app store: the installed base
 * APK (`ApplicationInfo.sourceDir`) is copied into this app's cache under a friendly name and offered
 * through the Android share sheet as one `ACTION_SEND` item, so the user can choose Quick Share/Nearby
 * Share, a file manager, or Bluetooth where the stack offers it. No permission and no radio of our own
 * are used. The installed base APK of a single-APK install is the distributed file itself, so its
 * SHA-256 equals the release's `.apk.sha256` (README "Verifying a release").
 *
 * Bluetooth (OPP) caveat, from AOSP packages/modules/Bluetooth 745ee92b87ed: the stock OPP share target
 * does not list `application/vnd.android.package-archive` (android/app/AndroidManifest.xml SEND filter),
 * and the stock OPP receiver derives the type from the `.apk` extension and rejects it as outside
 * `Constants.ACCEPTABLE_SHARE_INBOUND_TYPES` (opp/BluetoothOppObexServerSession.java onPut). OEM stacks
 * may differ. [AppShareFormat.ZIP_RENAMED] (Experimental) sends the same bytes named `.apk.zip` as
 * `application/zip`, which stock OPP offers and accepts; the receiver must rename it to `.apk` before
 * installing. Not yet tested on phones.
 *
 * Refused: split installs (base.apk alone would not install) and a release build signed with the
 * Android debug key. Copies are deleted at the next share, by [AppShare.cleanup], and after
 * [AppSharePolicy.COPY_TTL_MS] (swept on app start, each share, and a timer while the process lives).
 */
internal enum class AppShareFormat(val mimeType: String, val extension: String) {
    /** The normal `.apk` package type. */
    APK(AppSharePolicy.MIME_APK, ".apk"),

    /** Experimental: `.apk.zip` / application/zip for stock Bluetooth OPP; the receiver renames it. */
    ZIP_RENAMED(AppSharePolicy.MIME_ZIP, ".apk.zip"),
}

/** What the share screen shows and what [AppShare.shareApk] checks. */
internal data class AppApkInfo(
    val versionName: String,
    val versionCode: Long,
    val debugBuild: Boolean,
    val sizeBytes: Long,
    /** SHA-256 of the installed base APK, lowercase hex (the format of `sha256sum`). */
    val apkSha256: String,
    /** SHA-256 of each signing certificate, colon-separated uppercase (the format of `apksigner`). */
    val signingCertSha256: List<String>,
    /** True when the only signer is the published release key (README "Verifying a release"). */
    val matchesPublishedReleaseKey: Boolean,
    val splitCount: Int,
    /** Null when the APK may be shared; otherwise the message to show. */
    val refusal: String?,
) {
    val shareable: Boolean get() = refusal == null
}

internal sealed class AppShareResult {
    /** The share sheet opened for [fileName]; its bytes were verified against [info].apkSha256. */
    data class Started(val info: AppApkInfo, val fileName: String) : AppShareResult()

    /** Sharing is not allowed for this install; [reason] is user-facing. */
    data class Refused(val reason: String, val info: AppApkInfo?) : AppShareResult()

    /** Something failed (I/O, no share target); [reason] is user-facing. */
    data class Failed(val reason: String) : AppShareResult()
}

/** Pure rules, unit-tested without Android. */
internal object AppSharePolicy {
    const val MIME_APK = "application/vnd.android.package-archive"
    const val MIME_ZIP = "application/zip"
    const val AUTHORITY_SUFFIX = ".appshare"

    /** The only cache directory the FileProvider exposes (res/xml/app_share_paths.xml). */
    const val CACHE_SUBDIR = "app-share"
    const val COPY_TTL_MS = 30 * 60 * 1000L
    const val FILE_PREFIX = "BluetoothVideo"

    /** Must equal README "Verifying a release" (AppSharePolicyTest checks it). */
    const val PUBLISHED_RELEASE_CERT_SHA256 =
        "2E:EF:C0:5A:44:45:22:52:9D:9F:75:D7:EB:CE:FD:08:86:47:07:F2:1A:79:04:1D:B5:1E:BF:F8:36:49:E2:13"

    const val REFUSE_UNREADABLE = "This phone could not read the installed app package, so it cannot be shared."
    const val REFUSE_NO_SIGNER = "The installed app package has no readable signature, so it cannot be shared."

    fun refuseSplit(splitCount: Int): String =
        "This copy was installed as ${splitCount + 1} separate packages (for example from an app store). " +
            "Sharing one of them would not install on another phone. Install the single-file release to share it."

    const val REFUSE_DEBUG_SIGNED =
        "This release copy is signed with the Android debug key, which anyone can use, so the other phone " +
            "could not tell it from a tampered copy. Install a release signed with the project key to share it."

    /** Android's generated debug keystore always uses the subject `CN=Android Debug,O=Android,C=US`. */
    fun isAndroidDebugCertificate(subjectDn: String): Boolean =
        subjectDn.split(',').any { part ->
            val kv = part.trim().split('=', limit = 2)
            kv.size == 2 && kv[0].trim().equals("CN", ignoreCase = true) &&
                kv[1].trim().equals("Android Debug", ignoreCase = true)
        }

    /** Null when sharing is allowed, else the user-facing refusal. Debug builds may share themselves. */
    fun refusal(
        apkReadable: Boolean,
        splitCount: Int,
        debugBuild: Boolean,
        signerSubjects: List<String>,
    ): String? = when {
        !apkReadable -> REFUSE_UNREADABLE
        splitCount > 0 -> refuseSplit(splitCount)
        signerSubjects.isEmpty() -> REFUSE_NO_SIGNER
        !debugBuild && signerSubjects.any(::isAndroidDebugCertificate) -> REFUSE_DEBUG_SIGNED
        else -> null
    }

    fun matchesPublishedReleaseKey(certSha256: List<String>): Boolean =
        certSha256.size == 1 && certSha256[0].equals(PUBLISHED_RELEASE_CERT_SHA256, ignoreCase = true)

    fun fingerprint(bytes: ByteArray): String = bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** `BluetoothVideo-1.0-1.apk`; characters other than letters, digits, dot, dash and underscore become `_`. */
    fun shareFileName(versionName: String, versionCode: Long, format: AppShareFormat = AppShareFormat.APK): String {
        val version = versionName.ifBlank { "unknown" }.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
        return "$FILE_PREFIX-$version-$versionCode${format.extension}"
    }

    fun expired(lastModifiedMs: Long, nowMs: Long, ttlMs: Long): Boolean = nowMs - lastModifiedMs >= ttlMs

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return hex(digest.digest())
    }

    /**
     * Copies [source] to [target] while hashing the copied bytes, and deletes [target] unless the copy
     * hash equals [expectedSha256] (an APK replaced during the copy, or a short write). Returns the hash.
     */
    fun copyVerified(source: InputStream, target: File, expectedSha256: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            target.outputStream().use { out -> copyHashing(source, out, digest) }
            val actual = hex(digest.digest())
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                throw IOException("The copied package does not match the installed one ($actual != $expectedSha256)")
            }
            return actual
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    /** Deletes files in [dir] older than [ttlMs] (0 deletes all); returns how many were deleted. */
    fun cleanup(dir: File, nowMs: Long, ttlMs: Long): Int {
        val files = dir.listFiles() ?: return 0
        return files.count { file -> file.isFile && expired(file.lastModified(), nowMs, ttlMs) && file.delete() }
    }

    private fun copyHashing(source: InputStream, out: OutputStream, digest: MessageDigest) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            out.write(buffer, 0, read)
        }
    }

    private const val BUFFER_BYTES = 64 * 1024
}

/**
 * Public API for the share screen (the UI owns the button):
 * - [apkInfo] reads and hashes the installed APK (blocking I/O; call it off the main thread);
 * - [shareApk] does the checks and the copy on a worker thread, then opens the share sheet and reports
 *   the result on the main thread;
 * - [cleanup] deletes shared copies (all by default).
 */
internal object AppShare {
    private const val TAG = "BluetoothVideo"
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun apkInfo(context: Context): AppApkInfo {
        val appInfo = context.applicationInfo
        val packageInfo = packageInfo(context)
        val versionCode = packageInfo.longVersionCode
        val versionName = packageInfo.versionName ?: "unknown"
        val apk = File(appInfo.sourceDir ?: "")
        val splitCount = appInfo.splitSourceDirs?.size ?: 0
        val certificates = signingCertificates(packageInfo)
        val certSha256 = certificates.map { AppSharePolicy.fingerprint(sha256(it.encoded)) }
        val apkSha256 = runCatching { FileInputStream(apk).use(AppSharePolicy::sha256) }.getOrNull()
        val debugBuild = BuildConfig.DEBUG
        return AppApkInfo(
            versionName = versionName,
            versionCode = versionCode,
            debugBuild = debugBuild,
            sizeBytes = if (apk.isFile) apk.length() else 0L,
            apkSha256 = apkSha256 ?: "",
            signingCertSha256 = certSha256,
            matchesPublishedReleaseKey = AppSharePolicy.matchesPublishedReleaseKey(certSha256),
            splitCount = splitCount,
            refusal = AppSharePolicy.refusal(
                apkReadable = apkSha256 != null,
                splitCount = splitCount,
                debugBuild = debugBuild,
                signerSubjects = certificates.map { it.subjectX500Principal.name },
            ),
        )
    }

    /**
     * Opens the share sheet for this app's APK. [onResult] runs on the main thread. Old copies are
     * deleted first; the new copy is deleted by [cleanup], at the next share, or after the TTL.
     */
    fun shareApk(
        activity: Activity,
        format: AppShareFormat = AppShareFormat.APK,
        onResult: (AppShareResult) -> Unit = {},
    ) {
        val appContext = activity.applicationContext
        thread(name = "app-share", isDaemon = true) {
            val preparation = runCatching { prepare(appContext, format) }
                .getOrElse { error ->
                    Log.w(TAG, "App share copy failed", error)
                    Preparation.Done(
                        AppShareResult.Failed("The app package could not be prepared for sharing (${error.message})."),
                    )
                }
            mainHandler.post {
                val result = when (preparation) {
                    is Preparation.Ready -> launchChooser(activity, preparation, format)
                    is Preparation.Done -> preparation.result
                }
                log(result, format)
                onResult(result)
            }
        }
    }

    /** Deletes shared copies older than [olderThanMs] (default: all). Returns how many were deleted. */
    fun cleanup(context: Context, olderThanMs: Long = 0L): Int =
        AppSharePolicy.cleanup(shareDir(context), System.currentTimeMillis(), olderThanMs)

    /** App-start sweep: deletes copies older than the TTL. */
    fun cleanupExpired(context: Context): Int = cleanup(context, AppSharePolicy.COPY_TTL_MS)

    private sealed class Preparation {
        class Ready(val info: AppApkInfo, val file: File) : Preparation()
        class Done(val result: AppShareResult) : Preparation()
    }

    private fun prepare(context: Context, format: AppShareFormat): Preparation {
        cleanup(context)
        val info = apkInfo(context)
        info.refusal?.let { return Preparation.Done(AppShareResult.Refused(it, info)) }
        val dir = shareDir(context).apply { mkdirs() }
        val target = File(dir, AppSharePolicy.shareFileName(info.versionName, info.versionCode, format))
        FileInputStream(context.applicationInfo.sourceDir).use { source ->
            AppSharePolicy.copyVerified(source, target, info.apkSha256)
        }
        mainHandler.postDelayed({ cleanupExpired(context) }, AppSharePolicy.COPY_TTL_MS + 1_000L)
        return Preparation.Ready(info, target)
    }

    private fun launchChooser(activity: Activity, prepared: Preparation.Ready, format: AppShareFormat): AppShareResult {
        if (activity.isFinishing || activity.isDestroyed) {
            prepared.file.delete()
            return AppShareResult.Failed("The screen closed before sharing started.")
        }
        val uri = FileProvider.getUriForFile(
            activity,
            activity.packageName + AppSharePolicy.AUTHORITY_SUFFIX,
            prepared.file,
        )
        val send = Intent(Intent.ACTION_SEND)
            .setType(format.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, prepared.file.name)
            .putExtra(Intent.EXTRA_TEXT, shareText(prepared.info, format))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData carries the read grant through the chooser to the target the user picks.
        send.clipData = ClipData.newUri(activity.contentResolver, prepared.file.name, uri)
        return try {
            activity.startActivity(Intent.createChooser(send, "Share Bluetooth Video app"))
            AppShareResult.Started(prepared.info, prepared.file.name)
        } catch (error: RuntimeException) {
            prepared.file.delete()
            AppShareResult.Failed("No app on this phone can share the package (${error.javaClass.simpleName}).")
        }
    }

    /** The text some targets attach next to the file, so the receiver can compare fingerprints. */
    internal fun shareText(info: AppApkInfo, format: AppShareFormat): String = buildString {
        append("Bluetooth Video ${info.versionName} (${info.versionCode}). ")
        append("APK SHA-256 ${info.apkSha256}. ")
        append("Signing certificate SHA-256 ${info.signingCertSha256.joinToString(" / ")}.")
        if (format == AppShareFormat.ZIP_RENAMED) append(" Rename the received file to end in .apk before installing.")
    }

    private fun log(result: AppShareResult, format: AppShareFormat) {
        if (!BuildConfig.DEBUG) return
        val detail = when (result) {
            is AppShareResult.Started -> "started | file=${result.fileName} | bytes=${result.info.sizeBytes} | " +
                "sha256=${result.info.apkSha256} | releaseKey=${result.info.matchesPublishedReleaseKey}"
            is AppShareResult.Refused -> "refused | splits=${result.info?.splitCount} | debugBuild=${result.info?.debugBuild}"
            is AppShareResult.Failed -> "failed"
        }
        Log.i(TAG, "BENCHMARK app_share: result=$detail | format=${format.name.lowercase()}")
    }

    private fun shareDir(context: Context) = File(context.cacheDir, AppSharePolicy.CACHE_SUBDIR)

    private fun packageInfo(context: Context) = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    /** The current signers (not the rotation history), as X.509 certificates. */
    private fun signingCertificates(packageInfo: android.content.pm.PackageInfo): List<X509Certificate> {
        val signatures = packageInfo.signingInfo?.apkContentsSigners ?: return emptyList()
        val factory = CertificateFactory.getInstance("X.509")
        return signatures.mapNotNull { signature ->
            runCatching {
                factory.generateCertificate(ByteArrayInputStream(signature.toByteArray())) as X509Certificate
            }.getOrNull()
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

/** FileProvider for [AppShare] only; its paths file exposes nothing but `cache/app-share/`. */
class AppShareFileProvider : FileProvider()
