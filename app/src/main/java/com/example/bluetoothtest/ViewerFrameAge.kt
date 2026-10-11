package com.example.bluetoothtest

import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.max

/**
 * Viewer "is the picture still moving" state machine (PLAN "Visible age of the last received frame",
 * external review 2026-10-11: with A -> relay B -> viewer C, a frozen frame must never be presented as
 * live while B is away). Pure Kotlin: every time is a `System.nanoTime()` value handed in by the
 * caller, so each rule is JVM-tested. Default OFF behind [ViewerFeatures.frameAge]; with the switch
 * off nothing creates a monitor and the viewer behaves as before.
 *
 * What it knows, and what it does not:
 * - It knows when this phone last presented a picture (the TextureView notified a new frame), when the
 *   newest video arrived, and the source timestamps of both. Source timestamps (MediaCodec
 *   presentation times chosen by the sender) share no clock with this phone and the stream carries no
 *   wall-clock capture time, so the true capture-to-screen delay is unknowable here: the age is
 *   therefore "since this phone last received/presented", never an end-to-end latency. The one
 *   clock-free quantity is [ViewerFrameAgeSnapshot.behindReceivedMs]: both timestamps come from the
 *   sender, so their difference is how far the picture on screen trails the newest video this phone
 *   holds (playout delay plus decoder queue). Add the time since that newest video arrived and the
 *   result ([ViewerFrameAgeSnapshot.frameAgeMs]) is a lower bound of the picture's real age: it
 *   excludes everything the sender and relays still hold.
 * - A picture is stale when no new frame was presented for [ViewerFrameAgeConfig.thresholdMs], or
 *   when the parent link was lost and no frame of a newer link has been presented yet.
 *
 * Threshold: the larger of 1.5 s and three frame intervals. Three intervals alone (200 ms at 15 FPS)
 * would flash on ordinary Bluetooth bursts: the adaptive playout delay deliberately absorbs 0.6-2.5 s
 * (PlaybackDelayProfile) and measured scene-cut bursts take 0.65-1.3 s to cross a GATT link
 * (regress-57484598), so a gap under 1.5 s is the app working as designed and must not flash a
 * warning. Beyond 1.5 s the picture is visibly frozen, and longer stalls (M31 hardware VP9 scene cuts
 * freeze 2-3 s on GATT) are honest stalls, which is why the wording for a stall with the link up
 * ("Picture stalled") differs from a lost link ("Signal lost"). The frame-interval term only matters
 * for very slow streams (one frame per second or less, where 3 intervals exceed 1.5 s).
 */
data class ViewerFrameAgeConfig(
    val minStaleMs: Long = MIN_STALE_MS,
    val staleFrameIntervals: Int = STALE_FRAME_INTERVALS,
) {
    init {
        require(minStaleMs > 0) { "The stale floor must be positive" }
        require(staleFrameIntervals >= 1) { "At least one frame interval" }
    }

    /** No new picture for longer than this is stale. */
    fun thresholdMs(frameIntervalMs: Long): Long = max(minStaleMs, staleFrameIntervals * frameIntervalMs)

    companion object {
        const val MIN_STALE_MS = 1_500L
        const val STALE_FRAME_INTERVALS = 3
    }
}

enum class ViewerPictureState(val label: String) {
    /** Nothing has been shown yet in this viewing: the existing "Connecting" text applies. */
    WAITING("waiting"),
    LIVE("live"),
    STALE("stale"),

    /** The broadcaster paused: silence is expected, never stale. */
    PAUSED("paused"),
    ENDED("ended"),
}

enum class ViewerStaleCause(val label: String) {
    NONE("none"),

    /** The link is up (or not known to be down) but no new picture was presented. */
    NO_FRAMES("no_frames"),

    /** The parent link ended unexpectedly and no picture of a newer link has been presented. */
    LINK_LOST("link_lost"),
}

enum class ViewerStaleClearReason(val label: String) {
    FRAME("frame"),
    PAUSED("paused"),
    ENDED("ended"),
    RESET("reset"),
}

sealed class ViewerFrameAgeEvent {
    abstract val linkEpoch: Int

    /** `viewer_link` and `viewer_stale` log lines (without the BENCHMARK prefix). */
    abstract fun logLine(): String

    data class LinkStarted(override val linkEpoch: Int) : ViewerFrameAgeEvent() {
        override fun logLine() = "viewer_link: event=start | linkEpoch=$linkEpoch"
    }

    /** The link's playout clock started at [delayMs]: every link must start at the base delay, not at the last one's. */
    data class PlayoutBase(override val linkEpoch: Int, val delayMs: Long) : ViewerFrameAgeEvent() {
        override fun logLine() = "viewer_link: event=playout_base | linkEpoch=$linkEpoch | playoutDelayMs=$delayMs"
    }

    data class LinkLost(override val linkEpoch: Int, val sinceFrameMs: Long) : ViewerFrameAgeEvent() {
        override fun logLine() = "viewer_link: event=lost | linkEpoch=$linkEpoch | sinceFrameMs=$sinceFrameMs"
    }

    /** A notice from a newer link (paused/ended) or a presented frame proves the replacement link is up. */
    data class LinkRestored(override val linkEpoch: Int, val lostForMs: Long) : ViewerFrameAgeEvent() {
        override fun logLine() = "viewer_link: event=restored | linkEpoch=$linkEpoch | lostForMs=$lostForMs"
    }

    data class StaleEntered(
        val cause: ViewerStaleCause,
        val sinceFrameMs: Long,
        val thresholdMs: Long,
        override val linkEpoch: Int,
        val episode: Int,
    ) : ViewerFrameAgeEvent() {
        override fun logLine() =
            "viewer_stale: event=enter | cause=${cause.label} | sinceFrameMs=$sinceFrameMs | thresholdMs=$thresholdMs | " +
                "linkEpoch=$linkEpoch | episode=$episode"
    }

    data class StaleCauseChanged(
        val cause: ViewerStaleCause,
        val sinceFrameMs: Long,
        override val linkEpoch: Int,
    ) : ViewerFrameAgeEvent() {
        override fun logLine() =
            "viewer_stale: event=cause | cause=${cause.label} | sinceFrameMs=$sinceFrameMs | linkEpoch=$linkEpoch"
    }

    /**
     * [staleForMs] is the time shown as stale; [gapMs] is the whole freeze, from the last picture
     * before it to the first fresh one (-1 when the clear was not a picture).
     */
    data class StaleCleared(
        val reason: ViewerStaleClearReason,
        val fromCause: ViewerStaleCause,
        val staleForMs: Long,
        val gapMs: Long,
        override val linkEpoch: Int,
        val recoveries: Int,
    ) : ViewerFrameAgeEvent() {
        override fun logLine() =
            "viewer_stale: event=clear | reason=${reason.label} | fromCause=${fromCause.label} | staleForMs=$staleForMs | " +
                "gapMs=$gapMs | linkEpoch=$linkEpoch | recoveries=$recoveries"
    }
}

/** One evaluation of the viewer's picture. -1 means unknown. */
data class ViewerFrameAgeSnapshot(
    val state: ViewerPictureState,
    val cause: ViewerStaleCause,
    /** Time since this phone last presented a picture. */
    val sinceFrameMs: Long,
    /** How long the current stale episode has lasted (0 when not stale). */
    val staleForMs: Long,
    /** Lower bound of the picture's age: [behindReceivedMs] plus the time since the newest video arrived. */
    val frameAgeMs: Long,
    /** Newest received video timestamp minus the presented one (both from the sender's clock). */
    val behindReceivedMs: Long,
    val playoutDelayMs: Long,
    /** Longest gap between presented pictures since the last window reset (open gap included). */
    val maxGapMs: Long,
    val thresholdMs: Long,
    val linkEpoch: Int,
    val staleEpisodes: Int,
    val recoveries: Int,
) {
    val stale: Boolean get() = state == ViewerPictureState.STALE

    /** The once-per-second `viewer_frame_age` log line (without the BENCHMARK prefix). */
    fun logLine(): String =
        "viewer_frame_age: state=${state.label} | cause=${cause.label} | sinceFrameMs=$sinceFrameMs | " +
            "frameAgeMs=$frameAgeMs | behindReceivedMs=$behindReceivedMs | playoutDelayMs=$playoutDelayMs | " +
            "maxGapMs=$maxGapMs | thresholdMs=$thresholdMs | staleEpisodes=$staleEpisodes | " +
            "recoveries=$recoveries | linkEpoch=$linkEpoch"
}

/** A bounded event list: the log ticker drains it every few hundred milliseconds, and nothing grows without it. */
private class EventLog {
    private val items = ArrayDeque<ViewerFrameAgeEvent>()

    operator fun plusAssign(event: ViewerFrameAgeEvent) {
        if (items.size >= MAX_EVENTS) items.removeFirst()
        items.addLast(event)
    }

    fun clear() = items.clear()

    fun drain(): List<ViewerFrameAgeEvent> = items.toList().also { items.clear() }

    private companion object {
        const val MAX_EVENTS = 256
    }
}

class ViewerFrameAgeMonitor(private val config: ViewerFrameAgeConfig = ViewerFrameAgeConfig()) {
    private val events = EventLog()

    private var frameIntervalMs = DEFAULT_FRAME_INTERVAL_MS
    private var lastFrameAtNs = 0L
    private var everPresented = false

    /** Stall counting starts no earlier than this (resume after a pause, return to the foreground). */
    private var referenceFloorNs = 0L
    private var linkEpoch = 0

    /** The [linkEpoch] that was lost unexpectedly and has no newer link proven yet. */
    private var lossEpoch = NO_LOSS
    private var lossAtNs = 0L
    private var paused = false
    private var ended = false
    private var foreground = true

    private var stale = false
    private var staleCause = ViewerStaleCause.NONE
    private var staleSinceNs = 0L
    private var staleEpisodes = 0
    private var recoveries = 0
    private var windowMaxGapNs = 0L

    private var newestReceivedPtsUs = UNKNOWN
    private var newestReceivedAtNs = 0L
    private var presentedPtsUs = UNKNOWN
    private var playoutDelayMs = UNKNOWN

    /** A new viewing (the Watch screen opened): forget everything. Counters restart too. */
    @Synchronized
    fun startViewing(nowNs: Long, foreground: Boolean = true) {
        events.clear()
        frameIntervalMs = DEFAULT_FRAME_INTERVAL_MS
        lastFrameAtNs = nowNs
        everPresented = false
        referenceFloorNs = nowNs
        linkEpoch = 0
        lossEpoch = NO_LOSS
        lossAtNs = 0L
        paused = false
        ended = false
        this.foreground = foreground
        stale = false
        staleCause = ViewerStaleCause.NONE
        staleSinceNs = 0L
        staleEpisodes = 0
        recoveries = 0
        windowMaxGapNs = 0L
        newestReceivedPtsUs = UNKNOWN
        newestReceivedAtNs = 0L
        presentedPtsUs = UNKNOWN
        playoutDelayMs = UNKNOWN
    }

    /** From the stream header; a slow stream raises the threshold above the floor. */
    @Synchronized
    fun setFrameRate(fps: Int) {
        if (fps > 0) frameIntervalMs = 1_000L / fps
    }

    /** A receiver (a link attempt) started: pictures it presents may clear a loss of an older link. */
    @Synchronized
    fun linkStarted() {
        linkEpoch++
        newestReceivedPtsUs = UNKNOWN
        newestReceivedAtNs = 0L
        presentedPtsUs = UNKNOWN
        playoutDelayMs = UNKNOWN
        events += ViewerFrameAgeEvent.LinkStarted(linkEpoch)
    }

    /** The link's playout clock was created at [delayMs] (the profile's base delay, never the last link's). */
    @Synchronized
    fun playoutClockStarted(delayMs: Long) {
        playoutDelayMs = delayMs
        events += ViewerFrameAgeEvent.PlayoutBase(linkEpoch, delayMs)
    }

    @Synchronized
    fun onPlayoutDelay(delayMs: Long) {
        playoutDelayMs = delayMs
    }

    /** The parent link ended unexpectedly (not an explicit end, not a user exit). */
    @Synchronized
    fun linkLost(nowNs: Long) {
        if (ended || lossEpoch == linkEpoch) return
        lossEpoch = linkEpoch
        lossAtNs = nowNs
        events += ViewerFrameAgeEvent.LinkLost(linkEpoch, if (everPresented) msSince(lastFrameAtNs, nowNs) else UNKNOWN)
        reconcile(nowNs, UNKNOWN)
    }

    /** A video frame (not codec config) arrived from the parent. Call it before handing it to the decoder. */
    @Synchronized
    fun onVideoReceived(presentationTimeUs: Long, nowNs: Long) {
        if (presentationTimeUs > newestReceivedPtsUs) newestReceivedPtsUs = presentationTimeUs
        newestReceivedAtNs = nowNs
    }

    /** The decoder rendered the frame with this source timestamp to the surface. */
    @Synchronized
    fun onFrameRendered(presentationTimeUs: Long) {
        presentedPtsUs = presentationTimeUs
    }

    /** The TextureView reported a new picture. */
    @Synchronized
    fun onFramePresented(nowNs: Long) {
        val gapNs = if (everPresented) stalledNs(nowNs) else 0L
        windowMaxGapNs = max(windowMaxGapNs, gapNs)
        lastFrameAtNs = nowNs
        everPresented = true
        restoreLinkIfNewer(nowNs)
        reconcile(nowNs, gapNs / NANOS_PER_MS, ViewerStaleClearReason.FRAME)
    }

    /** The broadcaster paused: silence is expected until [onResumed]. */
    @Synchronized
    fun onPaused(nowNs: Long) {
        paused = true
        restoreLinkIfNewer(nowNs)
        reconcile(nowNs, UNKNOWN)
    }

    @Synchronized
    fun onResumed(nowNs: Long) {
        // A "live" notice is not proof of a working replacement link (a sender may repeat it on every
        // join); only a presented picture, or a paused/ended notice, ends a loss.
        paused = false
        referenceFloorNs = nowNs
        reconcile(nowNs, UNKNOWN)
    }

    @Synchronized
    fun onEnded(nowNs: Long) {
        ended = true
        reconcile(nowNs, UNKNOWN)
    }

    /** The app left or returned to the foreground. TextureView notifications stop while it is not drawn. */
    @Synchronized
    fun onForeground(foreground: Boolean, nowNs: Long) {
        if (this.foreground == foreground) return
        this.foreground = foreground
        if (foreground) {
            referenceFloorNs = nowNs
            reconcile(nowNs, UNKNOWN)
        }
    }

    /** Evaluates at [nowNs]. [resetWindow] starts a new [ViewerFrameAgeSnapshot.maxGapMs] window. */
    @Synchronized
    fun snapshot(nowNs: Long, resetWindow: Boolean = false): ViewerFrameAgeSnapshot {
        reconcile(nowNs, UNKNOWN)
        val (state, cause) = evaluate(nowNs)
        val behind = if (newestReceivedPtsUs != UNKNOWN && presentedPtsUs != UNKNOWN) {
            ((newestReceivedPtsUs - presentedPtsUs) / 1_000L).coerceIn(0L, MAX_BEHIND_MS)
        } else {
            UNKNOWN
        }
        val frameAge = if (behind != UNKNOWN && newestReceivedAtNs != 0L) {
            behind + max(0L, msSince(newestReceivedAtNs, nowNs))
        } else {
            UNKNOWN
        }
        // A deliberate pause, a background period or "nothing shown yet" is not a freeze.
        val counting = everPresented && foreground && !paused && !ended
        val maxGapMs = max(windowMaxGapNs, if (counting) stalledNs(nowNs) else 0L) / NANOS_PER_MS
        val snapshot = ViewerFrameAgeSnapshot(
            state = state,
            cause = cause,
            sinceFrameMs = if (everPresented) msSince(lastFrameAtNs, nowNs) else UNKNOWN,
            staleForMs = if (stale) msSince(staleSinceNs, nowNs) else 0L,
            frameAgeMs = frameAge,
            behindReceivedMs = behind,
            playoutDelayMs = playoutDelayMs,
            maxGapMs = maxGapMs,
            thresholdMs = thresholdMs(),
            linkEpoch = linkEpoch,
            staleEpisodes = staleEpisodes,
            recoveries = recoveries,
        )
        if (resetWindow) windowMaxGapNs = 0L
        return snapshot
    }

    /** Takes the events recorded since the last call, oldest first. */
    @Synchronized
    fun drainEvents(): List<ViewerFrameAgeEvent> = events.drain()

    private fun thresholdMs(): Long = config.thresholdMs(frameIntervalMs)

    private fun restoreLinkIfNewer(nowNs: Long) {
        if (lossEpoch != NO_LOSS && linkEpoch > lossEpoch) {
            lossEpoch = NO_LOSS
            events += ViewerFrameAgeEvent.LinkRestored(linkEpoch, msSince(lossAtNs, nowNs))
        }
    }

    private fun evaluate(nowNs: Long): Pair<ViewerPictureState, ViewerStaleCause> {
        if (ended) return ViewerPictureState.ENDED to ViewerStaleCause.NONE
        // Nothing has been shown, so nothing frozen can be mistaken for live.
        if (!everPresented) return ViewerPictureState.WAITING to ViewerStaleCause.NONE
        if (lossEpoch != NO_LOSS) return ViewerPictureState.STALE to ViewerStaleCause.LINK_LOST
        if (paused) return ViewerPictureState.PAUSED to ViewerStaleCause.NONE
        // Not drawn in the background: no notification can arrive, so no verdict either way.
        if (!foreground) {
            return if (stale) ViewerPictureState.STALE to staleCause else ViewerPictureState.LIVE to ViewerStaleCause.NONE
        }
        return if (stalledNs(nowNs) / NANOS_PER_MS > thresholdMs()) {
            ViewerPictureState.STALE to ViewerStaleCause.NO_FRAMES
        } else {
            ViewerPictureState.LIVE to ViewerStaleCause.NONE
        }
    }

    /** Moves the tracked episode to the evaluated state and records the transition. */
    private fun reconcile(
        nowNs: Long,
        gapMs: Long,
        clearReason: ViewerStaleClearReason? = null,
    ) {
        val (state, cause) = evaluate(nowNs)
        if (state == ViewerPictureState.STALE) {
            val sinceFrameMs = if (everPresented) msSince(lastFrameAtNs, nowNs) else UNKNOWN
            if (!stale) {
                stale = true
                staleCause = cause
                staleSinceNs = nowNs
                staleEpisodes++
                events += ViewerFrameAgeEvent.StaleEntered(cause, sinceFrameMs, thresholdMs(), linkEpoch, staleEpisodes)
            } else if (staleCause != cause) {
                staleCause = cause
                events += ViewerFrameAgeEvent.StaleCauseChanged(cause, sinceFrameMs, linkEpoch)
            }
        } else if (stale) {
            val reason = when {
                state == ViewerPictureState.ENDED -> ViewerStaleClearReason.ENDED
                state == ViewerPictureState.PAUSED -> ViewerStaleClearReason.PAUSED
                clearReason == ViewerStaleClearReason.FRAME -> ViewerStaleClearReason.FRAME
                else -> ViewerStaleClearReason.RESET
            }
            if (reason == ViewerStaleClearReason.FRAME) recoveries++
            events += ViewerFrameAgeEvent.StaleCleared(
                reason,
                staleCause,
                msSince(staleSinceNs, nowNs),
                if (reason == ViewerStaleClearReason.FRAME) gapMs else UNKNOWN,
                linkEpoch,
                recoveries,
            )
            stale = false
            staleCause = ViewerStaleCause.NONE
            staleSinceNs = 0L
        }
    }

    /** Time since the last presented picture, counted no earlier than the last resume or return to the foreground. */
    private fun stalledNs(nowNs: Long): Long = (nowNs - max(lastFrameAtNs, referenceFloorNs)).coerceAtLeast(0L)

    private fun msSince(thenNs: Long, nowNs: Long): Long = (nowNs - thenNs).coerceAtLeast(0L) / NANOS_PER_MS

    companion object {
        const val UNKNOWN = -1L
        private const val NO_LOSS = Int.MIN_VALUE
        private const val DEFAULT_FRAME_INTERVAL_MS = 67L
        private const val MAX_BEHIND_MS = 60_000L
        private const val NANOS_PER_MS = 1_000_000L
    }
}

enum class ViewerBadgeKind {
    NONE,

    /** "Signal lost - reconnecting": the parent link was lost. */
    SIGNAL_LOST,

    /** "Picture stalled - waiting": the link is up but no new picture arrives. */
    PICTURE_STALLED,
}

/**
 * What the Watch screen draws for one snapshot; the controller only applies it. One rule in one tested
 * place: a stale picture gets the dim and the badge and never the "Last update" label or a Watching
 * status, and a fresh picture gets the label only while the app is on screen.
 */
data class ViewerStaleUiPlan(val badge: ViewerBadgeKind, val showLabel: Boolean) {
    val stale: Boolean get() = badge != ViewerBadgeKind.NONE

    companion object {
        fun of(snapshot: ViewerFrameAgeSnapshot, foreground: Boolean): ViewerStaleUiPlan = when {
            snapshot.state == ViewerPictureState.STALE -> ViewerStaleUiPlan(
                if (snapshot.cause == ViewerStaleCause.LINK_LOST) ViewerBadgeKind.SIGNAL_LOST else ViewerBadgeKind.PICTURE_STALLED,
                showLabel = false,
            )
            snapshot.state == ViewerPictureState.LIVE && foreground -> ViewerStaleUiPlan(ViewerBadgeKind.NONE, showLabel = true)
            else -> ViewerStaleUiPlan(ViewerBadgeKind.NONE, showLabel = false)
        }
    }
}

/** On-screen wording of ages; the strings around them are resources. */
internal object ViewerFrameAgeText {
    /** "<1 s", "3.2 s", "1 min 05 s". Rounded down to a tenth of a second so a rising age never reads ahead. */
    fun age(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        return when {
            safe < 1_000L -> "<1 s"
            safe < 60_000L -> String.format(Locale.US, "%.1f s", (safe / 100) / 10.0)
            else -> String.format(Locale.US, "%d min %02d s", safe / 60_000L, safe / 1_000L % 60L)
        }
    }
}
