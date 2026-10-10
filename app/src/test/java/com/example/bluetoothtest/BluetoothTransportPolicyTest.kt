package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothTransportPolicyTest {
    @After
    fun reset() {
        DebugTunables.devicePolicyOff = emptySet()
    }

    private fun traits(sdk: Int, manufacturer: String = "samsung", soc: String = "Samsung") =
        DeviceTraits("s5e9945", "erd9945", sdk, manufacturer = manufacturer, socManufacturer = soc)

    @Test
    fun scanGateAllowsFiveStartsPerWindowThenWaitsForTheOldest() {
        var now = 1_000L
        val gate = ScanStartGate { now }
        val budget = LeScanStartBudget(maxStarts = 5, windowMs = 30_000L)
        repeat(5) {
            assertEquals(0L, gate.reserve(budget))
            now += 1_000L
        }
        // 6th start at t=6000: oldest (t=1000) leaves the window at t=31000.
        assertEquals(25_000L + ScanStartGate.SCAN_QUOTA_MARGIN_MS, gate.reserve(budget))
        assertEquals(5, gate.used(budget))
        now = 31_000L
        assertEquals(0L, gate.reserve(budget))
        // A refused reservation records nothing.
        assertEquals(5, gate.used(budget))
    }

    @Test
    fun scanGateWithoutBudgetNeverWaits() {
        val gate = ScanStartGate { 0L }
        repeat(20) { assertEquals(0L, gate.reserve(null)) }
    }

    @Test
    fun connectRetryCoversLinkLayerStatusesOnlyWithTheEntry() {
        val withEntry = BluetoothTransportPolicy.retryableConnectStatuses(DeviceFamilyPolicy.resolve(traits(34), emptySet()))
        assertEquals(setOf(8, 34, 62, 133, 147), withEntry)
        assertTrue(isRetryableGattVideoFailure("GATT video connection failed (status 62)", withEntry))
        assertTrue(isRetryableGattVideoFailure("GATT video receiver disconnected (status 8)", withEntry))
        assertTrue(isRetryableGattVideoFailure("GATT video connection failed (status 147)", withEntry))
        // Peer and local terminations, and anything after the connection stage, are not retried.
        assertFalse(isRetryableGattVideoFailure("GATT video receiver disconnected (status 19)", withEntry))
        assertFalse(isRetryableGattVideoFailure("GATT video receiver disconnected (status 22)", withEntry))
        assertFalse(isRetryableGattVideoFailure("GATT video service discovery failed (status 133)", withEntry))
        assertFalse(isRetryableGattVideoFailure("GATT video subscription failed (status 8)", withEntry))
        assertTrue(isRetryableBleControlFailure("BLE control connection failed (status 62)", withEntry))
        assertFalse(isRetryableBleControlFailure("BLE channel read failed (status 8)", withEntry))
        assertTrue(isRetryableBleControlFailure("BLE channel read failed (status 133)", withEntry))

        val off = BluetoothTransportPolicy.retryableConnectStatuses(
            DeviceFamilyPolicy.resolve(traits(34), setOf("le-connect-retry-link-statuses")),
        )
        assertEquals(setOf(133), off)
        assertFalse(isRetryableGattVideoFailure("GATT video connection failed (status 62)", off))
        assertTrue(isRetryableGattVideoFailure("GATT video connection failed (status 133)", off))
    }

    @Test
    fun mtuAndBusyRulesFollowTheirApiLevels() {
        assertEquals(517, BluetoothTransportPolicy.mtuStackForced(DeviceFamilyPolicy.resolve(traits(34), emptySet())))
        assertNull(BluetoothTransportPolicy.mtuStackForced(DeviceFamilyPolicy.resolve(traits(33), emptySet())))
        assertTrue(BluetoothTransportPolicy.writeBusyRetry(DeviceFamilyPolicy.resolve(traits(33), emptySet())) != null)
        assertNull(BluetoothTransportPolicy.writeBusyRetry(DeviceFamilyPolicy.resolve(traits(32), emptySet())))
        assertNull(BluetoothTransportPolicy.scanBudget(DeviceFamilyPolicy.resolve(traits(30), setOf("le-scan-start-quota"))))
        assertTrue(BluetoothTransportPolicy.scanBudget(DeviceFamilyPolicy.resolve(traits(29), emptySet())) != null)
    }

    @Test
    fun proposalsAreInertAndEveryEntryIsSourcedWithFallback() {
        for (entry in BluetoothTransportPolicy.entries) {
            assertTrue(entry.id, entry.sources.isNotEmpty())
            assertTrue(entry.id, entry.fallback.isNotBlank())
            assertEquals(entry.id, PolicyArea.BLUETOOTH_TRANSPORT, entry.area)
            if (entry.proposed) assertTrue(entry.id, entry.effects.isEmpty())
        }
        val resolved = DeviceFamilyPolicy.resolve(traits(36), emptySet())
        assertFalse(resolved.isActive("android16-bond-loss-dialog"))
        assertTrue(resolved.matchingProposals.any { it.id == "android16-bond-loss-dialog" })
        assertTrue(resolved.matchingProposals.any { it.id == "samsung-gatt-direct-connect-5s" })
    }

    @Test
    fun busyWriteRepeatsTheSameWriteUntilAcceptedOrTheDeadline() {
        val retry = GattWriteBusyRetry(maxWaitMs = 10L, stepMs = 2L)
        var clockNs = 0L
        val answers = ArrayDeque(listOf(201, 201, 0))
        var writes = 0
        val accepted = retryBusyGattWrite(201, retry, { writes++; answers.removeFirst() }, { clockNs += it * 1_000_000L }, { clockNs }, { false })
        assertEquals(BusyWriteOutcome(0, 2), accepted)
        assertEquals(3, writes)

        clockNs = 0L
        writes = 0
        val stuck = retryBusyGattWrite(201, retry, { writes++; 201 }, { clockNs += it * 1_000_000L }, { clockNs }, { false })
        assertEquals(201, stuck.result)
        assertEquals(5, stuck.retries) // 2 ms steps inside a 10 ms bound
        assertEquals(6, writes)

        // Without the entry (or below API 33) the first answer is final.
        writes = 0
        assertEquals(BusyWriteOutcome(201, 0), retryBusyGattWrite(201, null, { writes++; 201 }, {}, { 0L }, { false }))
        assertEquals(1, writes)
        // A closed stream stops retrying at once.
        assertEquals(BusyWriteOutcome(201, 0), retryBusyGattWrite(201, retry, { 201 }, {}, { 0L }, { true }))
    }

    @Test
    fun linkLineShowsRequestedAndObservedValuesSideBySide() {
        val diag = LeLinkDiagnostics("gatt-video", "AA:BB:CC:DD:EE:FF", stackForcedMtu = 517)
        diag.requestedPhy = "2M"
        diag.requestedMtu = 247
        diag.onPriority(BleConnectionPriority.HIGH, true)
        diag.onMtu(247, 0)
        diag.onPhy(2, 1, 0)
        diag.onParams(6, 0, 500, 0)
        val line = diag.line("setup")
        assertTrue(line, line.startsWith("LE link: link=gatt-video | event=setup | peer=...EE:FF"))
        assertTrue(line, "phy requested=2M observed=tx2M/rx1M status=0" in line)
        assertTrue(line, "mtu requested=247 effective=247 status=0 source=stack-sends-517-request-ignored" in line)
        assertTrue(line, "priority requested=high queued=true" in line)
        assertTrue(line, "interval observedMs=7.50 latency=0 supervisionMs=5000 status=0" in line)
        assertFalse(line, line.contains("BENCHMARK"))
        val older = LeLinkDiagnostics("ble-control", null, stackForcedMtu = null)
        assertTrue(older.line("setup"), "source=app" in older.line("setup"))
    }

    @Test
    fun linkLayerStatusesRetryOnceAndNeverEatTheCallsEarlyLossWindow() {
        val coc = 500L + 6_000L // CoC control: retry delay + one attempt
        fun allowed(message: String, used: Int, extended: Int, remaining: Long?) = leSetupRetryAllowed(
            message, attemptsUsed = used, maxAttempts = 3, classifiedRetryable = true,
            extendedRetriesUsed = extended, nextAttemptMs = coc, remainingBudgetMs = remaining,
        )
        // Status 8 / 0x22 / 0x3E: one retry at most; 133 keeps the full attempt count.
        assertTrue(allowed("BLE control connection failed (status 8)", 1, 0, null))
        assertFalse(allowed("BLE control connection failed (status 8)", 2, 1, null))
        assertFalse(allowed("BLE control connection failed (status 34)", 2, 1, null))
        assertTrue(allowed("BLE control connection failed (status 133)", 2, 1, null))
        assertFalse(allowed("BLE control connection failed (status 133)", 3, 1, null))
        // Budget: a 15 s early-loss window. A status-8 failure after 7 s (8 s left) may retry once; after
        // 10 s (5 s left) it must not, so the call's 2M -> 1M setup retry still starts inside 15 s.
        assertTrue(allowed("BLE control connection failed (status 8)", 1, 0, 8_000L))
        assertFalse(allowed("BLE control connection failed (status 8)", 1, 0, 5_000L))
        assertFalse(allowed("BLE control connection failed (status 133)", 1, 0, 5_000L))
        assertFalse(leSetupRetryAllowed("x (status 133)", 1, 3, classifiedRetryable = false, extendedRetriesUsed = 0, nextAttemptMs = 0L, remainingBudgetMs = null))
        // Worst case with the budget: attempts end before the window, leaving the 1M retry eligible.
        var elapsed = 0L
        var used = 1
        var extended = 0
        while (true) {
            elapsed += 6_000L // each status-8 attempt runs to the 6 s control timeout
            if (!allowed("BLE control connection failed (status 8)", used, extended, 15_000L - elapsed)) break
            elapsed += 500L
            used++
            extended++
        }
        assertTrue("first attempt ended at $elapsed ms", elapsed <= AdaptivePhyMemory.EARLY_LOSS_MS)
        assertTrue(adaptivePhySetupRetryEligible(true, true, true, true, true, elapsed))
    }

    @Test
    fun setupBudgetIsThreadLocalAndRestored() {
        assertNull(LeSetupBudget.remainingMs(1_000L))
        val inner = LeSetupBudget.within(16_000L) {
            val first = LeSetupBudget.remainingMs(1_000L)
            val nested = LeSetupBudget.within(5_000L) { LeSetupBudget.remainingMs(1_000L) }
            listOf(first, nested, LeSetupBudget.remainingMs(1_000L))
        }
        assertEquals(listOf(15_000L, 4_000L, 15_000L), inner)
        assertNull(LeSetupBudget.remainingMs(1_000L))
    }

    @Test
    fun scanGateSeedsFromThisBootsPersistedStartsAndReportsDeferral() {
        val budget = LeScanStartBudget(5, 30_000L)
        var now = 100_000L
        val saved = mutableListOf<Long>()
        val store = object : ScanStartStore {
            override fun load() = listOf(80_000L, 85_000L, 90_000L, 95_000L, 99_000L, 200_000L /* previous boot */)
            override fun save(starts: List<Long>) {
                saved.clear()
                saved.addAll(starts)
            }
        }
        val gate = ScanStartGate { now }
        gate.attach(store)
        assertEquals(5, gate.used(budget))
        assertFalse(gate.available(budget))
        val wait = gate.reserve(budget)
        assertEquals(10_000L + ScanStartGate.SCAN_QUOTA_MARGIN_MS, wait) // 80 000 leaves the window at 110 000
        assertEquals(wait, gate.deferredMs())
        now = 110_000L
        assertTrue(gate.available(budget))
        assertEquals(0L, gate.reserve(budget))
        assertEquals(0L, gate.deferredMs())
        assertEquals(110_000L, saved.last())
    }

    @Test
    fun optionalScanRestartTakesAFreeSlotAtomicallyAndNeverReportsADeferral() {
        val budget = LeScanStartBudget(2, 30_000L)
        var now = 1_000L
        val gate = ScanStartGate { now }
        assertTrue(gate.tryReserve(budget))
        assertTrue(gate.tryReserve(budget))
        assertFalse(gate.tryReserve(budget))
        assertEquals(0L, gate.deferredMs())
        assertEquals(2, gate.used(budget))
        now = 31_000L
        assertTrue(gate.tryReserve(budget))
        assertTrue(gate.tryReserve(null))
    }

    @Test
    fun scanStartsPersistPerBootAndDropCorruptOrForeignValues() {
        val raw = encodeScanStarts(7, listOf(80_000L, 85_000L))
        assertEquals("boot=7;80000,85000", raw)
        assertEquals(listOf(80_000L, 85_000L), decodeScanStarts(raw, 7))
        assertEquals(emptyList<Long>(), decodeScanStarts(raw, 8)) // another boot
        assertEquals(emptyList<Long>(), decodeScanStarts(raw, null)) // boot count unreadable now
        assertEquals(emptyList<Long>(), decodeScanStarts("80000,85000", 7)) // legacy, unkeyed
        assertEquals(emptyList<Long>(), decodeScanStarts(null, 7))
        assertEquals(listOf(1L, 3L), decodeScanStarts("boot=7;1,x,,-2,3", 7))
        assertEquals(listOf(5L), decodeScanStarts(encodeScanStarts(null, listOf(5L)), null))
    }

    @Test
    fun setupRetryNoticeKeepsTheStackStatusWording() {
        assertEquals("GATT video stack returned status 133; retrying once",
            leSetupRetryNotice("GATT video", "GATT video connection failed (status 133)", "retrying once"))
        assertEquals("BLE control stack returned status 8; retrying connection",
            leSetupRetryNotice("BLE control", "BLE control connection failed (status 8)", "retrying connection"))
        assertEquals("GATT video MTU exchange stalled; retrying once",
            leSetupRetryNotice("GATT video", GATT_MTU_STALLED_MESSAGE, "retrying once"))
    }
}
