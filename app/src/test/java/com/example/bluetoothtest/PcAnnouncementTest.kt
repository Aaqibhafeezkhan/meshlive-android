package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** "Find PCs" (Design QW): the shared vectors, the mapping, precedence, the switch-off plan and the guard. */
class PcAnnouncementTest {
    @After
    fun reset() {
        PcAnnouncementFeature.enabled = false
        PcAnnouncementFeature.filterRejected = false
    }

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Suppress("UNCHECKED_CAST")
    private fun vectors(): List<Map<String, Any?>> {
        val text = checkNotNull(javaClass.classLoader!!.getResourceAsStream("pc_announcement/pc_announcement_v1.json")) {
            "missing pc_announcement_v1.json"
        }.use { String(it.readBytes(), Charsets.UTF_8) }
        val root = TestJson.parse(text) as Map<String, Any?>
        assertEquals(PcAnnouncement.UUID.toString(), root["uuid"])
        return root["vectors"] as List<Map<String, Any?>>
    }

    private fun errorOf(result: Result<PcAnnouncement>): PcAnnouncementError =
        (result.exceptionOrNull() as PcAnnouncementException).error

    @Test
    fun everySharedVectorDecodesOrFailsWithItsExpectedError() {
        val all = vectors()
        assertEquals(14, all.size)
        var valid = 0
        var invalid = 0
        for (vector in all) {
            val name = vector["name"] as String
            val bytes = hex(vector["hex"] as String)
            val expectedError = vector["error"] as String?
            if (expectedError != null) {
                invalid++
                val failure = runCatching { PcAnnouncement.decode(bytes) }.exceptionOrNull()
                assertTrue(name, failure is PcAnnouncementException)
                assertEquals(name, expectedError, (failure as PcAnnouncementException).error.name)
            } else {
                valid++
                @Suppress("UNCHECKED_CAST")
                val decoded = vector["decoded"] as Map<String, Any?>
                val value = PcAnnouncement.decode(bytes)
                assertEquals(name, decoded["rdi"], hex(value.rdi))
                assertEquals(name, decoded["relay"], value.relayCapable)
                assertEquals(name, decoded["consent"], value.relayConsent)
                assertEquals(name, (decoded["codecs"] as Number).toInt(), value.codecs)
                assertEquals(name, (decoded["cost"] as Number).toInt(), value.cost)
                // The reference encoder reproduces the vector.
                assertArrayEquals(name, bytes, value.encode())
            }
        }
        assertEquals(4, valid)
        assertEquals(10, invalid)
    }

    @Test
    fun nullAndEveryWrongLengthAreLengthErrors() {
        assertEquals(PcAnnouncementError.Length, errorOf(PcAnnouncement.tryDecode(null)))
        for (size in 0..20) if (size != PcAnnouncement.LENGTH) {
            assertEquals(PcAnnouncementError.Length, errorOf(PcAnnouncement.tryDecode(ByteArray(size) { 0x10 })))
        }
    }

    @Test
    fun everyReservedBitAndTheMissingGattBitIsRejected() {
        val good = hex("10ebd54dc065ec0103")
        for (bit in 0..7) {
            val flags = good.copyOf().also { it[7] = (it[7].toInt() xor (1 shl bit)).toByte() }
            // Relay-capable (bit 1) and consent (bit 2) toggle freely; clearing GATT (bit 0) or setting bits 3..7 is an error.
            val accepted = bit in 1..2
            assertEquals("flag bit $bit", accepted, PcAnnouncement.tryDecode(flags).isSuccess)
            if (!accepted) assertEquals(PcAnnouncementError.Fields, errorOf(PcAnnouncement.tryDecode(flags)))
        }
        val reserved = good.copyOf().also { it[8] = (it[8].toInt() or 0x80).toByte() }
        assertEquals(PcAnnouncementError.Fields, errorOf(PcAnnouncement.tryDecode(reserved)))
    }

    @Test
    fun theMappingIsTheOrdinaryRotatingCallOffer() {
        val plain = PcAnnouncement.decode(hex("10ebd54dc065ec0103")).toDiscoveryAnnouncement()
        assertEquals(DiscoveryAnnouncement.ROTATING_VERSION, plain.version)
        assertTrue(plain.rotating)
        assertArrayEquals(hex("ebd54dc065ec"), plain.nodeIdPrefix)
        assertEquals(
            DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or
                DiscoveryAnnouncement.FLAG_GATT_TRANSPORT,
            plain.flags,
        )
        assertEquals(3, plain.capabilitySummary)
        assertEquals(0, plain.routeCost)
        assertEquals(0, plain.streamToken)
        assertEquals(0, plain.streamWidth)
        assertEquals(0, plain.streamHeight)
        assertEquals(0, plain.streamFps)
        assertTrue(plain.isPrivateCallOffer())

        val relay = PcAnnouncement.decode(hex("10a1b2c3d4e5f60717")).toDiscoveryAnnouncement()
        assertEquals(DiscoveryAnnouncement.FLAG_RELAY_CAPABLE, relay.flags and DiscoveryAnnouncement.FLAG_RELAY_CAPABLE)
        assertEquals(7 or CompactDiscoveryAnnouncement.CALL_RELAY_CONSENT_SUMMARY, relay.capabilitySummary)
        assertEquals(2, relay.routeCost)

        // Consent without relay-capable: the consent summary bit is set, the relay flag is not.
        val consentOnly = PcAnnouncement.decode(hex("100000000000000579")).toDiscoveryAnnouncement()
        assertEquals(0, consentOnly.flags and DiscoveryAnnouncement.FLAG_RELAY_CAPABLE)
        assertEquals(1 or CompactDiscoveryAnnouncement.CALL_RELAY_CONSENT_SUMMARY, consentOnly.capabilitySummary)
        assertEquals(15, consentOnly.routeCost)
    }

    @Test
    fun theMappingMatchesWhatAnIphoneCompactCallOfferDecodesTo() {
        for (vector in vectors().filter { it["error"] == null }) {
            val mapped = PcAnnouncement.decode(hex(vector["hex"] as String)).toDiscoveryAnnouncement()
            val viaCompact = CompactDiscoveryAnnouncement.decode(CompactDiscoveryAnnouncement.encode(mapped), acceptRotating = true)
            assertArrayEquals(viaCompact.nodeIdPrefix, mapped.nodeIdPrefix)
            assertEquals(viaCompact.flags, mapped.flags)
            assertEquals(viaCompact.capabilitySummary, mapped.capabilitySummary)
            assertEquals(viaCompact.routeCost, mapped.routeCost)
            assertEquals(viaCompact.streamToken, mapped.streamToken)
            assertEquals(viaCompact.version, mapped.version)
        }
    }

    private val compactOffer = CompactDiscoveryAnnouncement.encode(
        DiscoveryAnnouncement(
            nodeIdPrefix = hex("0102030405ff"),
            flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or
                DiscoveryAnnouncement.FLAG_GATT_TRANSPORT,
            capabilitySummary = 1,
            streamToken = 0,
            routeCost = 0,
            sequence = 0,
            version = DiscoveryAnnouncement.ROTATING_VERSION,
        ),
    )
    private val pcPayload = hex("10ebd54dc065ec0103")

    @Test
    fun aCompactUuidWinsOverPcServiceDataAndPcOnlyWinsWhenNothingElseIsThere() {
        val both = ScanRecordForms.resolve(compactOffer, pcPayload, acceptRotating = true)
        assertTrue(both is ScanRecordForms.Outcome.Compact)
        assertArrayEquals(hex("0102030405ff"), (both as ScanRecordForms.Outcome.Compact).announcement.nodeIdPrefix)

        val pcOnly = ScanRecordForms.resolve(null, pcPayload, acceptRotating = true)
        assertTrue(pcOnly is ScanRecordForms.Outcome.Pc)
        assertArrayEquals(hex("ebd54dc065ec"), (pcOnly as ScanRecordForms.Outcome.Pc).announcement.nodeIdPrefix)

        assertTrue(ScanRecordForms.resolve(null, null, acceptRotating = true) === ScanRecordForms.Outcome.None)
    }

    @Test
    fun anInvalidCompactCandidateIsNotRescuedByPcServiceData() {
        // Magic 0xC8D6 but version 3: the strict compact decoder rejects it; the PC form behind it is never tried.
        val badCompact = UUID((0xC8D6L shl 48) or (3L shl 46), 0L)
        assertTrue(ScanRecordForms.resolve(badCompact, pcPayload, acceptRotating = true) === ScanRecordForms.Outcome.CompactInvalid)
    }

    @Test
    fun anInvalidPcPayloadIsCountedOnItsOwnAndNeverReachesAcceptance() {
        val outcome = ScanRecordForms.resolve(null, hex("20ebd54dc065ec0103"), acceptRotating = true)
        assertTrue(outcome is ScanRecordForms.Outcome.PcInvalid)
        assertEquals(PcAnnouncementError.Version, (outcome as ScanRecordForms.Outcome.PcInvalid).error)
        assertEquals(PcAnnouncementError.Length, (ScanRecordForms.resolve(null, ByteArray(0), true) as ScanRecordForms.Outcome.PcInvalid).error)
    }

    @Test
    fun theMappedOfferIsRotatingWhateverTheScannerAccepts() {
        // acceptAnnouncement (the scanner's caller) is where a list that does not take rotating offers drops it,
        // exactly like any other rotating offer.
        val outcome = ScanRecordForms.resolve(null, pcPayload, acceptRotating = false) as ScanRecordForms.Outcome.Pc
        assertTrue(outcome.announcement.rotating)
    }

    @Test
    fun withTheSwitchOffTheScanFiltersAndSettingsAreExactlyTheOriginalOnes() {
        val app = ScanFilterSpec.ServiceUuid(UUID.fromString("c8d5a101-9f88-4f65-a03d-7f5a2fb921fa"))
        val compact = ScanFilterSpec.ServiceUuid(UUID(0xC8D6L shl 48, 0L), UUID(0xffffL shl 48, 0L))
        // Compact off / on, software off / on: the same lists as before this feature existed.
        assertEquals(listOf(app), DiscoveryScanPlan.filters(software = false, compact = false, pc = false))
        assertEquals(listOf(app, compact), DiscoveryScanPlan.filters(software = false, compact = true, pc = false))
        assertEquals(emptyList<ScanFilterSpec>(), DiscoveryScanPlan.filters(software = true, compact = true, pc = false))
        for (mode in listOf(0, 1, 2, -1)) {
            assertEquals(ScanSettingsSpec(mode, null, null), DiscoveryScanPlan.settings(mode, le1mOnly = false, pcExtended = false))
            assertEquals(ScanSettingsSpec(mode, true, 1), DiscoveryScanPlan.settings(mode, le1mOnly = true, pcExtended = false))
        }
        // The switch itself defaults off everywhere.
        assertFalse(PcAnnouncementFeature.filterActive())
        assertFalse(ConnectivityFeatureDefaults.FIND_PCS)
        assertFalse(ConnectivityFeatures.findPcs.defaultEnabled)
        assertFalse(PcAnnouncementFeature.resolve(false, { true }, { null }))
    }

    @Test
    fun withTheSwitchOnOneServiceDataFilterIsAppendedAndTheScanAsksForExtendedAdverts() {
        val off = DiscoveryScanPlan.filters(software = false, compact = true, pc = false)
        val on = DiscoveryScanPlan.filters(software = false, compact = true, pc = true)
        assertEquals(off + ScanFilterSpec.ServiceDataAny(PcAnnouncement.UUID), on)
        assertEquals(off, on.dropLast(1))
        assertEquals(emptyList<ScanFilterSpec>(), DiscoveryScanPlan.filters(software = true, compact = false, pc = true))
        assertEquals(ScanSettingsSpec(1, false, 1), DiscoveryScanPlan.settings(1, le1mOnly = false, pcExtended = true))
        // The PC scan replaces the F11 LE-1M-only settings only while the PC filter is active.
        assertEquals(ScanSettingsSpec(1, false, 1), DiscoveryScanPlan.settings(1, le1mOnly = true, pcExtended = true))
    }

    @Test
    fun theSwitchFollowsTheSharedFeatureRule() {
        assertEquals("test_find_pcs", ConnectivityFeatures.findPcs.benchmarkExtra)
        assertEquals("experimental_find_pcs", ConnectivityFeatures.findPcs.prefKey)
        assertTrue(ConnectivityFeatures.findPcs in ConnectivityFeatures.all)
        // A benchmark launch reads only its own extra; any other launch the stored choice, else the (off) default.
        assertTrue(PcAnnouncementFeature.resolve(true, { it == "test_find_pcs" }, { false }))
        assertFalse(PcAnnouncementFeature.resolve(true, { false }, { true }))
        assertTrue(PcAnnouncementFeature.resolve(false, { false }, { true }))
        assertFalse(PcAnnouncementFeature.resolve(false, { true }, { null }))
    }

    @Test
    fun theFilterGuardDropsThePcFilterFirstThenCompactAndNeverTheAppFilter() {
        val refusals = listOf(ScanFailurePolicy.FEATURE_UNSUPPORTED, ScanFailurePolicy.OUT_OF_HARDWARE_RESOURCES)
        for (code in refusals) {
            assertEquals(PcFilterGuard.Drop.PC, PcFilterGuard.dropFor(code, pcActive = true, compactActive = true))
            assertEquals(PcFilterGuard.Drop.PC, PcFilterGuard.dropFor(code, pcActive = true, compactActive = false))
            assertEquals(PcFilterGuard.Drop.COMPACT, PcFilterGuard.dropFor(code, pcActive = false, compactActive = true))
            // Nothing optional left: the guard never touches the app's own filter.
            assertEquals(PcFilterGuard.Drop.NONE, PcFilterGuard.dropFor(code, pcActive = false, compactActive = false))
        }
        val transient = listOf(
            ScanFailurePolicy.ALREADY_STARTED,
            ScanFailurePolicy.APPLICATION_REGISTRATION_FAILED,
            ScanFailurePolicy.INTERNAL_ERROR,
            ScanFailurePolicy.SCANNING_TOO_FREQUENTLY,
            99,
        )
        for (code in transient) {
            assertEquals(PcFilterGuard.Drop.NONE, PcFilterGuard.dropFor(code, pcActive = true, compactActive = true))
        }
        // Simulated sequence: after the PC drop the next refusal drops compact, and the filter list ends at the app filter.
        var pc = true
        var compact = true
        var steps = 0
        while (true) {
            when (PcFilterGuard.dropFor(ScanFailurePolicy.OUT_OF_HARDWARE_RESOURCES, pc, compact)) {
                PcFilterGuard.Drop.PC -> pc = false
                PcFilterGuard.Drop.COMPACT -> compact = false
                PcFilterGuard.Drop.NONE -> break
            }
            steps++
        }
        assertEquals(2, steps)
        assertEquals(1, DiscoveryScanPlan.filters(software = false, compact = compact, pc = pc).size)
    }

    @Test
    fun theFilterIsActiveOnlyWhileTheFeatureIsOnAndNoScannerRefusedIt() {
        assertFalse(PcAnnouncementFeature.filterActive())
        PcAnnouncementFeature.enabled = true
        assertTrue(PcAnnouncementFeature.filterActive())
        PcAnnouncementFeature.filterRejected = true
        assertFalse(PcAnnouncementFeature.filterActive())
        PcAnnouncementFeature.filterRejected = false
        assertTrue(PcAnnouncementFeature.filterActive())
    }

    @Test
    fun scanStatsAddPcCountersOnlyWhenTheScanCarriesThePcFilter() {
        val off = ScanStats("a1")
        assertFalse(off.line("running").contains("pc"))
        val compactOnly = ScanStats("a1", compactDiscovery = true)
        compactOnly.pc = 5 // never shown, never mixed into the compact counters
        assertFalse(compactOnly.line("running").contains("pc="))
        val on = ScanStats("a1", compactDiscovery = true, pcDiscovery = true)
        on.compact = 2
        on.pc = 3
        on.pcInvalid = 1
        val line = on.line("running")
        assertTrue(line, line.contains("compact=2 | compactInvalid=0 | pc=3 | pcInvalid=1"))
        on.reset()
        assertEquals(0, on.pc)
        assertEquals(0, on.pcInvalid)
    }

    @Test
    fun theLogLimiterAllowsOneLinePerAddressPerInterval() {
        val limiter = PcLogLimiter(intervalMs = 10_000L, maxAddresses = 2)
        assertTrue(limiter.allow("a", 0))
        assertFalse(limiter.allow("a", 9_999))
        assertTrue(limiter.allow("b", 5))
        assertTrue(limiter.allow("a", 10_000))
        assertTrue(limiter.allow("c", 10_001)) // evicts the oldest address (b)
        assertTrue(limiter.allow("b", 10_002)) // forgotten, so it logs again
    }
}
