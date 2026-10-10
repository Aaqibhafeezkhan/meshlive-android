package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoStreamIoConfigTest {
    @Test
    fun parsesOnlyKnownFlushPolicies() {
        assertEquals(VideoFlushPolicy.EVERY_5_MS, VideoFlushPolicy.fromDebugValue("5ms"))
        assertEquals(VideoFlushPolicy.BUFFER_32_KIB_OR_50_MS, VideoFlushPolicy.fromDebugValue("32K"))
        assertEquals(VideoFlushPolicy.EVERY_5_MS, VideoFlushPolicy.fromDebugValue("unknown"))
    }

    @Test
    fun usesMeasuredFairBufferForCocOnly() {
        assertEquals(4 * 1024, defaultVideoStreamBufferBytes(BluetoothTransportOption.BLE_L2CAP))
        assertEquals(
            VideoStreamIoConfig.DEFAULT_BUFFER_BYTES,
            defaultVideoStreamBufferBytes(BluetoothTransportOption.BLE_GATT),
        )
        assertEquals(
            VideoStreamIoConfig.DEFAULT_BUFFER_BYTES,
            defaultVideoStreamBufferBytes(BluetoothTransportOption.CLASSIC_RFCOMM),
        )
    }
}
