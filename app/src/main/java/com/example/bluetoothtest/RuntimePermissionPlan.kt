package com.example.bluetoothtest

import android.Manifest

/**
 * Which runtime permissions the app asks for, as pure functions of the Android version and what the user is about to do
 * (so the "permission refused" flows are tested without a phone). The activities ask; this decides what to ask.
 *
 * Camera access is requested at video use. Notifications affect [readiness], but never block an explicit
 * call, stream or scan through [required].
 */
internal object RuntimePermissionPlan {
    private const val ANDROID_12 = 31
    private const val ANDROID_13 = 33

    /** What the first-run screen explains, one row each: Bluetooth (or location before Android 12), microphone, notifications. */
    fun onboarding(sdkInt: Int, callReadiness: Boolean = true): List<String> = buildList {
        if (sdkInt >= ANDROID_12) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (!callReadiness) add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (sdkInt >= ANDROID_13) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** What the home readiness card needs before it says "ready": Nearby devices, microphone and notifications; camera is deferred. */
    fun readiness(sdkInt: Int, callReadiness: Boolean = true): List<String> =
        if (callReadiness) onboarding(sdkInt) else onboarding(sdkInt, false).filter { it != Manifest.permission.POST_NOTIFICATIONS }

    /** Notification denial is a readiness warning, never a prerequisite for an explicit Meet/call endpoint. */
    fun endpoint(sdkInt: Int, callReadiness: Boolean = true): List<String> =
        readiness(sdkInt, callReadiness).filter { it != Manifest.permission.POST_NOTIFICATIONS }

    /**
     * What the screen the user is on needs right now. [capturesMedia]: a call or a broadcast (microphone, and the camera
     * unless [needsCamera] is false, as in an audio-only broadcast). [needsBleAccess]: scanning or advertising for people.
     */
    fun required(sdkInt: Int, capturesMedia: Boolean, needsCamera: Boolean, needsBleAccess: Boolean): List<String> = buildList {
        if (capturesMedia) {
            if (needsCamera) add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
        }
        if (sdkInt >= ANDROID_12) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (needsBleAccess) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        } else if (needsBleAccess) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
