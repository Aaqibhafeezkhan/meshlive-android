package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewerVersionNearbyTest {
    private val day = 24L * 60 * 60 * 1000
    private val t0 = 1_800_000_000_000L
    private val none = NearbyNoticeState()

    private fun observe(state: NearbyNoticeState, release: Int, key: Boolean, installed: Int = 1, installedKey: Boolean = true, now: Long = t0) =
        NewerVersionNearby.observe(state, BuildTag(release, key), installed, installedKey, now)

    private fun notice(state: NearbyNoticeState, installed: Int = 1, installedKey: Boolean = true, now: Long = t0) =
        NewerVersionNearby.notice(state, installed, installedKey, now)

    @Test
    fun aNewerReleaseKeyPhoneProducesTheNotice() {
        val state = observe(none, 2, true)
        assertEquals(2, notice(state))
    }

    @Test
    fun equalOlderAndUnsignedClaimsProduceNothingOnAReleaseKeyPhone() {
        assertEquals(none, observe(none, 1, true))
        assertEquals(none, observe(none, 1, false, installed = 3))
        assertEquals("a build signed by someone else cannot update a release-key phone", none, observe(none, 2, false))
        assertNull(notice(none))
    }

    @Test
    fun aPhoneWithoutTheReleaseKeyAcceptsAnyNewerClaim() {
        val state = observe(none, 3, false, installed = 2, installedKey = false)
        assertEquals(3, notice(state, installed = 2, installedKey = false))
    }

    @Test
    fun aDebugBuildWithoutAReleaseNumberNeverShowsANotice() {
        assertFalse(NewerVersionNearby.plausible(BuildTag(5, true), installedRelease = 0, installedReleaseKey = false))
    }

    @Test
    fun aForgedHugeNumberIsIgnored() {
        val big = observe(none, 1 + NewerVersionNearby.MAX_PLAUSIBLE_JUMP + 1, true)
        assertEquals(none, big)
        val edge = observe(none, 1 + NewerVersionNearby.MAX_PLAUSIBLE_JUMP, true)
        assertEquals(1 + NewerVersionNearby.MAX_PLAUSIBLE_JUMP, notice(edge))
        assertEquals(ReleaseNumber.MAX, 0x7FFF)
        assertEquals(none, observe(none, ReleaseNumber.MAX, true))
    }

    @Test
    fun theBestClaimWinsAndASmallerOneDoesNotLowerIt() {
        var state = observe(none, 3, true)
        state = observe(state, 2, true, now = t0 + 1)
        assertEquals(3, state.bestRelease)
        assertEquals("a smaller claim does not refresh the age of the best one", t0, state.seenAtMs)
        state = observe(state, 3, true, now = t0 + 5)
        assertEquals(t0 + 5, state.seenAtMs)
        state = observe(state, 4, true, now = t0 + 6)
        assertEquals(4, state.bestRelease)
    }

    @Test
    fun aClaimFadesAfterTwoWeeksAndAnExpiredBestIsReplacedByAnyPlausibleClaim() {
        val state = observe(none, 3, true)
        assertEquals(3, notice(state, now = t0 + NewerVersionNearby.STALE_MS))
        assertNull(notice(state, now = t0 + NewerVersionNearby.STALE_MS + 1))
        val replaced = observe(state, 2, true, now = t0 + NewerVersionNearby.STALE_MS + 1)
        assertEquals(2, replaced.bestRelease)
        assertEquals(2, notice(replaced, now = t0 + NewerVersionNearby.STALE_MS + 2))
    }

    @Test
    fun dismissHidesThatClaimForAWeekButNotANewerOne() {
        var state = observe(none, 2, true)
        state = NewerVersionNearby.dismiss(state, t0)
        assertNull(notice(state, now = t0 + 1))
        assertNull(notice(state, now = t0 + NewerVersionNearby.DISMISS_MS))
        assertEquals("after a week the same claim returns", 2, notice(state, now = t0 + NewerVersionNearby.DISMISS_MS + 1))
        val newer = observe(state, 3, true, now = t0 + 10)
        assertEquals("a higher release is not covered by the dismissal", 3, notice(newer, now = t0 + 11))
    }

    @Test
    fun aForgedClaimCannotSuppressLaterNoticesForMoreThanAWeek() {
        // The attacker's claim is the largest plausible one; the user dismisses it.
        val forged = 1 + NewerVersionNearby.MAX_PLAUSIBLE_JUMP
        var state = NewerVersionNearby.dismiss(observe(none, forged, true), t0)
        // A real claim of release 2 arrives soon after: the dismissal still covers it, and the forged claim stays best.
        state = observe(state, 2, true, now = t0 + 1)
        assertEquals("the best claim stays the forged one", forged, state.bestRelease)
        assertNull(notice(state, now = t0 + 2))
        // After a week plus the stale limit the forged claim is gone and a real claim shows.
        val later = t0 + NewerVersionNearby.STALE_MS + 1
        assertEquals(2, notice(observe(state, 2, true, now = later), now = later + 1))
    }

    @Test
    fun theNoticeEndsOnceTheInstalledCopyCatchesUp() {
        val state = observe(none, 2, true)
        assertNull(notice(state, installed = 2))
        assertNull(notice(state, installed = 5))
        val tidy = NewerVersionNearby.tidy(state, installedRelease = 2, nowMs = t0)
        assertEquals(0, tidy.bestRelease)
        assertEquals(0L, tidy.seenAtMs)
        val old = NewerVersionNearby.dismiss(observe(none, 2, true), t0)
        assertEquals(0, NewerVersionNearby.tidy(old, 1, t0 + NewerVersionNearby.DISMISS_MS + 1).dismissedRelease)
        assertEquals(2, NewerVersionNearby.tidy(old, 1, t0 + 1).dismissedRelease)
    }

    @Test
    fun aKeyMismatchAfterTheFactHidesTheNotice() {
        val state = observe(none, 2, false, installedKey = false)
        assertNull("a release-key phone ignores a claim recorded from an unsigned build", notice(state, installedKey = true))
    }

    private class MapStore : NoticeKeyValue {
        val values = HashMap<String, Long>()
        override fun getLong(key: String, default: Long) = values[key] ?: default
        override fun putLongs(values: Map<String, Long>) {
            this.values.putAll(values)
        }
    }

    @Test
    fun theStateRoundTripsThroughTheStoreAndKeepsNoPeerIdentifier() {
        val kv = MapStore()
        val store = NewerVersionNearbyStore(kv)
        assertEquals(none, store.load())
        val state = NearbyNoticeState(7, true, t0, 6, t0 + 9)
        store.save(state)
        assertEquals(state, store.load())
        assertEquals(
            setOf("nearby_update_best", "nearby_update_best_key", "nearby_update_seen_at", "nearby_update_dismissed", "nearby_update_dismissed_at"),
            kv.values.keys,
        )
        assertTrue(kv.values.values.all { it >= 0 })
    }
}
