package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Identity terms (audit 2026-10-21): "Safety code" is the call's pair code and the chats keep a code of their own,
 * because the two are computed from different keys. If they ever became the same, the UI could merge them.
 */
class IdentityTermsTest {
    @Test
    fun theCallSafetyCodeIsTheSameForBothPhonesOfAPair() {
        val a = IdentityFingerprint.fromBytes(ByteArray(32) { 1 })
        val b = IdentityFingerprint.fromBytes(ByteArray(32) { 2 })
        assertEquals(SafetyCode.derive(a, b), SafetyCode.derive(b, a))
        assertNotEquals("a pair code covers both keys", SafetyCode.half(a), SafetyCode.derive(a, b))
    }
}
