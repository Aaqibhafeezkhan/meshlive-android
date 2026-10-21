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
