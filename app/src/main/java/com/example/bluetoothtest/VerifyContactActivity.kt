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
        scanLaunched = false
        result.contents?.let(::onScanned)
    }

    // ----- BVMID2 (Naming v2, `bvmid2`): expiring one-use code, scan with confirmation, mutual outcome -----
    private var meet = false
    private var scanLaunched = false
    private var showingCode = false
    private val meetSession = MeetSession()
    private val meetHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var meetScanning = false

    private val meetEvents = object : QrDisplayEvents {
        override fun onScanCompleted(peer: IdentityFingerprint) {
            runOnUiThread { if (!isDestroyed) onPeerScannedMe(peer) }
        }

        override fun onReuseAlert() {
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                meetSession.onReuseAlert()
                MaterialAlertDialogBuilder(this@VerifyContactActivity)
                    .setIcon(R.drawable.ic_warning)
                    .setTitle(R.string.meet_reuse_title)
                    .setMessage(R.string.meet_reuse_message)
                    .setPositiveButton(R.string.action_close, null)
                    .show()
            }
        }

        override fun onCodeEnded(reason: String) {
            runOnUiThread { if (!isDestroyed && showingCode) meetHandler.post(meetTick) }
        }
    }

    /** Draws a fresh code whenever none is live (first time, after a scan, an ID change, expiry). */
    private val meetTick = object : Runnable {
        override fun run() {
            if (!meet || isDestroyed || !showingCode) return
            drawMeetCode()
            meetHandler.postDelayed(this, 1_000L)
        }
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
        meet = MeetBridge.available()
        // The picture of a live code is a secret: no screenshots or recents thumbnails while it can be shown.
        if (meet) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
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
                scanLaunched = true
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
        findViewById<View>(R.id.verifyNoSessionText).visibility = if (peerNodeId == null && !meet) View.VISIBLE else View.GONE
        loadOwnCode()
        bindSession()
        showSegment(showCode = false)
        findViewById<View>(R.id.verifyStoreReset).setOnClickListener { confirmStoreReset() }
        val main = android.os.Handler(mainLooper)
        // The listener may run under the store lock: it only posts a refresh.
        stopWatchingStore = security.watchStoreProblems(applicationContext) { main.post { refreshStoreBanner() } }
    }

    override fun onStart() {
        super.onStart()
        if (meet) {
            QrMeet.display.events = meetEvents
            MeetBridge.host?.begin()
            if (showingCode) meetHandler.post(meetTick)
        }
    }

    override fun onStop() {
        if (meet) {
            meetHandler.removeCallbacksAndMessages(null)
            QrMeet.display.events = QrDisplayEvents.NONE
            // Leaving the screen kills the code. The camera covering this screen also leaves it, but the endpoint stays
            // on air for the scan that follows; everything else restores the user's reachability.
            QrMeet.display.leave()
            if (!scanLaunched) MeetBridge.host?.end()
        }
        super.onStop()
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
        if (meet) {
            MeetBridge.host?.end()
            QrMeet.display.events = QrDisplayEvents.NONE
        }
        stopWatchingStore()
        worker.shutdownNow()
        super.onDestroy()
    }

    /** "Scan their code" or "Show my code": the only place this phone's code and ID are shown. */
    private fun showSegment(showCode: Boolean) {
        showingCode = showCode
        if (meet) {
            meetHandler.removeCallbacks(meetTick)
            if (showCode) meetHandler.post(meetTick)
        }
        findViewById<View>(R.id.verifyScanGroup).visibility = if (showCode) View.GONE else View.VISIBLE
        findViewById<View>(R.id.verifyCodeGroup).visibility = if (showCode) View.VISIBLE else View.GONE
        SegmentedControl.bind(
            findViewById(R.id.verifySegments),
            R.string.verify_seg_scan,
            R.string.verify_seg_show,
            selectedFirst = !showCode,
            onFirst = { showSegment(false) },
            onSecond = { showSegment(true) },
        )
    }

    /** Fingerprint and QR are computed off the main thread (keystore access, QR encoding). */
    private fun loadOwnCode() {
        val context = applicationContext
        val size = (220 * resources.displayMetrics.density).toInt()
        worker.execute {
            val fingerprint = security.ownFingerprint(context)
            // Messages (experimental): the safety number of the message card, shown with the code.
            val safety = if (StoreForwardFeature.enabled) {
                runCatching { StoreForwardRuntime.node(context).ownCard.safetyNumber() }.getOrNull()
            } else {
                null
            }
            // BVMID2 draws its own expiring code (drawMeetCode); the static BVMID1 picture is not shown then.
            val qr: Bitmap? = if (meet) null else security.qrPayload(context)?.let { payload ->
                runCatching { BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, size, size) }.getOrNull()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                findViewById<TextView>(R.id.verifyFingerprintText).text =
                    fingerprint?.let { IdentityFormat.groupedHex(it) } ?: getString(R.string.identity_unavailable)
                findViewById<TextView>(R.id.verifySafetyNumberText).apply {
                    visibility = if (safety != null) View.VISIBLE else View.GONE
                    if (safety != null) text = getString(R.string.identity_safety_number, safety)
                }
                if (!meet) {
                    findViewById<ImageView>(R.id.verifyQrImage).apply {
                        setImageBitmap(qr)
                        visibility = if (qr != null) View.VISIBLE else View.GONE
                    }
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
                bindPresenceSwitch(peer, state)
                findViewById<View>(R.id.verifyMarkButton).apply {
                    isEnabled = security.verificationAvailable && state != ContactVerification.VERIFIED
                    setOnClickListener { markVerified(peer, null, viaQr = false) }
                }
            }
        }
    }

    /**
     * Rotating IDs, phase 2: "Can see when I'm nearby" for this verified contact (default on). Shown only with
     * the experiment on, a persistent presence store and a verified contact; turning it off after the contact
     * received a key starts a new presence key.
     */
    private fun bindPresenceSwitch(peer: String, state: ContactVerification) {
        val row = findViewById<View>(R.id.verifyVisibleRow)
        val vault = if (RotatingDiscoveryFeature.enabled) RotatingDiscovery.presenceVault() else null
        val node = runCatching {
            NodeId.fromBytes(ByteArray(peer.length / 2) { peer.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        }.getOrNull()
        if (vault == null || !vault.persistent || node == null || state != ContactVerification.VERIFIED) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        val toggle = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.verifyVisibleSwitch)
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = vault.visibleToThem(node)
        toggle.setOnCheckedChangeListener { _, checked ->
            worker.execute {
                runCatching { vault.setVisible(node, checked) }
                RotatingDiscovery.requestRecognitionRebuild()
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

    /**
     * Records the verification, then asks what to call the person ([askContactName]). The store gets no name
     * here: a name already saved stays, and a new contact is named only by the user (the phone model in a QR
     * or the handle on the call screen are not names).
     */
    private fun markVerified(peer: String, fingerprint: String?, viaQr: Boolean) {
        val context = applicationContext
        worker.execute {
            val outcome = runCatching { security.markVerified(context, peer, fingerprint, "", viaQr) }
                .getOrDefault(MarkVerifiedOutcome.UNAVAILABLE)
            val state = security.verificationState(context, peer)
            val saved = if (outcome == MarkVerifiedOutcome.VERIFIED) {
                security.contacts(context).firstOrNull { it.nodeIdHex.equals(peer, ignoreCase = true) }
            } else {
                null
            }
            val shown = saved?.title ?: peerName.ifEmpty { IdentityFormat.shortHex(peer) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                showState(state)
                bindPresenceSwitch(peer, state)
                findViewById<View>(R.id.verifyMarkButton).isEnabled = outcome != MarkVerifiedOutcome.VERIFIED
                val message = when (outcome) {
                    MarkVerifiedOutcome.VERIFIED -> getString(R.string.verify_scan_ok, shown)
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
                    saved?.let(::askContactName)
                }
            }
        }
    }

    /**
     * "Save contact as": prefilled with the name you already gave them, else the name they shared in this
     * session (Names and titles on). Skip or an empty name keeps the handle. The name is only ever on this phone.
     */
    private fun askContactName(contact: ContactEntry) {
        val shared = SessionPeerLabels.forPrefix(contact.prefixHex)?.selfName
        ContactNameDialog.show(
            context = this,
            title = R.string.contact_save_title,
            message = R.string.contact_save_message,
            initial = ContactNames.suggestion(contact.name, shared),
            allowBlank = true,
            positive = R.string.action_save,
            negative = R.string.action_skip,
            onSave = { name ->
                if (name.isNotEmpty() && name != contact.name) renameContact(contact, name)
            },
        )
    }

    private fun renameContact(contact: ContactEntry, name: String) {
        val context = applicationContext
        worker.execute {
            val outcome = security.renameContact(context, contact.fingerprintHex, name)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val message = when (outcome) {
                    RenameOutcome.RENAMED -> getString(R.string.contact_saved, name)
                    RenameOutcome.INVALID_NAME -> getString(R.string.contact_name_invalid)
                    RenameOutcome.NAME_TAKEN -> getString(R.string.contact_name_taken)
                    RenameOutcome.NOT_FOUND -> getString(R.string.contact_gone)
                    RenameOutcome.STORE_UNREADABLE -> getString(R.string.verify_result_store_unreadable)
                    RenameOutcome.UNAVAILABLE -> getString(R.string.verify_unavailable)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ----- BVMID2 -----

    /** The displayed code: the live one, or a new one on the ID now on air. Text only while the endpoint is not ready. */
    private fun drawMeetCode() {
        val host = MeetBridge.host ?: return
        val status = findViewById<TextView>(R.id.verifyMeetStatus)
        val image = findViewById<ImageView>(R.id.verifyQrImage)
        val rdi = host.currentRdi()
        if (rdi == null) {
            image.visibility = View.GONE
            if (meetSession.reuseAlert.not()) {
                status.visibility = View.VISIBLE
                status.setText(R.string.meet_getting_ready)
            }
            return
        }
        QrMeet.display.onRdiChanged(rdi)
        val live = QrMeet.display.livePayload()
        if (live != null) return // already drawn
        val context = applicationContext
        val size = (220 * resources.displayMetrics.density).toInt()
        worker.execute {
            val fingerprint = security.ownFingerprint(context)?.let { runCatching { IdentityFingerprint.fromHex(it.lowercase()) }.getOrNull() }
            val payload = fingerprint?.let { QrMeet.display.show(it, rdi) }
            val qr = payload?.let {
                runCatching { BarcodeEncoder().encodeBitmap(Bvmid2.encode(it), BarcodeFormat.QR_CODE, size, size) }.getOrNull()
            }
            runOnUiThread {
                if (isDestroyed || !showingCode) return@runOnUiThread
                image.setImageBitmap(qr)
                image.visibility = if (qr != null) View.VISIBLE else View.GONE
                if (qr == null) {
                    status.visibility = View.VISIBLE
                    status.setText(R.string.meet_unavailable)
                } else if (!meetSession.reuseAlert) {
                    status.visibility = View.GONE
                }
            }
        }
    }

    private fun peerDisplay(peer: IdentityFingerprint): String {
        val contactName = runCatching { security.contacts(applicationContext) }.getOrNull()
            ?.firstOrNull { it.fingerprintHex.equals(peer.hex, ignoreCase = true) }?.name
        return contactName?.takeIf { it.isNotBlank() } ?: IdentityHandle.fromPrefixHex(peer.nodePrefixHex)
    }

    private fun showMeetOutcome(peer: IdentityFingerprint) {
        val status = findViewById<TextView>(R.id.verifyMeetStatus)
        val name = peerDisplay(peer)
        val text = when (meetSession.outcome(peer)) {
            MeetSession.Outcome.NONE -> null
            MeetSession.Outcome.YOU_VERIFIED -> getString(R.string.meet_you_scanned, name)
            MeetSession.Outcome.THEY_SCANNED_YOU -> getString(R.string.meet_they_scanned, name)
            MeetSession.Outcome.VERIFIED_EACH_OTHER -> getString(R.string.meet_each_other, name)
        } ?: return
        status.text = text
        status.visibility = View.VISIBLE
        if (meetSession.outcome(peer) == MeetSession.Outcome.VERIFIED_EACH_OTHER) {
            val context = applicationContext
            val now = System.currentTimeMillis()
            worker.execute { runCatching { ContactClaims.store(context).markScannedBothWays(peer, now) } }
        }
    }

    /** An inbound verification session on the code this phone showed completed with [peer]'s real key. */
    private fun onPeerScannedMe(peer: IdentityFingerprint) {
        meetSession.scannedByPeer(peer)
        showMeetOutcome(peer)
        meetHandler.post(meetTick) // the code was consumed: draw a new one
    }

    private fun startMeetScan(code: Bvmid2Payload) {
        val host = MeetBridge.host
        val status = findViewById<TextView>(R.id.verifyMeetStatus)
        status.visibility = View.VISIBLE
        if (host == null || meetScanning) {
            status.setText(R.string.meet_not_ready)
            return
        }
        meetScanning = true
        status.setText(R.string.meet_searching)
        // The camera covered this screen: the endpoint is brought back by onStart and may need a moment to be on air.
        val deadline = android.os.SystemClock.elapsedRealtime() + 8_000L
        lateinit var launch: Runnable
        launch = Runnable {
            if (isDestroyed) return@Runnable
            if (host.currentRdi() == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                meetHandler.postDelayed(launch, 500L)
                return@Runnable
            }
            host.scan(code, object : MeetBridge.ScanListener {
                override fun onSearching() = Unit

                override fun onReached(peer: IdentityFingerprint, node: NodeId) {
                    meetScanning = false
                    if (!isDestroyed) askWhoseScreen(peer)
                }

                override fun onFailed(failure: MeetBridge.Failure) {
                    meetScanning = false
                    if (isDestroyed) return
                    status.setText(
                        when (failure) {
                            MeetBridge.Failure.NOT_READY -> R.string.meet_not_ready
                            MeetBridge.Failure.NOT_FOUND -> R.string.meet_not_found
                            MeetBridge.Failure.COULD_NOT_CONNECT -> R.string.meet_could_not_connect
                        },
                    )
                }
            })
        }
        meetHandler.post(launch)
    }

    /** A scan proves you reached the key in the code, not who is holding the phone: the user confirms whose screen it is. */
    private fun askWhoseScreen(peer: IdentityFingerprint) {
        val name = peerDisplay(peer)
        meetSession.scanReached(peer, name)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.meet_confirm_title, name))
            .setMessage(getString(R.string.meet_confirm_message, IdentityHandle.fromPrefixHex(peer.nodePrefixHex)))
            .setPositiveButton(R.string.meet_confirm_yes) { _, _ ->
                if (meetSession.confirmScreen(peer, yes = true)) markScannedVerified(peer)
            }
            .setNegativeButton(R.string.meet_confirm_no) { _, _ ->
                meetSession.confirmScreen(peer, yes = false)
                findViewById<TextView>(R.id.verifyMeetStatus).setText(R.string.meet_cancelled_scan)
            }
            .setOnCancelListener {
                meetSession.confirmScreen(peer, yes = false)
                findViewById<TextView>(R.id.verifyMeetStatus).setText(R.string.meet_cancelled_scan)
            }
            .show()
    }

    private fun markScannedVerified(peer: IdentityFingerprint) {
        val context = applicationContext
        worker.execute {
            val outcome = runCatching {
                security.markVerified(context, peer.nodeId.toString(), peer.hex, "", viaQr = true, bvmid2Scan = true)
            }.getOrDefault(MarkVerifiedOutcome.UNAVAILABLE)
            val saved = if (outcome == MarkVerifiedOutcome.VERIFIED) {
                security.contacts(context).firstOrNull { it.fingerprintHex.equals(peer.hex, ignoreCase = true) }
            } else {
                null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (outcome == MarkVerifiedOutcome.VERIFIED) {
                    showMeetOutcome(peer)
                    saved?.let(::askContactName)
                } else {
                    val message = when (outcome) {
                        MarkVerifiedOutcome.FINGERPRINT_MISMATCH -> R.string.verify_scan_mismatch
                        MarkVerifiedOutcome.PREFIX_CONFLICT -> R.string.verify_result_prefix_conflict
                        MarkVerifiedOutcome.STORE_FULL -> R.string.verify_result_store_full
                        MarkVerifiedOutcome.STORE_UNREADABLE -> R.string.verify_result_store_unreadable
                        MarkVerifiedOutcome.NO_AUTHENTICATED_SESSION -> R.string.verify_result_no_session
                        else -> R.string.verify_unavailable
                    }
                    findViewById<TextView>(R.id.verifyMeetStatus).apply {
                        visibility = View.VISIBLE
                        setText(message)
                    }
                }
            }
        }
    }

    private fun onScanned(payload: String) {
        if (meet && Bvmid2.looksLikeBvmid2(payload)) {
            val code = Bvmid2.tryParse(payload)
            if (code == null) {
                Toast.makeText(this, R.string.verify_scan_invalid, Toast.LENGTH_LONG).show()
            } else {
                startMeetScan(code)
            }
            return
        }
        val scanned = security.parseQr(payload)
        if (scanned == null) {
            Toast.makeText(this, R.string.verify_scan_invalid, Toast.LENGTH_LONG).show()
            return
        }
        val peer = peerNodeId
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
        markVerified(scanned.nodeIdHex, scanned.fingerprintHex, viaQr = true)
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
