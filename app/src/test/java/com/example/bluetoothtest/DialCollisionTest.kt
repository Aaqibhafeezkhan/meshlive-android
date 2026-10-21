package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F14: a call that arrives while the user dials (ql-b57d0eaf m31-xiaomi, 21:32): busy for others, rings for the target. */
class DialCollisionTest {
    private val m31 = "e7371febd3f0"
    private val a15s = "1c5fbbcd07e0"

    @Test
    fun noDialMeansEveryCallRingsAsBefore() {
        assertEquals(DialCollision.Action.RING, DialCollision.decide(null, a15s))
        assertEquals(DialCollision.Action.RING, DialCollision.decide(null, null))
    }

    @Test
    fun aCallFromSomeoneElseWhileDiallingIsDeclinedBusy() {
        val dial = OwnDial(advertKey = "a1b2c3d4e5f6", targetPrefix = m31, name = "Ravi")
        assertEquals(DialCollision.Action.DECLINE_BUSY, DialCollision.decide(dial, a15s))
        assertEquals("a stranger's throwaway is never the target", DialCollision.Action.DECLINE_BUSY, DialCollision.decide(dial, null))
    }

    @Test
    fun theDialledPersonCallingFirstRingsSoTwoPhonesDiallingEachOtherConnect() {
        val contact = OwnDial(advertKey = "a1b2c3d4e5f6", targetPrefix = m31, name = "Ravi")
        assertEquals(DialCollision.Action.RING, DialCollision.decide(contact, m31.uppercase()))
        val fixed = OwnDial(advertKey = m31, targetPrefix = m31, name = "Ravi", fixedPrefix = true)
        assertEquals(DialCollision.Action.RING, DialCollision.decide(fixed, m31))
        val unknownRotating = OwnDial(advertKey = m31, targetPrefix = null, name = "Gold Fox")
        assertEquals("an unrecognised rotating ID is not a prefix", DialCollision.Action.DECLINE_BUSY, DialCollision.decide(unknownRotating, m31))
    }

    @Test
    fun theCallerIsToldBusyWithTheExistingWireReasonAndTheLogNamesNobody() {
        assertEquals(2, PrivateCallReason.BUSY.wireValue)
        assertTrue(BUSY_DECLINE_MESSAGE.startsWith("Busy:"))
        assertEquals(
            "call_collision: action=declined_busy | dialling=true | callerKnown=false",
            DialCollision.line(DialCollision.Action.DECLINE_BUSY, callerKnown = false),
        )
    }
}
