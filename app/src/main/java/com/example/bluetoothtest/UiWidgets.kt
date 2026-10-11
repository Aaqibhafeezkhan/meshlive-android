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

/** One row of the people or live-video lists ([R.layout.item_list_row]). */
internal data class ListRow(
    val title: String,
    val subtitle: String,
    val iconRes: Int,
    val iconTintRes: Int = R.color.accent,
    /** Solid avatar colour (identity colour); null keeps the neutral accent circle. */
    val avatarColor: Int? = null,
    val badge: ContactVerification? = null,
    /** Visual only (dimmed); every row stays tappable so list behaviour is unchanged. */
    val enabled: Boolean = true,
    val contentDescription: String? = null,
)

internal class ListRowAdapter(private val context: Context, private val rows: List<ListRow>) : BaseAdapter() {
    override fun getCount(): Int = rows.size
    override fun getItem(position: Int): ListRow = rows[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_list_row, parent, false)
        val row = rows[position]
        view.findViewById<TextView>(R.id.rowTitle).text = row.title
        view.findViewById<TextView>(R.id.rowSubtitle).text = row.subtitle
        view.findViewById<ImageView>(R.id.rowIcon).apply {
            setImageResource(row.iconRes)
            if (row.avatarColor != null) {
                backgroundTintList = ColorStateList.valueOf(row.avatarColor)
                imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            } else {
                backgroundTintList = null
                imageTintList = ContextCompat.getColorStateList(context, row.iconTintRes)
            }
        }
        bindVerificationBadge(view.findViewById(R.id.rowBadge), row.badge)
        view.alpha = if (row.enabled) 1f else 0.55f
        view.contentDescription = row.contentDescription ?: "${row.title}, ${row.subtitle}"
        return view
    }
}

/** Verified / not verified / MISMATCH pill. UNVERIFIED stays quiet (hidden) in lists unless [showUnverified]. */
internal fun bindVerificationBadge(badge: TextView, state: ContactVerification?, showUnverified: Boolean = false) {
    val context = badge.context
    if (state == null || (state == ContactVerification.UNVERIFIED && !showUnverified)) {
        badge.visibility = View.GONE
        return
    }
    val (text, fg, bg, icon) = when (state) {
        ContactVerification.VERIFIED ->
            Quad(R.string.verification_verified, R.color.success, R.color.success_container, R.drawable.ic_verified_user)
        ContactVerification.UNVERIFIED ->
            Quad(R.string.verification_unverified, R.color.text_secondary, R.color.surface_variant, R.drawable.ic_lock)
        ContactVerification.MISMATCH ->
            Quad(R.string.verification_mismatch, R.color.white, R.color.mismatch_red, R.drawable.ic_warning)
    }
    badge.setText(text)
    badge.setTextColor(ContextCompat.getColor(context, fg))
    badge.backgroundTintList = ContextCompat.getColorStateList(context, bg)
    badge.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
    badge.compoundDrawableTintList = ColorStateList.valueOf(ContextCompat.getColor(context, fg))
    badge.visibility = View.VISIBLE
}

private data class Quad(val text: Int, val fg: Int, val bg: Int, val icon: Int)

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
