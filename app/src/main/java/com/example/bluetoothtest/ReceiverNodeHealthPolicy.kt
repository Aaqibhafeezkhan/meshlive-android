package com.example.bluetoothtest

import kotlin.math.max

class ReceiverNodeHealthPolicy(private val fps: Int) {
    private val expectedFrameIntervalMs = 1_000.0 / fps.coerceAtLeast(1)
    val minimumSamples = (fps.coerceAtLeast(1) * OBSERVATION_SECONDS)
        .coerceAtMost(MAXIMUM_REQUIRED_SAMPLES)
    private var baselineFrameIntervalMs = expectedFrameIntervalMs
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
        consecutiveUnstableObservations = 0
        consecutiveAudioLossObservations = 0
        lastAudioDroppedPacketsSinceChild = 0L
    }

    @Synchronized
    fun childDisconnected() {
        baselineFrameIntervalMs = expectedFrameIntervalMs
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
            expectedFrameIntervalMs * MAXIMUM_FRAME_INTERVAL_FACTOR,
            baselineFrameIntervalMs * MAXIMUM_BASELINE_REGRESSION_FACTOR,
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
