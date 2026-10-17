package com.example.bluetoothtest

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Messaging v2 phase 1b: a small DEBUG-only screen that exercises the attachment pipeline end to end on a phone before the
 * conversation UI (phase 1c) exists: pick a photo or video (Photo Picker), pick any file (document picker), send it to a
 * contact, and on the receiving side Download / Open / Save / Show picture for each file. Started with
 * `am start -n <id>/com.example.bluetoothtest.MessagingV2AttachActivity --es peer PREFIX12` on a debuggable build whose
 * master switch is on; it finishes at once otherwise. Not part of the product: phase 1c replaces it with the thread
 * screen and keeps its calls (AttachmentPreparer, MessagingV2Opens, TapToDecode, SaveName, VaultExporter).
 */
class MessagingV2AttachActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var originalBox: CheckBox
    private var peer: StoreForwardContact? = null
    private var engine: MessagingV2Engine? = null
    private var pendingSave: ThreadMessage? = null

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { send(it, SendMode.MEDIA) } }
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { send(it, SendMode.FILE) } }
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let(::save) }

    private val refresh = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG || !MessagingV2Feature.enabled) {
            finish()
            return
        }
        val node = runCatching { StoreForwardRuntime.node(applicationContext) }.getOrNull()
        engine = node?.v2
        val prefix = intent.getStringExtra("peer")?.lowercase()
        peer = node?.contacts?.accepted()?.singleOrNull { prefix != null && it.nodeId.toString().startsWith(prefix) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { text = if (peer == null || engine == null) "No accepted contact for this prefix" else "Contact ${peer!!.nodeId.toString().take(12)}" }
        originalBox = CheckBox(this).apply { text = "Original (photos keep their pixels; JPEG metadata removed)" }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Photo or video"
            setOnClickListener { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
        })
        buttons.addView(Button(this).apply {
            text = "File"
            setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(status)
        root.addView(originalBox)
        root.addView(buttons)
        root.addView(list)
        setContentView(ScrollView(this).apply { addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
    }

    override fun onResume() {
        super.onResume()
        if (peer != null) main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onStop() {
        // Leaving the screen revokes every external grant of this conversation (PROTOCOL: revoked when the thread is left).
        peer?.let { MessagingV2Opens.revokeScope(applicationContext, it.nodeId.toString()) }
        super.onStop()
    }

    private fun say(text: String) = main.post { status.text = text }

    private fun send(uri: Uri, mode: SendMode) {
        val engine = engine ?: return
        val contact = peer ?: return
        val original = originalBox.isChecked
        say("Preparing...")
        Thread {
            runCatching {
                AttachmentPreparer(applicationContext).prepare(uri, mode, original).use { prepared ->
                    val message = MessagingV2Attachments.send(engine, contact.nodeId, prepared)
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK message_v2_attach sent id=${message.messageIdHex.take(16)} name=${prepared.name} bytes=${prepared.size} " +
                            "mime=${prepared.mime} kind=${prepared.presentation.name.lowercase()} thumb=${prepared.thumbnail.size} " +
                            "w=${prepared.width} h=${prepared.height} notes=${prepared.notes.joinToString(",") { it.name.lowercase() }} " +
                            "sha256=${message.attachment?.sha256?.sfHex()}",
                    )
                    "Sent ${prepared.name}, ${prepared.size} bytes (${prepared.notes.joinToString { it.name.lowercase() }})"
                }
            }.onSuccess(::say).onFailure {
                Log.w("BluetoothVideo", "BENCHMARK message_v2_attach send failed: ${it.message}")
                say("Not sent: ${it.message}")
            }
        }.apply { name = "MessagingV2AttachSend"; isDaemon = true }.start()
    }

    private fun render() {
        val engine = engine ?: return
        val contact = peer ?: return
        val messages = engine.threads.forPeer(contact.nodeId.toString()).filter { it.attachment != null }
        list.removeAllViews()
        for (message in messages.takeLast(12).reversed()) list.addView(row(engine, message))
    }

    private fun row(engine: MessagingV2Engine, message: ThreadMessage): View {
        val attachment = requireNotNull(message.attachment)
        val vaultId = attachment.vaultId
        val complete = vaultId != null && engine.vault.info(vaultId)?.complete == true &&
            (attachment.state == FileState.STORED || attachment.state == FileState.COMPLETE)
        val sniff = if (complete) runCatching { FileSniffer.sniff(VaultSniffSource(engine.vault, vaultId!!)) }.getOrNull() else null
        val actions = sniff?.let { FileActionPolicy.forFile(it, attachment.presentation, true) }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 16, 0, 16) }
        layout.addView(TextView(this).apply {
            text = "${message.direction.name} ${attachment.name}  ${attachment.totalSize} B  ${attachment.state}" +
                (sniff?.let { "  sniffed=${it.type.name}${if (it.polyglot) " (polyglot: ${it.polyglotReason})" else ""}" } ?: "")
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(label: String, action: () -> Unit) = buttons.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        if (attachment.state == FileState.AWAITING_APPROVAL) add("Download") { runCatching { engine.approveDownload(message.id) } }
        if (attachment.state == FileState.DOWNLOADING) add("Cancel") { engine.cancelDownload(message.id) }
        if (actions != null) {
            if (actions.canOpen) add("Open") { open(message, attachment, vaultId!!) }
            add(if (actions.saveWarning == WarnLevel.NONE) "Save" else "Save (warning)") { askSave(message, actions.saveWarning) }
            if (actions.canPreview) add("Show") { show(vaultId!!) }
        }
        layout.addView(buttons)
        return layout
    }

    private fun open(message: ThreadMessage, attachment: AttachmentInfo, vaultId: String) {
        val granted = MessagingV2Opens.grantFor(applicationContext, vaultId, attachment.name, attachment.presentation, message.peerNode)
        if (granted == null) {
            Toast.makeText(this, "This file type is not opened from here", Toast.LENGTH_SHORT).show()
            return
        }
        Log.i("BluetoothVideo", "BENCHMARK message_v2_attach open mime=${granted.mime} bytes=${granted.grant.size}")
        try {
            startActivity(MessagingV2Opens.viewIntent(granted))
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, "No app can show this", Toast.LENGTH_SHORT).show()
        }
    }

    private fun askSave(message: ThreadMessage, warn: WarnLevel) {
        val go = {
            pendingSave = message
            val attachment = requireNotNull(message.attachment)
            val sniff = FileSniffer.sniff(VaultSniffSource(requireNotNull(engine).vault, requireNotNull(attachment.vaultId)))
            saveFile.launch(SaveName.suggest(attachment.name, sniff))
        }
        if (warn == WarnLevel.NONE) return go()
        val text = if (warn == WarnLevel.DANGEROUS) "This file can install or run code. Messages never open it. Save it only if you know and trust the sender."
        else "This kind of file is not opened from Messages. Save it only if you expect it."
        AlertDialog.Builder(this).setMessage(text).setPositiveButton("Save anyway") { _, _ -> go() }.setNegativeButton("Cancel", null).show()
    }

    private fun save(uri: Uri) {
        val message = pendingSave ?: return
        val vaultId = message.attachment?.vaultId ?: return
        val vault = engine?.vault ?: return
        Thread {
            val copied = runCatching { contentResolver.openOutputStream(uri)?.use { VaultExporter.copy(vault, vaultId, it) } }
            say(copied.fold({ "Saved $it bytes" }, { "Save failed: ${it.message}" }))
        }.apply { name = "MessagingV2AttachSave"; isDaemon = true }.start()
    }

    private fun show(vaultId: String) {
        say("Decoding...")
        Thread {
            val outcome = TapToDecode.decode(applicationContext, vaultId, peer?.nodeId.toString())
            main.post {
                when (outcome) {
                    is DecodeOutcome.Picture -> {
                        say("Decoded ${outcome.bitmap.width}x${outcome.bitmap.height} isolated=${outcome.isolated}")
                        Log.i("BluetoothVideo", "BENCHMARK message_v2_attach decode ok w=${outcome.bitmap.width} h=${outcome.bitmap.height} isolated=${outcome.isolated}")
                        AlertDialog.Builder(this).setView(ImageView(this).apply { setImageBitmap(outcome.bitmap) }).show()
                    }
                    is DecodeOutcome.Refused -> {
                        say("Not shown: ${outcome.reason}")
                        Log.i("BluetoothVideo", "BENCHMARK message_v2_attach decode refused reason=${outcome.reason}")
                    }
                }
            }
        }.apply { name = "MessagingV2AttachDecode"; isDaemon = true }.start()
    }
}
