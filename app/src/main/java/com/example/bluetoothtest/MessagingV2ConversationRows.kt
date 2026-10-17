package com.example.bluetoothtest

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.time.ZoneId
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Messaging v2 phase 1c: the Android side of the pure models: string lookup by resource name, the phone's clock and date
 * formats, the Contacts tab rows (Q-M1 option B) and the Settings > Privacy rows (read receipts, automatic download size).
 */

/** [ThreadText] over the app's string resources (the key is the resource name). */
internal class AndroidThreadText(context: Context) : ThreadText {
    private val resources = context.resources
    private val packageName = context.packageName
    private val ids = HashMap<String, Int>()

    override fun get(key: String, vararg args: Any): String {
        val id = synchronized(ids) { ids.getOrPut(key) { resources.getIdentifier(key, "string", packageName) } }
        require(id != 0) { "Missing string $key" }
        return if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args)
    }
}

internal object AndroidThreadTime {
    fun create(context: Context): ThreadTime {
        val clockFormat = DateFormat.getTimeFormat(context)
        return ThreadTime(
            nowMs = System::currentTimeMillis,
            zone = ZoneId.systemDefault(),
            clockText = { ms -> synchronized(clockFormat) { clockFormat.format(Date(ms)) } },
            dayText = { ms, withYear ->
                var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
                flags = flags or if (withYear) DateUtils.FORMAT_SHOW_YEAR else DateUtils.FORMAT_NO_YEAR
                DateUtils.formatDateTime(context, ms, flags)
            },
        )
    }
}

/**
 * The Contacts tab as conversation rows. [update] computes the rows off the main thread (the conversation index reads the
 * thread store and the v1 archive) and shows them; while the tab is visible it refreshes every few seconds so a new message,
 * a tick or "Nearby now" shows without leaving the tab.
 */
internal class ConversationRowsController(
    private val context: Context,
    private val list: ListView,
    private val empty: View,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "mv2-rows").apply { isDaemon = true } }
    private val text = AndroidThreadText(context)
    private val time = AndroidThreadTime.create(context)
    private val busy = AtomicBoolean(false)
    private val adapter = ConversationRowAdapter(context)
    private var contacts: List<ContactEntry> = emptyList()
    private var nearby: () -> Set<String> = { emptySet() }
    private var scheduled = false

    /** The node ID of the row at [position], or null. */
    var rows: List<ConversationRowModel> = emptyList()
        private set

    fun nodeIdAt(position: Int): String? = rows.getOrNull(position)?.nodeIdHex

    private val tick = object : Runnable {
        override fun run() {
            scheduled = false
            if (list.isShown) {
                update(contacts, nearby)
            }
        }
    }

    fun update(saved: List<ContactEntry>, nearbyPrefixes: () -> Set<String>) {
        contacts = saved
        nearby = nearbyPrefixes
        if (!busy.compareAndSet(false, true)) return
        val refs = saved.map { ContactRef(it.nodeIdHex, it.title) }
        val prefixes = nearbyPrefixes()
        worker.execute {
            val rowsNow = try {
                val node = runCatching { StoreForwardRuntime.node(context) }.getOrNull()
                val summaries = if (node?.v2 != null) {
                    val index = ConversationIndex(node)
                    index.summaries(showPreview = true, sending = { node.exchangeRunning(it) }).associateBy { it.peer.toString() }
                } else emptyMap()
                ConversationRows.build(refs, summaries, prefixes, text, time)
            } catch (error: Exception) {
                null
            } finally {
                busy.set(false)
            }
            main.post {
                if (rowsNow != null) show(rowsNow)
                if (!scheduled) {
                    scheduled = true
                    main.postDelayed(tick, REFRESH_MS)
                }
            }
        }
    }

    fun stop() {
        main.removeCallbacks(tick)
        scheduled = false
    }

    private fun show(model: List<ConversationRowModel>) {
        rows = model
        // One adapter for the life of the tab, so a refresh does not throw the scroll position away.
        if (list.adapter !== adapter) list.adapter = adapter
        adapter.submit(model)
        empty.visibility = if (model.isEmpty()) View.VISIBLE else View.GONE
    }

    private companion object {
        const val REFRESH_MS = 3_000L
    }
}

internal class ConversationRowAdapter(private val context: Context) : BaseAdapter() {
    private var rows: List<ConversationRowModel> = emptyList()

    fun submit(model: List<ConversationRowModel>) {
        rows = model
        notifyDataSetChanged()
    }

    override fun getCount(): Int = rows.size
    override fun getItem(position: Int): ConversationRowModel = rows[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        // Inflate with the list's (themed Activity) context: the row resolves ?attr theme values.
        val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_conversation_row, parent, false)
        val row = rows[position]
        view.findViewById<TextView>(R.id.convAvatar).text = row.title.firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty()
        view.findViewById<TextView>(R.id.convName).text = row.title
        view.findViewById<View>(R.id.convVerified).visibility = if (row.verified) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.convNearbyDot).visibility = if (row.nearby && row.hasActivity) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.convTime).apply {
            text = row.timeText
            visibility = if (row.timeText.isEmpty()) View.GONE else View.VISIBLE
        }
        view.findViewById<ImageView>(R.id.convGlyph).apply {
            val glyph = row.glyph
            if (glyph == null) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                setImageResource(
                    when (glyph.glyph) {
                        DeliveryGlyph.CLOCK -> R.drawable.ic_schedule
                        DeliveryGlyph.UPLOAD -> R.drawable.ic_arrow_upward
                        DeliveryGlyph.CHECK -> R.drawable.ic_check
                        DeliveryGlyph.CHECKS, DeliveryGlyph.CHECKS_READ -> R.drawable.ic_done_all
                        DeliveryGlyph.ERROR -> R.drawable.ic_error
                    },
                )
                imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(context, if (glyph.glyph == DeliveryGlyph.CHECKS_READ) R.color.text_primary else R.color.text_secondary),
                )
            }
        }
        view.findViewById<TextView>(R.id.convSubtitle).apply {
            text = row.subtitle
            visibility = if (row.subtitle.isEmpty()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.convUnread).apply {
            text = if (row.unread > 99) "99+" else row.unread.toString()
            visibility = if (row.unread > 0) View.VISIBLE else View.GONE
        }
        view.contentDescription = row.description
        return view
    }
}

/**
 * Settings > Privacy: "Read receipts" (a switch, off by default, symmetric) and "Download files automatically" (a value row
 * with a choice list). Visible only with Messaging v2 on. The choices are stored in the app preferences and applied to the
 * running engine at once; a benchmark launch ignores the stored values (its extras are the only switches).
 */
internal class MessagingV2PrivacyRows(
    private val activity: AppCompatActivity,
    private val container: View,
    private val prefs: SharedPreferences,
) {
    private val text = AndroidThreadText(activity)

    companion object {
        /** The user's stored choices (defaults: read receipts off, files up to 8 MB without a tap). */
        fun readChoices(prefs: SharedPreferences): MessagingV2UserChoices = MessagingV2UserChoices(
            prefs.getBoolean(MessagingV2UserChoices.PREF_READ_RECEIPTS, false),
            AutoDownloadChoices.nearest(prefs.getInt(MessagingV2UserChoices.PREF_AUTO_DOWNLOAD_MB, 8)),
        )
    }

    fun bind() {
        if (!MessagingV2Feature.enabled) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val choices = readChoices(prefs)
        val receipts = container.findViewById<MaterialSwitch>(R.id.mv2PrivacyReceipts)
        receipts.setOnCheckedChangeListener(null)
        receipts.isChecked = choices.readReceipts
        receipts.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(MessagingV2UserChoices.PREF_READ_RECEIPTS, checked).apply()
            MessagingV2Options.applyChoices(readChoices(prefs))
        }
        container.findViewById<View>(R.id.mv2PrivacyReceiptsInfo).setOnClickListener {
            SettingsInfo.show(activity, text.get("mv2_ui_settings_read_receipts"), text.get("mv2_ui_settings_read_receipts_info"))
        }
        val value = container.findViewById<TextView>(R.id.mv2PrivacyAutoValue)
        value.text = text.get(AutoDownloadChoices.key(choices.autoDownloadMb))
        container.findViewById<View>(R.id.mv2PrivacyAutoRow).setOnClickListener {
            val current = AutoDownloadChoices.MEGABYTES.indexOf(AutoDownloadChoices.nearest(readChoices(prefs).autoDownloadMb))
            MaterialAlertDialogBuilder(activity)
                .setTitle(text.get("mv2_ui_settings_autodl"))
                .setSingleChoiceItems(AutoDownloadChoices.MEGABYTES.map { text.get(AutoDownloadChoices.key(it)) }.toTypedArray(), current) { dialog, which ->
                    val mb = AutoDownloadChoices.MEGABYTES[which]
                    prefs.edit().putInt(MessagingV2UserChoices.PREF_AUTO_DOWNLOAD_MB, mb).apply()
                    value.text = text.get(AutoDownloadChoices.key(mb))
                    MessagingV2Options.applyChoices(readChoices(prefs))
                    dialog.dismiss()
                }
                .show()
        }
        container.findViewById<View>(R.id.mv2PrivacyAutoInfo).setOnClickListener {
            SettingsInfo.show(activity, text.get("mv2_ui_settings_autodl"), text.get("mv2_ui_settings_autodl_info"))
        }
    }
}
