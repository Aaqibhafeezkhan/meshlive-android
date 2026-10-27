package com.example.bluetoothtest

import com.example.bluetoothtest.PolicyConfidence.HIGH
import com.example.bluetoothtest.PolicyConfidence.LOW
import com.example.bluetoothtest.PolicyConfidence.MEDIUM
import com.example.bluetoothtest.PolicySourceKind.API_REFERENCE
import com.example.bluetoothtest.PolicySourceKind.CDD
import com.example.bluetoothtest.PolicySourceKind.GOOGLE_OPEN_SOURCE
import com.example.bluetoothtest.PolicySourceKind.PLATFORM_DOC
import com.example.bluetoothtest.PolicySourceStatus.OFFICIAL
import com.example.bluetoothtest.PolicySourceStatus.PARTIAL

/**
 * Camera and encoder configuration policy (device-family backlog batch V; area CAMERA). Entries are
 * contributed to the registry by one line (`CameraPolicy.entries` in DevicePolicyRegistry's
 * aggregate). Entries marked proposed have no effect: they only report that this phone matches, and
 * the Experimental switch in [CameraSwitches] is what turns the A/B arm on. Gates never depend on
 * these entries; requested resolution, FPS and bitrate are never substituted by them.
 *
 * Never use `DevicePolicyRegistry.entries` from here (initialisation order); matchers are the
 * `DeviceMatcher` fields only, never Build.MODEL.
 */
internal object CameraPolicy {
    private const val CAMERAX = "https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/camera/"
    private const val CAMERAX_QUIRKS = CAMERAX + "camera-camera2/src/main/java/androidx/camera/camera2/internal/compat/quirk/"
    private const val CAMERAX_VIDEO_QUIRKS = CAMERAX + "camera-video/src/main/java/androidx/camera/video/internal/compat/quirk/"
    private const val REVIEWED = "2026-10-14"

    private val CAMERAX_PIPE_CLOSE_SESSION = PolicySource(
        GOOGLE_OPEN_SOURCE,
        CAMERAX + "camera-camera2-pipe/src/main/java/androidx/camera/camera2/pipe/compat/Camera2Quirks.kt",
        "shouldCloseCaptureSessionOnDisconnect (b/277675483, b/282871038, b/316048171): HARDWARE qcom with SDK <= 31, " +
            "SDK <= 27 and samsungexynos7870 must close the capture session and wait before the next one; otherwise " +
            "the HAL produces a few frames then an unrecoverable error, or CameraDevice.close() stalls",
    )
    private val CAMERAX_CLOSE_DEVICE = PolicySource(
        GOOGLE_OPEN_SOURCE,
        CAMERAX_QUIRKS + "CloseCameraDeviceOnCameraGraphCloseQuirk.java",
        "b/282871038, b/369300443, b/425588561, b/426104225, b/369291594, b/385881561, b/538140458: recreating a " +
            "session without closing the CameraDevice crashes the camera HAL or stalls the pipeline on Samsung " +
            "Snapdragon (S22-S24 class) with API >= 36, Oppo/OnePlus/Realme API 30-33 (OplusHansManager freezes app " +
            "processes) and every Vivo; the fix is close the device, reopen, then create the session",
    )
    private val SESSION_CONFIGURATION_DOC = PolicySource(
        API_REFERENCE,
        "https://developer.android.com/reference/android/hardware/camera2/CameraDevice#isSessionConfigurationSupported(android.hardware.camera2.params.SessionConfiguration)",
        "API 29: asks the HAL whether a stream combination is supported before a session is created; the guaranteed " +
            "combination tables are a floor, so a combination outside them is not necessarily unsupported",
    )
    private val HARDWARE_LEVEL_DOC = PolicySource(
        API_REFERENCE,
        "https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL",
        "LIMITED=0, FULL=1, LEGACY=2, LEVEL_3=3, EXTERNAL=4: the constants are not ordered, so levels are compared by name",
    )
    private val WEBRTC_CAMERA_CAPTURER = PolicySource(
        GOOGLE_OPEN_SOURCE,
        "https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/src/java/org/webrtc/CameraCapturer.java",
        "opening a camera is retried 3 times 500 ms apart inside a 10 s budget; Camera2Session maps the error codes to " +
            "messages (in use, max cameras in use, disabled, device, service)",
    )
    private val CAMERA_STATE_CALLBACK_DOC = PolicySource(
        API_REFERENCE,
        "https://developer.android.com/reference/android/hardware/camera2/CameraDevice.StateCallback#onError(android.hardware.camera2.CameraDevice,%20int)",
        "ERROR_CAMERA_IN_USE and ERROR_MAX_CAMERAS_IN_USE are transient (another client holds the camera); " +
            "ERROR_CAMERA_DISABLED is a policy or privacy decision; ERROR_CAMERA_DEVICE and ERROR_CAMERA_SERVICE need a full close",
    )
    private val CAMERAX_TEMPLATE_QUIRKS = PolicySource(
        GOOGLE_OPEN_SOURCE,
        CAMERAX_QUIRKS + "AbnormalStreamWhenImageAnalysisBindWithTemplateRecordQuirk.java",
        "b/395822788, b/409478042: TEMPLATE_RECORD plus an extra YUV stream makes some HALs deliver only the first frames; " +
            "CAPTURE_INTENT_PREVIEW (TEMPLATE_PREVIEW) is the CameraX fix. Siblings: PreviewUnderExposureQuirk " +
            "(b/359425774, all TCL), PreviewDelayWhenVideoCaptureIsBoundQuirk (b/223643510, all Huawei), " +
            "ImageCaptureFailedWhenVideoCaptureIsBoundQuirk (b/239369953 and others, all UniSoc)",
    )
    private val CAMERAX_TIMEBASE = PolicySource(
        GOOGLE_OPEN_SOURCE,
        CAMERAX_VIDEO_QUIRKS + "CameraUseInconsistentTimebaseQuirk.java",
        "b/197805856, b/280121263, b/295060316, b/316057919: camera frame timestamps can use a different timebase than " +
            "SENSOR_INFO_TIMESTAMP_SOURCE reports (Samsung with samsungexynos7570/7870 or qcom, SOC sm4350/sm6375/sm7325); " +
            "CameraX VideoTimebaseConverter compares the first sensor timestamp with elapsedRealtime and uptime",
    )
    private val CAMERAX_REPORTED_SIZE = PolicySource(
        GOOGLE_OPEN_SOURCE,
        CAMERAX_VIDEO_QUIRKS + "MediaCodecInfoReportIncorrectInfoQuirk.java",
        "b/192431846, b/216583006, b/278843124, b/323984106 and others: VideoCapabilities.isSizeSupported is false for " +
            "1920x1080 on encoders that report a 1920x1072 maximum although configure succeeds; CameraX verifies by configuring",
    )
    private val CAMERAX_SIZE_CANNOT_ENCODE = PolicySource(
        GOOGLE_OPEN_SOURCE,
        CAMERAX_VIDEO_QUIRKS + "SizeCannotEncodeVideoQuirk.java",
        "b/318036483: an encoder that cannot encode a size is handled by aligning width and height to " +
            "VideoCapabilities width/height alignment",
    )
    private val VIDEO_CAPABILITIES_ALIGNMENT_DOC = PolicySource(
        API_REFERENCE,
        "https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities#getWidthAlignment()",
        "the encoder's required width/height granularity; WebRTC and Chromium (crbug 1084702) assume 16 and FFmpeg " +
            "documents c2.mtk H.264/HEVC needing 16x16",
    )
    private val CAMERAX_DATASPACE = PolicySource(
        GOOGLE_OPEN_SOURCE,
        CAMERAX_VIDEO_QUIRKS + "MediaCodecDefaultDataSpaceQuirk.java",
        "b/382186115: with GL between the camera and MediaCodec the colour standard, range and transfer are lost and the " +
            "encoder guesses (it varies by resolution and OEM); the fix sets them explicitly",
    )
    private val MEDIAFORMAT_COLOUR_DOC = PolicySource(
        API_REFERENCE,
        "https://developer.android.com/reference/android/media/MediaFormat#KEY_COLOR_STANDARD",
        "KEY_COLOR_STANDARD, KEY_COLOR_RANGE and KEY_COLOR_TRANSFER (API 24) carry BT.709 limited-range SDR explicitly",
    )
    private val EXYNOS_QP_RANGE = PolicySource(
        GOOGLE_OPEN_SOURCE,
        "https://github.com/LineageOS/android_hardware_samsung_slsi-linaro_openmax/blob/15ab30812af9fe39fff2ae3cd867d7e954c73881/openmax/component/video/enc/vp9/Exynos_OMX_Vp9enc.c",
        "the Exynos VP9 OMX component registers vendor.sec-ext-enc-qp-range.{I,P,B}-{min,max}QP and applies them through " +
            "OMX_IndexConfigVideoQPRange (Set_QpRange); the standard KEY_VIDEO_QP_* keys never reach OMX components",
    )
    private val VENDOR_PARAMETERS_DOC = PolicySource(
        API_REFERENCE,
        "https://developer.android.com/reference/android/media/MediaCodec#getSupportedVendorParameters()",
        "API 31: lists the vendor parameter names a component accepts; on any API the keys also appear in " +
            "getInputFormat()/getOutputFormat()",
    )
    private val CDD_DYNAMIC_BITRATE = PolicySource(
        CDD,
        "https://source.android.com/docs/compatibility/16/android-16-cdd#5_2_video_encoding",
        "dynamic bitrate change is required for H.264, VP8, VP9 and HEVC encoders, not AV1, and CBR accuracy " +
            "[C-SR-2] is a recommendation (15% over target per 1 s window)",
    )
    private val CHROMIUM_NDK_ENCODER = PolicySource(
        GOOGLE_OPEN_SOURCE,
        "https://chromium.googlesource.com/chromium/src/+/refs/heads/main/media/gpu/android/ndk_video_encode_accelerator.cc",
        "sets KEY_LATENCY for encoders but not for H.264 Baseline (crbug 409110228: some devices fail configure); " +
            "KEY_PRIORITY 0 only when low delay is required",
    )
    private val ENCODER_INIT_PATTERN = PolicySource(
        GOOGLE_OPEN_SOURCE,
        "https://github.com/androidx/media/blob/release/libraries/transformer/src/main/java/androidx/media3/transformer/DefaultEncoderFactory.java",
        "Media3 Transformer builds the encoder format with a fallback: a rejected or unsupported setting is relaxed and " +
            "a different encoder component of the same codec is tried; Signal's video converter and scrcpy's SurfaceEncoder " +
            "retry the next candidate when configure/start fails (NO_MEMORY 0xfffffff4 also seen as ERROR_INSUFFICIENT_RESOURCE)",
    )
    private val CODEC_EXCEPTION_DOC = PolicySource(
        API_REFERENCE,
        "https://developer.android.com/reference/android/media/MediaCodec.CodecException#getErrorCode()",
        "ERROR_INSUFFICIENT_RESOURCE (1100) and ERROR_RECLAIMED (1101) are resource-contention errors a retry can clear",
    )
    private val QUALCOMM_ANDROID16_REPORT = PolicySource(
        GOOGLE_OPEN_SOURCE,
        "https://github.com/androidx/media/issues/3087",
        "black, purple or brown encoded frames on Snapdragon 8 Gen 3 with Android 16 (12 of 12 reporters); cause unknown " +
            "(GPU driver or the HEVC encoder); AV1 and VP9 not reported",
    )
    private val SENSOR_PRIVACY_DOC = PolicySource(
        PLATFORM_DOC,
        "https://developer.android.com/training/permissions/explaining-access",
        "with the Android 12 camera toggle off the app receives a blank camera feed and no error callback",
    )

    // ---- Active entries ------------------------------------------------------------------------

    val SESSION_WAIT_CLOSED = DevicePolicyEntry(
        id = "camera-session-wait-closed",
        area = PolicyArea.CAMERA,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        title = "Camera session rebuild waits for onClosed on Qualcomm hardware up to Android 12",
        matcher = DeviceMatcher(hardware = setOf("qcom"), sdkMax = 31),
        effects = listOf(CameraWaitSessionClosed),
        behaviour = "When the camera session is rebuilt (viewfinder refresh, ear-proximity resume), the old session is " +
            "closed and its onClosed callback is awaited (at most 1 s) before the next session is created on the same " +
            "CameraDevice. Phones that do not match create it at once, as before.",
        sources = listOf(CAMERAX_PIPE_CLOSE_SESSION),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no local camera stall recorded; fake-callback state machine test only"),
        confidence = MEDIUM,
        confidenceNote = "HIGH that the CameraX quirk exists for this scope; MEDIUM that the ASUS (msmnile) and Xiaomi " +
            "(atoll) phones hit it, which a 50-cycle pause/resume run would show; the cost is tens of milliseconds",
        reviewed = REVIEWED,
        origin = "Device-family backlog V-01: CameraVideoEncoder closed the session and created the next one immediately",
        fallback = "Off: the next session is created immediately after session.close(), as before.",
    )

    private fun reopenProposal(
        id: String,
        title: String,
        matcher: DeviceMatcher,
        scope: String,
        status: PolicySourceStatus,
        confidence: PolicyConfidence,
        note: String,
    ) = DevicePolicyEntry(
        id = id,
        area = PolicyArea.CAMERA,
        validation = PolicyValidation.EXPERIMENTAL,
        title = title,
        matcher = matcher,
        effects = listOf(CameraReopenDeviceOnRebuild),
        behaviour = "Proposal ($scope): rebuild the camera by closing the session, closing the CameraDevice, reopening it " +
            "and creating the session. It costs 300-800 ms and flickers the camera privacy indicator, so it runs only with " +
            "the DEBUG Experimental switch test_camera_reopen=true; the entry itself changes nothing.",
        sources = listOf(CAMERAX_CLOSE_DEVICE),
        sourceStatus = status,
        evidence = listOf("none: needs an S24 / ASUS / Xiaomi A/B (50 pause/resume cycles)"),
        confidence = confidence,
        confidenceNote = note,
        reviewed = REVIEWED,
        origin = "Device-family backlog V-01 (b)",
        proposed = true,
        fallback = "Off or unmatched: the session is rebuilt on the same CameraDevice, as before.",
    )

    val REOPEN_SAMSUNG_QUALCOMM = reopenProposal(
        id = "camera-device-reopen-one-ui-qualcomm-api36",
        title = "Reopen the CameraDevice on rebuild: Samsung Snapdragon, Android 16+",
        matcher = DeviceMatcher(socFamilies = setOf(SocFamily.QUALCOMM), skins = setOf(OemSkin.ONE_UI), sdkMin = 36),
        scope = "Qualcomm SoC, One UI, API 36+",
        status = PARTIAL,
        confidence = MEDIUM,
        note = "CameraX lists the S22-S24 Snapdragon models on API >= 36 (b/538140458); this matcher is the runtime " +
            "equivalent without model names and is broader; whether the owned S24 is a Snapdragon variant is unconfirmed " +
            "(check ro.hardware)",
    )
    val REOPEN_OPLUS = reopenProposal(
        id = "camera-device-reopen-oplus-api30-33",
        title = "Reopen the CameraDevice on rebuild: Oppo, Realme and OnePlus on Android 11-13",
        matcher = DeviceMatcher(skins = setOf(OemSkin.COLOROS, OemSkin.REALME_UI, OemSkin.OXYGENOS), sdkMin = 30, sdkMax = 33),
        scope = "ColorOS / Realme UI / OxygenOS, API 30-33",
        status = OFFICIAL,
        confidence = MEDIUM,
        note = "CameraX documents OplusHansManager freezing app processes so a late camera close cannot be delayed; " +
            "no owned phone",
    )
    val REOPEN_VIVO = reopenProposal(
        id = "camera-device-reopen-vivo",
        title = "Reopen the CameraDevice on rebuild: Vivo",
        matcher = DeviceMatcher(manufacturers = setOf("vivo")),
        scope = "manufacturer vivo",
        status = OFFICIAL,
        confidence = LOW,
        note = "CameraX: custom Vivo code in CameraDevice.close NPEs when closed late; one source, no owned phone",
    )

    val STREAM_COMBINATION_VALIDATION = DevicePolicyEntry(
        id = "camera-stream-combination-validation",
        area = PolicyArea.CAMERA,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        title = "Camera BENCHMARK line carries the hardware level and isSessionConfigurationSupported",
        matcher = DeviceMatcher(),
        effects = listOf(CameraStreamCombinationCheck),
        behaviour = "Before the capture session is created the stream set (preview, encoder input, optional YUV reference) is " +
            "checked with CameraDevice.isSessionConfigurationSupported and logged with INFO_SUPPORTED_HARDWARE_LEVEL " +
            "(BENCHMARK camera_session). Diagnostic only: the check changes nothing. With the DEBUG Experimental switch " +
            "test_camera_session_configuration=true the session is created through SessionConfiguration with the AE fps " +
            "range as a session parameter, and an unsupported set drops the reference reader, then the preview, " +
            "recorded as requested != effective.",
        sources = listOf(SESSION_CONFIGURATION_DOC, HARDWARE_LEVEL_DOC),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: diagnostic; stream-plan unit tests"),
        confidence = HIGH,
        confidenceNote = "documented API; the call can throw or answer true on old HALs, so the watchdog still guards " +
            "the result, and the migration arm is unmeasured on all six phones",
        reviewed = REVIEWED,
        origin = "Device-family backlog V-02: the session was created blind and relied on onConfigureFailed",
        fallback = "Off: no query is made; the deprecated createCaptureSession(List<Surface>) path runs as before.",
    )

    val OPEN_ERROR_RETRY = DevicePolicyEntry(
        id = "camera-open-error-retry",
        area = PolicyArea.CAMERA,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        title = "Transient camera open errors are retried; policy errors are explained",
        matcher = DeviceMatcher(),
        effects = listOf(CameraOpenRetry),
        behaviour = "CameraDevice.StateCallback.onError with CAMERA_IN_USE or MAX_CAMERAS_IN_USE reopens up to 3 times, 500 ms " +
            "apart; CAMERA_DEVICE or CAMERA_SERVICE closes fully, backs off 1 s and reopens once; CAMERA_DISABLED reports " +
            "that the camera is blocked by policy or the privacy toggle. Only after a failure; onDisconnected is not retried. " +
            "Every retry is logged (BENCHMARK camera_open_retry).",
        sources = listOf(WEBRTC_CAMERA_CAPTURER, CAMERA_STATE_CALLBACK_DOC),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: failure path only; error-table unit test"),
        confidence = HIGH,
        confidenceNote = "the same retry shape ships in WebRTC; whether a Samsung HAL wedged by ERROR_CAMERA_DEVICE recovers " +
            "after 1 s is unmeasured, so the device-error retry is single",
        reviewed = REVIEWED,
        origin = "Device-family backlog V-05: any error code ended the stream at once",
        fallback = "Off: any camera error reports a failure at once, as before.",
    )

    val FRAME_STALL_WATCHDOG = DevicePolicyEntry(
        id = "camera-frame-stall-watchdog",
        area = PolicyArea.CAMERA,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        title = "Camera frame-stall watchdog logs a configured session that delivers no frames",
        matcher = DeviceMatcher(),
        effects = listOf(CameraFrameStallLog),
        behaviour = "After a camera session is configured, fewer than 3 encoded frames in 2 s (or none in 1.5 s) logs " +
            "BENCHMARK camera_frame_stall with the template and stream set. The response (one rebuild with " +
            "TEMPLATE_PREVIEW) is Experimental: test_camera_starvation_fallback=true, never in benchmark launches.",
        sources = listOf(CAMERAX_TEMPLATE_QUIRKS),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no local phone is expected to stall; threshold unit tests"),
        confidence = MEDIUM,
        confidenceNote = "CameraX documents the stalls per device model; the thresholds are ours (1.5 s / 2 s) and a slow " +
            "first keyframe on a loaded phone could log a false positive, which is why only the log is on",
        reviewed = REVIEWED,
        origin = "Device-family backlog V-06: a configured session that never delivered frames raised no error",
        fallback = "Off: no watchdog timer runs.",
    )

    val PRIVACY_TOGGLE_NOTICE = DevicePolicyEntry(
        id = "camera-privacy-toggle-notice",
        area = PolicyArea.CAMERA,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        title = "A blank camera feed with the Android 12 camera toggle off is explained",
        matcher = DeviceMatcher(sdkMin = 31),
        effects = listOf(CameraPrivacyToggleNotice),
        behaviour = "On the software-encoder YUV path, a camera feed that stays black (mean luma below 2 on every sample for " +
            "2 s) while SensorPrivacyManager.supportsSensorToggle(CAMERA) is true shows 'Camera access is turned off in " +
            "Quick Settings' once. A notice only: frames, encoder and stream are untouched. Surface-input encoders have " +
            "no pixels to inspect, so they get no notice.",
        sources = listOf(SENSOR_PRIVACY_DOC),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: black-frame detector unit tests"),
        confidence = MEDIUM,
        confidenceNote = "documented behaviour (blank feed, no error); a dark room is not a toggle, hence the toggle-support " +
            "condition and the 2 s of near-zero luma; the microphone side belongs to the audio batch",
        reviewed = REVIEWED,
        origin = "Device-family backlog V-07: the call looked alive while the peer saw black",
        fallback = "Off: no notice.",
    )

    val ENCODER_INIT_FALLBACK = DevicePolicyEntry(
        id = "encoder-init-fallback",
        area = PolicyArea.CODEC,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        title = "Encoder initialisation falls back: drop optional keys, retry on resource errors, next encoder",
        matcher = DeviceMatcher(),
        effects = listOf(EncoderInitFallback),
        behaviour = "User sessions: when the hardware encoder throws while it is created, configured, given its input surface " +
            "or started, optional format keys are dropped newest first (latency, colour, vendor QP, intra refresh, QP bounds, " +
            "encoding statistics, complexity, temporal layering); a resource error (insufficient resource, no memory) " +
            "releases the instance and retries once, then once with KEY_PRIORITY 1; then the next encoder component of " +
            "the same codec and input mode is tried. Configure and start are bounded (10 s). Every start logs " +
            "BENCHMARK encoder_init_fallback with requested and effective encoder and keys. A different codec is never " +
            "chosen here (it is protocol-visible). Benchmark launches and DEBUG encoder bench runs keep the former single attempt.",
        sources = listOf(ENCODER_INIT_PATTERN, CODEC_EXCEPTION_DOC),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: failure path only; fake-codec chain unit tests"),
        confidence = HIGH,
        confidenceNote = "the pattern ships in Media3, Signal and scrcpy; no local phone has failed encoder creation, " +
            "the M31/A55 under call + self-view + stream pressure is the first candidate to exercise it",
        reviewed = REVIEWED,
        origin = "Device-family backlog V-09: one createByCodecName plus one retry without layering",
        fallback = "Off or benchmark launch: a single configure attempt (plus the temporal-layering retry), as before.",
    )

    // ---- Proposed (reported when they match; switches decide) ----------------------------------

    private fun starvationSeed(id: String, title: String, matcher: DeviceMatcher, quirk: String) = DevicePolicyEntry(
        id = id,
        area = PolicyArea.CAMERA,
        validation = PolicyValidation.EXPERIMENTAL,
        title = title,
        matcher = matcher,
        effects = listOf(CameraTemplatePreviewSeed),
        behaviour = "Proposal: CameraX documents $quirk on this family; prefer TEMPLATE_PREVIEW (CAPTURE_INTENT_PREVIEW) " +
            "for the repeating request. No effect: the runtime frame-stall watchdog is the trigger, and only with " +
            "test_camera_starvation_fallback=true.",
        sources = listOf(CAMERAX_TEMPLATE_QUIRKS),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no owned phone of this family"),
        confidence = LOW,
        confidenceNote = "one open-source quirk list, device models rather than a family contract; Galaxy F55/M55 " +
            "(m55xq) cannot be matched without a model name",
        reviewed = REVIEWED,
        origin = "Device-family backlog V-06 seeds",
        proposed = true,
        fallback = "TEMPLATE_RECORD, as before.",
    )

    val STARVATION_HUAWEI = starvationSeed(
        "camera-starvation-template-preview-huawei", "Huawei: preview delay with an encoder surface",
        DeviceMatcher(manufacturers = setOf("huawei")), "PreviewDelayWhenVideoCaptureIsBoundQuirk (b/223643510)",
    )
    val STARVATION_TCL = starvationSeed(
        "camera-starvation-template-preview-tcl", "TCL: preview under-exposure with an encoder surface",
        DeviceMatcher(manufacturers = setOf("tcl")), "PreviewUnderExposureQuirk (b/359425774)",
    )
    val STARVATION_UNISOC = starvationSeed(
        "camera-starvation-template-preview-unisoc", "UniSoc: failures with a third camera stream",
        DeviceMatcher(socFamilies = setOf(SocFamily.UNISOC)), "ImageCaptureFailedWhenVideoCaptureIsBoundQuirk (b/239369953)",
    )

    val TIMEBASE_INCONSISTENT = DevicePolicyEntry(
        id = "camera-timebase-inconsistent",
        area = PolicyArea.CAMERA,
        validation = PolicyValidation.EXPERIMENTAL,
        title = "Samsung Qualcomm and old Exynos: camera timestamps may be on the wrong base",
        matcher = DeviceMatcher(manufacturers = setOf("samsung"), hardware = setOf("qcom", "samsungexynos7570", "samsungexynos7870")),
        effects = listOf(CameraTimebaseInfo),
        behaviour = "Information only: reports in the device_policy line that frame timestamps may not follow " +
            "SENSOR_INFO_TIMESTAMP_SOURCE. The encoder already normalises the first PTS against elapsedRealtime and uptime " +
            "(codecPresentationTimeEpochOffsetUs) and logs the offset (Encoder clock).",
        sources = listOf(CAMERAX_TIMEBASE),
        sourceStatus = PARTIAL,
        evidence = listOf("none: the Encoder clock log line shows the offset per run"),
        confidence = MEDIUM,
        confidenceNote = "CameraX lists Samsung HARDWARE qcom (every Samsung Snapdragon phone); MeshLive's existing offset " +
            "check probably already covers it, which the Encoder clock lines of a Snapdragon Samsung would confirm",
        reviewed = REVIEWED,
        origin = "Device-family backlog V-18",
        proposed = true,
        fallback = "No effect either way.",
    )

    val SIZE_ALIGNMENT = DevicePolicyEntry(
        id = "encoder-size-alignment",
        area = PolicyArea.CODEC,
        validation = PolicyValidation.EXPERIMENTAL,
        title = "Encoders that need 16-pixel alignment would need cropped or padded rungs",
        matcher = DeviceMatcher(),
        effects = listOf(EncoderSizeAlignmentInfo),
        behaviour = "Proposal. Diagnostic only today: BENCHMARK encoder_alignment logs the encoder's width/height alignment " +
            "against the requested size and the 16-pixel rule. Changing a coded size is protocol-visible (rungs 426x240, " +
            "640x360 and 854x480 are not all 16-aligned), so it waits for the hardware-ladder design record and is off.",
        sources = listOf(VIDEO_CAPABILITIES_ALIGNMENT_DOC, CAMERAX_SIZE_CANNOT_ENCODE),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no AV1/VP9 encoder failure on alignment recorded"),
        confidence = MEDIUM,
        confidenceNote = "the rule is universal in production clients, but only H.264/HEVC cases are documented; AV1/VP9 " +
            "encoders usually report 2 or 8",
        reviewed = REVIEWED,
        origin = "Device-family backlog V-10",
        proposed = true,
        fallback = "Requested sizes are used unchanged.",
    )

    val SIZE_SUPPORT_FALSE_NEGATIVE = DevicePolicyEntry(
        id = "encoder-size-support-false-negative",
        area = PolicyArea.CODEC,
        validation = PolicyValidation.EXPERIMENTAL,
        title = "isSizeSupported can be false for a size that configures",
        matcher = DeviceMatcher(),
        effects = listOf(EncoderSizeSupportProbe),
        behaviour = "Proposal. A size within 8 px of the encoder's reported maximum that isSizeSupported rejects is logged " +
            "(BENCHMARK capability_size_candidate); with test_capability_probe_configure=true it is probe-configured once on " +
            "a throwaway codec (cached per component and size) and allowed when that succeeds. Gates and requested " +
            "settings are unchanged; a capability refresh keeps the old evidence.",
        sources = listOf(CAMERAX_REPORTED_SIZE),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no owned phone reports it; the candidate log line shows exposure"),
        confidence = MEDIUM,
        confidenceNote = "HIGH that the lie exists on low-end MediaTek/UniSoc phones; whether any required phone or AV1/VP9 " +
            "encoder hits it is unmeasured",
        reviewed = REVIEWED,
        origin = "Device-family backlog V-08",
        proposed = true,
        fallback = "isSizeSupported decides, as before.",
    )

    val EXPLICIT_COLOUR_KEYS = DevicePolicyEntry(
        id = "encoder-explicit-colour-keys",
        area = PolicyArea.CODEC,
        validation = PolicyValidation.EXPERIMENTAL,
        title = "Explicit BT.709 limited-range SDR colour keys on the encoder format",
        matcher = DeviceMatcher(),
        effects = listOf(EncoderColourKeys),
        behaviour = "Proposal. test_encoder_colour_keys=true sets KEY_COLOR_STANDARD=BT709, KEY_COLOR_RANGE=LIMITED and " +
            "KEY_COLOR_TRANSFER=SDR_VIDEO on every MediaCodec encoder format so no stage infers the data space. It changes the " +
            "encoded colour tags on every phone, so it needs a colour/VMAF A/B on all six phones before any default; the " +
            "decoder side (VideoStreamDecoder) is a separate hook.",
        sources = listOf(CAMERAX_DATASPACE, MEDIAFORMAT_COLOUR_DOC),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: A/B not run"),
        confidence = MEDIUM,
        confidenceNote = "documented API and a CameraX fix for lost data space on GL paths; the effect on our encoders' " +
            "output colour is unmeasured",
        reviewed = REVIEWED,
        origin = "Device-family backlog V-11",
        proposed = true,
        fallback = "No colour keys, as before.",
    )

    val EXYNOS_QP_RANGE_ENTRY = DevicePolicyEntry(
        id = "exynos-vendor-qp-range",
        area = PolicyArea.CODEC,
        validation = PolicyValidation.EXPERIMENTAL,
        title = "OMX.Exynos / c2.exynos encoders: QP range through the vendor keys",
        matcher = DeviceMatcher(),
        component = CodecComponentMatcher(prefixes = setOf("OMX.Exynos.", "c2.exynos.")),
        effects = listOf(ExynosVendorQpRange),
        behaviour = "Proposal. test_exynos_qp_range=<min>,<max> sets vendor.sec-ext-enc-qp-range.{I,P}-{min,max}QP, only for " +
            "keys the component lists (getSupportedVendorParameters on API 31+, or the input/output format keys), after " +
            "configure and before start. An A/B arm against the M31 VP9 overshoot (+44% against the CDD C-SR-2 15% " +
            "recommendation). Encoder-dropped frames (drop control default On) are not transport loss.",
        sources = listOf(EXYNOS_QP_RANGE, VENDOR_PARAMETERS_DOC, CDD_DYNAMIC_BITRATE),
        sourceStatus = PARTIAL,
        evidence = listOf("benchmarks/runs/lane-b-burst-r1-529f36cc", "docs/dev/decisions/README.md row 73f517c5 -> b5ebe110"),
        confidence = LOW,
        confidenceNote = "HIGH that the keys exist in the Lineage-published vendor source; MEDIUM that retail firmware behaves " +
            "alike; the effect on the M31 is unmeasured",
        reviewed = REVIEWED,
        origin = "Device-family backlog V-12 (K A27/A28)",
        proposed = true,
        fallback = "No vendor keys; the bitrate adjuster path alone, as before.",
    )

    val ENCODER_KEY_LATENCY = DevicePolicyEntry(
        id = "encoder-key-latency",
        area = PolicyArea.CODEC,
        validation = PolicyValidation.EXPERIMENTAL,
        title = "Codec2 encoders: KEY_LATENCY=1",
        matcher = DeviceMatcher(sdkMin = 30),
        component = CodecComponentMatcher(prefixes = setOf("c2.")),
        effects = listOf(EncoderKeyLatency),
        behaviour = "Proposal. test_encoder_key_latency=true sets KEY_LATENCY=1 (retried without the key; it is the first key " +
            "dropped), never for H.264 Baseline (crbug 409110228). Decoder-side low latency is a separate component; " +
            "OMX components ignore the key.",
        sources = listOf(CHROMIUM_NDK_ENCODER),
        sourceStatus = PARTIAL,
        evidence = listOf("none: A/B not run"),
        confidence = MEDIUM,
        confidenceNote = "production code in Chromium, scrcpy and Linphone; no AV1/VP9 evidence of a latency gain",
        reviewed = REVIEWED,
        origin = "Device-family backlog V-14",
        proposed = true,
        fallback = "No latency key, as before.",
    )

    val QUALCOMM_ANDROID16_FIRST_FRAMES = DevicePolicyEntry(
        id = "qualcomm-android16-first-frames-suspect",
        area = PolicyArea.CODEC,
        validation = PolicyValidation.EXPERIMENTAL,
        title = "Snapdragon on Android 16: black or purple first frames reported",
        matcher = DeviceMatcher(socFamilies = setOf(SocFamily.QUALCOMM), sdkMin = 36),
        effects = listOf(EncoderFirstFramesSanity),
        behaviour = "Information only: the first YUV frames are checked for black or purple averages and logged as " +
            "BENCHMARK encoder_first_frames_suspect; no codec switch. Surface-input encoders have no pixels to check.",
        sources = listOf(QUALCOMM_ANDROID16_REPORT),
        sourceStatus = PARTIAL,
        evidence = listOf("none: S24 Android 16 run needed"),
        confidence = LOW,
        confidenceNote = "one tracker thread; reports concern the HEVC path, AV1 and VP9 are not reported",
        reviewed = REVIEWED,
        origin = "Device-family backlog V-19",
        proposed = true,
        fallback = "No check.",
    )

    val entries: List<DevicePolicyEntry> = listOf(
        SESSION_WAIT_CLOSED,
        REOPEN_SAMSUNG_QUALCOMM,
        REOPEN_OPLUS,
        REOPEN_VIVO,
        STREAM_COMBINATION_VALIDATION,
        OPEN_ERROR_RETRY,
        FRAME_STALL_WATCHDOG,
        PRIVACY_TOGGLE_NOTICE,
        STARVATION_HUAWEI,
        STARVATION_TCL,
        STARVATION_UNISOC,
        TIMEBASE_INCONSISTENT,
        ENCODER_INIT_FALLBACK,
        SIZE_ALIGNMENT,
        SIZE_SUPPORT_FALSE_NEGATIVE,
        EXPLICIT_COLOUR_KEYS,
        EXYNOS_QP_RANGE_ENTRY,
        ENCODER_KEY_LATENCY,
        QUALCOMM_ANDROID16_FIRST_FRAMES,
    )

    private val REOPEN_IDS = setOf(REOPEN_SAMSUNG_QUALCOMM.id, REOPEN_OPLUS.id, REOPEN_VIVO.id)

    /**
     * Resolves this batch's entries for [traits] with the registry's rules (universal matcher, component rule,
     * proposals reported but inert, `test_device_policy_off` ids and "all"). The camera paths ask here, so they behave
     * the same whether or not DevicePolicyRegistry already aggregates [entries]; the registry lists them for the
     * device_policy line and the exported JSON.
     */
    fun resolve(traits: DeviceTraits, disabled: Set<String> = DeviceFamilyPolicy.switchedOff): ResolvedDevicePolicy =
        DevicePolicyOff.resolve(entries, traits, disabled)

    /** True when one of the device-reopen proposals matches this phone and is not switched off. */
    fun reopenProposalMatches(resolved: ResolvedDevicePolicy, disabled: Set<String>): Boolean {
        val off = disabled + resolved.traits.policyOff
        if ("all" in off) return false
        return resolved.matchingProposals.any { it.id in REOPEN_IDS && it.id !in off }
    }

    /** True for an entry that is on for this phone and not switched off (active entries only). */
    fun isOn(id: String, traits: DeviceTraits = DeviceTraits.current(), disabled: Set<String> = DeviceFamilyPolicy.switchedOff): Boolean =
        resolve(traits, disabled).isActive(id)

    /** True when a proposed entry matches and is not switched off by `test_device_policy_off`. */
    fun proposalMatches(id: String, traits: DeviceTraits = DeviceTraits.current(), disabled: Set<String> = DeviceFamilyPolicy.switchedOff): Boolean {
        val resolved = resolve(traits, disabled)
        val off = disabled + traits.policyOff
        return "all" !in off && id !in off && resolved.matchingProposals.any { it.id == id }
    }

    /**
     * Encoder init fallback runs in user sessions only: not in a benchmark launch (a case measures what it requested),
     * not for the DEBUG encoder bench, and not when its entry is switched off.
     */
    fun encoderInitFallbackEnabled(
        traits: DeviceTraits = DeviceTraits.current(),
        benchmarkLaunch: Boolean = DebugTunables.benchmarkLaunch,
        bench: Boolean = false,
        disabled: Set<String> = DeviceFamilyPolicy.switchedOff,
    ): Boolean = !benchmarkLaunch && !bench && isOn(ENCODER_INIT_FALLBACK.id, traits, disabled)
}

internal data object CameraOpenRetry : DevicePolicyEffect {
    override fun describe(): String = "camera open errors: transient ones retried, policy ones explained"
}

internal data object CameraPrivacyToggleNotice : DevicePolicyEffect {
    override fun describe(): String = "notice when a black camera feed coincides with a camera privacy toggle"
}

internal data object CameraFrameStallLog : DevicePolicyEffect {
    override fun describe(): String = "log BENCHMARK camera_frame_stall when a configured session delivers no frames"
}

internal data object CameraTemplatePreviewSeed : DevicePolicyEffect {
    override fun describe(): String = "proposal: TEMPLATE_PREVIEW preferred for the repeating request on this family"
}

internal data object CameraTimebaseInfo : DevicePolicyEffect {
    override fun describe(): String = "information: camera timestamps may not follow SENSOR_INFO_TIMESTAMP_SOURCE"
}

internal data object EncoderInitFallback : DevicePolicyEffect {
    override fun describe(): String = "encoder init falls back: drop optional keys, retry resource errors, next encoder"
}

internal data object EncoderSizeAlignmentInfo : DevicePolicyEffect {
    override fun describe(): String = "proposal: align coded sizes to the encoder's width/height alignment"
}

internal data object EncoderSizeSupportProbe : DevicePolicyEffect {
    override fun describe(): String = "proposal: probe-configure a size isSizeSupported rejects near the reported maximum"
}

internal data object EncoderColourKeys : DevicePolicyEffect {
    override fun describe(): String = "proposal: BT.709 limited SDR colour keys on the encoder format"
}

internal data object ExynosVendorQpRange : DevicePolicyEffect {
    override fun describe(): String = "proposal: vendor.sec-ext-enc-qp-range keys on Exynos encoders"
}

internal data object EncoderKeyLatency : DevicePolicyEffect {
    override fun describe(): String = "proposal: KEY_LATENCY=1 on Codec2 encoders"
}

internal data object EncoderFirstFramesSanity : DevicePolicyEffect {
    override fun describe(): String = "information: check the first YUV frames for black or purple averages"
}
