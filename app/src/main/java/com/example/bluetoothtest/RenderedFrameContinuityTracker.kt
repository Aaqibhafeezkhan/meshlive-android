package com.example.bluetoothtest

import kotlin.math.max

data class RenderedFrameContinuitySnapshot(
    val renderedFrames: Long,
    val startupMs: Double?,
    val observedActiveMs: Double,
    val freezeEvents: Long,
    val freezeDurationMs: Double,
    val freezeRatioPercent: Double,
    val freezeThresholdMs: Double,
    val surfaceFrameInterval: LatencySnapshot,
    /** Longest gap between visible frames while active, including a gap still open (longest stall). */
    val longestGapMs: Double = 0.0,
    /**
     * How long before this snapshot the first frame reached the surface (null before one did). Each
     * periodic report carries it, so a device log that drops the one-off first-surface line (Oppo A15s
     * logs lost 80-90% of app lines, calls-noloss-s24a15s-1756e738) still dates the first surface.
     */
    val firstFrameAgoMs: Double? = null,
)

/** Measures gaps between frames that actually reach the receiver's TextureView. */
class RenderedFrameContinuityTracker(
    fps: Int,
    private val startupStartedAtNs: Long,
) {
    private val expectedFrameIntervalNs = 1_000_000_000L / fps.coerceAtLeast(1)
    private val freezeThresholdNs = max(MIN_FREEZE_NS, expectedFrameIntervalNs * 3)
    private var active = true
    private var firstFrameAtNs = 0L
    private var lastFrameAtNs = 0L
    private var renderedFrames = 0L
    private var observedActiveNs = 0L
    private var freezeEvents = 0L
    private var freezeDurationNs = 0L
    private var longestGapNs = 0L
    private val surfaceFrameInterval = RollingLatencyStats()

    @Synchronized
    fun recordFrame(renderedAtNs: Long) {
        if (!active) return
        if (firstFrameAtNs == 0L) firstFrameAtNs = renderedAtNs
        if (lastFrameAtNs > 0L && renderedAtNs > lastFrameAtNs) {
            val gapNs = renderedAtNs - lastFrameAtNs
            surfaceFrameInterval.recordNanos(gapNs)
            observedActiveNs += gapNs
            if (gapNs > longestGapNs) longestGapNs = gapNs
            if (gapNs > freezeThresholdNs) {
                freezeEvents++
                freezeDurationNs += gapNs - expectedFrameIntervalNs
            }
        }
        lastFrameAtNs = renderedAtNs
        renderedFrames++
    }

    @Synchronized
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        lastFrameAtNs = 0L
        surfaceFrameInterval.reset()
    }

    @Synchronized
    fun snapshot(nowNs: Long): RenderedFrameContinuitySnapshot {
        var activeNs = observedActiveNs
        var frozenNs = freezeDurationNs
        var events = freezeEvents
        var longestNs = longestGapNs
        if (active && lastFrameAtNs > 0L && nowNs > lastFrameAtNs) {
            val currentGapNs = nowNs - lastFrameAtNs
            activeNs += currentGapNs
            if (currentGapNs > longestNs) longestNs = currentGapNs
            if (currentGapNs > freezeThresholdNs) {
                events++
                frozenNs += currentGapNs - expectedFrameIntervalNs
            }
        }
        return RenderedFrameContinuitySnapshot(
            renderedFrames = renderedFrames,
            startupMs = firstFrameAtNs.takeIf { it > 0L }?.let {
                (it - startupStartedAtNs).coerceAtLeast(0L) / 1_000_000.0
            },
            observedActiveMs = activeNs / 1_000_000.0,
            freezeEvents = events,
            freezeDurationMs = frozenNs / 1_000_000.0,
            freezeRatioPercent = if (activeNs == 0L) 0.0 else frozenNs * 100.0 / activeNs,
            freezeThresholdMs = freezeThresholdNs / 1_000_000.0,
            surfaceFrameInterval = surfaceFrameInterval.snapshot(),
            longestGapMs = longestNs / 1_000_000.0,
            firstFrameAgoMs = firstFrameAtNs.takeIf { it > 0L }?.let {
                (nowNs - it).coerceAtLeast(0L) / 1_000_000.0
            },
        )
    }

    private companion object {
        const val MIN_FREEZE_NS = 250_000_000L
    }
}
