package com.example.bluetoothtest

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Messages (store-and-forward, direct neighbours only; Settings > Features "Messages"): your contact
 * card, cards received over a link, compose (text, voice note, photo) and the inbox/sent lists, in
 * the shared monochrome theme (activity_messages.xml). Messages move when two phones exchange them
 * from the Call screen (Exchange messages), or by themselves with "Sync messages automatically".
 * Unread inbox rows carry a dot until the screen is left; the Home tile and a quiet notification
 * carry the count ([MessageNotifications]).
 */
class MessagesActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var node: StoreForwardNode? = null
    private lateinit var contactSpinner: Spinner
    private lateinit var messageInput: EditText
    private lateinit var attachmentText: TextView
    private lateinit var recordButton: MaterialButton
    private lateinit var lifetimeSpinner: Spinner
    private lateinit var pendingSection: LinearLayout
    private lateinit var inboxSection: LinearLayout
    private lateinit var inboxHeader: TextView
    private lateinit var sentSection: LinearLayout
    private lateinit var storageText: TextView
    private lateinit var statusText: TextView
    private var contacts: List<StoreForwardContact> = emptyList()
    @Volatile private var failedIds: Set<String> = emptySet()
    /** Automatic sync only: own message ID -> (outbox status, delivery attempts). */
    @Volatile private var outboxStatus: Map<String, Pair<OutboxStatus, Int>> = emptyMap()
    /** When the user last left this screen: rows received after it show as new until the next exit. */
    @Volatile private var seenMs = 0L
    private var seenStore: InboxSeenStore? = null
    private var attachment: Pair<MessageKind, ByteArray>? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingStartedMs = 0L
    private var player: MediaPlayer? = null
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) worker.execute { preparePhoto(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!StoreForwardFeature.enabled) {
            finish()
            return
        }
        // No screenshots, screen recording or recent-apps thumbnail of message content (N6).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // Plaintext a previous process may have left behind (finding 8).
        StoreForwardPlaintextFiles.cleanLeftovers(cacheDir)
        setContentView(R.layout.activity_messages)
        bindViews()
        worker.execute {
            val opened = runCatching { StoreForwardRuntime.node(applicationContext) }
            val store = runCatching { InboxSeenStore.forRoot(StoreForwardRuntime.root(applicationContext)) }.getOrNull()
            runOnUiThread {
                opened.onSuccess {
                    node = it
                    seenStore = store
                    seenMs = store?.read() ?: 0L
                    statusText.visibility = View.GONE
                    refresh()
                    showKeyResetNoticeIfAny()
                }.onFailure {
                    Log.e("BluetoothVideo", "Store-forward could not open", it)
                    statusText.text = getString(R.string.messages_could_not_start, it.message ?: it.javaClass.simpleName)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        MessagesScreenState.visible = true
        MessageNotifications.cancel(this)
        handler.post(refresher)
    }

    override fun onPause() {
        MessagesScreenState.visible = false
        handler.removeCallbacks(refresher)
        stopRecording(keep = false)
        releasePlayer()
        StoreForwardPlaintextFiles.cleanLeftovers(cacheDir)
        // Leaving the screen marks what was shown as read.
        seenStore?.write(System.currentTimeMillis())
        super.onPause()
    }

    override fun onDestroy() {
        releasePlayer()
        StoreForwardPlaintextFiles.cleanLeftovers(cacheDir)
        worker.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        val root = findViewById<View>(R.id.messagesRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<View>(R.id.messagesBackButton).setOnClickListener { finish() }
        statusText = findViewById(R.id.messagesStatus)
        bindAutoSyncSwitch()
        findViewById<View>(R.id.messagesCopyCard).setOnClickListener { copyMyCard() }
        findViewById<View>(R.id.messagesAddContact).setOnClickListener { addContactDialog() }
        pendingSection = findViewById(R.id.messagesPending)
        contactSpinner = findViewById(R.id.messagesContactSpinner)
        messageInput = findViewById(R.id.messagesInput)
        attachmentText = findViewById(R.id.messagesAttachment)
        recordButton = findViewById(R.id.messagesRecord)
        recordButton.setOnClickListener { toggleRecording() }
        findViewById<View>(R.id.messagesAttachPhoto).setOnClickListener { pickPhoto.launch("image/*") }
        lifetimeSpinner = findViewById<Spinner>(R.id.messagesLifetimeSpinner).apply {
            adapter = ArrayAdapter(this@MessagesActivity, android.R.layout.simple_spinner_dropdown_item, LIFETIMES.map { it.first })
            setSelection(1)
        }
        findViewById<View>(R.id.messagesSend).setOnClickListener { send() }
        inboxHeader = findViewById(R.id.messagesInboxHeader)
        inboxSection = findViewById(R.id.messagesInbox)
        sentSection = findViewById(R.id.messagesSent)
        storageText = findViewById(R.id.messagesStorage)
    }

    /**
     * "Sync messages automatically" (the same switch as Settings > Features). A benchmark launch reads
     * only its extra, so the switch there shows that value and cannot be changed.
     */
    private fun bindAutoSyncSwitch() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.messagesAutoSyncSwitch)
        val benchmark = BuildConfig.DEBUG && DebugTunables.benchmarkLaunch
        switch.isChecked = StoreForwardAutoSyncFeature.enabled
        switch.isEnabled = !benchmark
        switch.setOnCheckedChangeListener { _, enabled ->
            // Settings > Features and MainActivity re-read the stored choice; this keeps the running value in step.
            getSharedPreferences(CallFeatureDefaults.APP_SETTINGS_PREFERENCES, MODE_PRIVATE).edit()
                .putBoolean(StoreForwardAutoSyncFeature.PREF, enabled).apply()
            StoreForwardAutoSyncFeature.enabled = enabled && StoreForwardFeature.enabled
        }
    }

    private fun refresh() {
        val node = node ?: return
        worker.execute {
            val allContacts = node.contacts.all()
            runCatching { node.sweepArchive() }.onFailure { Log.w("BluetoothVideo", "Store-forward archive sweep failed", it) }
            val messages = (node.archive.page(0, PAGE_SIZE, MessageDirection.IN) +
                node.archive.page(0, PAGE_SIZE, MessageDirection.OUT)).filterNot { LocalReporting.hidden(this, it.peerNode) }
            val states = node.store.all().associateBy { it.idHex }
            failedIds = node.store.failed().keys
            outboxStatus = if (!StoreForwardAutoSyncFeature.enabled) emptyMap() else runCatching {
                val ledger = StoreForwardRuntime.outboxLedger(applicationContext)
                val policy = AutoSyncPolicy()
                val own = states.values.filter { it.role == BundleRole.OWN }
                ledger.retain(own.map { it.idHex }.toSet())
                own.mapNotNull { bundle ->
                    StoreForwardAutoSyncPlanner.status(ledger, bundle, policy)?.let { bundle.idHex to (it to ledger.attempts(bundle.idHex)) }
                }.toMap()
            }.getOrElse { emptyMap() }
            val usage = BundleRole.entries.associateWith { node.store.usage(it) }
            runOnUiThread { render(allContacts, messages, states, usage) }
        }
    }

    private fun render(
        allContacts: List<StoreForwardContact>,
        messages: List<ArchivedSummary>,
        states: Map<String, StoredBundle>,
        usage: Map<BundleRole, Long>,
    ) {
        if (isDestroyed) return
        val accepted = allContacts.filter { it.state == ContactState.ACCEPTED }
        if (accepted.map { it.nodeId } != contacts.map { it.nodeId }) {
            val selected = contacts.getOrNull(contactSpinner.selectedItemPosition)?.nodeId
            contacts = accepted
            contactSpinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item,
                accepted.map { it.label }.ifEmpty { listOf(getString(R.string.messages_no_contacts)) },
            )
            accepted.indexOfFirst { it.nodeId == selected }.takeIf { it >= 0 }?.let(contactSpinner::setSelection)
        }
        val names = allContacts.associate { it.nodeId.toString() to it.label }
        pendingSection.removeAllViews()
        allContacts.filter { it.state == ContactState.PENDING }.forEach { contact ->
            val row = layoutInflater.inflate(R.layout.item_message_card_row, pendingSection, false)
            row.findViewById<TextView>(R.id.cardDescription).text =
                getString(R.string.messages_pending_row, contact.nodeId.shortLabel(), contact.card.safetyNumber())
            row.findViewById<View>(R.id.cardAccept).setOnClickListener { acceptDialog(contact) }
            row.findViewById<View>(R.id.cardIgnore).setOnClickListener {
                worker.execute { node?.removeContact(contact.nodeId); refresh() }
            }
            pendingSection.addView(row)
        }
        if (pendingSection.childCount == 0) pendingSection.addView(quietLine(R.string.messages_none))
        inboxSection.removeAllViews()
        sentSection.removeAllViews()
        val unread = UnreadMessages.count(messages, seenMs)
        inboxHeader.text = if (unread > 0) getString(R.string.messages_section_inbox_unread, unread) else getString(R.string.messages_section_inbox)
        messages.sortedByDescending { it.receivedMs }.forEach { entry ->
            val peer = names[entry.peerNode] ?: entry.peerNode.take(12)
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.createdMs))
            val body = when (entry.kind) {
                MessageKind.TEXT -> entry.preview
                MessageKind.VOICE -> getString(R.string.messages_kind_voice, entry.bodyBytes / 1024)
                MessageKind.PHOTO -> getString(R.string.messages_kind_photo, entry.bodyBytes / 1024)
                else -> getString(R.string.messages_kind_other, entry.kind.label, entry.bodyBytes)
            }
            val isNew = UnreadMessages.isUnread(entry, seenMs)
            val header = if (entry.direction == MessageDirection.IN) {
                getString(R.string.messages_row_from, peer, time)
            } else {
                val status = OutgoingMessageStatus.of(
                    states[entry.bundleIdHex]?.state,
                    outboxStatus[entry.bundleIdHex],
                    entry.bundleIdHex in failedIds,
                    AutoSyncPolicy().maxAttempts,
                )
                getString(R.string.messages_row_to, peer, time, getString(status.stringRes, *status.args.toTypedArray()))
            }
            val row = layoutInflater.inflate(R.layout.item_message_row, null)
            row.findViewById<TextView>(R.id.messageHeader).text = header
            row.findViewById<TextView>(R.id.messageBody).text = body
            row.findViewById<View>(R.id.messageUnreadDot).visibility = if (isNew) View.VISIBLE else View.INVISIBLE
            row.contentDescription = (if (isNew) getString(R.string.messages_new) + ". " else "") + header + ". " + body
            row.setOnClickListener { open(entry.bundleIdHex) }
            row.setOnLongClickListener {
                MaterialAlertDialogBuilder(this@MessagesActivity)
                    .setMessage(R.string.messages_delete_prompt)
                    .setPositiveButton(R.string.messages_delete) { _, _ -> worker.execute { node?.deleteMessage(entry.bundleIdHex); refresh() } }
                    .setNegativeButton(R.string.messages_cancel, null)
                    .show()
                true
            }
            (if (entry.direction == MessageDirection.IN) inboxSection else sentSection).addView(
                row,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        if (inboxSection.childCount == 0) inboxSection.addView(quietLine(R.string.messages_inbox_empty))
        if (sentSection.childCount == 0) sentSection.addView(quietLine(R.string.messages_sent_empty))
        storageText.text = getString(
            R.string.messages_storage,
            kb(usage[BundleRole.OWN]), kb(usage[BundleRole.INBOX]), kb(usage[BundleRole.FOREIGN]),
        )
    }

    private fun quietLine(textRes: Int) = TextView(this).apply {
        setText(textRes)
        setTextAppearance(R.style.TextAppearance_App_Body)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun showKeyResetNoticeIfAny() {
        val root = StoreForwardRuntime.root(applicationContext)
        MailboxKeyResetNotice.pendingSinceMs(root) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.messages_key_reset_title)
            .setMessage(R.string.messages_key_reset_body)
            .setPositiveButton(R.string.messages_ok) { _, _ -> MailboxKeyResetNotice.dismiss(root) }
            .setCancelable(false)
            .show()
    }

    private fun copyMyCard() {
        val node = node ?: return
        worker.execute {
            val card = runCatching { node.ownCard.toText() }.getOrNull()
            runOnUiThread {
                if (card == null) return@runOnUiThread
                // Marked sensitive so the system clipboard preview does not show it (N6; honoured on API 33+).
                val clip = ClipData.newPlainText("Contact card", card).apply {
                    description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
                }
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.messages_card_copied_title)
                    .setMessage(getString(R.string.messages_card_copied_body, card, node.nodeId.shortLabel(), node.ownCard.safetyNumber()))
                    .setPositiveButton(R.string.messages_ok, null)
                    .show()
            }
        }
    }

    private fun addContactDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val cardInput = EditText(this).apply { hint = getString(R.string.messages_card_hint) }
        val nameInput = EditText(this).apply { hint = getString(R.string.messages_name_hint) }
        layout.addView(cardInput)
        layout.addView(nameInput)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.messages_add_title)
            .setView(layout)
            .setPositiveButton(R.string.messages_add_contact) { _, _ ->
                val node = node ?: return@setPositiveButton
                val cardText = cardInput.text.toString()
                val name = nameInput.text.toString()
                worker.execute {
                    val result = runCatching {
                        val card = ContactCard.fromText(cardText)
                        require(card.nodeId != node.nodeId) { "That is your own card" }
                        node.contacts.addAccepted(card, name)
                        node.openPendingFrom(card.nodeId)
                    }
                    runOnUiThread {
                        toast(result.fold({ getString(R.string.messages_add_done) }, { getString(R.string.messages_add_failed, it.message) }))
                        refresh()
                    }
                }
            }
            .setNegativeButton(R.string.messages_cancel, null)
            .show()
    }

    private fun acceptDialog(contact: StoreForwardContact) {
        val nameInput = EditText(this).apply { hint = getString(R.string.messages_name_hint) }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.messages_accept_title, contact.nodeId.shortLabel()))
            .setMessage(getString(R.string.messages_accept_body, contact.card.safetyNumber()))
            .setView(nameInput)
            .setPositiveButton(R.string.messages_accept) { _, _ ->
                val name = nameInput.text.toString()
                worker.execute {
                    val result = runCatching {
                        node?.contacts?.accept(contact.nodeId, name)
                        node?.openPendingFrom(contact.nodeId).orEmpty()
                    }
                    runOnUiThread {
                        result.onSuccess { opened ->
                            if (opened.isNotEmpty()) toast(getString(R.string.messages_accept_opened, opened.size))
                        }.onFailure { toast(getString(R.string.messages_accept_failed, it.message)) }
                        refresh()
                    }
                }
            }
            .setNegativeButton(R.string.messages_cancel, null)
            .show()
    }

    private fun send() {
        val node = node ?: return
        val contact = contacts.getOrNull(contactSpinner.selectedItemPosition)
        if (contact == null) {
            toast(getString(R.string.messages_add_first))
            return
        }
        val textBody = messageInput.text.toString().trim()
        val media = attachment
        if (textBody.isEmpty() && media == null) {
            toast(getString(R.string.messages_write_something))
            return
        }
        val ttl = LIFETIMES[lifetimeSpinner.selectedItemPosition].second
        worker.execute {
            val result = runCatching {
                val conversation = sfSha256(
                    listOf(node.nodeId.toString(), contact.nodeId.toString()).sorted().joinToString("").toByteArray(),
                ).copyOf(16)
                if (media != null) {
                    node.send(contact.nodeId, BundleMessage(media.first, System.currentTimeMillis(), conversation, null,
                        if (media.first == MessageKind.VOICE) "audio/mp4" else "image/jpeg", media.second), ttl)
                }
                if (textBody.isNotEmpty()) {
                    node.send(contact.nodeId, BundleMessage(MessageKind.TEXT, System.currentTimeMillis(), conversation, null,
                        "text/plain; charset=utf-8", textBody.toByteArray(Charsets.UTF_8)), ttl)
                }
            }
            runOnUiThread {
                result.onSuccess {
                    messageInput.setText("")
                    attachment = null
                    showAttachment(null)
                    toast(getString(R.string.messages_queued))
                }.onFailure { toast(getString(R.string.messages_not_sent, it.message)) }
                refresh()
            }
        }
    }

    private fun showAttachment(text: String?) {
        attachmentText.text = text.orEmpty()
        attachmentText.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun preparePhoto(uri: Uri) {
        val result = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_MAX_EDGE) sample *= 2
            val decoded = contentResolver.openInputStream(uri).use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("Not an image")
            val scale = minOf(1f, PHOTO_MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
            val bitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
            } else decoded
            var quality = 80
            var bytes: ByteArray
            do {
                bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                quality -= 15
            } while (bytes.size > PHOTO_TARGET_BYTES && quality >= 35)
            bytes
        }
        runOnUiThread {
            result.onSuccess { bytes ->
                attachment = MessageKind.PHOTO to bytes
                showAttachment(getString(R.string.messages_photo_attached, bytes.size / 1024))
            }.onFailure { toast(getString(R.string.messages_photo_failed, it.message)) }
        }
    }

    private fun toggleRecording() {
        if (recorder != null) {
            stopRecording(keep = true)
            return
        }
        // The microphone belongs to the call (or live stream) that holds it: recording now could leave
        // the other person hearing silence.
        if (VoiceNoteGate.blockedNow()) {
            toast(getString(R.string.messages_recording_blocked))
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
            return
        }
        val file = File(cacheDir, StoreForwardPlaintextFiles.RECORDING).also { it.delete() }
        val started = runCatching {
            @Suppress("DEPRECATION")
            MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(24_000)
                setMaxDuration(VOICE_MAX_MS)
                setOutputFile(file.absolutePath)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) runOnUiThread { stopRecording(keep = true) }
                }
                prepare()
                start()
            }
        }
        started.onSuccess {
            recorder = it
            recordingFile = file
            recordingStartedMs = System.currentTimeMillis()
            recordButton.setText(R.string.messages_stop_recording)
            showAttachment(getString(R.string.messages_recording))
        }.onFailure { toast(getString(R.string.messages_recording_failed, it.message)) }
    }

    private fun stopRecording(keep: Boolean) {
        val active = recorder ?: return
        recorder = null
        recordButton.setText(R.string.messages_record)
        val ok = runCatching { active.stop() }.isSuccess
        active.release()
        val file = recordingFile
        recordingFile = null
        if (keep && ok && file != null && file.exists()) {
            val bytes = file.readBytes()
            file.delete()
            attachment = MessageKind.VOICE to bytes
            val seconds = (System.currentTimeMillis() - recordingStartedMs) / 1000
            showAttachment(getString(R.string.messages_voice_attached, seconds.toInt(), bytes.size / 1024))
        } else {
            file?.delete()
            showAttachment(null)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleRecording()
    }

    /** Loads one message body on demand (the list holds only small encrypted listing records). */
    private fun open(bundleIdHex: String) {
        val node = node ?: return
        worker.execute {
            val entry = node.archive.load(bundleIdHex) ?: return@execute
            runOnUiThread { show(entry) }
        }
    }

    private fun show(entry: ArchivedMessage) {
        when (entry.message.kind) {
            MessageKind.VOICE -> {
                releasePlayer()
                // Played from memory: decrypted audio is never written to a file (finding 8).
                player = runCatching {
                    MediaPlayer().apply {
                        setDataSource(MemoryMediaDataSource(entry.message.body))
                        setOnCompletionListener { releasePlayer() }
                        setOnErrorListener { _, _, _ -> releasePlayer(); true }
                        prepare()
                        start()
                    }
                }.onFailure { toast(getString(R.string.messages_cannot_play, it.message)) }.getOrNull()
            }
            MessageKind.PHOTO -> {
                val bitmap = BitmapFactory.decodeByteArray(entry.message.body, 0, entry.message.body.size) ?: return
                MaterialAlertDialogBuilder(this)
                    .setView(ImageView(this).apply {
                        setImageBitmap(bitmap)
                        adjustViewBounds = true
                    })
                    .setPositiveButton(R.string.messages_close, null)
                    .show()
            }
            MessageKind.TEXT -> MaterialAlertDialogBuilder(this)
                .setMessage(String(entry.message.body, Charsets.UTF_8))
                .setPositiveButton(R.string.messages_close, null)
                .show()
            else -> Unit
        }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    private class MemoryMediaDataSource(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
            StoreForwardPlaintextFiles.readAt(bytes, position, buffer, offset, size)

        override fun getSize(): Long = bytes.size.toLong()

        override fun close() = Unit
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun kb(bytes: Long?) = ((bytes ?: 0) / 1024).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        /** ClipDescription.EXTRA_IS_SENSITIVE (API 33); the same key is ignored by older systems. */
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
        const val REQUEST_AUDIO = 41
        const val REFRESH_MS = 3_000L
        const val PAGE_SIZE = 50
        const val PHOTO_MAX_EDGE = 1280
        const val PHOTO_TARGET_BYTES = 300 * 1024
        /** QA9: voice notes up to 2 minutes. */
        const val VOICE_MAX_MS = 120_000
        /** QA4: 3 days default, 14 days maximum. */
        val LIFETIMES = listOf("1 day" to 24, "3 days" to 72, "7 days" to 168, "14 days" to 336)
    }
}
