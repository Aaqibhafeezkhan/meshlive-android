package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design SG: the automatic GATT fallback for streams. Pure decision functions only: the receiver restart, the
 * source's viewer-request filter and the separate GATT child pool admission. Everything is off unless `coc_auto_gatt`
 * and the `test_coc_auto_gatt_streams` sub-switch are both on.
 */
class StreamGattFallbackTest {
    private val l2cap = BluetoothTransportOption.BLE_L2CAP
    private val gatt = BluetoothTransportOption.BLE_GATT
    private val classic = BluetoothTransportOption.CLASSIC_RFCOMM
    private val on = CocAutoGattSettings(enabled = true)
    private val off = CocAutoGattSettings(enabled = false)

    // ---- switches ----

    @Test
    fun theSubSwitchIsOffByDefaultAndNeedsCocAutoGatt() {
        assertFalse("shipped default off", ConnectivityFeatureDefaults.COC_AUTO_GATT_STREAMS)
        assertFalse(StreamGattFallback.resolveSwitch(debugBuild = true, benchmarkLaunch = false, extra = true))
        assertTrue(StreamGattFallback.resolveSwitch(debugBuild = true, benchmarkLaunch = true, extra = true))
        assertFalse("a release build ignores the extra", StreamGattFallback.resolveSwitch(debugBuild = false, benchmarkLaunch = true, extra = true))
        assertFalse("the sub-switch alone does nothing", StreamGattFallback.active(off, streams = true))
        assertFalse("coc_auto_gatt alone does nothing", StreamGattFallback.active(on, streams = false))
        assertTrue(StreamGattFallback.active(on, streams = true))
    }

    // ---- stage 1: receiver restart ----

    private fun restart(
        active: Boolean = true,
        transport: BluetoothTransportOption = l2cap,
        kind: ReceiverStopKind? = ReceiverStopKind.STARTUP,
        listenFailed: Boolean = true,
        wasActive: Boolean = false,
        ended: Boolean = false,
        forced: Boolean = false,
    ) = StreamGattFallback.receiverRestart(active, transport, kind, listenFailed, wasActive, ended, forced)

    @Test
    fun aFailedListenOnAnL2capReceiverRestartsOnGatt() {
        assertEquals(StreamGattFallback.ReceiverRestart.RESTART_ON_GATT, restart())
        assertTrue(restart().restart)
    }

    @Test
    fun theRestartNeedsBothSwitchesAnL2capReceiverAndAListenFailure() {
        assertEquals(StreamGattFallback.ReceiverRestart.OFF, restart(active = false))
        assertEquals(StreamGattFallback.ReceiverRestart.NOT_L2CAP, restart(transport = gatt))
        assertEquals(StreamGattFallback.ReceiverRestart.NOT_L2CAP, restart(transport = classic))
        assertEquals("a startup failure that is not the listen", StreamGattFallback.ReceiverRestart.NOT_LISTEN_FAILURE, restart(listenFailed = false))
        ReceiverStopKind.entries.filter { it != ReceiverStopKind.STARTUP }.forEach { kind ->
            assertEquals("$kind never restarts", StreamGattFallback.ReceiverRestart.NOT_LISTEN_FAILURE, restart(kind = kind))
        }
        assertEquals(StreamGattFallback.ReceiverRestart.NOT_LISTEN_FAILURE, restart(kind = null))
    }

    @Test
    fun anActiveEndedOrAlreadyFallenBackReceiverDoesNotRestart() {
        assertEquals(StreamGattFallback.ReceiverRestart.WAS_ACTIVE, restart(wasActive = true))
        assertEquals(StreamGattFallback.ReceiverRestart.STREAM_ENDED, restart(ended = true))
        assertEquals("once per adapter session", StreamGattFallback.ReceiverRestart.ALREADY_FALLEN_BACK, restart(forced = true))
        assertFalse(restart(wasActive = true).restart || restart(ended = true).restart || restart(forced = true).restart)
    }

    @Test
    fun theFallbackIsOncePerAdapterSessionOnTheCallLedger() {
        val ledger = CocRegistrationLedger()
        val session = CocAutoGattSession(ledger)
        assertNull("nothing forced yet", session.forcedFor(l2cap, on))
        val first = session.onCocFailure(l2cap, on, CocFallbackReason.AVAILABILITY_FAILED)
        assertNotNull(first)
        assertEquals(gatt, first!!.effective)
        assertEquals(l2cap, first.requested)
        assertEquals(CocFallbackReason.AVAILABILITY_FAILED, first.reason)
        assertNull("a second failure in the same adapter session does not restart again", session.onCocFailure(l2cap, on, CocFallbackReason.AVAILABILITY_FAILED))
        val forced = session.forcedFor(l2cap, on)
        assertNotNull(forced)
        assertEquals(gatt, forced!!.effective)
        assertNull("flag off: nothing", session.forcedFor(l2cap, off))
        assertNull("only an L2CAP request is ever moved", session.forcedFor(gatt, on))
        ledger.reset() // Bluetooth off/on
        assertNull("recovery: the next join is L2CAP again", session.forcedFor(l2cap, on))
    }

    @Test
    fun theReceiverStartsOnGattOnlyWhenForcedAndActive() {
        val forced = CocAutoGattSession(CocRegistrationLedger()).also {
            it.onCocFailure(l2cap, on, CocFallbackReason.AVAILABILITY_FAILED)
        }.forcedFor(l2cap, on)
        assertEquals(gatt, StreamGattFallback.receiverTransport(l2cap, true, forced))
        assertEquals("sub-switch off: the offer's transport", l2cap, StreamGattFallback.receiverTransport(l2cap, false, forced))
        assertEquals("nothing forced", l2cap, StreamGattFallback.receiverTransport(l2cap, true, null))
        assertEquals("a GATT offer stays GATT", gatt, StreamGattFallback.receiverTransport(gatt, true, forced))
        assertEquals("a Classic offer is never changed", classic, StreamGattFallback.receiverTransport(classic, true, forced))
    }

    @Test
    fun theNoticesAndLinesNameTransportsAndNeverIdentities() {
        val choice = CocAutoGattSession(CocRegistrationLedger()).onCocFailure(l2cap, on, CocFallbackReason.AVAILABILITY_FAILED)!!
        val notice = StreamGattFallback.restartNotice(choice)
        assertTrue(notice, "BLE GATT" in notice && "L2CAP" in notice && "off and on" in notice)
        val line = StreamGattFallback.restartLine(StreamGattFallback.ReceiverRestart.RESTART_ON_GATT, choice, 0x1234)
        assertTrue(line, "requested=BLE_L2CAP" in line && "effective=BLE_GATT" in line && "reason=availability_failed" in line)
        val failed = StreamGattFallback.listenFailedLine(l2cap, 7, "IOException|boom", 13, on, streamsSwitch = true, injected = true)
        assertTrue(failed, failed.startsWith("stream_listen_failed: transport=BLE_L2CAP"))
        assertTrue(failed, "cocRegistrations=13" in failed && "injected=true" in failed && "streams_gatt=true" in failed)
        assertTrue("stage 0 notice without the fallback", "Turn Bluetooth off and on" in StreamGattFallback.listenFailedNotice("x", fallbackActive = false))
        assertTrue("stage 0 notice with the fallback", "Trying BLE GATT" in StreamGattFallback.listenFailedNotice("x", fallbackActive = true))
    }

    // ---- stage 2: the source filter ----

    @Test
    fun withTheSwitchOffTheSourceFilterIsExactlyTheOldEquality() {
        BluetoothTransportOption.entries.forEach { session ->
            BluetoothTransportOption.entries.forEach { request ->
                assertEquals("$session/$request", request == session, StreamGattFallback.acceptsViewerRequest(request, session, active = false))
            }
        }
    }

    @Test
    fun onlyAnL2capSourceTakesGattRequestsAndNeverTheReverse() {
        assertTrue(StreamGattFallback.acceptsViewerRequest(gatt, l2cap, active = true))
        assertTrue(StreamGattFallback.acceptsViewerRequest(l2cap, l2cap, active = true))
        assertFalse("an L2CAP source takes no Classic request", StreamGattFallback.acceptsViewerRequest(classic, l2cap, active = true))
        assertFalse("a GATT source never takes an L2CAP request", StreamGattFallback.acceptsViewerRequest(l2cap, gatt, active = true))
        assertFalse(StreamGattFallback.acceptsViewerRequest(classic, gatt, active = true))
        assertFalse("a Classic source never takes an LE request", StreamGattFallback.acceptsViewerRequest(gatt, classic, active = true) || StreamGattFallback.acceptsViewerRequest(l2cap, classic, active = true))
        assertEquals(StreamGattFallback.ChildPool.PRIMARY, StreamGattFallback.poolFor(l2cap, l2cap, true))
        assertEquals(StreamGattFallback.ChildPool.GATT_POOL, StreamGattFallback.poolFor(gatt, l2cap, true))
        assertEquals(StreamGattFallback.ChildPool.NOT_ACCEPTED, StreamGattFallback.poolFor(gatt, l2cap, false))
        assertEquals(StreamGattFallback.ChildPool.PRIMARY, StreamGattFallback.poolFor(gatt, gatt, true))
    }

    // ---- stage 2: the GATT child pool admission ----

    private fun input(
        gattChildren: Int = 0,
        capacity: Int = StreamGattFallback.GATT_POOL_CAPACITY,
        charged: Double = 0.0,
        l2cap: Double = 480.0,
        budget: Double = 1_400.0,
        need: Double = 480.0,
        gattCapacity: Double = 700.0,
    ) = StreamGattFallback.GattChildInput(gattChildren, capacity, charged, l2cap, budget, need, gattCapacity)

    @Test
    fun aGattChildThatFitsIsAdmittedAndChargedAtItsPlannedRate() {
        val admission = StreamGattFallback.admitGattChild(input())
        assertEquals(StreamGattFallback.GattChildVerdict.ADMIT, admission.verdict)
        assertEquals(480.0, admission.chargeKbps, 0.0)
        assertEquals("the pool starts at one", 1, StreamGattFallback.GATT_POOL_CAPACITY)
    }

    @Test
    fun theSecondGattRequestIsRefusedBecauseThePoolIsFull() {
        val admission = StreamGattFallback.admitGattChild(input(gattChildren = 1, charged = 480.0))
        assertEquals(StreamGattFallback.GattChildVerdict.REFUSE_POOL_FULL, admission.verdict)
        assertEquals(0.0, admission.chargeKbps, 0.0)
        assertFalse(admission.verdict.admit)
    }

    @Test
    fun aStreamTooFastForAGattLinkIsRefusedNeverSubstituted() {
        val admission = StreamGattFallback.admitGattChild(input(need = 900.0, gattCapacity = 700.0))
        assertEquals(StreamGattFallback.GattChildVerdict.REFUSE_RATE, admission.verdict)
        assertEquals("a layered stream charges only its base layer", StreamGattFallback.GattChildVerdict.ADMIT,
            StreamGattFallback.admitGattChild(input(need = 900.0 * 0.5, gattCapacity = 700.0)).verdict)
    }

    @Test
    fun theAggregateChargeMustFitTheBudgetWithoutEvictingL2capChildren() {
        val full = StreamGattFallback.admitGattChild(input(l2cap = 960.0, budget = 1_200.0, need = 480.0))
        assertEquals(StreamGattFallback.GattChildVerdict.REFUSE_AGGREGATE, full.verdict)
        val room = StreamGattFallback.admitGattChild(input(l2cap = 480.0, budget = 1_200.0, need = 480.0))
        assertEquals(StreamGattFallback.GattChildVerdict.ADMIT, room.verdict)
        // the decision never changes the L2CAP side: it only reports how much budget is left for later L2CAP admissions
        assertEquals(720.0, StreamGattFallback.fanOutBudgetKbps(1_200.0, 480.0), 0.0)
        assertEquals(1_200.0, StreamGattFallback.fanOutBudgetKbps(1_200.0, 0.0), 0.0)
        assertEquals("never negative", 0.0, StreamGattFallback.fanOutBudgetKbps(300.0, 480.0), 0.0)
    }

    @Test
    fun theRefusalChecksRunInTheDocumentedOrder() {
        val all = input(gattChildren = 1, need = 900.0, l2cap = 5_000.0)
        assertEquals("pool first", StreamGattFallback.GattChildVerdict.REFUSE_POOL_FULL, StreamGattFallback.admitGattChild(all).verdict)
        assertEquals("then the rate", StreamGattFallback.GattChildVerdict.REFUSE_RATE, StreamGattFallback.admitGattChild(all.copy(gattChildren = 0)).verdict)
        assertEquals("then the aggregate", StreamGattFallback.GattChildVerdict.REFUSE_AGGREGATE, StreamGattFallback.admitGattChild(all.copy(gattChildren = 0, needKbps = 480.0)).verdict)
    }

    @Test
    fun everyRefusalIsVisibleAndLoggedWithItsReason() {
        StreamGattFallback.GattChildVerdict.entries.filter { !it.admit }.forEach { verdict ->
            assertTrue(verdict.name, StreamGattFallback.refusalStatus(verdict).startsWith("A receiver that can only use BLE GATT was not admitted"))
            val line = StreamGattFallback.poolLine(input(), StreamGattFallback.GattChildAdmission(verdict, 0.0), l2capChildren = 1)
            assertTrue(line, "event=refuse" in line && "reason=${verdict.label}" in line && "gattChildren=0/1" in line)
        }
        val admit = StreamGattFallback.poolLine(input(), StreamGattFallback.admitGattChild(input()), l2capChildren = 1)
        assertTrue(admit, "event=admit" in admit && "chargedKbps=480" in admit && "budgetKbps=1400" in admit)
    }

    // ---- the DEBUG injection ----

    @Test
    fun theListenInjectionIsDebugOnlyAndExact() {
        assertTrue(CocListenInject.shouldFail(debugBuild = true, configured = "stream", role = "stream"))
        assertFalse("a release build never injects", CocListenInject.shouldFail(debugBuild = false, configured = "stream", role = "stream"))
        assertFalse("not configured", CocListenInject.shouldFail(debugBuild = true, configured = null, role = "stream"))
        assertFalse("a different value", CocListenInject.shouldFail(debugBuild = true, configured = "call", role = "stream"))
        assertFalse("exact match only", CocListenInject.shouldFail(debugBuild = true, configured = "stream,call", role = "stream"))
        assertEquals("test_coc_listen_inject", CocListenInject.EXTRA)
        assertEquals("test_coc_auto_gatt_streams", StreamGattFallback.EXTRA_STREAMS)
    }
}
