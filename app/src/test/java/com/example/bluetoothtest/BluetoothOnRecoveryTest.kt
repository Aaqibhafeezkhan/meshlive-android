package com.example.bluetoothtest

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothOnRecoveryTest {
    private val clock = FakeScanClock()
    private val registry = ScanRegistryImpl(clock = { clock.now })
    private val events = ArrayList<String>()

    private fun scanner(name: String, renewable: Boolean = true, restartable: Boolean = true) =
        registry.register(
            name,
            stop = { events += "stop:$name" },
            start = { events += "start:$name" },
            renewable = renewable,
            restartable = restartable,
        )

    // ----- scanner decisions -----

    @Test
    fun aScannerStartedBeforeTheAdapterWasOnIsRestartedAndOneStartedAfterIsKept() {
        val edge = 5_000L
        assertEquals(
            BluetoothOnDecision(true, "started_before_adapter_on"),
            BluetoothOnRecoveryPolicy.scanner(ScanState.RUNNING, true, 4_000L, edge),
        )
        assertEquals(
            BluetoothOnDecision(false, "started_after_adapter_on"),
            BluetoothOnRecoveryPolicy.scanner(ScanState.RUNNING, true, 5_000L, edge),
        )
        // An unknown edge is conservative: restart.
        assertTrue(BluetoothOnRecoveryPolicy.scanner(ScanState.RUNNING, true, 9_000L, -1L).restart)
    }

    @Test
    fun pausedScannersResumeAndProbesAndHaltedOnesAreLeftAlone() {
        assertEquals("paused_at_off", BluetoothOnRecoveryPolicy.scanner(ScanState.PAUSED, true, 0L, 5_000L).reason)
        assertFalse(BluetoothOnRecoveryPolicy.scanner(ScanState.RUNNING, false, 0L, 5_000L).restart)
        assertEquals("not_restartable", BluetoothOnRecoveryPolicy.scanner(ScanState.PAUSED, false, 0L, 5_000L).reason)
        assertEquals("halted", BluetoothOnRecoveryPolicy.scanner(ScanState.HALTED, true, 0L, 5_000L).reason)
    }

    @Test
    fun registryStopsAndRestartsTheScannerThatStartedWhileBluetoothWasDown() {
        // Lane15 a55m31-edge3-d2: a scanner registered after the off callback stays RUNNING and never gets a result.
        scanner("paused-before")
        scanner("probe", renewable = false, restartable = false)
        registry.pause(registry.running())
        events.clear()
        clock.advance(2_000L)
        scanner("started-during-off")
        clock.advance(3_000L)
        val onEdge = clock.now
        clock.advance(700L)
        val reported = LinkedHashMap<String, BluetoothOnDecision>()
        registry.recoverAfterBluetoothOn(onEdge) { name, decision -> reported[name] = decision }
        // The stale running scanner is stopped, then every paused one (including it) starts; the probe starts as before (a no-op).
        assertEquals(
            listOf("stop:started-during-off", "start:paused-before", "start:probe", "start:started-during-off"),
            events,
        )
        assertEquals("paused_at_off", reported.getValue("paused-before").reason)
        assertEquals("not_restartable", reported.getValue("probe").reason)
        assertEquals("started_before_adapter_on", reported.getValue("started-during-off").reason)
        assertEquals(ScanState.RUNNING, registry.state("started-during-off"))
    }

    @Test
    fun aScannerStartedAfterTheAdapterWasOnIsNotTouched() {
        val onEdge = clock.now
        clock.advance(300L)
        scanner("fresh")
        events.clear()
        registry.recoverAfterBluetoothOn(onEdge)
        assertTrue(events.isEmpty())
        assertEquals(ScanState.RUNNING, registry.state("fresh"))
    }

    // ----- stream offer -----

    @Test
    fun anArmedBroadcastOnTheCameraScreenOffersAgain() {
        assertEquals(BluetoothOnDecision(true, "offer_died_with_adapter"), BluetoothOnRecoveryPolicy.streamOffer(true, true, true, true))
        assertEquals("stream_not_armed", BluetoothOnRecoveryPolicy.streamOffer(false, true, true, true).reason)
        assertEquals("not_on_camera_screen", BluetoothOnRecoveryPolicy.streamOffer(true, false, true, true).reason)
        assertEquals("no_broadcast_discovery", BluetoothOnRecoveryPolicy.streamOffer(true, true, false, true).reason)
        assertEquals("permissions_missing", BluetoothOnRecoveryPolicy.streamOffer(true, true, true, false).reason)
    }

    @Test
    fun anOfferFailureWhileBluetoothIsDownKeepsTheBroadcastArmedOnlyWithTheSwitchOn() {
        assertTrue(BluetoothOnRecoveryPolicy.keepStreamArmedAfterAdvertiseFailure(enabled = true, recoveryPending = true))
        assertFalse(BluetoothOnRecoveryPolicy.keepStreamArmedAfterAdvertiseFailure(enabled = true, recoveryPending = false))
        assertFalse(BluetoothOnRecoveryPolicy.keepStreamArmedAfterAdvertiseFailure(enabled = false, recoveryPending = true))
        assertTrue(BluetoothOnRecoveryPolicy.deferStreamOfferStart(true, true))
        assertFalse(BluetoothOnRecoveryPolicy.deferStreamOfferStart(true, false))
        assertFalse(BluetoothOnRecoveryPolicy.deferStreamOfferStart(false, true))
    }

    // ----- call listener -----

    @Test
    fun onlyAnIdleListeningEndpointReopensItsListener() {
        assertEquals(BluetoothOnDecision(true, "listener_died_with_adapter"), BluetoothOnRecoveryPolicy.callListener(true, true, true))
        assertEquals("endpoint_closed", BluetoothOnRecoveryPolicy.callListener(false, true, true).reason)
        assertEquals("call_active", BluetoothOnRecoveryPolicy.callListener(true, false, true).reason)
        assertEquals("not_listening", BluetoothOnRecoveryPolicy.callListener(true, true, false).reason)
    }

    // ----- viewer link -----

    @Test
    fun anActiveViewerParentLinkIsClosedWhenTheAdapterGoesOff() {
        assertEquals(BluetoothOnDecision(true, "adapter_off"), BluetoothOnRecoveryPolicy.viewerLink(running = true, activeLink = true))
        assertEquals("no_active_link", BluetoothOnRecoveryPolicy.viewerLink(running = true, activeLink = false).reason)
        assertEquals("receiver_closed", BluetoothOnRecoveryPolicy.viewerLink(running = false, activeLink = true).reason)
        assertEquals(
            "bluetooth_on_recovery: component=viewer_link | action=closed | reason=adapter_off",
            BluetoothOnRecovery.line(BluetoothOnRecovery.COMPONENT_VIEWER_LINK, "closed", "adapter_off"),
        )
    }

    @Test
    fun offCallbacksRunWhenTheAdapterLeavesOnAndAreIdempotent() {
        val epoch = BluetoothEpochCore(clock, Random(1), { clock.now })
        var offs = 0
        epoch.register("viewer", { offs++ }, {})
        epoch.seed(12)
        epoch.onState(13)
        epoch.onState(10)
        assertEquals(1, offs)
    }

    // ----- epoch window and log line -----

    @Test
    fun recoveryIsPendingFromTheOffEdgeUntilTheOnCallbacksRan() {
        val epoch = BluetoothEpochCore(clock, Random(1), { clock.now })
        var rebuilt = false
        epoch.register("c", {}, { rebuilt = true })
        epoch.seed(12)
        assertFalse(epoch.recoveryPending)
        epoch.onState(13)
        assertTrue(epoch.recoveryPending)
        epoch.onState(10)
        assertTrue(epoch.recoveryPending)
        epoch.onState(11)
        val onEdge = clock.now
        epoch.onState(12)
        assertTrue("the delayed on callbacks are still pending", epoch.recoveryPending)
        clock.advance(BluetoothEpochCore.BASE_DELAY_MS + BluetoothEpochCore.JITTER_MS + 1)
        assertTrue(rebuilt)
        assertFalse(epoch.recoveryPending)
        // The on edge time is what the scan registry compares start times with.
        assertEquals(onEdge, epoch.lastStateChangeMs)
    }

    @Test
    fun theLogLineHasTheDocumentedShape() {
        assertEquals(
            "bluetooth_on_recovery: component=scanner | action=restarted | reason=started_before_adapter_on | name=discovery-ab12",
            BluetoothOnRecovery.line("scanner", "restarted", "started_before_adapter_on", "name=discovery-ab12"),
        )
        assertEquals(
            "bluetooth_on_recovery: component=call_listener | action=skipped | reason=call_active",
            BluetoothOnRecovery.line("call_listener", "skipped", "call_active"),
        )
    }
}
