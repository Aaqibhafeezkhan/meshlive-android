package com.example.bluetoothtest

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Press and hold to talk while muted: after [holdMs] the press becomes "talking" until release,
 * and the click that would toggle Mute is swallowed. A short tap stays a normal click. The rules live
 * in [HoldToTalkCore] (unit-tested); this maps touch events and a Handler onto them.
 */
internal class HoldToTalkGesture(
    canStart: () -> Boolean,
    onTalk: (Boolean) -> Unit,
    holdMs: Long = HoldToTalkCore.DEFAULT_HOLD_MS,
) : View.OnTouchListener {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private val core = HoldToTalkCore(
        object : HoldToTalkCore.Scheduler {
            override fun schedule(delayMs: Long, action: () -> Unit) {
                val task = Runnable { pending = null; action() }
                pending = task
                handler.postDelayed(task, delayMs)
            }

            override fun cancel() {
                pending?.let(handler::removeCallbacks)
                pending = null
            }
        },
        canStart,
        onTalk,
        holdMs,
    )

    val talking: Boolean get() = core.talking

    /** Screen-reader activation: starts talking, or stops it when already talking. */
    fun toggleLatched(): Boolean = core.toggleLatched()

    /** Ends talking from outside the touch stream (activity paused); the call hears the mute. */
    fun release() = core.release()

    /** The call this hold belonged to is gone: forget it silently. */
    fun reset() = core.reset()

    override fun onTouch(view: View, event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> core.down()
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (core.up()) {
                    view.isPressed = false
                    return true // the hold was the action; no Mute toggle
                }
            }
        }
        return false
    }
}

/** One row of the live-stream and contact lists ([R.layout.item_list_row]). */
internal data class ListRow(
    val title: String,
    val subtitle: String,
    val iconRes: Int,
    val iconTintRes: Int = R.color.text_primary,
    /** Solid avatar colour (identity colour); null keeps the neutral raised circle. */
    val avatarColor: Int? = null,
    /** Shows a verified check after the title; MISMATCH shows a short warning pill. */
    val badge: ContactVerification? = null,
    /** Visual only (dimmed); every row stays tappable so list behaviour is unchanged. */
    val enabled: Boolean = true,
    val contentDescription: String? = null,
    /** A contact's initial, drawn in the circle instead of the icon. */
    val initial: String? = null,
    /** Red LIVE badge on a stream row. */
    val live: Boolean = false,
    /** Trailing chevron (hidden for rows that do nothing on tap). */
    val chevron: Boolean = true,
)

internal class ListRowAdapter(private val context: Context, private val rows: List<ListRow>) : BaseAdapter() {
    override fun getCount(): Int = rows.size
    override fun getItem(position: Int): ListRow = rows[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_list_row, parent, false)
        val row = rows[position]
        view.findViewById<TextView>(R.id.rowTitle).text = row.title
        view.findViewById<TextView>(R.id.rowSubtitle).apply {
            text = row.subtitle
            visibility = if (row.subtitle.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<ImageView>(R.id.rowIcon).apply {
            // iconRes 0 with no initial: no leading circle at all (the Live list shows icons on radio rows only).
            // this.parent: the icon's 44 dp frame (plain `parent` here is getView's ListView argument).
            (this.parent as View).visibility = if (row.iconRes == 0 && row.initial == null) View.GONE else View.VISIBLE
            setImageResource(row.iconRes)
            // Plain grey icon like the Settings rows (2026-10-17); only a coloured avatar keeps its circle.
            if (row.avatarColor == null) background = null else setBackgroundResource(R.drawable.icon_circle_background)
            if (row.avatarColor != null) {
                backgroundTintList = ColorStateList.valueOf(row.avatarColor)
                imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            } else {
                backgroundTintList = null
                imageTintList = ContextCompat.getColorStateList(context, row.iconTintRes)
            }
            visibility = if (row.initial == null) View.VISIBLE else View.INVISIBLE
        }
        view.findViewById<TextView>(R.id.rowInitial).apply {
            text = row.initial.orEmpty()
            visibility = if (row.initial == null) View.GONE else View.VISIBLE
        }
        bindVerificationBadge(view.findViewById(R.id.rowBadge), row.badge, view.findViewById(R.id.rowVerified))
        view.findViewById<View>(R.id.rowLiveBadge).visibility = if (row.live) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.rowAction).visibility = if (row.chevron) View.VISIBLE else View.GONE
        view.alpha = if (row.enabled) 1f else 0.55f
        view.contentDescription = row.contentDescription ?: "${row.title}, ${row.subtitle}"
        return view
    }
}

/**
 * Verified shows only a green check beside the name ([check]); a MISMATCH shows a short warning
 * pill in [badge]. UNVERIFIED stays quiet (hidden) in lists unless [showUnverified].
 */
internal fun bindVerificationBadge(
    badge: TextView,
    state: ContactVerification?,
    check: View? = null,
    showUnverified: Boolean = false,
) {
    val context = badge.context
    check?.visibility = if (state == ContactVerification.VERIFIED) View.VISIBLE else View.GONE
    if (state == null || state == ContactVerification.VERIFIED ||
        (state == ContactVerification.UNVERIFIED && !showUnverified)
    ) {
        badge.visibility = View.GONE
        return
    }
    val (text, icon) = when (state) {
        ContactVerification.UNVERIFIED -> R.string.verification_unverified to R.drawable.ic_lock
        else -> R.string.verification_mismatch to R.drawable.ic_warning
    }
    badge.setText(text)
    badge.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
    badge.backgroundTintList = ContextCompat.getColorStateList(context, R.color.surface_raised)
    badge.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
    badge.compoundDrawableTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_primary))
    badge.visibility = View.VISIBLE
}

/**
 * Fades the call's top and bottom bars out after [hideAfterMs] while video plays, and back in on a
 * tap. Only alpha/visibility change (INVISIBLE keeps layout, so the picture-in-picture area and the
 * video surface never move). Never hides while TalkBack is on, or while [enabled] is false.
 */
internal class CallChromeAutoHide(
    private val views: () -> List<View>,
    private val hideAfterMs: Long = 4_000L,
    /** Whether the call details overlay is showing, and a toggle for it (the info button). */
    private val detailsShown: () -> Boolean = { false },
    private val toggleDetails: () -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private val hidden = mutableSetOf<View>()
    private val hideRunnable = Runnable { hideNow() }
    var enabled = false
        set(value) {
            field = value
            show()
        }

    /** Shows the bars and restarts the hide timer. */
    fun show() {
        handler.removeCallbacks(hideRunnable)
        hidden.forEach { view ->
            view.animate().cancel()
            if (view.visibility == View.INVISIBLE) view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(FADE_MS).start()
        }
        hidden.clear()
        views().forEach { if (it.alpha < 1f && it.visibility == View.VISIBLE) it.alpha = 1f }
        if (enabled) handler.postDelayed(hideRunnable, hideAfterMs)
    }

    /**
     * A tap on the video cycles three modes while video plays: controls -> controls with call
     * details -> everything hidden (pocket use) -> controls. Before media it only shows the bars.
     */
    fun toggle() {
        when {
            !enabled || hidden.isNotEmpty() -> show()
            !detailsShown() -> {
                toggleDetails()
                show()
            }
            else -> {
                toggleDetails()
                hideNow()
            }
        }
    }

    fun reset() {
        handler.removeCallbacks(hideRunnable)
        enabled = false
    }

    /** Activity teardown: drop the pending hide and any running fades. */
    fun release() {
        handler.removeCallbacks(hideRunnable)
        views().forEach { it.animate().cancel() }
        hidden.clear()
    }

    private fun hideNow() {
        if (!enabled) return
        val context = views().firstOrNull()?.context ?: return
        val accessibility = context.getSystemService(AccessibilityManager::class.java)
        if (accessibility?.isTouchExplorationEnabled == true) return
        views().filter { it.visibility == View.VISIBLE }.forEach { view ->
            hidden += view
            view.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                if (view in hidden) view.visibility = View.INVISIBLE
            }.start()
        }
    }

    private companion object {
        const val FADE_MS = 220L
    }
}
