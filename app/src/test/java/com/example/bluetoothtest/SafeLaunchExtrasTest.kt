package com.example.bluetoothtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeLaunchExtrasTest {
    @Test
    fun extrasThatCannotBeUnparcelledAreDroppedInsteadOfCrashing() {
        // What Bundle.keySet() does for an unknown Parcelable class sent by another app.
        assertTrue(SafeLaunchExtras.unreadable { throw android.os.BadParcelableException("ClassNotFoundException when unmarshalling: x.Y") })
    }

    @Test
    fun readableExtrasAreKept() {
        assertFalse(SafeLaunchExtras.unreadable { setOf("test_control") })
    }
}
