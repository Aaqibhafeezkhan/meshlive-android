package com.example.bluetoothtest

/*
 * Device-family registry entries of batch U (audio capture, routing, focus), listed in
 * DevicePolicyRegistry's area aggregate.
 *
 * Every active entry has a universal matcher: the behaviours are runtime-conditional (a refusal that is not a call,
 * a silent capture, a rejected route), never keyed on a model. Entries for the Experimental arms are `proposed`:
 * carried with sources and the matching traits, inert in the registry; the arm itself is a default-off DEBUG
 * switch in [AudioSwitches]. Off-switch of every entry: DEBUG `test_device_policy_off=<id>`.
 */

internal val AUDIO_POLICY_AREA: PolicyArea = PolicyArea.AUDIO

internal const val CAPTURE_WATCHDOG_ENTRY_ID = "audio-capture-silence-watchdog"
internal const val OPUS_ENCODER_FALLBACK_ID = "opus-encoder-native-fallback"
internal const val ECHO_HINT_ENTRY_ID = "audio-echo-risk-hint"
internal const val EFFECT_LOGGING_ENTRY_ID = "audio-effect-default-state-logging"

/** Audio focus: a refusal holds the call only with call evidence; delayed focus accepted. */
internal data object AudioFocusRefusalClassification : DevicePolicyEffect {
    override fun describe(): String =
        "a refused audio-focus request holds the call only when the audio mode or telephony state shows a call; otherwise log, retry once"
}

/** Effect default state, UUID, implementor and control are logged before the app enables AEC/NS/AGC. */
internal data object AudioEffectStateLogging : DevicePolicyEffect {
    override fun describe(): String = "log each capture effect's default state before enabling; never switch an effect off"
}

/** Capture start retry, silence notice and dead-object rebuild. */
internal data class AudioCaptureSilenceWatchdog(val startAttempts: Int, val silenceMs: Long) : DevicePolicyEffect {
    override fun describe(): String =
        "retry AudioRecord start up to $startAttempts times; notice after ${silenceMs} ms of digital silence; rebuild on ERROR_DEAD_OBJECT"
}

/** setCommunicationDevice result check with re-pick, watch filter and rate-limited re-assert. */
internal data object CallRouteResultCheck : DevicePolicyEffect {
    override fun describe(): String =
        "check setCommunicationDevice's result and try the next candidate; ignore watches; re-assert a changed device (3 per 10 s)"
}

/** The bundled libopus encodes when no MediaCodec Opus encoder works. */
internal data object OpusEncoderNativeFallback : DevicePolicyEffect {
    override fun describe(): String = "encode with the bundled libopus when no MediaCodec Opus encoder can be created"
}

internal data object EchoRiskHint : DevicePolicyEffect {
    override fun describe(): String = "one-time hint to use the earpiece or a headset on the loudspeaker without an effective AEC"
}

internal object AudioPolicyEntries {
    private const val REVIEWED = "2026-10-14"
    private val ALL = DeviceMatcher()

    private val FOCUS_ANDROID15 = PolicySource(
        PolicySourceKind.PLATFORM_DOC,
        "https://developer.android.com/about/versions/15/behavior-changes-15#audio-focus",
        "Apps targeting Android 15 can request audio focus only while they are the top app or run a foreground " +
            "service; otherwise requestAudioFocus returns AUDIOFOCUS_REQUEST_FAILED",
    )
    private val FOCUS_GUIDE = PolicySource(
        PolicySourceKind.PLATFORM_DOC,
        "https://developer.android.com/media/optimize/audio-focus#delayed-focus",
        "AUDIOFOCUS_REQUEST_DELAYED with setAcceptsDelayedFocusGain(true): the system grants focus later through the " +
            "listener (for example after a phone call); a FAILED request never produces a later callback",
    )
    private val AUDIO_MODE = PolicySource(
        PolicySourceKind.API_REFERENCE,
        "https://developer.android.com/reference/android/media/AudioManager#getMode()",
        "getMode() returns the current audio mode: MODE_IN_CALL during a cellular call, MODE_RINGTONE while ringing, " +
            "MODE_CALL_SCREENING, MODE_IN_COMMUNICATION for VoIP; no permission needed",
    )
    private val AEC_API = PolicySource(
        PolicySourceKind.API_REFERENCE,
        "https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler",
        "An AEC may be inserted by default on VOICE_COMMUNICATION capture; call getEnabled() to check the default state",
    )
    private val PRE_PROCESSING = PolicySource(
        PolicySourceKind.PLATFORM_DOC,
        "https://source.android.com/docs/core/audio/implement-pre-processing",
        "Vendors declare input pre-processing (AEC/NS/AGC) per audio source in audio_effects.xml",
    )
    private val CDD_AEC = PolicySource(
        PolicySourceKind.CDD,
        "https://source.android.com/docs/compatibility/13/android-13-cdd#544_acoustic_echo_canceler",
        "CDD 5.4.4: an AEC SHOULD be applied to VOICE_COMMUNICATION capture and is STRONGLY RECOMMENDED to be exposed " +
            "through AcousticEchoCanceler.isAvailable(); it is not required",
    )
    private val WEBRTC_EFFECTS = PolicySource(
        PolicySourceKind.GOOGLE_OPEN_SOURCE,
        "https://webrtc.googlesource.com/src/+/42995c384b59f0da002a6078d4a0320af39a16b8/sdk/android/src/java/org/webrtc/audio/WebRtcAudioEffects.java",
        "WebRTC skips the AOSP software AEC/NS (UUIDs bb392ec0-... and c06c8400-...) because it ships a better software " +
            "canceler; MeshLive has none, so it keeps them (BACKLOG conflict K2)",
    )
    private val SHARING_INPUT = PolicySource(
        PolicySourceKind.PLATFORM_DOC,
        "https://developer.android.com/media/platform/sharing-audio-input",
        "From Android 10 a lower-priority capture is silenced (zeros) rather than stopped when another app takes the " +
            "input; the app can observe it with an AudioRecordingCallback",
    )
    private val CLIENT_SILENCED = PolicySource(
        PolicySourceKind.API_REFERENCE,
        "https://developer.android.com/reference/android/media/AudioRecordingConfiguration#isClientSilenced()",
        "isClientSilenced(): true when the client's capture is currently silenced by the platform (API 29)",
    )
    private val SENSOR_PRIVACY = PolicySource(
        PolicySourceKind.API_REFERENCE,
        "https://developer.android.com/reference/android/hardware/SensorPrivacyManager#supportsSensorToggle(int)",
        "supportsSensorToggle(Sensors.MICROPHONE): whether the phone has a user-facing microphone privacy toggle (API 31)",
    )
    private val OBOE_START = PolicySource(
        PolicySourceKind.GOOGLE_OPEN_SOURCE,
        "https://github.com/google/oboe/issues/1427",
        "Redmi: capture start fails (HAL start_input_stream -22) while an assistant holds the input; also Oboe #2123 " +
            "(Galaxy A15 VOICE_COMMUNICATION blank) and #1526 (Exynos MMAP silence)",
    )
    private val OBOE_ANR = PolicySource(
        PolicySourceKind.GOOGLE_OPEN_SOURCE,
        "https://github.com/google/oboe/issues/1216",
        "Galaxy M30s/M31/Note10 Lite (Exynos 9611): ANR in an ioctl on stream open/stop; #1474 adds Pixel 5, Vivo, " +
            "S20 5G, Redmi, Oppo; Google's reply: do all audio calls off the UI thread in a command queue",
    )
    private val OBOE_ECHO = PolicySource(
        PolicySourceKind.GOOGLE_OPEN_SOURCE,
        "https://github.com/google/oboe/issues/1405",
        "Echo with VOICE_COMMUNICATION on several Samsung, OnePlus and Motorola phones; the CDD only says AEC SHOULD exist",
    )
    private val SET_COMM_DEVICE = PolicySource(
        PolicySourceKind.API_REFERENCE,
        "https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)",
        "setCommunicationDevice returns false when the device is not available or the request is rejected; " +
            "OnCommunicationDeviceChangedListener reports a changed or cleared device (API 31)",
    )
    private val SIGNAL_ROUTING = PolicySource(
        PolicySourceKind.VENDOR_DOC,
        "https://github.com/signalapp/Signal-Android/tree/main/app/src/main/java/org/thoughtcrime/securesms/webrtc/audio",
        "FullSignalAudioManagerApi31: focus, mode (waits for OnModeChangedListener), setCommunicationDevice with a " +
            "re-pick on false, ignores devices named ' Watch', AudioDeviceCallback re-assert, clear and restore on stop",
    )
    private val TWILIO_USB = PolicySource(
        PolicySourceKind.VENDOR_DOC,
        "https://github.com/twilio/audioswitch",
        "AudioDeviceManager: Samsung Galaxy S9/S21 (SM-G960, SM-G99x) route a USB headset only in MODE_NORMAL, so with " +
            "the speaker off and a USB headset present it sets MODE_NORMAL",
    )
    private val AOSP_OPUS_ENCODER = PolicySource(
        PolicySourceKind.AOSP_SOURCE,
        "https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-12.0.0_r1/media/codec2/components/opus/C2SoftOpusEnc.cpp",
        "The only AOSP MediaCodec Opus encoder: OPUS_APPLICATION_AUDIO, no FEC; CDD 5.1.1 [C-1-3] makes an Opus encoder " +
            "mandatory with a microphone, so absence is rare and configure failure is the realistic case",
    )
    private val WEBRTC_COMPLEXITY = PolicySource(
        PolicySourceKind.GOOGLE_OPEN_SOURCE,
        "https://webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_coding/codecs/opus/audio_encoder_opus.cc",
        "WebRTC lowers the Opus complexity on mobile (default 5 on Android, 9 on desktop) to save CPU",
    )
    private val NO_RUN = "none: no benchmark run has exercised this condition; unit tests cover the decision"

    val entries: List<DevicePolicyEntry> = listOf(
        DevicePolicyEntry(
            id = CALL_FOCUS_ENTRY_ID,
            title = "Audio-focus refusal is a cellular call only with call evidence",
            matcher = ALL,
            effects = listOf(AudioFocusRefusalClassification),
            behaviour = "A refused or delayed call audio-focus request holds the call only when AudioManager.getMode() is " +
                "IN_CALL, RINGTONE or CALL_SCREENING (or the telephony state, when permitted, is not idle). Any other " +
                "refusal, such as Android 15's top-app-or-foreground-service rule, is logged as " +
                "focus=failed_not_foreground, does not hold, and is retried once when the call's foreground service holds " +
                "or after 1 s. Delayed focus is accepted so the platform grants it after a call.",
            sources = listOf(FOCUS_ANDROID15, FOCUS_GUIDE, AUDIO_MODE),
            sourceStatus = PolicySourceStatus.OFFICIAL,
            evidence = listOf(NO_RUN),
            confidence = PolicyConfidence.HIGH,
            reviewed = REVIEWED,
            origin = "CallAudioFocus.request() held the call on any AUDIOFOCUS_REQUEST_FAILED (a failed request gets no " +
                "later callback, so a wrong hold lasted the whole call)",
            confidenceNote = "The Android 15 rule and the API behaviour are official; no owned phone has shown a refusal yet " +
                "(S24 on Android 16 is the test: answer a relayed call from the notification while backgrounded). A real " +
                "cellular call must still hold: gated by the call audio mode.",
            area = AUDIO_POLICY_AREA,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            fallback = "The former rule: every refused request holds the call",
        ),
        DevicePolicyEntry(
            id = EFFECT_LOGGING_ENTRY_ID,
            title = "Log capture-effect default state before enabling; keep the AOSP software AEC",
            matcher = ALL,
            effects = listOf(AudioEffectStateLogging),
            behaviour = "Before enabling AEC, NS and AGC the app reads each effect's default enabled state, descriptor UUID, " +
                "implementor and control ownership and logs them with the setEnabled status in BENCHMARK audio_capture " +
                "(effectState=). No effect is switched off and the AOSP software AEC stays enabled; WebRTC's skip rule " +
                "is not adopted because MeshLive has no software echo canceller of its own (user question Q-N4).",
            sources = listOf(AEC_API, PRE_PROCESSING, CDD_AEC, WEBRTC_EFFECTS),
            sourceStatus = PolicySourceStatus.OFFICIAL,
            evidence = listOf(NO_RUN),
            confidence = PolicyConfidence.HIGH,
            reviewed = REVIEWED,
            origin = "MicrophoneOpusEncoder logged only the implementor and the final state",
            confidenceNote = "Logging only; no behaviour change. Whether the AOSP software AEC helps or hurts a given phone " +
                "is unmeasured: an echo-return-loss test on the six phones is the pending measurement.",
            area = AUDIO_POLICY_AREA,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            fallback = "The former log line (implementor and final state only)",
        ),
        DevicePolicyEntry(
            id = CAPTURE_WATCHDOG_ENTRY_ID,
            title = "Capture start retry, silence notice and dead-object rebuild",
            matcher = ALL,
            effects = listOf(AudioCaptureSilenceWatchdog(CaptureStartRetry.ATTEMPTS, CaptureSilenceDetector.THRESHOLD_MS)),
            behaviour = "A failed AudioRecord start is retried twice (250 ms apart) on the capture thread before the call " +
                "ends. Two seconds of digital silence produce one notice: a different one when the platform reports the " +
                "client silenced (another app, or the microphone privacy switch) than for a blank capture. ERROR_DEAD_OBJECT " +
                "rebuilds the recorder and effects up to three times. Changing the capture source is a separate " +
                "Experimental arm (test_capture_source_fallback, default off).",
            sources = listOf(SHARING_INPUT, CLIENT_SILENCED, SENSOR_PRIVACY, OBOE_START),
            sourceStatus = PolicySourceStatus.PARTIAL,
            evidence = listOf(NO_RUN),
            confidence = PolicyConfidence.MEDIUM,
            reviewed = REVIEWED,
            origin = "MicrophoneOpusEncoder threw at the first failed start and at any read error, ending the call",
            confidenceNote = "The platform behaviour is official; which owned phones show a blocked start or blank capture is " +
                "unknown (Redmi with an assistant, Galaxy A15 VOICE_COMMUNICATION are third-party reports). The 2 s threshold " +
                "is ours; it can misfire on a fully gated quiet room, which only shows a notice.",
            area = AUDIO_POLICY_AREA,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            fallback = "The former behaviour: an immediate error ends the call; no silence detection",
        ),
        DevicePolicyEntry(
            id = ECHO_HINT_ENTRY_ID,
            title = "Echo-risk hint when the loudspeaker is used without an effective AEC",
            matcher = ALL,
            effects = listOf(EchoRiskHint),
            behaviour = "About one second into a call, if call audio plays from the loudspeaker and neither the app's own " +
                "AcousticEchoCanceler nor the framework's recording effect list shows an AEC, the user sees one hint to use " +
                "the earpiece or a headset. No software echo canceller is added (question Q-N4: measure first).",
            sources = listOf(CDD_AEC, AEC_API, OBOE_ECHO),
            sourceStatus = PolicySourceStatus.PARTIAL,
            evidence = listOf(NO_RUN),
            confidence = PolicyConfidence.MEDIUM,
            reviewed = REVIEWED,
            origin = "new (no echo diagnostics existed)",
            confidenceNote = "Detection of 'no effective AEC' relies on the effect list; a phone whose HAL cancels echo without " +
                "listing it would get an unnecessary hint. The hint is advice only. Needs the root hook that shows it.",
            area = AUDIO_POLICY_AREA,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            fallback = "No hint",
        ),
        DevicePolicyEntry(
            id = "call-route-set-result-and-reassert",
            title = "Check setCommunicationDevice's result, ignore watches, re-assert a changed route",
            matcher = ALL,
            effects = listOf(CallRouteResultCheck),
            behaviour = "On Android 12+ the call tries every communication device of the wanted kind until " +
                "setCommunicationDevice accepts one, never offers a device whose name contains ' Watch', and re-applies the " +
                "call's choice when the platform or another app changes or clears it (at most 3 times in 10 s). " +
                "Bluetooth headsets remain behind their existing experimental switch.",
            sources = listOf(SET_COMM_DEVICE, SIGNAL_ROUTING),
            sourceStatus = PolicySourceStatus.PARTIAL,
            evidence = listOf(NO_RUN),
            confidence = PolicyConfidence.MEDIUM,
            reviewed = REVIEWED,
            origin = "PrivateCallAudioSession tried one device once and fell back to the platform's choice on false",
            confidenceNote = "The API contract is official; the sequence is Signal's production flow. Not yet run on a phone: " +
                "the re-assert could fight an OEM that moves the route itself, hence the rate limit and the control arm " +
                "test_audio_route_reassert=false.",
            area = AUDIO_POLICY_AREA,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            fallback = "The first matching device is tried once; no re-assert",
        ),
        DevicePolicyEntry(
            id = OPUS_ENCODER_FALLBACK_ID,
            title = "Bundled libopus when the MediaCodec Opus encoder cannot be created",
            matcher = ALL,
            effects = listOf(OpusEncoderNativeFallback),
            behaviour = "If findEncoderForFormat finds no Opus encoder or configure fails, the microphone encoder uses the " +
                "bundled libopus (requested=mediacodec, effective=native is logged) instead of ending the call or stream audio.",
            sources = listOf(AOSP_OPUS_ENCODER),
            sourceStatus = PolicySourceStatus.OFFICIAL,
            evidence = listOf(NO_RUN),
            confidence = PolicyConfidence.MEDIUM,
            reviewed = REVIEWED,
            origin = "MicrophoneOpusEncoder threw 'This phone has no Opus audio encoder'",
            confidenceNote = "Only runs after a failure, so it cannot change a working phone. The receiving side already decodes " +
                "the native encoder's codec-config block (call experiment callNativeOpus); stream audio from the native " +
                "encoder has not been played back on a second phone.",
            area = AUDIO_POLICY_AREA,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            fallback = "The error ends the call or stream audio as before",
        ),

        // ---- Experimental arms: carried with sources, inert in the registry; the arm is a default-off DEBUG switch ----
        proposal(
            id = "audio-capture-source-fallback-chain",
            title = "Relax the capture source VOICE_COMMUNICATION -> MIC -> CAMCORDER after confirmed silence",
            matcher = ALL,
            behaviour = "Switch test_capture_source_fallback (default off). After 2 s of digital silence that the platform does " +
                "not attribute to silencing, the recorder is rebuilt on the next source; the platform AEC is lost " +
                "(requested != effective is logged). Never used in benchmarks.",
            sources = listOf(SHARING_INPUT, OBOE_START),
            confidenceNote = "Linphone and Oboe reports show the chain helps some phones; none of the six owned phones is known " +
                "to need it, and the loss of echo control is a real cost.",
        ),
        proposal(
            id = "audio-off-main-thread-commands",
            title = "Run audio teardown and the opt-in start off the main thread (M31 audio ANR class)",
            matcher = DeviceMatcher(socFamilies = setOf(SocFamily.EXYNOS), sdkMax = 31),
            behaviour = "Switch test_audio_off_main (default off). AudioRecord/AudioManager teardown and " +
                "MicrophoneOpusEncoder.startOffMain run on one serialized command thread. Commands are always timed and " +
                "logged (BENCHMARK audio_command) with a 3 s stall watchdog.",
            sources = listOf(OBOE_ANR),
            confidenceNote = "Third-party ANR reports on Exynos 9611 phones; no ANR is recorded on the owned M31. The M31 " +
                "50-cycle call start/stop test decides whether to promote it.",
        ),
        proposal(
            id = "call-audio-mode-change-wait",
            title = "Wait for OnModeChangedListener before setCommunicationDevice (API 31+)",
            matcher = DeviceMatcher(sdkMin = 31),
            behaviour = "Switch test_audio_mode_change_wait (default off). After setMode(IN_COMMUNICATION) the call waits up " +
                "to 500 ms for the platform's mode callback, as Signal does. Blocks the calling thread for that time.",
            sources = listOf(SIGNAL_ROUTING),
            confidenceNote = "Signal's production order; no local phone has shown a route set too early.",
        ),
        proposal(
            id = "call-usb-headset-mode-normal",
            title = "MODE_NORMAL for a USB headset on One UI (Twilio S9/S21 workaround)",
            matcher = DeviceMatcher(skins = setOf(OemSkin.ONE_UI)),
            behaviour = "Switch test_audio_usb_headset_mode_normal (default off). With a USB headset plugged in and the " +
                "speaker not requested, the call uses MODE_NORMAL instead of MODE_IN_COMMUNICATION.",
            sources = listOf(TWILIO_USB),
            confidence = PolicyConfidence.LOW,
            confidenceNote = "Twilio's list is two Galaxy families matched by model; this is wider (any One UI phone) and has " +
                "never been run with a USB headset. Needs a USB-headset hardware test before any promotion.",
        ),
        proposal(
            id = "opus-complexity-governor",
            title = "Step the native Opus encoder complexity 10 -> 8 -> 5 from measured encode time",
            matcher = ALL,
            behaviour = "Switch test_audio_complexity_governor (default off). The native call encoder is recreated at a lower " +
                "complexity when the per-frame encode time averages above 25% of the 20 ms frame. The bitrate never changes; " +
                "MediaCodec encoders cannot be changed mid-call.",
            sources = listOf(WEBRTC_COMPLEXITY),
            confidenceNote = "Complexity 10 was chosen for speech quality (PESQ gain at 24-32 kbps); a slower phone may need " +
                "the ladder, none of the owned phones is known to. Recreating the encoder restarts its prediction state.",
        ),
    )

    private fun proposal(
        id: String,
        title: String,
        matcher: DeviceMatcher,
        behaviour: String,
        sources: List<PolicySource>,
        confidenceNote: String,
        confidence: PolicyConfidence = PolicyConfidence.MEDIUM,
    ) = DevicePolicyEntry(
        id = id,
        title = title,
        matcher = matcher,
        effects = emptyList(),
        behaviour = behaviour,
        sources = sources,
        sourceStatus = PolicySourceStatus.PARTIAL,
        evidence = listOf(NO_RUN),
        confidence = confidence,
        reviewed = REVIEWED,
        origin = "Proposal (Experimental arm, default off)",
        confidenceNote = confidenceNote,
        proposed = true,
        area = AUDIO_POLICY_AREA,
        validation = PolicyValidation.EXPERIMENTAL,
        fallback = "The switch is off by default; the former behaviour runs",
    )
}
