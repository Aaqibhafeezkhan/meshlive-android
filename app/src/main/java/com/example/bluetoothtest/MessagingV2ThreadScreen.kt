package com.example.bluetoothtest

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Messaging v2 phase 1c: the thread screen (DECISIONS Design M, "Where the conversations live" and the UI spec). It is a view
 * drawn over MainActivity, not an activity of its own, because the thread link is dialled from the discovery that the Call
 * screen runs (phase 1d): leaving MainActivity would stop that scan. Everything it shows comes from the pure models in
 * MessagingV2ThreadModel.kt; this class draws them and forwards taps to the engine.
 *
 * Open = [ChatPresence.threadOpened] (`onResume` of the host and `open`), left = [ChatPresence.threadClosed] (`onPause` and
 * `close`); every external grant of the conversation ends when the thread is left (PROTOCOL). FLAG_SECURE covers the
 * window while the thread is up; a DEBUG launch with `test_allow_screenshots` is exempt so benchmark screenshots work.
 */

/** What the thread needs from the main screen. */
internal interface ThreadHost {
    /** 12-hex prefixes of the phones the discovery sees now. */
    fun nearbyPrefixes(): Set<String>

    /** The verified contact with this node ID, or null. */
    fun contactEntry(nodeIdHex: String): ContactEntry?

    /** Labels of the saved contacts by 12-hex prefix. */
    fun contactNames(): Map<String, String>

    /** Starts a call back to this contact (the thread closes first). */
    fun call(prefixHex: String, video: Boolean, name: String)

    /** The contact's own sheet (rename, block, remove, voice, video). */
    fun showContactActions(contact: ContactEntry)

    /** The safety-number comparison. */
    fun openVerify(nodeIdHex: String, name: String)

    /** The thread was closed (back, a call, or the contact was removed). */
    fun threadClosed()
}

internal class ThreadScreen(
    private val activity: AppCompatActivity,
    private val root: View,
    private val host: ThreadHost,
) {
    private val context: Context = activity.applicationContext
    private val text: ThreadText = AndroidThreadText(activity)
    private val time: ThreadTime = AndroidThreadTime.create(activity)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "mv2-thread").apply { isDaemon = true } }
    private val density = activity.resources.displayMetrics.density

    private val list: RecyclerView = root.findViewById(R.id.threadList)
    private val input: EditText = root.findViewById(R.id.threadInput)
    private val action: ImageButton = root.findViewById(R.id.threadAction)
    private val attach: ImageButton = root.findViewById(R.id.threadAttach)
    private val progress: TextView = root.findViewById(R.id.threadProgress)
    private val empty: TextView = root.findViewById(R.id.threadEmpty)
    private val banner: View = root.findViewById(R.id.threadBanner)
    private val bannerText: TextView = root.findViewById(R.id.threadBannerText)
    private val bannerActions: LinearLayout = root.findViewById(R.id.threadBannerActions)
    private val nameView: TextView = root.findViewById(R.id.threadName)
    private val verifiedView: View = root.findViewById(R.id.threadVerified)
    private val stateView: TextView = root.findViewById(R.id.threadState)
    private val voiceCall: View = root.findViewById(R.id.threadVoice)
    private val videoCall: View = root.findViewById(R.id.threadVideo)

    private val adapter = ThreadAdapter(
        activity, text, density,
        onCard = ::onCardAction,
        onRetry = ::retry,
        onToggleCaption = ::toggleCaption,
        onDelete = ::confirmDeleteMessage,
    )
    private val voice = VoiceNoteRecorder(context)

    var isOpen = false
        private set
    private var resumed = true
    private var peerHex = ""
    private var peer: NodeId? = null
    private var title = ""
    private var link = ConversationLinkState.NOT_A_CONTACT
    private var composerEnabled = false
    private var bannerDismissed = false
    private val unreadLock = Any()
    private var unreadFor: String? = null
    private var firstUnreadKey: String? = null

    /** False until the first snapshot of the open thread arrived: the state line and the composer stay neutral until then. */
    private var loaded = false
    private var scrollToEnd = false
    private var sendOriginal = false
    private var pendingSave: ThreadMessage? = null
    private var recordDownX = 0f
    private var recordSlideDp = 0f
    @Volatile private var engine: MessagingV2Engine? = null
    @Volatile private var nearbyNow = false
    private val busy = AtomicBoolean(false)
    private val expanded = HashSet<String>()
    private val drafts = HashMap<String, String>()
    private val actionCache = HashMap<String, FileActions?>()
    private var lastItemCount = 0

    private val secure: Boolean = !(BuildConfig.DEBUG && activity.intent?.getBooleanExtra("test_allow_screenshots", false) == true)

    private val pickMedia = activity.activityResultRegistry.register(
        "mv2-thread-media", ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { send(it, SendMode.MEDIA) } }
    private val pickFile = activity.activityResultRegistry.register(
        "mv2-thread-file", ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { send(it, SendMode.FILE) } }
    private val saveFile = activity.activityResultRegistry.register(
        "mv2-thread-save", ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let(::save) }
    private val askAudio = activity.activityResultRegistry.register(
        "mv2-thread-audio", ActivityResultContracts.RequestPermission(),
    ) { granted -> if (!granted) toast(text.get("mv2_ui_voice_permission")) else toast(text.get("mv2_ui_voice_hold")) }

    private val poll = object : Runnable {
        override fun run() {
            if (!isOpen || !resumed) return
            refresh()
            main.postDelayed(this, POLL_MS)
        }
    }
    private val recordTick = object : Runnable {
        override fun run() {
            if (!voice.recording) return
            progress.text = text.get("mv2_ui_voice_recording", VoiceNoteGesture.clock(voice.elapsedMs()))
            main.postDelayed(this, 250)
        }
    }

    init {
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        list.itemAnimator = null
        root.findViewById<View>(R.id.threadBack).setOnClickListener { close() }
        root.findViewById<View>(R.id.threadTitleBlock).setOnClickListener { showInfo() }
        root.findViewById<View>(R.id.threadMore).setOnClickListener { showInfo() }
        voiceCall.setOnClickListener { call(false) }
        videoCall.setOnClickListener { call(true) }
        attach.setOnClickListener { showAttachSheet() }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateActionIcon()
        })
        action.setOnClickListener { if (input.text.isNotBlank()) sendText() else toast(text.get("mv2_ui_voice_hold")) }
        action.setOnTouchListener { view, event -> onActionTouch(view, event) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            applyInsets(insets)
            insets
        }
    }

    /**
     * Status bar on top, navigation bar and keyboard at the bottom. The listener is set when the thread is first opened, long
     * after the window dispatched its insets, so [open] asks for them again and applies the ones the window already holds
     * (without that, an Android 10 phone drew the title under the clock and the composer under the navigation buttons).
     */
    private fun applyInsets(insets: WindowInsetsCompat) {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        // Before Android 11 the keyboard is only part of the system window inset, not a separate one.
        @Suppress("DEPRECATION")
        val legacyBottom = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) insets.systemWindowInsetBottom else 0
        val pad = ThreadInsets.padding(bars.left, bars.top, bars.right, bars.bottom, ime.bottom, legacyBottom)
        root.setPadding(pad.left, pad.top, pad.right, pad.bottom)
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------------------

    fun open(nodeIdHex: String) {
        val id = runCatching { NodeId.fromBytes(hexToBytes(nodeIdHex)) }.getOrNull() ?: return
        if (isOpen) saveDraft()
        peerHex = nodeIdHex
        peer = id
        isOpen = true
        resumed = true
        synchronized(unreadLock) { unreadFor = null; firstUnreadKey = null }
        loaded = false
        scrollToEnd = false
        bannerDismissed = false
        lastItemCount = 0
        expanded.clear()
        synchronized(actionCache) { actionCache.clear() }
        adapter.expanded = expanded
        adapter.submitList(emptyList())
        input.setText(drafts[nodeIdHex].orEmpty())
        progress.visibility = View.GONE
        if (secure) activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        root.visibility = View.VISIBLE
        ViewCompat.getRootWindowInsets(root)?.let(::applyInsets)
        ViewCompat.requestApplyInsets(root)
        bindHeader()
        applyComposer(ComposerModel(false, text.get("mv2_ui_composer_hint"), null))
        reportOpen(true)
        // The conversation is on screen: its notification (and the older-app count) goes now.
        ConversationNotifier.opened(context, nodeIdHex)
        refresh()
        main.removeCallbacks(poll)
        main.postDelayed(poll, POLL_MS)
    }

    fun close() {
        if (!isOpen) return
        saveDraft()
        stopVoice(keep = false)
        isOpen = false
        main.removeCallbacks(poll)
        reportOpen(false)
        revokeGrants()
        hideKeyboard()
        root.visibility = View.GONE
        if (secure) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        synchronized(actionCache) { actionCache.clear() }
        host.threadClosed()
    }

    fun onResume() {
        resumed = true
        if (!isOpen) return
        reportOpen(true)
        refresh()
        main.removeCallbacks(poll)
        main.postDelayed(poll, POLL_MS)
    }

    fun onPause() {
        resumed = false
        main.removeCallbacks(poll)
        if (!isOpen) return
        saveDraft()
        stopVoice(keep = false)
        reportOpen(false)
        revokeGrants()
    }

    fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        pickMedia.unregister()
        pickFile.unregister()
        saveFile.unregister()
        askAudio.unregister()
        worker.shutdown()
    }

    /** The contact list changed (a rename, a removal): the name and the checks follow. */
    fun contactsChanged() {
        if (isOpen) bindHeader()
    }

    private fun saveDraft() {
        val draft = input.text?.toString().orEmpty()
        if (draft.isBlank()) drafts.remove(peerHex) else drafts[peerHex] = draft
    }

    private fun reportOpen(open: Boolean) {
        val id = peer ?: return
        worker.execute {
            runCatching {
                val presence = StoreForwardRuntime.chat(context)?.presence ?: return@runCatching
                if (open) presence.threadOpened(id) else presence.threadClosed(id)
            }
        }
    }

    private fun revokeGrants() {
        val scope = peerHex
        if (scope.isNotEmpty()) MessagingV2Opens.revokeScope(context, scope)
    }

    // ---- drawing ---------------------------------------------------------------------------------------------------------------

    private fun bindHeader() {
        val prefix = peerHex.take(12)
        val entry = host.contactEntry(peerHex)
        title = ThreadNaming.title(peerHex, host.contactNames()[prefix])
        nameView.text = title
        verifiedView.visibility = if (entry != null) View.VISIBLE else View.GONE
        val callable = entry != null
        voiceCall.alpha = if (callable) 1f else 0.4f
        videoCall.alpha = if (callable) 1f else 0.4f
        stateView.text = if (loaded) ThreadBanners.stateLine(link, nearbyNow, text) else ""
        stateView.visibility = if (stateView.text.isEmpty()) View.GONE else View.VISIBLE
        empty.text = text.get("mv2_ui_empty_thread", title)
    }

    private fun applyComposer(model: ComposerModel) {
        composerEnabled = model.enabled
        input.isEnabled = model.enabled
        input.hint = model.hint
        attach.isEnabled = model.enabled
        attach.alpha = if (model.enabled) 1f else 0.4f
        action.isEnabled = model.enabled
        action.alpha = if (model.enabled) 1f else 0.4f
        bannerActions.removeAllViews()
        val b = model.banner
        if (b == null) {
            banner.visibility = View.GONE
            return
        }
        banner.visibility = View.VISIBLE
        bannerText.text = b.text
        b.actions.forEach { act ->
            val button = LayoutInflater.from(activity).inflate(R.layout.item_text_button, bannerActions, false) as MaterialButton
            button.text = text.get(
                when (act) {
                    BannerAction.ADD_CONTACT -> "mv2_ui_action_add_contact"
                    BannerAction.COMPARE -> "mv2_ui_action_compare"
                    BannerAction.ACCEPT_KEYS -> "mv2_ui_action_accept_keys"
                    BannerAction.DISMISS -> "mv2_ui_action_dismiss"
                },
            )
            button.setOnClickListener { onBanner(act) }
            bannerActions.addView(button)
        }
    }

    private fun updateActionIcon() {
        val hasText = input.text?.isNotBlank() == true
        action.setImageResource(if (hasText) R.drawable.ic_send else R.drawable.ic_mic)
        action.contentDescription = text.get(if (hasText) "mv2_ui_send" else "mv2_ui_voice_hold")
    }

    // ---- refresh -------------------------------------------------------------------------------------------------------------------

    private class Snapshot(val items: List<ThreadItem>, val link: ConversationLinkState, val nearby: Boolean, val firstUnread: String?)

    private fun refresh() {
        val id = peer ?: return
        if (!busy.compareAndSet(false, true)) return
        val prefixes = host.nearbyPrefixes()
        val shownTitle = title
        val onScreen = resumed
        val hex = peerHex
        worker.execute {
            val snapshot = try {
                snapshot(id, hex, shownTitle, prefixes, onScreen)
            } catch (error: Exception) {
                null
            } finally {
                busy.set(false)
            }
            if (snapshot != null) main.post { if (isOpen && peerHex == hex) showSnapshot(snapshot) }
        }
    }

    private fun snapshot(id: NodeId, hex: String, name: String, prefixes: Set<String>, onScreen: Boolean): Snapshot? {
        val node = runCatching { StoreForwardRuntime.node(context) }.getOrNull() ?: return null
        val engine = node.v2 ?: return null
        this.engine = engine
        val entries = ConversationIndex(node).entries(id)
        val unreadKey = synchronized(unreadLock) {
            if (unreadFor != hex) {
                firstUnreadKey = ThreadItems.firstUnreadKey(entries)
                unreadFor = hex
            }
            firstUnreadKey
        }
        if (onScreen) {
            // The open thread showed them: this is what "read" means (starts disappearing timers, sends receipts when both agreed).
            var marked = false
            entries.forEach { entry ->
                if (entry is ConversationEntry.V2 && entry.message.direction == MessageDirection.IN && entry.message.readAtMs == 0L) {
                    runCatching { engine.markRead(entry.message.id) }
                    marked = true
                }
            }
            if (marked) ConversationNotifier.update(context, hex)
        }
        val linkState = engine.linkState(id)
        val nearby = hex.take(12) in prefixes || node.chatSessionActive(id)
        val sending = node.exchangeRunning(id)
        val items = ThreadItems.build(entries, name, unreadKey, sending, text, time) { cardInputs(engine, it, nearby) }
        return Snapshot(items, linkState, nearby, unreadKey)
    }

    private fun cardInputs(engine: MessagingV2Engine, message: ThreadMessage, nearby: Boolean): CardInputs {
        val attachment = message.attachment ?: return CardInputs(null)
        val vaultId = attachment.vaultId
        val complete = vaultId != null && engine.vault.info(vaultId)?.complete == true &&
            (attachment.state == FileState.STORED || attachment.state == FileState.COMPLETE)
        val actions = if (complete && vaultId != null) {
            synchronized(actionCache) {
                actionCache.getOrPut(message.id) {
                    runCatching { FileActionPolicy.forFile(FileSniffer.sniff(VaultSniffSource(engine.vault, vaultId)), attachment.presentation, true) }.getOrNull()
                }
            }
        } else null
        var fraction: Float? = null
        if (attachment.state == FileState.DOWNLOADING) {
            val record = engine.transfers.forMessage(MessageDirection.IN, message.peerNode, message.messageIdHex)
            if (record != null && record.segmentCount > 0) fraction = record.doneSegments.cardinality().toFloat() / record.segmentCount
        }
        return CardInputs(actions, fraction, nearby)
    }

    private fun showSnapshot(snapshot: Snapshot) {
        nearbyNow = snapshot.nearby
        link = snapshot.link
        val wasAtBottom = !list.canScrollVertically(1)
        loaded = true
        bindHeader()
        applyComposer(ThreadBanners.compose(snapshot.link, title, text, bannerDismissed))
        val items = snapshot.items
        val first = lastItemCount == 0
        val grew = items.size > lastItemCount
        lastItemCount = items.size
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(items) {
            if (items.isEmpty()) return@submitList
            if (first) {
                val marker = items.indexOfFirst { it is ThreadItem.UnreadMarker }
                list.scrollToPosition(if (marker >= 0) marker else items.size - 1)
            } else if (grew && (wasAtBottom || scrollToEnd)) {
                list.scrollToPosition(items.size - 1)
            }
            scrollToEnd = false
        }
    }

    // ---- actions -------------------------------------------------------------------------------------------------------------------

    private fun call(video: Boolean) {
        val entry = host.contactEntry(peerHex) ?: return
        host.call(entry.prefixHex, video, title)
    }

    private fun onBanner(act: BannerAction) {
        when (act) {
            BannerAction.ADD_CONTACT, BannerAction.COMPARE -> host.openVerify(peerHex, title)
            BannerAction.DISMISS -> {
                bannerDismissed = true
                applyComposer(ThreadBanners.compose(link, title, text, true))
            }
            BannerAction.ACCEPT_KEYS -> MaterialAlertDialogBuilder(activity)
                .setTitle(text.get("mv2_ui_accept_keys_title"))
                .setMessage(text.get("mv2_ui_accept_keys_message"))
                .setPositiveButton(text.get("mv2_ui_action_accept_keys")) { _, _ ->
                    val id = peer ?: return@setPositiveButton
                    worker.execute {
                        runCatching { engine?.acceptKeyChange(id) }
                        refresh()
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun sendText() {
        val body = input.text.toString().trim()
        val id = peer ?: return
        if (body.isEmpty() || !composerEnabled) return
        input.setText("")
        drafts.remove(peerHex)
        scrollToEnd = true
        worker.execute {
            val engine = engine ?: return@execute
            try {
                engine.sendText(id, body, null, engine.threadSettings.disappearSeconds(id.toString()))
            } catch (error: MessagingV2QuotaException) {
                main.post { toast(text.get("mv2_ui_send_full")) }
            } catch (error: MessagingV2KeysChangedException) {
                // The banner already says so; the text stays in the field.
                main.post { input.setText(body) }
            } catch (error: Exception) {
                main.post { toast(text.get("mv2_ui_send_failed", error.message ?: error.javaClass.simpleName)); input.setText(body) }
            }
            refresh()
        }
    }

    private fun retry(model: BubbleModel) {
        val id = model.messageId ?: return
        worker.execute {
            try {
                engine?.retry(id)
            } catch (error: MessagingV2QuotaException) {
                main.post { toast(text.get("mv2_ui_send_full")) }
            } catch (error: IllegalStateException) {
                main.post { toast(text.get("mv2_ui_file_gone")) }
            } catch (error: Exception) {
                main.post { toast(text.get("mv2_ui_send_failed", error.message ?: error.javaClass.simpleName)) }
            }
            refresh()
        }
    }

    private fun toggleCaption(model: BubbleModel) {
        if (!expanded.add(model.key)) expanded.remove(model.key)
        adapter.notifyItemChanged(adapter.currentList.indexOfFirst { it.key == model.key })
    }

    private fun confirmDeleteMessage(model: BubbleModel) {
        val id = model.messageId ?: return
        MaterialAlertDialogBuilder(activity)
            .setTitle(text.get("mv2_ui_delete_message_title"))
            .setPositiveButton(text.get("mv2_ui_delete_confirm")) { _, _ ->
                worker.execute { engine?.deleteMessage(id); ConversationNotifier.update(context, peerHex); refresh() }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---- file cards ---------------------------------------------------------------------------------------------------------------

    private fun onCardAction(model: BubbleModel, act: CardAction) {
        val id = model.messageId ?: return
        when (act) {
            CardAction.DOWNLOAD -> worker.execute {
                try {
                    engine?.approveDownload(id)
                } catch (error: MessagingV2QuotaException) {
                    main.post { toast(text.get("mv2_ui_file_storage_full")) }
                } catch (error: Exception) {
                    main.post { toast(text.get("mv2_ui_send_failed", error.message ?: error.javaClass.simpleName)) }
                }
                refresh()
            }
            CardAction.CANCEL -> if (model.incoming) {
                worker.execute { engine?.cancelDownload(id); refresh() }
            } else {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(text.get("mv2_ui_file_cancel_title"))
                    .setMessage(text.get("mv2_ui_file_cancel_message"))
                    .setPositiveButton(text.get("mv2_ui_file_cancel_confirm")) { _, _ -> worker.execute { engine?.deleteMessage(id); ConversationNotifier.update(context, peerHex); refresh() } }
                    .setNegativeButton(text.get("mv2_ui_file_cancel_keep"), null)
                    .show()
            }
            CardAction.OPEN -> openExternally(id)
            CardAction.SAVE -> askSave(id, model.card?.saveWarning ?: WarnLevel.NONE)
            CardAction.SHOW_PICTURE -> showPicture(id)
        }
    }

    private fun messageOf(id: String): Pair<MessagingV2Engine, ThreadMessage>? {
        val engine = engine ?: return null
        val message = engine.threads.get(id) ?: return null
        return engine to message
    }

    private fun openExternally(id: String) {
        worker.execute {
            val (_, message) = messageOf(id) ?: return@execute
            val attachment = message.attachment ?: return@execute
            val vaultId = attachment.vaultId ?: return@execute
            val granted = MessagingV2Opens.grantFor(context, vaultId, attachment.name, attachment.presentation, message.peerNode)
            main.post {
                if (granted == null) {
                    toast(activity.getString(R.string.mv2_file_not_openable))
                    return@post
                }
                try {
                    activity.startActivity(MessagingV2Opens.viewIntent(granted))
                } catch (error: ActivityNotFoundException) {
                    toast(activity.getString(R.string.mv2_file_no_app))
                }
            }
        }
    }

    private fun askSave(id: String, warn: WarnLevel) {
        val go = {
            worker.execute {
                val (engine, message) = messageOf(id) ?: return@execute
                val attachment = message.attachment ?: return@execute
                val vaultId = attachment.vaultId ?: return@execute
                val sniff = runCatching { FileSniffer.sniff(VaultSniffSource(engine.vault, vaultId)) }.getOrNull() ?: return@execute
                pendingSave = message
                main.post { saveFile.launch(SaveName.suggest(attachment.name, sniff)) }
            }
            Unit
        }
        if (warn == WarnLevel.NONE) return go()
        MaterialAlertDialogBuilder(activity)
            .setMessage(activity.getString(if (warn == WarnLevel.DANGEROUS) R.string.mv2_file_warning_dangerous else R.string.mv2_file_warning_general))
            .setPositiveButton(R.string.mv2_file_save_anyway) { _, _ -> go() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun save(uri: Uri) {
        val message = pendingSave ?: return
        val vaultId = message.attachment?.vaultId ?: return
        val vault = engine?.vault ?: return
        worker.execute {
            val copied = runCatching { activity.contentResolver.openOutputStream(uri)?.use { VaultExporter.copy(vault, vaultId, it) } }
            main.post { toast(text.get(if (copied.isSuccess) "mv2_ui_saved" else "mv2_ui_save_failed")) }
        }
    }

    private fun showPicture(id: String) {
        worker.execute {
            val (_, message) = messageOf(id) ?: return@execute
            val vaultId = message.attachment?.vaultId ?: return@execute
            val outcome = TapToDecode.decode(context, vaultId, message.peerNode)
            main.post {
                when (outcome) {
                    is DecodeOutcome.Picture -> {
                        val view = ImageView(activity).apply { setImageBitmap(outcome.bitmap); adjustViewBounds = true }
                        val dialog = MaterialAlertDialogBuilder(activity).setView(view).create()
                        if (secure) dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                        dialog.show()
                    }
                    is DecodeOutcome.Refused -> toast(activity.getString(R.string.mv2_file_picture_failed))
                }
            }
        }
    }

    // ---- attaching and voice notes ---------------------------------------------------------------------------------------------

    private fun showAttachSheet() {
        if (!composerEnabled) return
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_thread_attach, null)
        val dialog = BottomSheetDialog(activity)
        val original = view.findViewById<MaterialSwitch>(R.id.attachOriginal)
        original.isChecked = sendOriginal
        original.setOnCheckedChangeListener { _, checked -> sendOriginal = checked }
        view.findViewById<View>(R.id.attachOriginalInfo).setOnClickListener {
            SettingsInfo.show(activity, activity.getString(R.string.mv2_attach_original), activity.getString(R.string.mv2_attach_original_hint))
        }
        view.findViewById<View>(R.id.attachPhoto).setOnClickListener {
            dialog.dismiss()
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        view.findViewById<View>(R.id.attachFile).setOnClickListener {
            dialog.dismiss()
            pickFile.launch(arrayOf("*/*"))
        }
        dialog.setContentView(view)
        if (secure) dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    private fun send(uri: Uri, mode: SendMode) {
        val id = peer ?: return
        val original = sendOriginal
        progress.visibility = View.VISIBLE
        progress.text = text.get("mv2_ui_preparing")
        scrollToEnd = true
        worker.execute {
            val engine = engine
            try {
                if (engine == null) throw IllegalStateException("Messages are not available")
                AttachmentPreparer(context).prepare(uri, mode, original).use { prepared ->
                    MessagingV2Attachments.send(engine, id, prepared, disappearSeconds = engine.threadSettings.disappearSeconds(id.toString()))
                }
            } catch (error: AttachException) {
                val refusal = error.refusal
                main.post {
                    toast(
                        if (refusal is AttachRefusal.TooLarge) activity.getString(R.string.mv2_file_too_large, (refusal.limit / 1_000_000L).toInt())
                        else text.get("mv2_ui_send_failed", refusal.message),
                    )
                }
            } catch (error: MessagingV2QuotaException) {
                main.post { toast(text.get("mv2_ui_send_full")) }
            } catch (error: Exception) {
                main.post { toast(text.get("mv2_ui_send_failed", error.message ?: error.javaClass.simpleName)) }
            }
            main.post { if (!voice.recording) progress.visibility = View.GONE }
            refresh()
        }
    }

    private fun onActionTouch(view: View, event: MotionEvent): Boolean {
        // With text in the field the button is Send and the normal click handles it.
        if (!composerEnabled || input.text?.isNotBlank() == true) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                recordDownX = event.rawX
                recordSlideDp = 0f
                startVoice()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                recordSlideDp = ((recordDownX - event.rawX) / density).coerceAtLeast(0f)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasRecording = voice.recording
                stopVoice(keep = true)
                // A short tap (no recording started) is a tap: say how it works.
                if (!wasRecording) view.performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                stopVoice(keep = false)
                return true
            }
        }
        return false
    }

    private fun startVoice() {
        if (VoiceNoteGate.blockedNow()) {
            toast(activity.getString(R.string.messages_recording_blocked))
            return
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askAudio.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val started = voice.start { main.post { stopVoice(keep = true) } }
        if (!started) {
            toast(text.get("mv2_ui_send_failed", "microphone"))
            return
        }
        progress.visibility = View.VISIBLE
        progress.text = text.get("mv2_ui_voice_recording", "0:00")
        action.parent.requestDisallowInterceptTouchEvent(true)
        main.removeCallbacks(recordTick)
        main.post(recordTick)
    }

    private fun stopVoice(keep: Boolean) {
        if (!voice.recording) return
        main.removeCallbacks(recordTick)
        val elapsed = voice.elapsedMs()
        val decision = if (keep) VoiceNoteGesture.pressDecision(elapsed, recordSlideDp) else VoiceNoteGesture.Decision.CANCELLED
        val result = voice.finish(decision == VoiceNoteGesture.Decision.SEND)
        progress.visibility = View.GONE
        when (decision) {
            VoiceNoteGesture.Decision.CANCELLED -> if (keep) toast(text.get("mv2_ui_voice_cancelled"))
            VoiceNoteGesture.Decision.TOO_SHORT -> toast(text.get("mv2_ui_voice_too_short"))
            VoiceNoteGesture.Decision.SEND -> {
                val (bytes, duration) = result ?: return
                val id = peer ?: return
                worker.execute {
                    val engine = engine ?: return@execute
                    try {
                        AttachmentPreparer(context).prepareVoice(bytes, duration).use { prepared ->
                            MessagingV2Attachments.send(engine, id, prepared, disappearSeconds = engine.threadSettings.disappearSeconds(id.toString()))
                        }
                    } catch (error: Exception) {
                        main.post { toast(text.get("mv2_ui_send_failed", error.message ?: error.javaClass.simpleName)) }
                    }
                    refresh()
                }
            }
        }
    }

    // ---- conversation info ---------------------------------------------------------------------------------------------------------

    private fun showInfo() {
        val engine = engine
        val id = peer ?: return
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_thread_info, null)
        val dialog = BottomSheetDialog(activity)
        view.findViewById<TextView>(R.id.infoName).text = title
        val timerValue = view.findViewById<TextView>(R.id.infoTimerValue)
        fun timerNow() = engine?.threadSettings?.disappearSeconds(peerHex) ?: 0
        timerValue.text = text.get(DisappearLabels.key(DisappearChoices.nearest(timerNow())))
        view.findViewById<View>(R.id.infoTimerRow).setOnClickListener {
            val current = DisappearChoices.SECONDS.indexOf(DisappearChoices.nearest(timerNow()))
            MaterialAlertDialogBuilder(activity)
                .setTitle(text.get("mv2_ui_info_disappearing"))
                .setSingleChoiceItems(DisappearChoices.SECONDS.map { text.get(DisappearLabels.key(it)) }.toTypedArray(), current) { picker, which ->
                    engine?.threadSettings?.setDisappearSeconds(peerHex, DisappearChoices.SECONDS[which])
                    timerValue.text = text.get(DisappearLabels.key(DisappearChoices.SECONDS[which]))
                    picker.dismiss()
                }
                .show()
        }
        val receipts = view.findViewById<MaterialSwitch>(R.id.infoReceipts)
        receipts.isChecked = engine?.readReceiptsFor(id) ?: false
        receipts.isEnabled = engine != null
        receipts.setOnCheckedChangeListener { _, checked -> engine?.threadSettings?.setReadReceipts(peerHex, checked) }
        view.findViewById<View>(R.id.infoReceiptsInfo).setOnClickListener {
            SettingsInfo.show(activity, text.get("mv2_ui_info_read_receipts"), text.get("mv2_ui_info_read_receipts_info"))
        }
        val entry = host.contactEntry(peerHex)
        view.findViewById<View>(R.id.infoContactRow).apply {
            visibility = if (entry != null) View.VISIBLE else View.GONE
            setOnClickListener { dialog.dismiss(); entry?.let(host::showContactActions) }
        }
        view.findViewById<View>(R.id.infoCompareRow).setOnClickListener { dialog.dismiss(); host.openVerify(peerHex, title) }
        view.findViewById<View>(R.id.infoDeleteRow).setOnClickListener { dialog.dismiss(); confirmDeleteConversation() }
        dialog.setContentView(view)
        if (secure) dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    private fun confirmDeleteConversation() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(text.get("mv2_ui_delete_title"))
            .setMessage(text.get("mv2_ui_delete_message", title))
            .setPositiveButton(text.get("mv2_ui_delete_confirm")) { _, _ ->
                val id = peer ?: return@setPositiveButton
                worker.execute {
                    runCatching {
                        val node = StoreForwardRuntime.node(context)
                        val v2 = node.v2 ?: return@runCatching
                        v2.threads.forPeer(id.toString()).forEach { v2.deleteMessage(it.id) }
                        node.archive.page(0, Int.MAX_VALUE).filter { it.peerNode == id.toString() }.forEach { node.deleteMessage(it.bundleIdHex) }
                    }
                    ConversationNotifier.opened(context, id.toString())
                    refresh()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---- small helpers -------------------------------------------------------------------------------------------------------------

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    private fun hideKeyboard() {
        val manager = activity.getSystemService(InputMethodManager::class.java)
        manager?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private companion object {
        const val POLL_MS = 1_500L
    }
}

// ---- the list --------------------------------------------------------------------------------------------------------------------------

internal class ThreadAdapter(
    private val activity: AppCompatActivity,
    private val text: ThreadText,
    private val density: Float,
    private val onCard: (BubbleModel, CardAction) -> Unit,
    private val onRetry: (BubbleModel) -> Unit,
    private val onToggleCaption: (BubbleModel) -> Unit,
    private val onDelete: (BubbleModel) -> Unit,
) : androidx.recyclerview.widget.ListAdapter<ThreadItem, RecyclerView.ViewHolder>(Diff) {
    var expanded: Set<String> = emptySet()

    private val maxBubblePx = (activity.resources.displayMetrics.widthPixels * 0.8f).toInt()

    override fun getItemViewType(position: Int): Int = if (getItem(position) is ThreadItem.Bubble) TYPE_BUBBLE else TYPE_SEPARATOR

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_BUBBLE) BubbleHolder(inflater.inflate(R.layout.item_thread_message, parent, false))
        else SeparatorHolder(inflater.inflate(R.layout.item_thread_separator, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ThreadItem.Day -> (holder as SeparatorHolder).label.text = item.label
            is ThreadItem.UnreadMarker -> (holder as SeparatorHolder).label.text = text.get("mv2_ui_unread_marker")
            is ThreadItem.Bubble -> (holder as BubbleHolder).bind(item.model)
        }
    }

    private class SeparatorHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.separatorText)
    }

    private inner class BubbleHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val bubble: LinearLayout = view.findViewById(R.id.msgBubble)
        private val body: TextView = view.findViewById(R.id.msgText)
        private val card: View = view.findViewById(R.id.msgCard)
        private val cardHead: View = view.findViewById(R.id.msgCardHead)
        private val cardIcon: ImageView = view.findViewById(R.id.msgCardIcon)
        private val cardName: TextView = view.findViewById(R.id.msgCardName)
        private val cardMeta: TextView = view.findViewById(R.id.msgCardMeta)
        private val cardProgress: android.widget.ProgressBar = view.findViewById(R.id.msgCardProgress)
        private val cardActions: LinearLayout = view.findViewById(R.id.msgCardActions)
        private val time: TextView = view.findViewById(R.id.msgTime)
        private val disappears: TextView = view.findViewById(R.id.msgDisappears)
        private val glyph: ImageView = view.findViewById(R.id.msgGlyph)
        private val caption: TextView = view.findViewById(R.id.msgCaption)
        private val retry: View = view.findViewById(R.id.msgRetry)

        fun bind(model: BubbleModel) {
            val params = bubble.layoutParams as LinearLayout.LayoutParams
            params.gravity = if (model.incoming) android.view.Gravity.START else android.view.Gravity.END
            bubble.layoutParams = params
            bubble.setBackgroundResource(if (model.incoming) R.drawable.bubble_in else R.drawable.bubble_out)
            val inner = maxBubblePx - (28 * density).toInt()
            body.maxWidth = inner
            body.visibility = if (model.text.isEmpty()) View.GONE else View.VISIBLE
            body.text = model.text
            bindCard(model, inner)
            time.text = model.timeText
            disappears.visibility = if (model.disappears == null) View.GONE else View.VISIBLE
            disappears.text = model.disappears.orEmpty()
            val state = model.state
            if (state == null) {
                glyph.visibility = View.GONE
            } else {
                glyph.visibility = View.VISIBLE
                glyph.setImageResource(glyphRes(state.glyph))
                glyph.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(activity, if (state.glyph == DeliveryGlyph.CHECKS_READ || state.glyph == DeliveryGlyph.ERROR) R.color.text_primary else R.color.text_secondary),
                )
                glyph.contentDescription = state.word
            }
            val failed = state?.state == OwnMessageState.NOT_SENT
            val showCaption = model.stateCaption != null && (failed || model.key in expanded)
            caption.visibility = if (showCaption) View.VISIBLE else View.GONE
            caption.text = model.stateCaption.orEmpty()
            val root = itemView.findViewById<LinearLayout>(R.id.msgRoot)
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child === caption || child === retry) {
                    (child.layoutParams as LinearLayout.LayoutParams).gravity = if (model.incoming) android.view.Gravity.START else android.view.Gravity.END
                }
            }
            retry.visibility = if (model.canRetry) View.VISIBLE else View.GONE
            retry.setOnClickListener { onRetry(model) }
            bubble.setOnClickListener { if (model.stateCaption != null && !failed) onToggleCaption(model) }
            bubble.setOnLongClickListener {
                if (model.messageId != null) { onDelete(model); true } else false
            }
        }

        private fun bindCard(model: BubbleModel, inner: Int) {
            val c = model.card
            if (c == null) {
                card.visibility = View.GONE
                return
            }
            card.visibility = View.VISIBLE
            cardIcon.setImageResource(
                when (c.icon) {
                    CardIcon.PICTURE -> R.drawable.ic_image
                    CardIcon.VIDEO -> R.drawable.ic_videocam
                    CardIcon.AUDIO -> R.drawable.ic_music_note
                    CardIcon.DOCUMENT, CardIcon.FILE -> R.drawable.ic_file
                },
            )
            cardName.maxWidth = inner - (52 * density).toInt()
            cardName.text = c.name
            cardMeta.text = c.status ?: c.sizeText
            cardProgress.visibility = if (c.progress != null) View.VISIBLE else View.GONE
            cardProgress.progress = ((c.progress ?: 0f) * 100).toInt()
            cardHead.setOnClickListener { c.tap?.let { onCard(model, it) } }
            cardHead.isClickable = c.tap != null
            cardActions.removeAllViews()
            c.actions.forEach { act ->
                val button = LayoutInflater.from(activity).inflate(R.layout.item_text_button, cardActions, false) as MaterialButton
                button.text = when (act) {
                    CardAction.DOWNLOAD -> c.downloadLabel ?: text.get("mv2_ui_file_download", c.sizeText)
                    CardAction.CANCEL -> text.get("mv2_ui_file_cancel")
                    CardAction.OPEN -> activity.getString(R.string.mv2_file_open)
                    CardAction.SAVE -> activity.getString(R.string.mv2_file_save)
                    CardAction.SHOW_PICTURE -> activity.getString(R.string.mv2_file_show_picture)
                }
                button.setOnClickListener { onCard(model, act) }
                cardActions.addView(button)
            }
        }
    }

    private fun glyphRes(glyph: DeliveryGlyph): Int = when (glyph) {
        DeliveryGlyph.CLOCK -> R.drawable.ic_schedule
        DeliveryGlyph.UPLOAD -> R.drawable.ic_arrow_upward
        DeliveryGlyph.CHECK -> R.drawable.ic_check
        DeliveryGlyph.CHECKS, DeliveryGlyph.CHECKS_READ -> R.drawable.ic_done_all
        DeliveryGlyph.ERROR -> R.drawable.ic_error
    }

    private object Diff : androidx.recyclerview.widget.DiffUtil.ItemCallback<ThreadItem>() {
        override fun areItemsTheSame(a: ThreadItem, b: ThreadItem): Boolean = a.key == b.key
        override fun areContentsTheSame(a: ThreadItem, b: ThreadItem): Boolean = a == b
    }

    private companion object {
        const val TYPE_BUBBLE = 0
        const val TYPE_SEPARATOR = 1
    }
}
