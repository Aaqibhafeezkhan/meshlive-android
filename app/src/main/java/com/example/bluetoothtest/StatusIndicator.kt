package com.example.bluetoothtest

/** What the small status indicator in every tab's title bar says. One at a time, the first match wins. */
internal enum class StatusKind(val labelRes: Int, val problem: Boolean) {
    READY(R.string.status_ready, false),
    BLUETOOTH_OFF(R.string.status_bluetooth_off, true),
    BLUETOOTH_UNAVAILABLE(R.string.status_bluetooth_unavailable, true),
    PERMISSIONS(R.string.status_permissions, true),
    HIDDEN(R.string.status_hidden, true),
    BATTERY_SAVER(R.string.status_battery_saver, true),
    HOT(R.string.status_hot, true),
}

/**
 * Highest-priority state first: Bluetooth (or its permission, which Android checks first, see
 * [homeReadiness]), then not reachable for calls, then relaying paused by battery, then reduced quality
 * from heat. [hidden], [relayPaused] and [hot] come from the existing reachability, battery and thermal
 * logic; this adds no behaviour of its own.
 */
internal fun statusKind(readiness: HomeReadiness, hidden: Boolean, relayPaused: Boolean, hot: Boolean): StatusKind = when {
    readiness == HomeReadiness.UNSUPPORTED -> StatusKind.BLUETOOTH_UNAVAILABLE
    readiness == HomeReadiness.BLUETOOTH_OFF -> StatusKind.BLUETOOTH_OFF
    readiness == HomeReadiness.PERMISSIONS_NEEDED -> StatusKind.PERMISSIONS
    hidden -> StatusKind.HIDDEN
    relayPaused -> StatusKind.BATTERY_SAVER
    hot -> StatusKind.HOT
    else -> StatusKind.READY
}
