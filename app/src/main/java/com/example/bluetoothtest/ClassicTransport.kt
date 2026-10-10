package com.example.bluetoothtest

import java.util.UUID

data class ClassicTransport(val displayName: String, val serviceUuid: UUID) {
    companion object {
        val APP_SERVICE = ClassicTransport(
            "Bluetooth Classic RFCOMM",
            UUID.fromString("c8d5a100-9f88-4f65-a03d-7f5a2fb921fa"),
        )
    }
}
