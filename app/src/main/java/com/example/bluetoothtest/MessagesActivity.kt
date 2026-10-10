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
import android.os.PersistableBundle
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Experimental store-and-forward Messages screen (StoreForwardFeature, default off): your contact
 * card, cards received over a link, compose (text, voice note, photo) and the inbox/sent lists.
 * Messages move when two phones exchange them from the Call screen (Exchange messages).
 */
class MessagesActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var node: StoreForwardNode? = null
    private lateinit var content: LinearLayout
    private lateinit var contactSpinner: Spinner
    private lateinit var messageInput: EditText
    private lateinit var attachmentText: TextView
    private lateinit var recordButton: Button
    private lateinit var lifetimeSpinner: Spinner
    private lateinit var pendingSection: LinearLayout
    private lateinit var inboxSection: LinearLayout
    private lateinit var sentSection: LinearLayout
    private lateinit var storageText: TextView
    private var contacts: List<StoreForwardContact> = emptyList()
    @Volatile private var failedIds: Set<String> = emptySet()
    /** Experimental automatic sync only: own message ID -> (outbox status, delivery attempts). */
    @Volatile private var outboxStatus: Map<String, Pair<OutboxStatus, Int>> = emptyMap()
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
        setContentView(buildLayout())
        worker.execute {
            val opened = runCatching { StoreForwardRuntime.node(applicationContext) }
            runOnUiThread {
                opened.onSuccess {
                    node = it
                    refresh()
                    showKeyResetNoticeIfAny()
                }.onFailure {
                    Log.e("BluetoothVideo", "Store-forward could not open", it)
                    storageText.text = "Messages could not start: ${it.message ?: it.javaClass.simpleName}"
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        stopRecording(keep = false)
        releasePlayer()
        StoreForwardPlaintextFiles.cleanLeftovers(cacheDir)
        super.onPause()
    }

    override fun onDestroy() {
        releasePlayer()
        StoreForwardPlaintextFiles.cleanLeftovers(cacheDir)
        worker.shutdown()
        super.onDestroy()
    }

    private fun buildLayout(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(color(R.color.app_background)) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        scroll.addView(content)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        content.addView(text("Messages", 28f, bold = true))
        content.addView(text(
            "Experimental. End-to-end encrypted to one contact; only that contact's phone can open them. " +
                "To move messages, open Call on both phones, tap the other person and choose Exchange messages. " +
                "Messages expire after their lifetime.",
            13f, secondary = true,
        ))

        content.addView(heading("Your contact card"))
        content.addView(row(
            button("Copy my card") { copyMyCard() },
            button("Add contact") { addContactDialog() },
        ))
        content.addView(heading("Cards received nearby"))
        pendingSection = vertical().also(content::addView)

        content.addView(heading("Write"))
        contactSpinner = Spinner(this).also(content::addView)
        messageInput = EditText(this).apply {
            hint = "Message"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            setTextColor(color(R.color.text_primary))
        }
        content.addView(messageInput)
        recordButton = button("Record voice note") { toggleRecording() }
        content.addView(row(button("Attach photo") { pickPhoto.launch("image/*") }, recordButton))
        attachmentText = text("", 13f, secondary = true).also(content::addView)
        lifetimeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MessagesActivity, android.R.layout.simple_spinner_dropdown_item, LIFETIMES.map { it.first })
            setSelection(1)
        }
        content.addView(row(text("Lifetime", 14f), lifetimeSpinner))
        content.addView(button("Send") { send() })

        content.addView(heading("Inbox"))
        inboxSection = vertical().also(content::addView)
        content.addView(heading("Sent"))
        sentSection = vertical().also(content::addView)
        storageText = text("", 12f, secondary = true).also(content::addView)
        return scroll
    }

    private fun refresh() {
        val node = node ?: return
        worker.execute {
            val allContacts = node.contacts.all()
            runCatching { node.sweepArchive() }.onFailure { Log.w("BluetoothVideo", "Store-forward archive sweep failed", it) }
            val messages = node.archive.page(0, PAGE_SIZE, MessageDirection.IN) +
                node.archive.page(0, PAGE_SIZE, MessageDirection.OUT)
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
        val accepted = allContacts.filter { it.state == ContactState.ACCEPTED }
        if (accepted.map { it.nodeId } != contacts.map { it.nodeId }) {
            val selected = contacts.getOrNull(contactSpinner.selectedItemPosition)?.nodeId
            contacts = accepted
            contactSpinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item,
                accepted.map { it.label }.ifEmpty { listOf("No contacts yet") },
            )
            accepted.indexOfFirst { it.nodeId == selected }.takeIf { it >= 0 }?.let(contactSpinner::setSelection)
        }
        val names = allContacts.associate { it.nodeId.toString() to it.label }
        pendingSection.removeAllViews()
        allContacts.filter { it.state == ContactState.PENDING }.forEach { contact ->
            pendingSection.addView(row(
                text("${contact.nodeId.shortLabel()}\nSafety number ${contact.card.safetyNumber()}", 13f),
                button("Accept") { acceptDialog(contact) },
                button("Ignore") { worker.execute { node?.removeContact(contact.nodeId); refresh() } },
            ))
        }
        if (pendingSection.childCount == 0) pendingSection.addView(text("None", 13f, secondary = true))
        inboxSection.removeAllViews()
        sentSection.removeAllViews()
        messages.sortedByDescending { it.receivedMs }.forEach { entry ->
            val peer = names[entry.peerNode] ?: entry.peerNode.take(12)
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.createdMs))
            val label = when (entry.kind) {
                MessageKind.TEXT -> entry.preview
                MessageKind.VOICE -> "Voice note (${entry.bodyBytes / 1024} KB) - tap to play"
                MessageKind.PHOTO -> "Photo (${entry.bodyBytes / 1024} KB) - tap to view"
                else -> "${entry.kind.label} (${entry.bodyBytes} bytes)"
            }
            val status = if (entry.direction == MessageDirection.OUT) {
                val auto = outboxStatus[entry.bundleIdHex]
                when (states[entry.bundleIdHex]?.state) {
                    BundleState.QUEUED -> when (auto?.first) {
                        OutboxStatus.FAILED -> "not delivered after ${auto?.second} attempts - open Call and Exchange to retry"
                        OutboxStatus.CARRIED -> "with a courier"
                        else -> if ((auto?.second ?: 0) > 0) "waiting for a link (attempt ${auto?.second} of ${AutoSyncPolicy().maxAttempts})" else "waiting for a link"
                    }
                    BundleState.HANDED -> "on their phone"
                    BundleState.DELIVERED -> "delivered"
                    BundleState.FAILED -> "failed (stored copy damaged) - send again"
                    null -> if (entry.bundleIdHex in failedIds) "failed (stored copy damaged) - send again" else "expired or removed"
                    else -> states[entry.bundleIdHex]?.state?.name?.lowercase()
                }
            } else null
            val header = if (entry.direction == MessageDirection.IN) "From $peer - $time" else "To $peer - $time - $status"
            val view = vertical().apply {
                setPadding(0, dp(8), 0, dp(8))
                addView(text(header, 12f, secondary = true))
                addView(text(label, 15f))
                setOnClickListener { open(entry.bundleIdHex) }
                setOnLongClickListener {
                    AlertDialog.Builder(this@MessagesActivity)
                        .setMessage("Delete this message from this phone?")
                        .setPositiveButton("Delete") { _, _ -> worker.execute { node?.deleteMessage(entry.bundleIdHex); refresh() } }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
            }
            (if (entry.direction == MessageDirection.IN) inboxSection else sentSection).addView(view)
        }
        if (inboxSection.childCount == 0) inboxSection.addView(text("No messages yet", 13f, secondary = true))
        if (sentSection.childCount == 0) sentSection.addView(text("Nothing sent", 13f, secondary = true))
        storageText.text = "Storage: outgoing ${kb(usage[BundleRole.OWN])}, incoming ${kb(usage[BundleRole.INBOX])}, " +
            "carried ${kb(usage[BundleRole.FOREIGN])}"
    }

    private fun showKeyResetNoticeIfAny() {
        val root = StoreForwardRuntime.root(applicationContext)
        MailboxKeyResetNotice.pendingSinceMs(root) ?: return
        AlertDialog.Builder(this)
            .setTitle("Message key reset")
            .setMessage("This phone's message key was lost and a new one was made. Messages sent to your old card " +
                "cannot be opened. Give your contacts your new card (Copy my card) or exchange messages with them again.")
            .setPositiveButton("OK") { _, _ -> MailboxKeyResetNotice.dismiss(root) }
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
                AlertDialog.Builder(this)
                    .setTitle("Your contact card (copied)")
                    .setMessage(card + "\n\nGive this text to a contact in person (for example as a QR code) or exchange cards " +
                        "over an encrypted link with Exchange messages. Your node: ${node.nodeId.shortLabel()}\n\n" +
                        "Your safety number: ${node.ownCard.safetyNumber()}\nCompare it in person with the number " +
                        "your contact sees for you.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun addContactDialog() {
        val layout = vertical().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val cardInput = EditText(this).apply { hint = "BCC1:..." }
        val nameInput = EditText(this).apply { hint = "Name" }
        layout.addView(cardInput)
        layout.addView(nameInput)
        AlertDialog.Builder(this)
            .setTitle("Add contact from card text")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
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
                        toast(result.fold({ "Contact added" }, { "Card not added: ${it.message}" }))
                        refresh()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun acceptDialog(contact: StoreForwardContact) {
        val nameInput = EditText(this).apply { hint = "Name" }
        AlertDialog.Builder(this)
            .setTitle("Accept ${contact.nodeId.shortLabel()}?")
            .setMessage("This card came over an encrypted link from that phone. Accept it only if you know who it is.\n\n" +
                "Safety number: ${contact.card.safetyNumber()}\nAsk them to read their safety number from their " +
                "Messages screen (Copy my card) and check that it matches.")
            .setView(nameInput)
            .setPositiveButton("Accept") { _, _ ->
                val name = nameInput.text.toString()
                worker.execute {
                    val result = runCatching {
                        node?.contacts?.accept(contact.nodeId, name)
                        node?.openPendingFrom(contact.nodeId).orEmpty()
                    }
                    runOnUiThread {
                        result.onSuccess { opened ->
                            if (opened.isNotEmpty()) toast("${opened.size} waiting message(s) opened")
                        }.onFailure { toast("Not accepted: ${it.message}") }
                        refresh()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun send() {
        val node = node ?: return
        val contact = contacts.getOrNull(contactSpinner.selectedItemPosition)
        if (contact == null) {
            toast("Add or accept a contact first")
            return
        }
        val textBody = messageInput.text.toString().trim()
        val media = attachment
        if (textBody.isEmpty() && media == null) {
            toast("Write a message or attach something")
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
                    attachmentText.text = ""
                    toast("Queued. It moves at the next message exchange.")
                }.onFailure { toast("Not sent: ${it.message}") }
                refresh()
            }
        }
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
                attachmentText.text = "Photo attached (${bytes.size / 1024} KB)"
            }.onFailure { toast("Photo not attached: ${it.message}") }
        }
    }

    private fun toggleRecording() {
        if (recorder != null) {
            stopRecording(keep = true)
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
            recordButton.text = "Stop recording"
            attachmentText.text = "Recording (up to 2 minutes)..."
        }.onFailure { toast("Recording failed: ${it.message}") }
    }

    private fun stopRecording(keep: Boolean) {
        val active = recorder ?: return
        recorder = null
        recordButton.text = "Record voice note"
        val ok = runCatching { active.stop() }.isSuccess
        active.release()
        val file = recordingFile
        recordingFile = null
        if (keep && ok && file != null && file.exists()) {
            val bytes = file.readBytes()
            file.delete()
            attachment = MessageKind.VOICE to bytes
            val seconds = (System.currentTimeMillis() - recordingStartedMs) / 1000
            attachmentText.text = "Voice note attached (${seconds}s, ${bytes.size / 1024} KB)"
        } else {
            file?.delete()
            attachmentText.text = ""
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
                }.onFailure { toast("Cannot play: ${it.message}") }.getOrNull()
            }
            MessageKind.PHOTO -> {
                val bitmap = BitmapFactory.decodeByteArray(entry.message.body, 0, entry.message.body.size) ?: return
                AlertDialog.Builder(this)
                    .setView(ImageView(this).apply {
                        setImageBitmap(bitmap)
                        adjustViewBounds = true
                    })
                    .setPositiveButton("Close", null)
                    .show()
            }
            MessageKind.TEXT -> AlertDialog.Builder(this)
                .setMessage(String(entry.message.body, Charsets.UTF_8))
                .setPositiveButton("Close", null)
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

    private fun heading(value: String) = text(value, 18f, bold = true).apply { setPadding(0, dp(20), 0, dp(6)) }

    private fun text(value: String, size: Float, bold: Boolean = false, secondary: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color(if (secondary) R.color.text_secondary else R.color.text_primary))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { view ->
            addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun kb(bytes: Long?) = "${(bytes ?: 0) / 1024} KB"
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
