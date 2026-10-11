package com.example.bluetoothtest

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Updates (github flavour). Two ways to a newer MeshLive, one install path:
 *
 * - from the release page: "Check now" and "Download" ask GitHub (only after the user opted in);
 * - from a nearby phone: the user picks the APK that arrived any way (Share app nearby, Quick Share, a
 *   file manager) and MeshLive verifies it before offering to install it.
 *
 * Whatever the source, [UpdateEngine] copies the file into the app's private directory, verifies it
 * ([UpdateVerifier]) and writes exactly those bytes into a PackageInstaller session; Android then
 * asks the user to confirm. Nothing here installs without that confirmation.
 */
class UpdateActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences(UpdateSettings.PREFERENCES_FILE, Context.MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private lateinit var build: InstalledBuild
    private var busy = false
    private var prepared: PreparedUpdate? = null
    private var preparedNearby: PreparedUpdate? = null
    private var nearbyRelease = 0
    private var cancellable = false

    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var primary: MaterialButton
    private lateinit var check: MaterialButton
    private lateinit var onlineOff: TextView
    private lateinit var enable: MaterialButton
    private lateinit var nearbyIntro: TextView
    private lateinit var nearbyResult: TextView
    private lateinit var installPicked: MaterialButton
    private lateinit var hideNearby: MaterialButton

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_update)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.updateRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val installed = InstalledBuild.read(this)
        if (installed == null) {
            finish()
            return
        }
        build = installed
        nearbyRelease = intent.getIntExtra(EXTRA_NEARBY_RELEASE, 0)
        status = findViewById(R.id.updateStatus)
        detail = findViewById(R.id.updateDetail)
        progress = findViewById(R.id.updateProgress)
        primary = findViewById(R.id.updatePrimaryButton)
        check = findViewById(R.id.updateCheckButton)
        onlineOff = findViewById(R.id.updateOnlineOff)
        enable = findViewById(R.id.updateEnableButton)
        nearbyIntro = findViewById(R.id.updateNearbyIntro)
        nearbyResult = findViewById(R.id.updateNearbyResult)
        installPicked = findViewById(R.id.updateInstallPickedButton)
        hideNearby = findViewById(R.id.updateHideNearbyButton)
        findViewById<View>(R.id.updateBackButton).setOnClickListener { finish() }
        check.setOnClickListener { checkNow() }
        enable.setOnClickListener {
            // The same explanation as the one-time question and the Settings switch: who is contacted, what they see.
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_ask_title)
                .setMessage(R.string.update_ask_message)
                .setPositiveButton(R.string.update_ask_yes) { _, _ ->
                    UpdateConsent.set(this, prefs, true)
                    FeatureCatalog.refresh(
                        DebugTunables.benchmarkLaunch,
                        { false },
                        { key -> if (prefs.contains(key)) prefs.getBoolean(key, false) else null },
                    )
                    UpdateJobService.schedule(this, true)
                    render()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
        findViewById<View>(R.id.updateChooseFileButton).setOnClickListener {
            if (!busy) pickFile.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "application/zip", "*/*"))
        }
        installPicked.setOnClickListener { preparedNearby?.let(::startInstall) }
        hideNearby.setOnClickListener {
            UpdatesHome.dismissNearby(this, prefs)
            finish()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::build.isInitialized) {
            render()
            showLastInstallFailure()
            UpdateInstallReceiver.pendingConfirm?.let { confirm ->
                UpdateInstallReceiver.pendingConfirm = null
                runCatching { startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    override fun onDestroy() {
        cancelled.set(true)
        worker.shutdown()
        super.onDestroy()
    }

    // ---- rendering ----

    private fun onlineEnabled() = UpdateConsent.granted(this, prefs)

    private fun render() {
        val release = ReleaseNumber.fromVersionCode(build.versionCode)
        findViewById<TextView>(R.id.updateInstalledLine).text = if (release != null) {
            getString(R.string.update_installed_line, build.versionName, release)
        } else {
            getString(R.string.update_installed_line_dev, build.versionName)
        }
        val offered = UpdateRuntime.onlineCheckOffered(build)
        val ready = prepared
        val state = UpdateCheckStore(PrefsUpdateStore(prefs)).load()
        val latest = state.latestTag?.let(ReleaseVersion::parse)
        val installedVersion = ReleaseVersion.parse(build.versionName)
        val newer = latest != null && installedVersion != null && latest > installedVersion
        val on = offered && onlineEnabled()
        onlineOff.visibility = if (offered && !on) View.VISIBLE else View.GONE
        enable.visibility = onlineOff.visibility
        // Nothing here can contact GitHub while the switch is off (security review F4).
        check.visibility = if (on) View.VISIBLE else View.GONE
        check.isEnabled = !busy
        detail.visibility = View.GONE
        primary.visibility = View.GONE
        when {
            busy && cancellable -> {
                primary.setText(R.string.update_cancel)
                primary.visibility = View.VISIBLE
                primary.setOnClickListener { cancelled.set(true) }
            }
            !offered -> status.setText(R.string.update_not_offered)
            !on -> status.setText(R.string.update_online_off_status)
            busy -> Unit
            ready != null -> {
                status.text = getString(R.string.update_ready, ready.versionName.ifEmpty { "" })
                detail.setText(R.string.update_ready_detail)
                detail.visibility = View.VISIBLE
                primary.setText(R.string.update_install)
                primary.visibility = View.VISIBLE
                primary.setOnClickListener { startInstall(ready) }
            }
            newer -> {
                status.text = getString(R.string.update_available, latest?.toString().orEmpty())
                primary.setText(R.string.update_download_plain)
                primary.visibility = View.VISIBLE
                primary.setOnClickListener { startDownload() }
            }
            state.latestTag != null -> status.text = getString(R.string.update_up_to_date, build.versionName)
            else -> status.setText(R.string.update_not_checked_yet)
        }
        // The nearby card.
        findViewById<TextView>(R.id.updateNearbyFingerprint).apply {
            if (ReleaseSigner.isPinnedSingleSigner(build.signersSha256)) {
                text = getString(R.string.update_nearby_fingerprint, ReleaseSigner.PINNED.first())
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        if (nearbyRelease > 0 && release != null) {
            nearbyIntro.text = getString(R.string.update_nearby_intro, nearbyRelease, release)
            nearbyIntro.visibility = View.VISIBLE
            hideNearby.visibility = View.VISIBLE
        } else {
            nearbyIntro.visibility = View.GONE
            hideNearby.visibility = View.GONE
        }
        installPicked.visibility = if (preparedNearby != null && !busy) View.VISIBLE else View.GONE
    }

    private fun post(block: () -> Unit) {
        if (!isFinishing && !isDestroyed) runOnUiThread(block)
    }

    private fun setBusy(value: Boolean, message: Int? = null, canCancel: Boolean = false) {
        busy = value
        cancellable = value && canCancel
        progress.visibility = if (value) View.VISIBLE else View.GONE
        progress.isIndeterminate = true
        render()
        if (value && message != null) status.setText(message)
    }

    // ---- online ----

    private fun checkNow() {
        if (busy) return
        setBusy(true, R.string.update_checking)
        worker.execute {
            val result = UpdateEngine.check(applicationContext, manual = true)
            post {
                setBusy(false)
                when (result) {
                    is CheckResult.TooSoon -> status.text = getString(R.string.update_too_soon, (result.waitMs / 60_000 + 1).toInt())
                    CheckResult.Offline -> status.setText(R.string.update_offline)
                    CheckResult.NotOffered -> status.setText(R.string.update_not_offered)
                    is CheckResult.Failed -> status.setText(R.string.update_fail_server)
                    else -> Unit
                }
            }
        }
    }

    private fun startDownload() {
        if (busy) return
        cancelled.set(false)
        prepared = null
        setBusy(true, R.string.update_downloading_start, canCancel = true)
        worker.execute {
            val result = UpdateEngine.downloadAndVerify(applicationContext, cancelled::get) { done, total ->
                post {
                    progress.isIndeterminate = false
                    progress.max = 1000
                    progress.progress = if (total > 0) (done * 1000 / total).toInt() else 0
                    status.text = getString(
                        R.string.update_downloading,
                        Formatter.formatShortFileSize(this, done),
                        Formatter.formatShortFileSize(this, total),
                    )
                }
            }
            post {
                setBusy(false)
                handle(result) { prepared = it }
            }
        }
    }

    // ---- a file from a nearby phone ----

    private fun importPicked(uri: Uri) {
        if (busy) return
        preparedNearby = null
        nearbyResult.visibility = View.GONE
        cancelled.set(false)
        setBusy(true, R.string.update_verifying, canCancel = true)
        worker.execute {
            val result = UpdateEngine.importAndVerify(applicationContext, uri, cancelled::get)
            post {
                setBusy(false)
                handle(result, nearby = true) { preparedNearby = it }
            }
        }
    }

    // ---- results ----

    private fun handle(result: PrepareResult, nearby: Boolean = false, onReady: (PreparedUpdate) -> Unit) {
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK update_prepare: nearby=$nearby | result=${result::class.java.simpleName} ${(result as? PrepareResult.Failed)?.let { "kind=${it.kind} refusal=${it.refusal} log=${it.log}" }.orEmpty()}")
        val target = if (nearby) nearbyResult else status
        target.visibility = View.VISIBLE
        when (result) {
            is PrepareResult.Ready -> {
                onReady(result.update)
                val release = ReleaseNumber.fromVersionCode(result.update.versionCode)
                if (nearby) {
                    nearbyResult.text = getString(R.string.update_nearby_ok, result.update.versionName, release ?: 0) + "\n" +
                        getString(R.string.update_ready_detail_nearby)
                }
                render()
            }
            is PrepareResult.UpToDate -> status.text = getString(R.string.update_up_to_date, build.versionName)
            is PrepareResult.Failed -> target.setText(failureText(result))
        }
    }

    private fun failureText(failed: PrepareResult.Failed): Int = when (failed.kind) {
        PrepareResult.Kind.OFFLINE -> R.string.update_fail_offline
        PrepareResult.Kind.SERVER -> R.string.update_fail_server
        PrepareResult.Kind.RATE_LIMITED -> R.string.update_fail_rate
        PrepareResult.Kind.NO_FILE -> R.string.update_fail_no_file
        PrepareResult.Kind.NO_HASH -> R.string.update_fail_no_hash
        PrepareResult.Kind.HASH_MISMATCH -> R.string.update_fail_hash
        PrepareResult.Kind.STORAGE -> R.string.update_fail_storage
        PrepareResult.Kind.CANCELLED -> R.string.update_fail_cancelled
        PrepareResult.Kind.UNREADABLE -> R.string.update_fail_unreadable
        PrepareResult.Kind.CHECKS_OFF -> R.string.update_online_off
        PrepareResult.Kind.REFUSED -> when (failed.refusal) {
            UpdateRefusal.WRONG_PACKAGE -> R.string.update_refused_package
            UpdateRefusal.NOT_NEWER -> R.string.update_refused_not_newer
            UpdateRefusal.WRONG_ABI -> R.string.update_refused_abi
            UpdateRefusal.SIGNER_COUNT -> R.string.update_refused_signers
            UpdateRefusal.SIGNER_NOT_INSTALLED -> R.string.update_refused_signer_installed
            UpdateRefusal.SIGNER_NOT_PINNED -> R.string.update_refused_signer_pinned
            UpdateRefusal.ONLINE_UPDATE_NOT_OFFERED -> R.string.update_refused_online_off
            else -> R.string.update_refused_unreadable
        }
    }

    // ---- install ----

    private fun startInstall(update: PreparedUpdate) {
        if (busy) return
        if (!UpdateEngine.canInstall(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_install_blocked_title)
                .setMessage(R.string.update_install_blocked_message)
                .setPositiveButton(R.string.update_open_settings) { _, _ ->
                    runCatching {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            return
        }
        setBusy(true, R.string.update_installing)
        worker.execute {
            val error = try {
                UpdateEngine.install(applicationContext, update)
                null
            } catch (e: HashMismatchException) {
                update.file.delete()
                R.string.update_fail_hash
            } catch (e: Exception) {
                Log.w("BluetoothVideo", "Update install failed: ${e.javaClass.simpleName}: ${e.message}")
                R.string.update_fail_storage
            }
            post {
                setBusy(false)
                if (error != null) {
                    prepared = null
                    preparedNearby = null
                    render()
                    status.setText(error)
                } else {
                    status.setText(R.string.update_installing)
                }
            }
        }
    }

    private fun showLastInstallFailure() {
        val code = prefs.getInt(UpdateInstallReceiver.PREF_LAST_STATUS, 0)
        if (code == 0) return
        val message = prefs.getString(UpdateInstallReceiver.PREF_LAST_MESSAGE, "").orEmpty()
        prefs.edit().remove(UpdateInstallReceiver.PREF_LAST_STATUS).remove(UpdateInstallReceiver.PREF_LAST_MESSAGE).apply()
        // STATUS_FAILURE_ABORTED (the user said no) needs no explanation.
        if (code == android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED) return
        detail.text = getString(R.string.update_install_failed, message.ifEmpty { code.toString() })
        detail.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_NEARBY_RELEASE = "nearby_release"

        fun intent(context: Context, nearbyRelease: Int): Intent =
            Intent(context, UpdateActivity::class.java).putExtra(EXTRA_NEARBY_RELEASE, nearbyRelease)
    }
}
