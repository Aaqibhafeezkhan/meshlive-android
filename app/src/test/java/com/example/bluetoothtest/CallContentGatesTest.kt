package com.example.bluetoothtest

import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The four review findings of 2026-10-19, verified against the code and fixed. */
class CallContentGatesTest {
    private fun source(name: String): String = listOf(
        File("src/main/java/com/example/bluetoothtest/$name"),
        File("app/src/main/java/com/example/bluetoothtest/$name"),
    ).first { it.exists() }.readText().replace("\r\n", "\n")

    // ----- P1: no caller media before the answer -----

    @Test
    fun callMediaIsRefusedUntilTheCallIsAnsweredAndDroppedAfterItEnded() {
        listOf(null, PrivateCallPhase.OUTGOING_INVITE, PrivateCallPhase.OUTGOING_RINGING, PrivateCallPhase.INCOMING_RINGING)
            .forEach { assertEquals("$it", CallMediaGate.Decision.REFUSE, CallMediaGate.decide(it)) }
        assertEquals(CallMediaGate.Decision.ALLOW, CallMediaGate.decide(PrivateCallPhase.NEGOTIATING_MEDIA))
        assertEquals(CallMediaGate.Decision.ALLOW, CallMediaGate.decide(PrivateCallPhase.ACTIVE))
        assertEquals(CallMediaGate.Decision.DROP, CallMediaGate.decide(PrivateCallPhase.ENDED))
        assertEquals(
            setOf(EndToEndContentType.STREAM_HEADER, EndToEndContentType.MEDIA_PACKET, EndToEndContentType.VIDEO_FRAGMENT),
            CallMediaGate.MEDIA_TYPES,
        )
    }

    @Test
    fun theEndpointChecksTheGateBeforeItBuildsAReceiverOrDeliversMedia() {
        val handle = source("AndroidPrivateVideoCall.kt").substringAfter("private fun handleContent(").substringBefore("private fun handleSignal(")
        val gate = handle.indexOf("CallMediaGate.decide(state?.phase)")
        assertTrue(gate > 0)
        assertTrue("gate before the stream header branch", gate < handle.indexOf("EndToEndContentType.STREAM_HEADER -> {"))
        assertTrue("gate before any media delivery", gate < handle.indexOf("deliverMedia("))
    }

    // ----- P1: a verification code must match at both layers -----

    @Test
    fun aCodeMatchedAtOneLayerOnlyEndsTheConnection() {
        assertTrue(QrLayerBinding.consistent(linkMatched = true, endpointMatched = true))
        assertTrue(QrLayerBinding.consistent(linkMatched = false, endpointMatched = false))
        assertFalse("link tagged, untagged end-to-end handshake: was an ordinary call", QrLayerBinding.consistent(true, false))
        assertFalse(QrLayerBinding.consistent(false, true))
        val call = source("AndroidPrivateVideoCall.kt")
        val check = call.indexOf("QrLayerBinding.consistent(link.qrTagMatched, endpoint.qrTagMatched)")
        assertTrue(check > 0 && check < call.indexOf("if (!initiator && endpoint.qrTagMatched) beginQrVerifySession(endpoint)"))
    }

    // ----- P2: bounded codec configuration -----

    @Test
    fun codecConfigurationIsBoundedAndKeepsTheNewestEntries() {
        val list = mutableListOf<ByteArray>()
        repeat(1_000) { i -> CodecConfigBudget.add(list, ByteArray(1_024) { i.toByte() }) }
        assertEquals(CodecConfigBudget.MAX_ENTRIES, list.size)
        assertTrue(list.sumOf { it.size } <= CodecConfigBudget.MAX_BYTES)
        assertEquals((999).toByte(), list.last()[0])
        repeat(10) { CodecConfigBudget.add(list, ByteArray(100_000)) }
        assertTrue(list.sumOf { it.size } <= CodecConfigBudget.MAX_BYTES)
        CodecConfigBudget.add(list, ByteArray(CodecConfigBudget.MAX_BYTES + 1))
        assertTrue("an oversized entry is never kept", list.none { it.size > CodecConfigBudget.MAX_BYTES })
        val small = mutableListOf<ByteArray>()
        val sps = byteArrayOf(0, 0, 0, 1, 0x67)
        CodecConfigBudget.add(small, sps)
        sps[4] = 0
        assertEquals("a copy is kept", 0x67.toByte(), small.single()[4])
    }

    // ----- P2: a LEASE never overtakes this side's own OFFER -----

    @Test
    fun actionsAreHandedOnInTheOrderTheSessionProducedThem() {
        val session = PresenceSession(localEligible = true)
        val queued = Collections.synchronizedList(mutableListOf<PresenceSession.Action>())
        val dispatch = OrderedPresenceDispatch { task -> task.run() }
        val startProduced = CountDownLatch(1)
        val starter = thread {
            dispatch.dispatch(
                {
                    val actions = session.start()
                    startProduced.countDown()
                    Thread.sleep(100) // the OFFER's send-time check is slow (Keystore): the peer's OFFER arrives meanwhile
                    actions
                },
                runIfEmpty = true,
            ) { queued += it }
        }
        assertTrue(startProduced.await(5, TimeUnit.SECONDS))
        val reader = thread {
            dispatch.dispatch({ session.onRecord(DiscoveryExchange.RecognitionOffer, true) }, runIfEmpty = false) { queued += it }
        }
        starter.join(5_000)
        reader.join(5_000)
        assertEquals(listOf<Any>(PresenceSession.Action.SendOffer, PresenceSession.Action.SendLease), queued.toList())
    }

    @Test
    fun theEndpointRoutesEveryPresenceEventThroughTheOrderedDispatch() {
        val call = source("AndroidPrivateVideoCall.kt")
        assertTrue(call.contains("presenceDispatch.dispatch({ session.start() }, runIfEmpty = true)"))
        assertTrue(call.contains("presenceDispatch.dispatch({ session.onRecord(record, verifiedNow) }, runIfEmpty = false)"))
        assertFalse(call.contains("thread(name = \"PresenceAction\""))
    }
}
