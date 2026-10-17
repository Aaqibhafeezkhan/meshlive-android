package com.example.bluetoothtest

import android.content.Intent

/**
 * Batch U (audio capture, routing, focus) switches. Every non-default arm is DEBUG-only: a launch extra from
 * another app must not change a release build's behaviour (same rule as [AudioLiveTunables]). The shipped
 * defaults are the behaviour; each Experimental arm is kept, off by default, and listed in the SWITCHES
 * register by the root (see the batch U hand-back).
 *
 * Behaviour that is on by default (focus classification, start retry, silence notice, routing result checks,
 * effect-state logging) is controlled by its DevicePolicyRegistry entry in [AudioPolicyEntries]; the entry's
 * off-switch is the shared DEBUG extra `test_device_policy_off=<id>` read through [policyActive].
 */
object AudioSwitches {
    /**
     * `test_capture_source_fallback` (Experimental, default off): after confirmed digital silence the capture
     * restarts once per step on the next source VOICE_COMMUNICATION -> MIC -> CAMCORDER. Loses the platform
     * echo canceller (requested != effective is logged); never used in benchmarks.
     */
    @Volatile var captureSourceFallback: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * `test_audio_off_main` (Experimental, default off): AudioRecord/AudioTrack/AudioManager teardown (and the
     * opt-in [MicrophoneOpusEncoder.startOffMain]) run on the serialized [AudioCommandQueue] instead of the
     * caller's main thread. Aimed at the M31/Exynos 9611 audio ANR class (Oboe #1216/#1474).
     */
    @Volatile var offMainThread: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * `test_audio_mode_change_wait` (Experimental, default off, API 31+): after setMode(IN_COMMUNICATION) wait up
     * to [CallRoutingPolicy.MODE_CHANGE_WAIT_MS] for OnModeChangedListener before setCommunicationDevice
     * (Signal FullSignalAudioManagerApi31 sequence).
     */
    @Volatile var modeChangeWait: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * `test_audio_usb_headset_mode_normal` (Experimental, default off, low confidence): One UI + Qualcomm with a USB
     * headset plugged in uses MODE_NORMAL instead of MODE_IN_COMMUNICATION (Twilio's Galaxy S9/S21 workaround,
     * matched by traits, never by model). Needs a USB-headset hardware test before any promotion.
     */
    @Volatile var usbHeadsetModeNormal: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * `test_audio_complexity_governor` (Experimental, default off): the native call Opus encoder steps its
     * complexity 10 -> 8 -> 5 when the measured per-frame encode time stays high. The requested bitrate never
     * changes; the effective complexity is logged. MediaCodec encoders cannot be changed mid-call.
     */
    @Volatile var complexityGovernor: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * `test_audio_route_reassert` (default true, the control arm is false): re-assert the call's communication
     * device when the platform or another app changes it (rate limited). false restores the former behaviour of
     * setting the route once and on explicit user or headset events.
     */
    @Volatile var routeReassert: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /** True while the registry entry [id] is not switched off (`test_device_policy_off=id` or `all`, DEBUG only). */
    fun policyActive(id: String): Boolean {
        val off = DebugTunables.devicePolicyOff
        return "all" !in off && id !in off
    }

    /** Reads the DEBUG launch extras `test_audio_*` and `test_capture_source_fallback`. */
    fun applyExtras(boolExtra: (String, Boolean) -> Boolean) {
        captureSourceFallback = boolExtra("test_capture_source_fallback", false)
        offMainThread = boolExtra("test_audio_off_main", false)
        modeChangeWait = boolExtra("test_audio_mode_change_wait", false)
        usbHeadsetModeNormal = boolExtra("test_audio_usb_headset_mode_normal", false)
        complexityGovernor = boolExtra("test_audio_complexity_governor", false)
        routeReassert = boolExtra("test_audio_route_reassert", true)
    }

    /** The one call root hooks use (MainActivity launch parsing). */
    fun applyLaunchExtras(intent: Intent) = applyExtras(intent::getBooleanExtra)

    /** One line for the device-policy / switch report. */
    fun describe(): String =
        "captureSourceFallback=$captureSourceFallback;offMainThread=$offMainThread;modeChangeWait=$modeChangeWait;" +
            "usbHeadsetModeNormal=$usbHeadsetModeNormal;complexityGovernor=$complexityGovernor;routeReassert=$routeReassert"
}
