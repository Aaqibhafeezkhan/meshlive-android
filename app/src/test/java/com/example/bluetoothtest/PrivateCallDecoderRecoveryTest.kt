package com.example.bluetoothtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateCallDecoderRecoveryTest {
    @Test
    fun softwareVp9RecoveryIsBoundedToTwoRestartsPerCall() {
        assertTrue(canRecoverPrivateCallVideoDecoder("c2.android.vp9.decoder", 0))
        assertTrue(canRecoverPrivateCallVideoDecoder("c2.android.vp9.decoder", 1))
        assertFalse(canRecoverPrivateCallVideoDecoder("c2.android.vp9.decoder", 2))
    }

    @Test
    fun unaffectedDecodersDoNotEnterTheCompatibilityRecoveryPath() {
        assertFalse(canRecoverPrivateCallVideoDecoder("OMX.Exynos.vp9.dec", 0))
        assertFalse(canRecoverPrivateCallVideoDecoder("c2.android.av1.decoder", 0))
    }
}
