package com.example.bluetoothtest

enum class BluetoothTransportOption(
    val label: String,
    val shortLabel: String,
    val maxVideoBitrateKbps: Int,
) {
    CLASSIC_RFCOMM(
        "Bluetooth Classic (RFCOMM) - Higher throughput; pairing may be required",
        "Classic RFCOMM",
        1_500,
    ),
    BLE_L2CAP(
        "Bluetooth LE (L2CAP CoC, Android only) - Experimental",
        "BLE L2CAP CoC - Experimental",
        1_200,
    ),
    BLE_GATT(
        "Bluetooth LE (GATT, Android only) - Pairing-free default",
        "BLE GATT",
        1_100,
    );

    companion object {
        val DEFAULT = BLE_GATT
    }
}

val BluetoothTransportOption.isBle: Boolean
    get() = this != BluetoothTransportOption.CLASSIC_RFCOMM
