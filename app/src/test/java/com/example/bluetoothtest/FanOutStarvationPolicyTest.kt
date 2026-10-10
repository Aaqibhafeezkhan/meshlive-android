package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FanOutStarvationPolicyTest {
    private val second = 1_000_000_000L

    private fun sample(offered: Long, sent: Long, ageMs: Int = 0, childLate: Boolean = false) =
        LinkRateSample(second, offered, sent, ageMs, largeFrameGrace = false, childLate = childLate)

    @Test
    fun aChildPoorlyServedForEightOfTenSecondsWhileItsSiblingIsHealthyIsReleased() {
        // M31 fan-out: the S24 carried its ~48 KB/s while the ASUS link carried a fraction of its offer.
        val policy = FanOutStarvationPolicy()
        for (t in 0 until 9) {
            assertNull(policy.onSample("s24", sample(48_000, 48_000), t * second))
            val asus = if (t % 5 == 4) sample(48_000, 40_000) else sample(48_000, 5_000, ageMs = 1_500)
            assertNull(policy.onSample("asus", asus, t * second))
        }
        policy.onSample("s24", sample(48_000, 48_000), 9 * second)
        val withdrawal = policy.onSample("asus", sample(48_000, 5_000, ageMs = 2_000), 9 * second)
        assertEquals("asus", withdrawal?.child)
        // 384 kbps + the ASUS average (9 x 40 + 1 x 320) / 10 = 68 kbps.
        assertEquals(452, withdrawal?.carriedAggregateKbps)
    }

    @Test
    fun aShortBurstIsNotEnough() {
        val policy = FanOutStarvationPolicy()
        for (t in 0 until 12) {
            policy.onSample("s24", sample(48_000, 48_000), t * second)
            val asus = if (t in 3..5) sample(48_000, 4_000, ageMs = 900) else sample(48_000, 46_000)
            assertNull(policy.onSample("asus", asus, t * second))
        }
    }

    @Test
    fun whenEveryChildIsStarvedTheNewestAdmissionIsUndone() {
        // trend-3641df77: the M31 admitted a second 300 kbps GATT viewer it could not carry and
        // both fell to 1.4 / 7.9 FPS. Undo the newest admission instead of degrading everyone.
        val policy = FanOutStarvationPolicy()
        assertNull(policy.onSample("first", sample(48_000, 10_000, ageMs = 900), 0))
        var withdrawal: FanOutStarvationPolicy.Withdrawal? = null
        for (t in 1 until 12) {
            withdrawal = withdrawal ?: policy.onSample("first", sample(48_000, 10_000, ageMs = 900), t * second)
            withdrawal = withdrawal ?: policy.onSample("second", sample(48_000, 10_000, ageMs = 900), t * second)
        }
        assertEquals("second", withdrawal?.child)
    }

    @Test
    fun aSiblingWhoseChildReportsLateArrivalsIsNotHealthy() {
        val policy = FanOutStarvationPolicy()
        for (t in 0 until 12) {
            assertNull(policy.onSample("a", sample(48_000, 48_000, childLate = true), t * second))
            assertNull(policy.onSample("b", sample(48_000, 10_000, ageMs = 900), t * second))
        }
    }

    @Test
    fun aChildChronicallyBehindLiveIsStarvedEvenThoughNothingIsDropped() {
        // kfshape-a99f1ef7: the ASUS child carried what it was offered but sat 2.0-3.7 s behind
        // at the rate floor while the S24 sibling's queue was empty.
        val policy = FanOutStarvationPolicy()
        var withdrawal: FanOutStarvationPolicy.Withdrawal? = null
        for (t in 0 until 30) {
            assertNull(policy.onSample("s24", sample(36_000, 36_000, ageMs = 0), t * second))
            withdrawal = withdrawal ?: policy.onSample("asus", sample(36_000, 36_000, ageMs = 2_600), t * second)
            // Rate control and the encoder trim get 24 s before a merely-late child is released.
            if (t < 23) assertNull(withdrawal)
        }
        assertEquals("asus", withdrawal?.child)
    }

    // mesh4-57484598 gap C: the A15s sat at the encoder floor, its backlog grew, and the 24-of-30 s rule
    // held both viewers at about 100 kbps for about 20 s.

    private fun growingSample(t: Int, grace: Boolean = false) =
        LinkRateSample(second, 12_000, 9_000, 1_500 + t * 200, largeFrameGrace = grace)

    @Test
    fun aChildWhoseBacklogKeepsGrowingAtTheRateFloorIsReleasedEarly() {
        val policy = FanOutStarvationPolicy(earlyRelease = { true })
        var withdrawal: FanOutStarvationPolicy.Withdrawal? = null
        var releasedAt = -1
        for (t in 0 until 30) {
            assertNull(policy.onSample("s24", sample(12_000, 12_000), t * second, encoderAtFloor = true))
            withdrawal = withdrawal ?: policy.onSample("a15s", growingSample(t), t * second, encoderAtFloor = true)
            if (withdrawal != null && releasedAt < 0) releasedAt = t
        }
        assertEquals("a15s", withdrawal?.child)
        assertEquals(FanOutStarvationPolicy.EARLY_WINDOWS - 1, releasedAt)
    }

    @Test
    fun theEarlyReleaseNeedsTheEncoderAtItsFloor() {
        val policy = FanOutStarvationPolicy(earlyRelease = { true })
        for (t in 0 until 20) {
            policy.onSample("s24", sample(12_000, 12_000), t * second)
            // Same growing backlog, encoder still above its floor: rate control may still cut.
            assertNull(policy.onSample("a15s", growingSample(t), t * second, encoderAtFloor = false))
        }
    }

    @Test
    fun theEarlyReleaseCanBeSwitchedOff() {
        val policy = FanOutStarvationPolicy(earlyRelease = { false })
        for (t in 0 until 20) {
            policy.onSample("s24", sample(12_000, 12_000), t * second, encoderAtFloor = true)
            assertNull(policy.onSample("a15s", growingSample(t), t * second, encoderAtFloor = true))
        }
    }

    @Test
    fun theEarlyReleaseNeedsAHealthySibling() {
        val policy = FanOutStarvationPolicy(earlyRelease = { true })
        for (t in 0 until 20) {
            // Alone: nothing to protect by releasing it, shared-overload rules decide.
            assertNull(policy.onSample("a15s", growingSample(t), t * second, encoderAtFloor = true))
        }
    }

    @Test
    fun aKeyframeOrLargeFrameDrainRestartsTheEarlyStreak() {
        val policy = FanOutStarvationPolicy(earlyRelease = { true })
        for (t in 0 until 25) {
            policy.onSample("s24", sample(12_000, 12_000), t * second, encoderAtFloor = true)
            // A large-frame drain every 6th second: its high backlog is expected, not starvation.
            assertNull(policy.onSample("a15s", growingSample(t, grace = t % 6 == 5), t * second, encoderAtFloor = true))
        }
    }

    @Test
    fun aBacklogThatIsDrainingOrHoldingSteadyIsNotReleasedEarly() {
        val policy = FanOutStarvationPolicy(earlyRelease = { true })
        for (t in 0 until 25) {
            policy.onSample("s24", sample(12_000, 12_000), t * second, encoderAtFloor = true)
            // Constant 2 s behind with the offer carried: no growth.
            val steady = LinkRateSample(second, 12_000, 11_000, 2_000, largeFrameGrace = false)
            assertNull(policy.onSample("a15s", steady, t * second, encoderAtFloor = true))
        }
        // Growing, then clearly draining, then growing again: the streak restarts each time.
        val drainPolicy = FanOutStarvationPolicy(earlyRelease = { true })
        for (t in 0 until 25) {
            drainPolicy.onSample("s24", sample(12_000, 12_000), t * second, encoderAtFloor = true)
            val age = 1_500 + (t % 8) * 300 - if (t % 8 == 7) 2_000 else 0
            val s = LinkRateSample(second, 12_000, 9_000, maxOf(age, 1_500), largeFrameGrace = false)
            assertNull(drainPolicy.onSample("a15s", s, t * second, encoderAtFloor = true))
        }
    }
}
