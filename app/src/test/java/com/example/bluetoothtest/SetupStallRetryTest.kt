package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStallRetryTest {
    private class FakeWatch(private val dropAfterMs: Long?, private val clock: LongArray) : GattLinkLifetime.AclDownWatch {
        var released = false
        var awaitedBoundMs = -1L

        override fun await(timeoutMs: Long): Boolean {
            awaitedBoundMs = timeoutMs
            return if (dropAfterMs != null && dropAfterMs <= timeoutMs) {
                clock[0] += dropAfterMs
                true
            } else {
                clock[0] += timeoutMs
                false
            }
        }

        override fun release() {
            released = true
        }
    }

    @Test
    fun theRetryWaitsForTheStalledLinksAclEvenWhenTheStackCallsItDisconnected() {
        // A15s -> ASUS: the ACL lingered 1.1-3.7 s after the stall close; the wait ends when it drops.
        val clock = longArrayOf(10_000L)
        val watch = FakeWatch(dropAfterMs = 3_700L, clock = clock)
        val lines = mutableListOf<String>()
        val dropped = SetupRetryAclWait.await(watch, nowMs = { clock[0] }, log = { lines += it })
        assertTrue(dropped)
        assertEquals(SetupRetryAclWait.BOUND_MS, watch.awaitedBoundMs)
        assertTrue(watch.released)
        assertEquals("BENCHMARK setup_retry_acl_wait: waitedMs=3700 | dropped=true | boundMs=5000", lines.last())
    }

    @Test
    fun theAclWaitIsBoundedAndReportsAMissedDrop() {
        val clock = longArrayOf(0L)
        val watch = FakeWatch(dropAfterMs = null, clock = clock)
        val lines = mutableListOf<String>()
        assertFalse(SetupRetryAclWait.await(watch, nowMs = { clock[0] }, log = { lines += it }))
        assertTrue(watch.released)
        assertEquals("BENCHMARK setup_retry_acl_wait: waitedMs=5000 | dropped=false | boundMs=5000", lines.last())
    }

    @Test
    fun outsideAStallRetryTheOrdinaryRuleDecides() {
        assertEquals(
            StallRetryDials.Verdict.NotApplicable,
            StallRetryDials.next(ConnectDurationClass.FAST_ESTABLISH_FAILURE, attemptsUsed = 1, nowMs = 0L, deadlineMs = null),
        )
        // Inside a stall retry, only a fast 0x3E failure gets the extra dials.
        for (other in listOf(ConnectDurationClass.OWN_DEADLINE, ConnectDurationClass.STACK_30S_TIMEOUT, ConnectDurationClass.OTHER, null)) {
            assertEquals(
                StallRetryDials.Verdict.NotApplicable,
                StallRetryDials.next(other, attemptsUsed = 1, nowMs = 0L, deadlineMs = 15_000L),
            )
        }
    }

    @Test
    fun aStallRetryRedialsFast0x3eFailuresWithinTheEarlyLossWindow() {
        // longcall lc-...-080235 timeline from call start: stall close at 5.3 s, retry dial at 5.5 s failed in 0.5 s,
        // then 0.1 s, and the call ended at 6.6 s. With the budget the same failures are redialed 1.5 s apart.
        val deadline = 15_000L
        var now = 6_000L
        var attempts = 1
        val dials = mutableListOf<Long>()
        while (true) {
            val verdict = StallRetryDials.next(ConnectDurationClass.FAST_ESTABLISH_FAILURE, attempts, now, deadline)
            if (verdict !is StallRetryDials.Verdict.Dial) {
                assertEquals(StallRetryDials.Verdict.Stop, verdict)
                break
            }
            assertEquals(StallRetryDials.SPACING_MS, verdict.delayMs)
            now += verdict.delayMs
            dials += now
            attempts += 1
            now += 500L // each further dial fails fast too
        }
        assertEquals(StallRetryDials.MAX_ATTEMPTS, attempts)
        assertTrue(dials.all { it < deadline })
    }

    @Test
    fun noFurtherDialStartsWhenTheWindowCannotHoldTheSpacing() {
        assertEquals(
            StallRetryDials.Verdict.Stop,
            StallRetryDials.next(ConnectDurationClass.FAST_ESTABLISH_FAILURE, attemptsUsed = 1, nowMs = 13_600L, deadlineMs = 15_000L),
        )
    }

    @Test
    fun theStallBudgetOnlyAppliesInsideItsBlockOnThisThread() {
        assertEquals(
            StallRetryDials.Verdict.NotApplicable,
            StallRetryDials.next(ConnectDurationClass.FAST_ESTABLISH_FAILURE, attemptsUsed = 1, nowMs = 0L),
        )
        val inside = StallRetryDials.within(Long.MAX_VALUE) {
            val other = arrayOfNulls<StallRetryDials.Verdict>(1)
            Thread { other[0] = StallRetryDials.next(ConnectDurationClass.FAST_ESTABLISH_FAILURE, 1, nowMs = 0L) }
                .apply { start(); join() }
            assertEquals(StallRetryDials.Verdict.NotApplicable, other[0])
            StallRetryDials.next(ConnectDurationClass.FAST_ESTABLISH_FAILURE, attemptsUsed = 1, nowMs = 0L)
        }
        assertTrue(inside is StallRetryDials.Verdict.Dial)
        assertEquals(
            StallRetryDials.Verdict.NotApplicable,
            StallRetryDials.next(ConnectDurationClass.FAST_ESTABLISH_FAILURE, attemptsUsed = 1, nowMs = 0L),
        )
    }

    private val pseudonym = byteArrayOf(0xfd.toByte(), 0x41, 0xf2.toByte(), 0x6a, 0x37, 0x93.toByte())
    private val contact = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { (it * 17 + 3).toByte() })

    @Test
    fun aRecognisedRotatingOfferKeysTheMemoryByTheNodeThe1MRetryRecords() {
        val lookup = AdaptivePhyMemory.offerMemoryNode(
            rotating = true,
            nodePrefix = pseudonym,
            contactKey = true,
            recognisedContact = { contact.shortLabel() },
        )
        // The 1M retry records secureSession.peerNodeId.shortLabel(); the stall records the same key now.
        assertEquals(contact.shortLabel(), lookup)
        assertEquals(
            AdaptivePhyMemory.key(BluetoothTransportOption.BLE_GATT, contact.shortLabel()),
            AdaptivePhyMemory.key(BluetoothTransportOption.BLE_GATT, lookup!!),
        )
        assertEquals(
            contact.shortLabel(),
            AdaptivePhyMemory.advertisedPeerToRemember(
                enabled = true, stalled = true, direct = true, rotating = true, nodePrefix = pseudonym, contactNode = lookup,
            ),
        )
    }

    @Test
    fun anUnrecognisedRotatingOfferHasNoMemoryAndKeepsTheRequestedPhy() {
        val node = AdaptivePhyMemory.offerMemoryNode(true, pseudonym, contactKey = true, recognisedContact = { null })
        assertNull(node)
        assertNull(AdaptivePhyMemory.offerMemoryNode(true, pseudonym, contactKey = true, recognisedContact = { error("vault") }))
        assertNull(
            AdaptivePhyMemory.advertisedPeerToRemember(true, true, true, rotating = true, nodePrefix = pseudonym, contactNode = node),
        )
        assertEquals(
            BlePhyPreference.TWO_M,
            AdaptivePhyMemory.phyFor(BluetoothTransportOption.BLE_GATT, pseudonym, BlePhyPreference.TWO_M, "call", memoryNode = node),
        )
    }

    @Test
    fun stableOffersAndTheControlArmKeepTheAdvertisedPrefix() {
        val label = AdaptivePhyMemory.prefixLabel(pseudonym)
        assertEquals(label, AdaptivePhyMemory.offerMemoryNode(false, pseudonym, contactKey = true) { contact.shortLabel() })
        assertEquals(label, AdaptivePhyMemory.offerMemoryNode(true, pseudonym, contactKey = false) { contact.shortLabel() })
        // Control arm: a rotating offer is not recorded at the stall (no contact node passed).
        assertNull(AdaptivePhyMemory.advertisedPeerToRemember(true, true, true, rotating = true, nodePrefix = pseudonym))
    }
}
