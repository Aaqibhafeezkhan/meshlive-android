package com.example.bluetoothtest

import android.os.Handler
import android.util.Log
import android.view.View
import android.widget.TextView

/**
 * Android side of the experimental viewer frame age (ViewerFrameAge.kt, default off behind
 * [ViewerFeatures.frameAge]): owns the monitor, a 250 ms UI tick while the Watch screen is open, the
 * stale-picture overlay, the small "Last update" label and the `viewer_frame_age` log. All methods run
 * on the main thread; only [monitor] is touched from receiver and decoder threads.
 *
 * What the viewer sees while it is on:
 * - Fresh picture: a small "Last update <1 s ago" label (bottom left). It counts on this phone from the
 *   last picture it presented; it is not a camera-to-screen delay, which no clock here can establish.
 * - Stale picture (no new picture for more than the threshold, or the parent link was lost and no
 *   picture of a newer link has been shown yet): the frozen picture is dimmed and a badge says
 *   "Signal lost - reconnecting" (link lost) or "Picture stalled - waiting" (link up, nothing arriving),
 *   with "Last picture N s ago". The Watch status chip says the same in two words instead of
 *   "Watching ...". The first fresh picture clears all of it.
 * The older centre overlay ("Connecting", "Recovering video", "Connection lost ...") is not drawn
 * while the badge is up; its message becomes the badge's last line, so two texts never overlap.
 */
internal class ViewerFrameAgeController(
    private val handler: Handler,
    private val views: Views,
    private val texts: Texts,
    private val isWatchScreen: () -> Boolean,
    private val isForeground: () -> Boolean,
    private val onStateChanged: () -> Unit,
    private val hideLegacyOverlay: () -> Unit,
    private val clockNs: () -> Long = System::nanoTime,
) {
    /** The views of the stale badge and label (activity_main.xml). */
    class Views(
        val scrim: View,
        val badge: View,
        val progress: View,
        val title: TextView,
        val detail: TextView,
        val note: TextView,
        val label: TextView,
    )

    /** Resource texts, injected so the controller itself needs no Context. */
    interface Texts {
        fun signalLost(): String
        fun pictureStalled(): String
        fun lastPicture(age: String): String
        fun lastUpdate(age: String): String
        fun chipSignalLost(): String
        fun chipStalled(): String
    }

    val monitor = ViewerFrameAgeMonitor()

    /** Whether the switch is on for this viewing; read when the Watch screen opens. */
    var enabled = false
        private set

    private var ticking = false

    /** On and the Watch screen is open (between [startViewing] and [stop]). */
    private val active: Boolean get() = enabled && ticking
    private var lastLogNs = 0L
    private var staleShown = false
    private var shownBadge = ViewerBadgeKind.NONE
    private var legacyNote = ""
    private var lastLabel = ""

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            if (!isWatchScreen()) {
                stop()
                return
            }
            refresh()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** The Watch screen opened (a fresh viewing, not a reconnect of the same one). */
    fun startViewing(enabled: Boolean) {
        stop()
        this.enabled = enabled
        if (!enabled) return
        monitor.startViewing(clockNs(), foreground = isForeground())
        lastLogNs = 0L
        ticking = true
        handler.postDelayed(tick, TICK_MS)
    }

    /** Leaves the Watch screen or turns the feature off: hides everything and forgets the tick. */
    fun stop() {
        ticking = false
        handler.removeCallbacks(tick)
        if (staleShown) {
            staleShown = false
            shownBadge = ViewerBadgeKind.NONE
            onStateChanged()
        }
        hideAll()
    }

    fun onLinkStarted() {
        if (!active) return
        monitor.linkStarted()
        refresh()
    }

    fun onLinkLost() {
        if (!active) return
        monitor.linkLost(clockNs())
        refresh()
    }

    /** A picture was presented (TextureView). Cheap unless it ends a stale state. */
    fun onFramePresented() {
        if (!active) return
        monitor.onFramePresented(clockNs())
        if (staleShown) refresh()
    }

    fun onPaused() {
        if (!active) return
        monitor.onPaused(clockNs())
        refresh()
    }

    fun onResumed() {
        if (!active) return
        monitor.onResumed(clockNs())
        refresh()
    }

    fun onEnded() {
        if (!active) return
        monitor.onEnded(clockNs())
        refresh()
    }

    fun onForeground(foreground: Boolean) {
        if (!active) return
        monitor.onForeground(foreground, clockNs())
        if (foreground) refresh()
    }

    /**
     * The older overlay wants to show [message] ("" is the corruption cover). While the stale badge is
     * up it is replaced by the badge, and the message becomes its last line. True: do not draw it.
     */
    fun interceptOverlay(message: String): Boolean {
        if (!active || !staleShown) return false
        if (message != legacyNote) {
            legacyNote = message
            renderNote()
        }
        return true
    }

    /** Two-word status for the Watch chip while stale (null otherwise): never "Watching" over a frozen picture. */
    fun chipText(): String? {
        if (!active || !staleShown) return null
        return if (shownBadge == ViewerBadgeKind.SIGNAL_LOST) texts.chipSignalLost() else texts.chipStalled()
    }

    /** Evaluates now, logs, and updates the views. */
    fun refresh() {
        if (!active) return
        val nowNs = clockNs()
        val logDue = lastLogNs == 0L || nowNs - lastLogNs >= LOG_INTERVAL_NS
        val snapshot = monitor.snapshot(nowNs, resetWindow = logDue)
        val prefix = if (BuildConfig.DEBUG) "BENCHMARK " else ""
        monitor.drainEvents().forEach { event -> Log.i("BluetoothVideo", prefix + event.logLine()) }
        if (logDue && isWatchScreen()) {
            lastLogNs = nowNs
            Log.i("BluetoothVideo", prefix + snapshot.logLine())
        }
        render(snapshot)
    }

    private fun render(snapshot: ViewerFrameAgeSnapshot) {
        val plan = ViewerStaleUiPlan.of(snapshot, isForeground())
        val nowStale = plan.stale
        val changed = nowStale != staleShown || plan.badge != shownBadge
        if (nowStale) {
            if (!staleShown) {
                hideLegacyOverlay()
                legacyNote = ""
            }
            setText(
                views.title,
                if (plan.badge == ViewerBadgeKind.SIGNAL_LOST) texts.signalLost() else texts.pictureStalled(),
            )
            setText(views.detail, texts.lastPicture(ViewerFrameAgeText.age(snapshot.sinceFrameMs)))
            renderNote()
            setVisible(views.scrim, true)
            setVisible(views.badge, true)
            setVisible(views.progress, true)
            setVisible(views.label, false)
        } else {
            setVisible(views.scrim, false)
            setVisible(views.badge, false)
            val showLabel = plan.showLabel
            if (showLabel) {
                val text = texts.lastUpdate(ViewerFrameAgeText.age(snapshot.sinceFrameMs))
                if (text != lastLabel) {
                    lastLabel = text
                    setText(views.label, text)
                }
            }
            setVisible(views.label, showLabel)
        }
        staleShown = nowStale
        shownBadge = plan.badge
        if (changed) onStateChanged()
    }

    private fun renderNote() {
        val note = legacyNote
        setText(views.note, note)
        setVisible(views.note, note.isNotBlank() && note != views.title.text.toString())
    }

    private fun hideAll() {
        setVisible(views.scrim, false)
        setVisible(views.badge, false)
        setVisible(views.label, false)
        legacyNote = ""
        lastLabel = ""
    }

    private fun setText(view: TextView, text: String) {
        if (view.text.toString() != text) view.text = text
    }

    private fun setVisible(view: View, visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (view.visibility != target) view.visibility = target
    }

    private companion object {
        const val TICK_MS = 250L
        const val LOG_INTERVAL_NS = 1_000_000_000L
    }
}
