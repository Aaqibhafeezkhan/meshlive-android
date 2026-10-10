package com.example.bluetoothtest

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.concurrent.Executors

/**
 * Verify a contact in person: this phone's QR code and full fingerprint, scanning the other
 * phone's code, and (when opened from a call) the safety code for that session with
 * "Mark verified". All security decisions go through [SecurityUiBackend].
 */
class VerifyContactActivity : AppCompatActivity() {
    private val security: SecurityUi get() = SecurityUiBackend.current
    private val worker = Executors.newSingleThreadExecutor()
    private var stopWatchingStore: () -> Unit = {}
    private var peerNodeId: String? = null
    private var peerName: String = ""

    private val scan = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::onScanned)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_verify_contact)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.verifyRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        peerNodeId = intent.getStringExtra(EXTRA_PEER_NODE_ID)?.lowercase()
        peerName = intent.getStringExtra(EXTRA_PEER_NAME).orEmpty()
        findViewById<View>(R.id.verifyBackButton).setOnClickListener { finish() }
        findViewById<View>(R.id.verifyScanButton).apply {
            isEnabled = security.verificationAvailable
            setOnClickListener {
                // The live call holds the camera; the scanner would compete with it (and with its
                // video). During a call the safety code is the way to verify.
                if (intent.getBooleanExtra(EXTRA_IN_CALL, false)) {
                    MaterialAlertDialogBuilder(this@VerifyContactActivity)
                        .setMessage(R.string.verify_scan_in_call)
                        .setPositiveButton(R.string.action_close, null)
                        .show()
                    return@setOnClickListener
                }
                scan.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt(getString(R.string.verify_scan_prompt))
                        .setBeepEnabled(false)
                        .setOrientationLocked(true),
                )
            }
        }
        findViewById<View>(R.id.verifyUnavailableText).visibility =
            if (security.verificationAvailable) View.GONE else View.VISIBLE
        findViewById<View>(R.id.verifyNoSessionText).visibility = if (peerNodeId == null) View.VISIBLE else View.GONE
        loadOwnCode()
        bindSession()
        findViewById<View>(R.id.verifyStoreReset).setOnClickListener { confirmStoreReset() }
        val main = android.os.Handler(mainLooper)
        // The listener may run under the store lock: it only posts a refresh.
        stopWatchingStore = security.watchStoreProblems(applicationContext) { main.post { refreshStoreBanner() } }
    }

    override fun onResume() {
        super.onResume()
        // No "recovered" signal exists, so the state is polled whenever the screen returns.
        refreshStoreBanner()
    }

    private fun refreshStoreBanner() {
        val context = applicationContext
        worker.execute {
            val problem = runCatching { security.storeProblem(context) }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                findViewById<View>(R.id.verifyStoreBanner).visibility = if (problem != null) View.VISIBLE else View.GONE
                findViewById<TextView>(R.id.verifyStoreText).setText(
                    if (problem == StoreProblem.CORRUPT) R.string.verify_store_corrupt else R.string.verify_store_temporary,
                )
                findViewById<View>(R.id.verifyStoreReset).visibility =
                    if (problem == StoreProblem.CORRUPT) View.VISIBLE else View.GONE
            }
        }
    }

    private fun confirmStoreReset() {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_warning)
            .setMessage(R.string.verify_store_reset_confirm)
            .setPositiveButton(R.string.verify_store_reset) { _, _ ->
                val context = applicationContext
                worker.execute {
                    runCatching { security.resetVerifiedContacts(context) }
                    runOnUiThread { if (!isDestroyed) refreshStoreBanner() }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroy() {
        stopWatchingStore()
        worker.shutdownNow()
        super.onDestroy()
    }

    /** Fingerprint and QR are computed off the main thread (keystore access, QR encoding). */
    private fun loadOwnCode() {
        val context = applicationContext
        val size = (220 * resources.displayMetrics.density).toInt()
        worker.execute {
            val fingerprint = security.ownFingerprint(context)
            val qr: Bitmap? = security.qrPayload(context)?.let { payload ->
                runCatching { BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, size, size) }.getOrNull()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                findViewById<TextView>(R.id.verifyFingerprintText).text =
                    fingerprint?.let { IdentityFormat.groupedHex(it) } ?: getString(R.string.identity_unavailable)
                findViewById<ImageView>(R.id.verifyQrImage).apply {
                    setImageBitmap(qr)
                    visibility = if (qr != null) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun bindSession() {
        val peer = peerNodeId ?: return
        val context = applicationContext
        findViewById<View>(R.id.verifySessionCard).visibility = View.VISIBLE
        findViewById<TextView>(R.id.verifySessionTitle).text =
            getString(R.string.verify_session_title, peerName.ifEmpty { IdentityFormat.shortHex(peer) })
        worker.execute {
            val code = security.safetyCode(context, peer)
            val state = security.verificationState(context, peer)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                findViewById<TextView>(R.id.verifySafetyCode).text =
                    code?.let { IdentityFormat.groupedDigits(it) } ?: IdentityFormat.groupedHex(peer)
                showState(state)
                findViewById<View>(R.id.verifyMarkButton).apply {
                    isEnabled = security.verificationAvailable && state != ContactVerification.VERIFIED
                    setOnClickListener { markVerified(peer, null, viaQr = false) }
                }
            }
        }
    }

    private fun showState(state: ContactVerification) {
        val text = findViewById<TextView>(R.id.verifyStateText)
        val (message, color, icon) = when (state) {
            ContactVerification.VERIFIED -> Triple(R.string.verify_state_verified, R.color.success, R.drawable.ic_verified_user)
            ContactVerification.UNVERIFIED -> Triple(R.string.verify_state_unverified, R.color.text_secondary, R.drawable.ic_lock)
            ContactVerification.MISMATCH -> Triple(R.string.verify_state_mismatch, R.color.danger, R.drawable.ic_warning)
        }
        text.setText(message)
        text.setTextColor(ContextCompat.getColor(this, color))
        text.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        text.compoundDrawableTintList = ContextCompat.getColorStateList(this, color)
        findViewById<View>(R.id.verifyMismatchBanner).visibility =
            if (state == ContactVerification.MISMATCH) View.VISIBLE else View.GONE
    }

    private fun markVerified(peer: String, fingerprint: String?, viaQr: Boolean, name: String = peerName) {
        val context = applicationContext
        worker.execute {
            val outcome = runCatching { security.markVerified(context, peer, fingerprint, name, viaQr) }
                .getOrDefault(MarkVerifiedOutcome.UNAVAILABLE)
            val state = security.verificationState(context, peer)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                showState(state)
                findViewById<View>(R.id.verifyMarkButton).isEnabled = outcome != MarkVerifiedOutcome.VERIFIED
                val message = when (outcome) {
                    MarkVerifiedOutcome.VERIFIED -> getString(
                        R.string.verify_scan_ok,
                        name.ifEmpty { IdentityFormat.shortHex(peer) },
                    )
                    MarkVerifiedOutcome.NO_AUTHENTICATED_SESSION -> getString(R.string.verify_result_no_session)
                    MarkVerifiedOutcome.FINGERPRINT_MISMATCH -> getString(R.string.verify_scan_mismatch)
                    MarkVerifiedOutcome.PREFIX_CONFLICT -> getString(R.string.verify_result_prefix_conflict)
                    MarkVerifiedOutcome.STORE_FULL -> getString(R.string.verify_result_store_full)
                    MarkVerifiedOutcome.STORE_UNREADABLE -> getString(R.string.verify_result_store_unreadable)
                    MarkVerifiedOutcome.UNAVAILABLE -> getString(R.string.verify_unavailable)
                }
                if (outcome == MarkVerifiedOutcome.FINGERPRINT_MISMATCH) {
                    showState(ContactVerification.MISMATCH)
                    MaterialAlertDialogBuilder(this)
                        .setIcon(R.drawable.ic_warning)
                        .setTitle(R.string.call_mismatch_title)
                        .setMessage(message)
                        .setPositiveButton(R.string.action_close, null)
                        .show()
                } else {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun onScanned(payload: String) {
        val scanned = security.parseQr(payload)
        if (scanned == null) {
            Toast.makeText(this, R.string.verify_scan_invalid, Toast.LENGTH_LONG).show()
            return
        }
        val peer = peerNodeId
        val name = peerName.ifEmpty { IdentityFormat.shortHex(scanned.nodeIdHex) }
        if (peer != null && !scanned.nodeIdHex.equals(peer, ignoreCase = true)) {
            showState(ContactVerification.MISMATCH)
            MaterialAlertDialogBuilder(this)
                .setIcon(R.drawable.ic_warning)
                .setTitle(R.string.call_mismatch_title)
                .setMessage(R.string.verify_scan_mismatch)
                .setPositiveButton(R.string.action_close, null)
                .show()
            return
        }
        // The security core accepts a QR only for a phone with an encrypted connection now, and
        // checks the scanned fingerprint against that connection.
        markVerified(scanned.nodeIdHex, scanned.fingerprintHex, viaQr = true, name = scanned.displayName.ifEmpty { name })
    }

    companion object {
        const val EXTRA_PEER_NODE_ID = "verify_peer_node_id"
        const val EXTRA_PEER_NAME = "verify_peer_name"
        const val EXTRA_IN_CALL = "verify_in_call"

        fun intent(
            context: Context,
            peerNodeIdHex: String? = null,
            peerName: String? = null,
            inCall: Boolean = false,
        ): Intent =
            Intent(context, VerifyContactActivity::class.java).apply {
                peerNodeIdHex?.let { putExtra(EXTRA_PEER_NODE_ID, it) }
                peerName?.let { putExtra(EXTRA_PEER_NAME, it) }
                putExtra(EXTRA_IN_CALL, inCall)
            }
    }
}
