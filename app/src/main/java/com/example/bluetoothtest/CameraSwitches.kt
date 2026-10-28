package com.example.bluetoothtest

import android.content.Intent

/**
 * Switches of the camera and encoder configuration batch (device-family backlog batch V). Every arm
 * here is Experimental and DEFAULT OFF; every setter ignores `true` outside DEBUG builds, like
 * [AudioLiveTunables], because a launch extra from another app must not change a release build.
 * The register of switches is SWITCHES ("Experimental, alternative and optional behaviour").
 *
 * Defaults that are ON (diagnostics, after-failure fallbacks) are not switches: they are turned off
 * per policy entry with `test_device_policy_off=<id>` (see [CameraPolicy.entries]).
 */
object CameraSwitches {
    /** `test_camera_reopen=true`: close and reopen the CameraDevice on families that need it (V-01 b). */
    @Volatile var reopenDevice: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_camera_session_configuration=true`: SessionConfiguration + session parameters path (V-02). */
    @Volatile var sessionConfiguration: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_camera_legacy_ae_range=true`: LEGACY level picks the smallest lower bound of the AE range (V-03). */
    @Volatile var legacyAeRange: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_camera_starvation_fallback=true`: a frame stall rebuilds the session with TEMPLATE_PREVIEW (V-06). */
    @Volatile var starvationFallback: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_capability_probe_configure=true`: a size within 8 px of the limit is probe-configured (V-08). */
    @Volatile var capabilityProbeConfigure: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_encoder_colour_keys=true`: BT.709 limited SDR colour keys on the encoder format (V-11). */
    @Volatile var encoderColourKeys: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * `test_exynos_qp_range=<min>,<max>`: vendor QP range of OMX.Exynos / c2.exynos encoders, set only
     * when the component lists the keys (V-12). Null is off.
     */
    @Volatile var exynosQpRange: IntRange? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /** `test_encoder_key_latency=true`: KEY_LATENCY=1 on the encoder format, retried without the key (V-14). */
    @Volatile var encoderKeyLatency: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_camera_release_timing=true`: capture-interval monitor outside DEBUG logging (V-22). */
    @Volatile var releaseCaptureTiming: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_camera_rotate_and_crop_none=true`: SCALER_ROTATE_AND_CROP = NONE where supported (V-23). */
    @Volatile var rotateAndCropNone: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_yuv_tail_clamp=true`: a YUV plane short by its last samples is copied with the tail clamped (V-24). */
    @Volatile var yuvTailClamp: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * `test_proximity_pause_hysteresis=true`: the ear-proximity camera pause needs near held 1.5 s and spaces two
     * toggles 3 s apart ([ProximityPauseDebounce], V-01). Experimental, default off: the root review kept the shipped
     * immediate pause/resume, because the hysteresis changes a shipped feature for everyone without phone evidence
     * (pending the 50-cycle ear check on the ASUS).
     */
    @Volatile var proximityPauseHysteresis: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** Root hook: call once while parsing the DEBUG launch extras. */
    fun applyLaunchExtras(intent: Intent?) {
        if (intent == null) return
        applyExtras(intent::getBooleanExtra, intent::getStringExtra)
    }

    /** The same parsing without Android types (unit tests). */
    fun applyExtras(boolExtra: (String, Boolean) -> Boolean, stringExtra: (String) -> String?) {
        reopenDevice = boolExtra("test_camera_reopen", false)
        sessionConfiguration = boolExtra("test_camera_session_configuration", false)
        legacyAeRange = boolExtra("test_camera_legacy_ae_range", false)
        starvationFallback = boolExtra("test_camera_starvation_fallback", false)
        capabilityProbeConfigure = boolExtra("test_capability_probe_configure", false)
        encoderColourKeys = boolExtra("test_encoder_colour_keys", false)
        exynosQpRange = parseQpRange(stringExtra("test_exynos_qp_range"))
        encoderKeyLatency = boolExtra("test_encoder_key_latency", false)
        releaseCaptureTiming = boolExtra("test_camera_release_timing", false)
        rotateAndCropNone = boolExtra("test_camera_rotate_and_crop_none", false)
        yuvTailClamp = boolExtra("test_yuv_tail_clamp", false)
        proximityPauseHysteresis = boolExtra("test_proximity_pause_hysteresis", false)
    }

    /** "20,50" -> 20..50; anything malformed or outside 0..255 is off. */
    internal fun parseQpRange(text: String?): IntRange? {
        val parts = text?.split(',', ';', '-')?.map(String::trim) ?: return null
        if (parts.size != 2) return null
        val low = parts[0].toIntOrNull() ?: return null
        val high = parts[1].toIntOrNull() ?: return null
        if (low !in 0..255 || high !in 0..255 || low > high) return null
        return low..high
    }

    /** Restores every default (unit tests). */
    internal fun resetForTest() {
        reopenDevice = false
        sessionConfiguration = false
        legacyAeRange = false
        starvationFallback = false
        capabilityProbeConfigure = false
        encoderColourKeys = false
        exynosQpRange = null
        encoderKeyLatency = false
        releaseCaptureTiming = false
        rotateAndCropNone = false
        yuvTailClamp = false
        proximityPauseHysteresis = false
    }
}
