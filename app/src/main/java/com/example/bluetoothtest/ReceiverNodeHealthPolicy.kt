package com.example.bluetoothtest

import kotlin.math.max

/**
 * Whether a forwarding viewer's own playback became unstable after it took a child (then the child is detached).
 * Experimental [baselineP95] (DebugTunables.receiverNodeBaselineP95, default off, 2026-10-17): the healthy p95 also
 * allows 1.35x the node's own pre-child p95, not only its p50. At 15 FPS the fixed limit is 116.7 ms (1.75 periods);
 * the S22 relay already received at p95 ~133 ms before its child joined and detached that child after ~14 s
 * (lane15 chain5), so the guard fired on the node's normal cadence rather than on a regression the child caused.
 */
class ReceiverNodeHealthPolicy(
    private val fps: Int,
    private val baselineP95: Boolean = DebugTunables.receiverNodeBaselineP95,
) {
    private val expectedFrameIntervalMs = 1_000.0 / fps.coerceAtLeast(1)
    val minimumSamples = (fps.coerceAtLeast(1) * OBSERVATION_SECONDS)
        .coerceAtMost(MAXIMUM_REQUIRED_SAMPLES)
    private var baselineFrameIntervalMs = expectedFrameIntervalMs
    private var baselineP95Ms = 0.0
    private var consecutiveUnstableObservations = 0
    private var consecutiveAudioLossObservations = 0
    private var lastAudioDroppedPacketsSinceChild = 0L

    @Synchronized
    fun childConnected(baseline: LatencySnapshot) {
        baselineFrameIntervalMs = if (baseline.samples >= fps.coerceAtLeast(1)) {
            max(expectedFrameIntervalMs, baseline.p50Ms)
        } else {
            expectedFrameIntervalMs
        }
        baselineP95Ms = if (baselineP95 && baseline.samples >= fps.coerceAtLeast(1)) baseline.p95Ms else 0.0
        consecutiveUnstableObservations = 0
        consecutiveAudioLossObservations = 0
        lastAudioDroppedPacketsSinceChild = 0L
    }

    @Synchronized
    fun childDisconnected() {
        baselineFrameIntervalMs = expectedFrameIntervalMs
        baselineP95Ms = 0.0
        consecutiveUnstableObservations = 0
        consecutiveAudioLossObservations = 0
        lastAudioDroppedPacketsSinceChild = 0L
    }

    @Synchronized
    fun shouldDetachChild(
        cadence: LatencySnapshot?,
        audioDroppedPacketsSinceChild: Long = 0L,
    ): Boolean {
        val audioLossContinued =
            audioDroppedPacketsSinceChild > lastAudioDroppedPacketsSinceChild
        lastAudioDroppedPacketsSinceChild = audioDroppedPacketsSinceChild
        consecutiveAudioLossObservations = if (audioLossContinued) {
            consecutiveAudioLossObservations + 1
        } else 0
        if (cadence == null || cadence.samples < minimumSamples) {
            return consecutiveAudioLossObservations >= REQUIRED_UNSTABLE_OBSERVATIONS
        }
        val maximumHealthyP95Ms = max(
            max(
                expectedFrameIntervalMs * MAXIMUM_FRAME_INTERVAL_FACTOR,
                baselineFrameIntervalMs * MAXIMUM_BASELINE_REGRESSION_FACTOR,
            ),
            baselineP95Ms * MAXIMUM_BASELINE_REGRESSION_FACTOR,
        )
        if (cadence.p95Ms > maximumHealthyP95Ms) {
            consecutiveUnstableObservations++
        } else {
            consecutiveUnstableObservations = 0
        }
        return consecutiveUnstableObservations >= REQUIRED_UNSTABLE_OBSERVATIONS ||
            consecutiveAudioLossObservations >= REQUIRED_UNSTABLE_OBSERVATIONS
    }

    private companion object {
        const val OBSERVATION_SECONDS = 3
        const val MAXIMUM_REQUIRED_SAMPLES = 180
        const val MAXIMUM_FRAME_INTERVAL_FACTOR = 1.75
        const val MAXIMUM_BASELINE_REGRESSION_FACTOR = 1.35
        const val REQUIRED_UNSTABLE_OBSERVATIONS = 3
    }
}
