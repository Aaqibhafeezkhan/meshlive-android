package com.example.bluetoothtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverNodeHealthPolicyTest {
    @Test
    fun keepsChildWhenForwardingCadenceRemainsHealthy() {
        val policy = ReceiverNodeHealthPolicy(fps = 15)
        policy.childConnected(snapshot(samples = 60, p95Ms = 67.0))

        repeat(5) {
            assertFalse(policy.shouldDetachChild(snapshot(samples = 60, p95Ms = 84.0)))
        }
    }

    @Test
    fun detachesChildAfterSustainedAbsoluteAndRelativeRegression() {
        val policy = ReceiverNodeHealthPolicy(fps = 15)
        policy.childConnected(snapshot(samples = 60, p95Ms = 67.0))

        assertFalse(policy.shouldDetachChild(snapshot(samples = 60, p95Ms = 124.0)))
        assertFalse(policy.shouldDetachChild(snapshot(samples = 75, p95Ms = 124.0)))
        assertTrue(policy.shouldDetachChild(snapshot(samples = 90, p95Ms = 124.0)))
    }

    @Test
    fun doesNotBlameChildForAnAlreadyUnstableSource() {
        val policy = ReceiverNodeHealthPolicy(fps = 15)
        policy.childConnected(snapshot(samples = 60, p50Ms = 100.0, p95Ms = 120.0))

        repeat(5) {
            assertFalse(policy.shouldDetachChild(snapshot(samples = 75, p95Ms = 130.0)))
        }
    }

    @Test
    fun startupOutliersDoNotHideAForwardingRegression() {
        val policy = ReceiverNodeHealthPolicy(fps = 15)
        policy.childConnected(snapshot(samples = 60, p50Ms = 67.0, p95Ms = 160.0))

        assertFalse(policy.shouldDetachChild(snapshot(samples = 60, p95Ms = 124.0)))
        assertFalse(policy.shouldDetachChild(snapshot(samples = 75, p95Ms = 124.0)))
        assertTrue(policy.shouldDetachChild(snapshot(samples = 90, p95Ms = 124.0)))
    }

    @Test
    fun requiresEnoughFramesAndResetsAfterDisconnect() {
        val policy = ReceiverNodeHealthPolicy(fps = 30)
        policy.childConnected(snapshot(samples = 90, p95Ms = 34.0))

        repeat(3) {
            assertFalse(policy.shouldDetachChild(snapshot(samples = 60, p95Ms = 90.0)))
        }
        policy.childDisconnected()
        assertFalse(policy.shouldDetachChild(snapshot(samples = 90, p95Ms = 34.0)))
    }

    @Test
    fun detachesChildAfterAudioLossKeepsIncreasing() {
        val policy = ReceiverNodeHealthPolicy(fps = 15)
        policy.childConnected(snapshot(samples = 60, p95Ms = 67.0))

        assertFalse(
            policy.shouldDetachChild(
                snapshot(samples = 60, p95Ms = 67.0),
                audioDroppedPacketsSinceChild = 4,
            ),
        )
        assertFalse(
            policy.shouldDetachChild(
                snapshot(samples = 75, p95Ms = 67.0),
                audioDroppedPacketsSinceChild = 9,
            ),
        )
        assertTrue(
            policy.shouldDetachChild(
                snapshot(samples = 90, p95Ms = 67.0),
                audioDroppedPacketsSinceChild = 15,
            ),
        )
    }

    @Test
    fun repeatedPollsWithoutFreshCadenceDoNotCountTheSameJitterAgain() {
        val policy = ReceiverNodeHealthPolicy(fps = 15)
        policy.childConnected(snapshot(samples = 60, p95Ms = 67.0))
        assertFalse(policy.shouldDetachChild(snapshot(samples = 45, p95Ms = 142.0)))
        repeat(5) { assertFalse(policy.shouldDetachChild(null)) }
        assertFalse(policy.shouldDetachChild(snapshot(samples = 45, p95Ms = 67.0)))
        assertFalse(policy.shouldDetachChild(snapshot(samples = 45, p95Ms = 142.0)))
    }

    @Test
    fun sustainedAudioLossStillDetachesWithoutWaitingForCadenceWindows() {
        val policy = ReceiverNodeHealthPolicy(fps = 15)
        policy.childConnected(snapshot(samples = 60, p95Ms = 67.0))
        assertFalse(policy.shouldDetachChild(null, 4))
        assertFalse(policy.shouldDetachChild(null, 9))
        assertTrue(policy.shouldDetachChild(null, 15))
    }

    @Test
    fun oneAudioLossBurstDoesNotDetachARecoveredChild() {
        val policy = ReceiverNodeHealthPolicy(fps = 15)
        policy.childConnected(snapshot(samples = 60, p95Ms = 67.0))

        assertFalse(
            policy.shouldDetachChild(
                snapshot(samples = 60, p95Ms = 67.0),
                audioDroppedPacketsSinceChild = 5,
            ),
        )
        repeat(4) {
            assertFalse(
                policy.shouldDetachChild(
                    snapshot(samples = 75, p95Ms = 67.0),
                    audioDroppedPacketsSinceChild = 5,
                ),
            )
        }
    }

    private fun snapshot(samples: Int, p95Ms: Double, p50Ms: Double = p95Ms) = LatencySnapshot(
        samples = samples,
        averageMs = p95Ms,
        p50Ms = p50Ms,
        p95Ms = p95Ms,
        p99Ms = p95Ms,
        maxMs = p95Ms,
    )
}
