package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The copied-ID warning (Design G review F4): honest phones must not trip it, simultaneous copies must. */
class CopiedIdTest {
    private val rdi = "44e69c0a7a7d"

    // ----- Q-PC-COPIED-ID option A: a Windows PC's legacy and extended twin of one compact call offer -----

    /** The PC's legacy set (win-userflow-20261019 compact-ab a15s-rawobs-1): Flags, 16-bit list with 0x180A, compact UUID. */
    private val pcLegacy = hex("020118" + "03030a18" + "1107" + "00112233445566778899aabbccddeeff")
    /** The extended set: the same three ADs plus the PC's service data (not compared). */
    private val pcExtended = hex("020118" + "03030a18" + "1107" + "00112233445566778899aabbccddeeff" + "05211234abcd")

    private fun legacyForm(bytes: ByteArray = pcLegacy) = AdvertTwinForm(true, false, requireNotNull(AdvertTwinAds.compared(bytes)))
    private fun extendedForm(bytes: ByteArray = pcExtended) = AdvertTwinForm(false, true, requireNotNull(AdvertTwinAds.compared(bytes)))

    /** Sightings alternating every [stepMs] over 20 s; returns whether the warning came. */
    private fun alternate(tracker: CopiedIdTracker, senders: List<Pair<String, AdvertTwinForm?>>, stepMs: Long = 600L): Boolean {
        var warned = false
        var t = 0L
        var i = 0
        while (t < 20_000L) {
            val (address, form) = senders[i % senders.size]
            warned = tracker.observe(rdi, address, 0, t, form) || warned
            t += stepMs
            i++
        }
        return warned
    }

    @Test
    fun aPcLegacyAndExtendedPairIsOneSender() {
        assertFalse(alternate(CopiedIdTracker(), listOf("46:B4:FE:DD:52:2E" to legacyForm(), "75:F8:A4:68:6C:AF" to extendedForm())))
    }

    @Test
    fun aThirdAddressBesideThePcPairStillWarns() {
        val copy = "11:22:33:44:55:66" to legacyForm()
        assertTrue(alternate(CopiedIdTracker(), listOf("46:B4:FE:DD:52:2E" to legacyForm(), "75:F8:A4:68:6C:AF" to extendedForm(), copy), 300L))
    }

    @Test
    fun aPairWithDifferentAdsStillWarns() {
        val otherUuid = hex("020118" + "03030a18" + "1107" + "ffeeddccbbaa99887766554433221100" + "05211234abcd")
        assertTrue(alternate(CopiedIdTracker(), listOf("46:B4:FE:DD:52:2E" to legacyForm(), "75:F8:A4:68:6C:AF" to extendedForm(otherUuid))))
    }

    @Test
    fun twoLegacyAddressesInterleavingStillWarn() {
        assertTrue(alternate(CopiedIdTracker(), listOf("46:B4:FE:DD:52:2E" to legacyForm(), "12:34:56:78:9A:BC" to legacyForm())))
    }

    @Test
    fun withoutTheSwitchThePcPairWarnsAsBefore() {
        val tracker = CopiedIdTracker(pcPairExempt = { false })
        assertTrue(alternate(tracker, listOf("46:B4:FE:DD:52:2E" to legacyForm(), "75:F8:A4:68:6C:AF" to extendedForm())))
    }

    @Test
    fun onlyAdsWithTheDeviceInformationUuidAreComparable() {
        assertEquals(null, AdvertTwinAds.compared(hex("020118" + "0303ffff" + "1107" + "00112233445566778899aabbccddeeff")))
        assertEquals(null, AdvertTwinAds.compared(hex("0201")))
        assertEquals(AdvertTwinAds.compared(pcLegacy), AdvertTwinAds.compared(pcExtended))
        assertEquals("trailing zero padding is ignored", AdvertTwinAds.compared(pcLegacy), AdvertTwinAds.compared(pcLegacy + ByteArray(8)))
    }

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ----- Design CI review 1: the full twin matrix, set restarts (T5), gaps and timestamp boundaries -----

    /** One advertising set as the scanner hears it: a sighting every [periodMs] from [fromMs] (inclusive) to [untilMs] (exclusive). */
    private class Tx(
        val address: String,
        val form: AdvertTwinForm?,
        val fromMs: Long,
        val untilMs: Long,
        val periodMs: Long = 200L,
        val lost: (Long) -> Boolean = { false },
    )

    /** Plays every set's sightings in time order (ties in list order); returns whether the warning came. */
    private fun play(tracker: CopiedIdTracker, sets: List<Tx>, endMs: Long = 30_000L): Boolean {
        val events = sets.flatMapIndexed { index, tx ->
            generateSequence(tx.fromMs) { it + tx.periodMs }.takeWhile { it < tx.untilMs && it < endMs }
                .filterNot(tx.lost).map { Triple(it, index, tx) }.toList()
        }.sortedWith(compareBy({ it.first }, { it.second }))
        var warned = false
        events.forEach { (t, _, tx) -> warned = tracker.observe(rdi, tx.address, 0, t, tx.form) || warned }
        return warned
    }

    private fun form(legacy: Boolean, bytes: ByteArray, secondaryPhy: Boolean = !legacy): AdvertTwinForm? =
        AdvertTwinAds.compared(bytes)?.let { AdvertTwinForm(legacy, secondaryPhy, it) }

    /** The PC's extended set for the whole run, offset 100 ms from the legacy sightings. */
    private fun pcExtendedSet(address: String = "75:F8:A4:68:6C:AF", untilMs: Long = 30_000L, lost: (Long) -> Boolean = { false }) =
        Tx(address, extendedForm(), 100L, untilMs, lost = lost)

    @Test
    fun aStablePcTwinNeverWarnsOverThirtySeconds() {
        assertFalse(play(CopiedIdTracker(), listOf(Tx("46:B4:FE:DD:52:2E", legacyForm(), 0L, 30_000L), pcExtendedSet())))
    }

    @Test
    fun twoExtendedAddressesInterleavingStillWarn() {
        assertTrue(alternate(CopiedIdTracker(), listOf("75:F8:A4:68:6C:AF" to extendedForm(), "12:34:56:78:9A:BC" to extendedForm())))
    }

    @Test
    fun aHalfWithoutAFormStillWarns() {
        assertTrue(alternate(CopiedIdTracker(), listOf("46:B4:FE:DD:52:2E" to legacyForm(), "75:F8:A4:68:6C:AF" to null)))
        assertTrue(alternate(CopiedIdTracker(), listOf("46:B4:FE:DD:52:2E" to null, "75:F8:A4:68:6C:AF" to extendedForm())))
    }

    @Test
    fun aLegacyAdvertWithoutTheDeviceInformationUuidBesideAnExtendedCopyStillWarns() {
        // The iPhone shape: only the compact 128-bit UUID, so no form; an extended twin-shaped copy beside it is a copy.
        val iphone = hex("020106" + "1107" + "00112233445566778899aabbccddeeff")
        assertEquals(null, form(true, iphone))
        assertTrue(alternate(CopiedIdTracker(), listOf("46:B4:FE:DD:52:2E" to form(true, iphone), "75:F8:A4:68:6C:AF" to extendedForm())))
    }

    @Test
    fun aDifferenceInAnyComparedAdStillWarns() {
        val uuid = "1107" + "00112233445566778899aabbccddeeff" + "05211234abcd"
        val variants = mapOf(
            "flags" to hex("020106" + "03030a18" + uuid),
            "16-bit list" to hex("020118" + "05030a18ffff" + uuid),
            "128-bit list" to hex("020118" + "03030a18" + "1107" + "ffeeddccbbaa99887766554433221100" + "05211234abcd"),
        )
        variants.forEach { (what, bytes) ->
            val extended = requireNotNull(form(false, bytes))
            assertTrue(what, alternate(CopiedIdTracker(), listOf("46:B4:FE:DD:52:2E" to legacyForm(), "75:F8:A4:68:6C:AF" to extended)))
        }
    }

    @Test
    fun anExtendedHalfWithoutASecondaryPhyStillWarns() {
        val noSecondary = form(false, pcExtended, secondaryPhy = false)
        assertTrue(alternate(CopiedIdTracker(), listOf("46:B4:FE:DD:52:2E" to legacyForm(), "75:F8:A4:68:6C:AF" to noSecondary)))
    }

    @Test
    fun aPhoneBesideATwinShapedCopyStillWarns() {
        // An Android phone's sightings carry no form; a copier's legacy and extended twin of its ID are a copy.
        val sets = listOf(
            Tx("AA:01:02:03:04:05", null, 0L, 30_000L),
            Tx("C1:00:00:00:00:01", legacyForm(), 50L, 30_000L),
            Tx("C1:00:00:00:00:02", extendedForm(), 150L, 30_000L),
        )
        assertTrue(play(CopiedIdTracker(), sets))
    }

    @Test
    fun aSightingWithoutAFormDoesNotEraseTheFormItsAddressShowed() {
        // Every fourth extended sighting fails to parse (truncated data): the twin stays one sender.
        val sets = listOf(Tx("46:B4:FE:DD:52:2E", legacyForm(), 0L, 30_000L), pcExtendedSet())
        val flaky = listOf(sets[0], Tx("75:F8:A4:68:6C:AF", null, 900L, 30_000L, periodMs = 800L), sets[1])
        assertFalse(play(CopiedIdTracker(), flaky))
    }

    /** The legacy half restarts with a new address every [everyMs], the new set starting [gapMs] after the old one stops. */
    private fun restartingLegacy(everyMs: Long, gapMs: Long, endMs: Long = 30_000L): List<Tx> =
        (0 until (endMs / everyMs).toInt() + 1).map { i ->
            val from = if (i == 0) 0L else i * everyMs + gapMs
            Tx("46:B4:FE:DD:52:%02X".format(i), legacyForm(), from, (i + 1) * everyMs + 1)
        }

    @Test
    fun repeatedRestartsOfTheLegacyHalfAreNotACopy() {
        // T5: each restart briefly leaves three recent addresses; before the fix two restarts brought the warning back.
        assertFalse(play(CopiedIdTracker(), restartingLegacy(4_000L, 31L) + pcExtendedSet()))
    }

    @Test
    fun repeatedRestartsOfTheExtendedHalfAreNotACopy() {
        val extended = (0 until 7).map { i ->
            Tx("75:F8:A4:68:6C:%02X".format(i), extendedForm(), if (i == 0) 100L else i * 5_000L + 140L, (i + 1) * 5_000L + 1)
        }
        assertFalse(play(CopiedIdTracker(), listOf(Tx("46:B4:FE:DD:52:2E", legacyForm(), 0L, 30_000L)) + extended))
    }

    @Test
    fun bothHalvesRestartingTogetherRepeatedlyAreNotACopy() {
        val extended = (0 until 8).map { i ->
            Tx("75:F8:A4:68:6C:%02X".format(i), extendedForm(), if (i == 0) 100L else i * 4_000L + 131L, (i + 1) * 4_000L + 101)
        }
        assertFalse(play(CopiedIdTracker(), restartingLegacy(4_000L, 31L) + extended))
    }

    @Test
    fun aNewAddressFirstHeardAtTheSameMomentTheOldWasLastHeardCountsAsInterleaving() {
        // The boundary is strict: equal timestamps are not a handover, so two such restarts warn; one millisecond later does not.
        assertTrue(play(CopiedIdTracker(), restartingLegacy(4_000L, 0L) + pcExtendedSet()))
        assertFalse(play(CopiedIdTracker(), restartingLegacy(4_000L, 1L) + pcExtendedSet()))
    }

    @Test
    fun aRetiredAddressThatReturnsCountsAgain() {
        val sets = listOf(
            Tx("46:B4:FE:DD:52:00", legacyForm(), 0L, 5_001L),
            Tx("46:B4:FE:DD:52:01", legacyForm(), 5_031L, 30_000L),
            Tx("46:B4:FE:DD:52:00", legacyForm(), 10_050L, 30_000L), // the retired address is back, alongside its successor
            pcExtendedSet(),
        )
        assertTrue(play(CopiedIdTracker(), sets))
    }

    @Test
    fun aLiveSameFormCopierDuringARestartStillWarns() {
        val sets = restartingLegacy(4_000L, 31L) + pcExtendedSet() + Tx("C1:00:00:00:00:01", legacyForm(), 2_050L, 30_000L)
        assertTrue(play(CopiedIdTracker(), sets))
    }

    @Test
    fun aMakeBeforeBreakRestartStillWarns() {
        // The new legacy set starts 300 ms before the old one stops: both are on the air at once, as a copy would be.
        val legacy = (0 until 8).map { i ->
            Tx("46:B4:FE:DD:52:%02X".format(i), legacyForm(), if (i == 0) 0L else i * 4_000L - 300L, (i + 1) * 4_000L + 1)
        }
        assertTrue(play(CopiedIdTracker(), legacy + pcExtendedSet()))
    }

    @Test
    fun packetLossGapsInATwinAreNotACopy() {
        val sets = listOf(
            // The legacy half drops every third sighting and goes silent for 1.9 s once; the extended half for 3 s once.
            Tx("46:B4:FE:DD:52:2E", legacyForm(), 0L, 30_000L, lost = { it % 600L == 0L || it in 3_000L until 4_900L }),
            pcExtendedSet(lost = { it in 8_000L until 11_000L }),
        )
        assertFalse(play(CopiedIdTracker(), sets))
        // A restart while the other half is in a gap is still a handover.
        val afterGap = listOf(
            Tx("46:B4:FE:DD:52:00", legacyForm(), 0L, 6_001L),
            Tx("46:B4:FE:DD:52:01", legacyForm(), 7_000L, 30_000L),
            pcExtendedSet(lost = { it in 5_000L until 8_000L }),
        )
        assertFalse(play(CopiedIdTracker(), afterGap))
    }

    @Test
    fun aWarningStaysAfterTheCopyLeaves() {
        val tracker = CopiedIdTracker()
        val sets = listOf(
            Tx("46:B4:FE:DD:52:2E", legacyForm(), 0L, 30_000L),
            pcExtendedSet(),
            Tx("C1:00:00:00:00:01", legacyForm(), 50L, 8_000L),
        )
        assertTrue(play(tracker, sets))
        assertTrue(tracker.warned(rdi))
    }

    @Test
    fun twoTwinShapedCopiesWithTheOwnerOutOfRangeAreExempt() {
        // Design CI T3 residual, stated as behaviour: with no real sender heard, a copier's own twin is one sender.
        val sets = listOf(Tx("C1:00:00:00:00:01", legacyForm(), 0L, 30_000L), Tx("C1:00:00:00:00:02", extendedForm(), 100L, 30_000L))
        assertFalse(play(CopiedIdTracker(), sets))
        assertTrue(play(CopiedIdTracker(pcPairExempt = { false }), sets))
    }

    @Test
    fun aSecondAddressForOneIdInsideAnEpochIsNotACopy() {
        val tracker = CopiedIdTracker()
        // The stack rotates the advertising address: the old one stops, the new one starts 30 s later.
        var warned = false
        for (t in 0..10) warned = tracker.observe(rdi, "AA:01", 1, t * 1_000L) || warned
        for (t in 40..50) warned = tracker.observe(rdi, "AA:02", 1, t * 1_000L) || warned
        assertFalse(warned)
        assertFalse(tracker.warned(rdi))
    }

    /**
     * edgecases-20261020 m31-a15s-redmi-1: the A15s replaced its advertising set when its Meet code boosted the advert and
     * again when the boost ended (old set stopped 31 ms before the new one started); the M31 heard the ID on three
     * addresses in turn (79:35.. -> 5B:2A.. -> 57:E0..) and showed "More than one phone is using this ID".
     */
    private fun handovers(tracker: CopiedIdTracker): Boolean {
        var warned = false
        val addresses = listOf("79:35:5B:FC:82:C9", "5B:2A:45:E2:E6:E4", "57:E0:85:46:8B:93", "4F:9A:8E:DB:E0:BA")
        addresses.forEachIndexed { index, address ->
            val start = index * 10_000L + if (index == 0) 0L else 31L
            var t = start
            while (t < (index + 1) * 10_000L) {
                warned = tracker.observe(rdi, address, 0, t) || warned
                t += 100L
            }
        }
        return warned
    }

    @Test
    fun addressHandoversOneAfterAnotherAreNotACopyEvenWithinTwoSeconds() {
        val tracker = CopiedIdTracker(requireOverlap = { true })
        assertFalse(handovers(tracker))
        assertFalse(tracker.warned(rdi))
    }

    @Test
    fun theControlArmKeepsTheTwoSecondRuleThatFlaggedTheHandovers() {
        val tracker = CopiedIdTracker(requireOverlap = { false })
        assertTrue(handovers(tracker))
    }

    @Test
    fun aCopyThatStartsWhileTheRealPhoneKeepsAdvertisingIsStillFound() {
        val tracker = CopiedIdTracker(requireOverlap = { true })
        var warned = false
        // The real phone alone for 5 s, then a copy joins at 200 ms offsets: the sightings interleave.
        var t = 0L
        while (t < 5_000L) {
            warned = tracker.observe(rdi, "AA:01", 0, t) || warned
            t += 100L
        }
        while (t < 15_000L) {
            warned = tracker.observe(rdi, "AA:01", 0, t) || warned
            warned = tracker.observe(rdi, "EE:66", 0, t + 50L) || warned
            t += 200L
        }
        assertTrue(warned)
        assertTrue(tracker.warned(rdi))
    }

    @Test
    fun aRelayMirrorAtRouteCostPlusOneIsNotACopy() {
        val tracker = CopiedIdTracker()
        var warned = false
        for (t in 0..20) {
            warned = tracker.observe(rdi, "AA:01", 0, t * 1_000L) || warned
            warned = tracker.observe(rdi, "BB:09", 1, t * 1_000L + 300L) || warned
        }
        assertFalse(warned)
    }

    @Test
    fun twoAddressesAtTheSameCostAtTheSameTimeRepeatedlyAreACopy() {
        val tracker = CopiedIdTracker()
        var firstWarningAt = -1L
        for (t in 0..20) {
            val now = t * 1_000L
            val a = tracker.observe(rdi, "AA:01", 1, now)
            val b = tracker.observe(rdi, "CC:07", 1, now + 400L)
            if ((a || b) && firstWarningAt < 0) firstWarningAt = now
        }
        assertTrue(firstWarningAt in 1..10_000L)
        assertTrue(tracker.warned(rdi))
    }

    @Test
    fun oneOverlapIsNotEnough() {
        val tracker = CopiedIdTracker()
        tracker.observe(rdi, "AA:01", 1, 0L)
        assertFalse(tracker.observe(rdi, "CC:07", 1, 500L))
        assertFalse(tracker.warned(rdi))
    }

    @Test
    fun aForgottenIdStartsOver() {
        val tracker = CopiedIdTracker()
        for (t in 0..20) {
            tracker.observe(rdi, "AA:01", 1, t * 1_000L)
            tracker.observe(rdi, "CC:07", 1, t * 1_000L + 400L)
        }
        assertTrue(tracker.warned(rdi))
        tracker.forget(rdi)
        assertFalse(tracker.warned(rdi))
    }

    @Test
    fun idsNotHeardForAWhileAreDroppedSoMemoryStaysBounded() {
        val tracker = CopiedIdTracker()
        repeat(2_000) { tracker.observe("%012x".format(it), "AA:01", 0, it * 10L) }
        tracker.observe("ffffffffffff", "AA:01", 0, 2_000L * 10 + CopiedIdTracker.STALE_MS + 1)
        // Only the fresh ID is left (the 512-key bound pruned the rest on the way).
        assertFalse(tracker.warned("000000000000"))
        assertFalse(tracker.warned("ffffffffffff"))
    }
}
