package com.example.bluetoothtest

import android.Manifest

/**
 * The "access needed" status after the permission prompt names only what was refused (test-suite report 2026-10-21: it
 * always said "Camera, microphone, and Nearby devices permissions are required." whatever was missing, and the text was
 * not a string resource).
 */
internal object MissingPermissions {
    enum class Group { NEARBY_DEVICES, CAMERA, MICROPHONE, LOCATION }

    /** The user-facing groups of [missing] Android permissions, in a fixed order, each once. */
    fun groups(missing: Collection<String>): List<Group> = missing.mapNotNull { permission ->
        when (permission) {
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE -> Group.NEARBY_DEVICES
            Manifest.permission.CAMERA -> Group.CAMERA
            Manifest.permission.RECORD_AUDIO -> Group.MICROPHONE
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION -> Group.LOCATION
            else -> null
        }
    }.distinct().sortedBy { it.ordinal }

    /** "a", "a and b", "a, b and c": [separator] between the first ones, [lastSeparator] before the last. */
    fun join(names: List<String>, separator: String, lastSeparator: String): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(separator) + lastSeparator + names.last()
    }
}
