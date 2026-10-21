package com.example.bluetoothtest

import android.content.ComponentCallbacks2
import android.os.PowerManager

/**
 * Thermal and memory-pressure policy (area MEMORY_THERMAL; entries join DevicePolicyRegistry). Pure
 * functions plus two DEBUG-only experiment switches, so every rule is unit-testable on the host. Requested settings are never changed by a rule here unless the
 * existing automatic thermal-reduction setting is on (benchmark profiles set `thermal_reduction`
 * false, which makes every rule a logged no-op).
 */
internal object MemoryThermalPolicy {
    /**
     * Experimental (off): act on the ADPF thermal-headroom forecast before the reported status rises.
     * DEBUG launch extra `test_thermal_forecast=true`. Read when the activity starts.
     */
    @Volatile var headroomForecast: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (off): a hot phone also moves the libaom real-time speed preset to a faster one
     * (less CPU per frame) in addition to the FPS/bitrate limit. DEBUG launch extra
     * `test_thermal_speed_floor=true`.
     */
    @Volatile var speedFloor: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    // ---- Thermal ------------------------------------------------------------------------------

    /**
     * Status implied by a [PowerManager.getThermalHeadroom] value, from the thresholds the Android
     * ADPF thermal guide gives for devices whose status lags: above 1.0 the device could be severely
     * throttled, above 0.95 moderately, above 0.85 lightly. A forecast is less accurate than the
     * reported status (same guide), so this only ever raises the effective status to SEVERE at most;
     * it never reaches CRITICAL, which keeps blocking streaming tied to the platform's own status.
     * NaN implies no status. NaN means the device has no skin sensor with a SEVERE threshold (unsupported), or
     * the call came sooner than PowerManager.MINIMUM_HEADROOM_TIME_MILLIS (500 ms) after the previous one. The
     * "once per 10 s" in the official guide is advice, not the client limit; and a call every 10 s can never
     * produce a forecast, because the server drops its samples after 10 s of silence and needs 3 to forecast
     * (see [ThermalHeadroomSampler], which samples every 2 s inside the Experimental forecast path).
     */
    fun statusFromHeadroom(headroom: Float): Int = when {
        headroom.isNaN() -> PowerManager.THERMAL_STATUS_NONE
        headroom > 1.0f -> PowerManager.THERMAL_STATUS_SEVERE
        headroom > 0.95f -> PowerManager.THERMAL_STATUS_MODERATE
        headroom > 0.85f -> PowerManager.THERMAL_STATUS_LIGHT
        else -> PowerManager.THERMAL_STATUS_NONE
    }

    /** The status the workload limits follow: the reported one, raised by a forecast only when enabled. */
    fun effectiveStatus(reported: Int, forecastHeadroom: Float?, forecastEnabled: Boolean): Int =
        if (forecastEnabled && forecastHeadroom != null) {
            maxOf(reported, statusFromHeadroom(forecastHeadroom))
        } else {
            reported
        }

    /**
     * Slowest libaom real-time preset (cpu-used 5..10, higher is faster) allowed at [status]; 0 means
     * no floor. Defaults already run 7-10 (VideoCodec.nativeCpuUsed), so the floor only pushes slower
     * remembered or trial presets back up while the phone is throttled.
     */
    fun nativeSpeedFloor(status: Int, reductionEnabled: Boolean, speedFloorEnabled: Boolean): Int = when {
        !reductionEnabled || !speedFloorEnabled -> 0
        status >= PowerManager.THERMAL_STATUS_SEVERE -> NativeEncoderSpeedGovernor.MAX_REALTIME_CPU_USED
        status >= PowerManager.THERMAL_STATUS_MODERATE -> MODERATE_SPEED_FLOOR
        else -> 0
    }

    fun statusName(status: Int): String = when {
        status >= PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        status >= PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        status >= PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        status >= PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        status >= PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        status >= PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        else -> "NONE"
    }

    /** What the app does at [status]; the same ladder the existing ThermalGuardPolicy applies. */
    fun thermalAction(effective: Int, reductionEnabled: Boolean, speedFloorValue: Int): String = when {
        ThermalGuardPolicy.shouldBlockStreaming(effective) -> "stop_streaming"
        effective >= PowerManager.THERMAL_STATUS_MODERATE && !reductionEnabled -> "none_reduction_disabled"
        ThermalGuardPolicy.shouldReduceWorkload(effective, reductionEnabled) ->
            "limit_workload_${ThermalGuardPolicy.workloadPercent(effective, reductionEnabled)}pct" +
                if (speedFloorValue > 0) "_cpu_used_floor_$speedFloorValue" else ""
        else -> "none"
    }

    /** `BENCHMARK thermal:` evidence line; [forecast] is the sampled headroom or null when never read. */
    fun benchmarkLine(
        reported: Int,
        forecast: Float?,
        effective: Int,
        reductionEnabled: Boolean,
        speedFloorValue: Int,
        source: String,
    ): String =
        "BENCHMARK thermal: status=${statusName(reported)} | forecastHeadroom=" +
            (forecast?.takeIf { it.isFinite() }?.let { "%.2f".format(java.util.Locale.US, it) } ?: "na") +
            " | effective=${statusName(effective)} | action=" +
            thermalAction(effective, reductionEnabled, speedFloorValue) +
            " | thermalReduction=$reductionEnabled | forecastEnabled=$headroomForecast | " +
            "speedFloorEnabled=$speedFloor | source=$source"

    // ---- Memory -------------------------------------------------------------------------------

    /** Caps the control queues that were unbounded (private-call controls, group-relay child controls). */
    const val CALL_CONTROL_QUEUE_CAPACITY = 256
    const val RELAY_CONTROL_QUEUE_CAPACITY = 1_024

    /** Sender packet-queue byte cap on a normal device (unchanged from the original constant). */
    const val DEFAULT_SENDER_QUEUE_BYTES = 8L * 1024 * 1024

    /** Sender packet-queue byte cap on an ActivityManager.isLowRamDevice() phone (Android Go). */
    const val LOW_RAM_SENDER_QUEUE_BYTES = 4L * 1024 * 1024

    @Volatile var senderQueueBytes: Long = DEFAULT_SENDER_QUEUE_BYTES
        private set

    /** Memory tier of this phone (null until configured with a MemTotal), see [MemoryTier]. */
    @Volatile var memoryTier: MemoryTier? = null
        private set

    /** Buffer sizing for the tier; the original constants unless `test_memory_tier=true`. */
    @Volatile var memorySizing: MemorySizing = MemorySizing(DEFAULT_SENDER_QUEUE_BYTES, MemoryTierPolicy.DEFAULT_MAX_LAG_FRAMES, "defaults")
        private set

    /**
     * [policy] decides whether the low-RAM cap entry applies (`test_device_policy_off=low-ram-sender-queue`).
     * With [totalMiB] the tier is derived and logged ([tierLoggingActive]); the tier sizes buffers only with the
     * Experimental switch `test_memory_tier=true` and while `memory-tier-sizing` is not switched off, and then only
     * downwards: the sender queue is the smaller of the low-RAM rule and the tier rule.
     */
    fun configureForDevice(
        isLowRamDevice: Boolean,
        policy: ResolvedDevicePolicy = DeviceFamilyPolicy.current(),
        totalMiB: Long? = null,
        sdkInt: Int = android.os.Build.VERSION.SDK_INT,
        sizingSwitch: Boolean = ThermalSwitches.memoryTierSizing,
    ) {
        val lowRamBytes = if (isLowRamDevice && policy.has<SenderQueueByteLimit>()) {
            LOW_RAM_SENDER_QUEUE_BYTES
        } else {
            DEFAULT_SENDER_QUEUE_BYTES
        }
        val tier = totalMiB?.let(MemoryTier::fromTotalMiB)
        memoryTier = tier
        val sizing = if (tier != null) {
            MemoryTierPolicy.sizing(tier, sdkInt, isLowRamDevice, sizingSwitch && "memory-tier-sizing" !in policy.disabled)
        } else {
            MemorySizing(DEFAULT_SENDER_QUEUE_BYTES, MemoryTierPolicy.DEFAULT_MAX_LAG_FRAMES, "defaults")
        }
        memorySizing = sizing
        senderQueueBytes = minOf(lowRamBytes, sizing.senderQueueBytes)
    }

    /**
     * Reads ActivityManager (low-RAM flag, MemTotal, heap class), configures the policy and returns the
     * `BENCHMARK memory_tier:` line for the log. Root hook: replaces the existing configureForDevice call.
     */
    fun configureFromSystem(context: android.content.Context, policy: ResolvedDevicePolicy = DeviceFamilyPolicy.current()): String? {
        val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val lowRam = manager?.isLowRamDevice == true
        val totalMiB = MemoryTierPolicy.readTotalMiB(context)
        configureForDevice(lowRam, policy, totalMiB)
        if (!tierLoggingActive(policy)) return null
        val tier = memoryTier ?: return "BENCHMARK memory_tier: totalMiB=unknown | lowRam=$lowRam | senderQueueBytes=$senderQueueBytes"
        return MemoryTierPolicy.logLine(
            totalMiB ?: 0L,
            tier,
            android.os.Build.VERSION.SDK_INT,
            lowRam,
            manager?.memoryClass ?: 0,
            memorySizing,
            ThermalSwitches.memoryTierSizing && "memory-tier-sizing" !in policy.disabled,
        )
    }

    /** True while the tier log entry is on (`test_device_policy_off=memory-tier-log`). */
    fun tierLoggingActive(policy: ResolvedDevicePolicy = DeviceFamilyPolicy.current()): Boolean =
        policy.has<MemoryTierLogging>()

    /** True when the headroom sampler has a reason to run: reduction is on and the forecast or the fallback experiment is. */
    fun headroomSamplerNeeded(thermalReductionOn: Boolean): Boolean =
        thermalReductionOn && (headroomForecast || ThermalSwitches.batteryFallback)

    /** True while the thermal workload ladder entry is on (switch: `test_device_policy_off=thermal-status-ladder`). */
    fun ladderActive(policy: ResolvedDevicePolicy = DeviceFamilyPolicy.current()): Boolean =
        policy.has<ThermalWorkloadLadder>()

    fun trimReleaseActive(policy: ResolvedDevicePolicy = DeviceFamilyPolicy.current()): Boolean =
        policy.has<TrimMemoryRelease>()

    /** Capacity for a control queue, or unbounded when the entry is switched off. */
    fun controlQueueCapacity(requested: Int, policy: ResolvedDevicePolicy = DeviceFamilyPolicy.current()): Int =
        if (policy.has<BoundedControlQueues>()) requested else Int.MAX_VALUE

    /** What to release for a [ComponentCallbacks2.onTrimMemory] level. */
    enum class TrimAction(val label: String) {
        NONE("none"),

        /** Foreground and running low: drop only data that is rebuilt on its next use. */
        DROP_REBUILDABLE("drop_rebuildable_caches"),

        /** Process cached (BACKGROUND or worse): also release scan results while no stream, call or reconnect is active. */
        RELEASE_IDLE_DISCOVERY("release_idle_discovery_state"),
    }

    /**
     * Android's documented levels: RUNNING_LOW (10) and RUNNING_CRITICAL (15) mean the app is in the
     * foreground and the system is short of memory (Android 14+ no longer sends them); UI_HIDDEN (20)
     * fires on every Home press or screen-off and says nothing about memory, so it does nothing here;
     * BACKGROUND/MODERATE/COMPLETE (40/60/80) mean the process is cached and may be killed. An active
     * stream, call or pending reconnect is never stopped or stripped of its state here.
     */
    fun trimAction(level: Int): TrimAction = when {
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> TrimAction.RELEASE_IDLE_DISCOVERY
        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> TrimAction.NONE
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> TrimAction.DROP_REBUILDABLE
        else -> TrimAction.NONE
    }

    fun trimLine(level: Int, action: TrimAction, streamActive: Boolean, releasedEntries: Int): String =
        "BENCHMARK memory: trimLevel=$level | action=${action.label} | streamActive=$streamActive | " +
            "releasedEntries=$releasedEntries | senderQueueBytes=$senderQueueBytes"

    const val MODERATE_SPEED_FLOOR = 8

    /** This area's registry entries (joined by DevicePolicyRegistry). */
    val entries: List<DevicePolicyEntry> get() = MemoryThermalEntries.entries + ThermalFamilyEntries.entries
}

// ---- Registry entries (area MEMORY_THERMAL) ------------------------------------------------------

/** Thermal workload ladder: FPS and bitrate limits by thermal status (ThermalGuardPolicy values). */
internal data class ThermalWorkloadLadder(val moderatePercent: Int, val severePercent: Int) : DevicePolicyEffect {
    override fun describe(): String =
        "limit FPS and bitrate to $moderatePercent% at MODERATE and $severePercent% at SEVERE while thermal reduction is on"
}

/** Stream sender queue cap on an ActivityManager.isLowRamDevice() phone. */
internal data class SenderQueueByteLimit(val lowRamBytes: Long) : DevicePolicyEffect {
    override fun describe(): String = "cap the stream sender queue at ${lowRamBytes / (1024 * 1024)} MiB when isLowRamDevice()"
}

internal data object TrimMemoryRelease : DevicePolicyEffect {
    override fun describe(): String = "onTrimMemory drops rebuildable scan/route state; never stops a stream or call"
}

internal data class BoundedControlQueues(val callCapacity: Int, val relayCapacity: Int) : DevicePolicyEffect {
    override fun describe(): String = "private-call control queue capped at $callCapacity, group-relay child control queue at $relayCapacity"
}

/** Proposed experiments (inert in the registry; enabled by their DEBUG extras, default off). */
internal data object ThermalHeadroomForecastWorkload : DevicePolicyEffect {
    override fun describe(): String = "raise the workload ladder's status from the ADPF headroom forecast (never CRITICAL)"
}

internal data class ThermalNativeSpeedFloor(val moderateCpuUsed: Int, val severeCpuUsed: Int) : DevicePolicyEffect {
    override fun describe(): String = "libaom cpu-used floor $moderateCpuUsed at MODERATE and $severeCpuUsed at SEVERE"
}

internal data object ThermalBatteryTemperatureFallback : DevicePolicyEffect {
    override fun describe(): String = "when the thermal HAL reports nothing, drive the ladder from battery temperature"
}

internal object MemoryThermalEntries {
    private const val REVIEWED = "2026-10-10"
    private const val ADPF_PAGE = "https://developer.android.com/games/optimize/adpf/thermal"
    private val ALL = DeviceMatcher()

    private val ADPF_STATUSES = PolicySource(
        PolicySourceKind.PLATFORM_DOC,
        ADPF_PAGE,
        "Statuses: LIGHT/MODERATE are 'some throttling, no significant performance impact'; SEVERE, CRITICAL, EMERGENCY, " +
            "SHUTDOWN 'significant throttling that impacts performance'. getThermalHeadroom: 0.0 no throttling to 1.0 " +
            "THERMAL_STATUS_SEVERE; the forecast is 'less accurate than using the current thermal status'. The page says " +
            "NaN means the API is unsupported or was called more than once per 10 s; AOSP's client limit is 500 ms " +
            "(see HEADROOM_CALLS)",
    )
    private val HEADROOM_CALLS = PolicySource(
        PolicySourceKind.AOSP_SOURCE,
        "https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/PowerManager.java",
        "getThermalHeadroom: MINIMUM_HEADROOM_TIME_MILLIS = 500, a call sooner than that returns NaN; 'no benefit to " +
            "calling more frequently than about once per second'. Forecast range 0..60 s",
    )
    private val HEADROOM_SERVER = PolicySource(
        PolicySourceKind.AOSP_SOURCE,
        "https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/power/ThermalManagerService.java",
        "TemperatureWatcher: samples skin temperature once per second only while forecast calls arrive within " +
            "INACTIVITY_THRESHOLD_MILLIS (10000), otherwise clears all samples; MINIMUM_SAMPLE_COUNT = 3 (fewer returns " +
            "the current normalised value); NaN when no TYPE_SKIN SEVERE threshold exists ('No temperature thresholds found')",
    )
    private val HAL_SEVERITY = PolicySource(
        PolicySourceKind.AOSP_SOURCE,
        "https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/android14-release/thermal/aidl/android/hardware/thermal/ThrottlingSeverity.aidl",
        "The statuses come from the vendor thermal HAL: SEVERE is 'UX is largely impacted. Similar to 1.0 " +
            "throttlingThreshold', CRITICAL 'Platform has done everything to reduce power'. Thresholds are vendor-defined, " +
            "so the same status means different temperatures on different SoC families",
    )
    private val POWER_MANAGER = PolicySource(
        PolicySourceKind.API_REFERENCE,
        "https://developer.android.com/reference/android/os/PowerManager#getThermalHeadroom(int)",
        "getThermalHeadroom(int) is API 30+; addThermalStatusListener and getCurrentThermalStatus are API 29+",
    )
    private val LOW_RAM = PolicySource(
        PolicySourceKind.API_REFERENCE,
        "https://developer.android.com/reference/android/app/ActivityManager#isLowRamDevice()",
        "isLowRamDevice(): true on devices configured as low-RAM (Android Go edition builds); API 19+",
    )
    private val MEMORY_GUIDE = PolicySource(
        PolicySourceKind.PLATFORM_DOC,
        "https://developer.android.com/topic/performance/memory#release",
        "onTrimMemory: RUNNING_LOW/RUNNING_CRITICAL mean the app is running and the system is short of memory; " +
            "UI_HIDDEN and BACKGROUND/MODERATE/COMPLETE mean the UI is hidden or the process is cached; release " +
            "resources that can be rebuilt",
    )
    private val BLOCKING_QUEUE = PolicySource(
        PolicySourceKind.API_REFERENCE,
        "https://developer.android.com/reference/java/util/concurrent/LinkedBlockingQueue#LinkedBlockingQueue(int)",
        "A capacity bounds the queue; offer() returns false when it is full instead of growing without limit",
    )
    private val BATTERY = PolicySource(
        PolicySourceKind.API_REFERENCE,
        "https://developer.android.com/reference/android/os/BatteryManager#EXTRA_TEMPERATURE",
        "ACTION_BATTERY_CHANGED carries the battery temperature in tenths of a degree Celsius (API 5+)",
    )
    private val NO_RUN =
        "none: no benchmark run has exercised this phone-side condition; benchmark profiles set thermal_reduction=false"

    val entries: List<DevicePolicyEntry> = listOf(
        DevicePolicyEntry(
            id = "thermal-status-ladder",
            title = "Thermal status workload ladder",
            matcher = ALL,
            effects = listOf(ThermalWorkloadLadder(moderatePercent = 90, severePercent = 80)),
            behaviour = "With automatic cooling on, limit FPS and bitrate to 90% at MODERATE and 80% at SEVERE; stop " +
                "streaming at CRITICAL or higher. Applies to every SoC family because the status is the platform's own " +
                "ADPF signal; the thermal HAL defines when each status is reached.",
            sources = listOf(ADPF_STATUSES, HAL_SEVERITY, POWER_MANAGER),
            sourceStatus = PolicySourceStatus.PARTIAL,
            evidence = listOf(NO_RUN),
            confidence = PolicyConfidence.MEDIUM,
            reviewed = REVIEWED,
            origin = "ThermalGuardPolicy and MainActivity thermalStatusListener (kept; the 90/80% steps are our values)",
            confidenceNote = "Statuses and their meaning are official; the percentages are ours and were never measured on a " +
                "throttled phone. The CRITICAL stop is not switched by this entry (platform safety).",
            area = PolicyArea.MEMORY_THERMAL,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            fallback = "No reduction (the selected FPS and bitrate run unchanged); the CRITICAL stop still applies",
            legacySwitches = listOf("Settings automatic-cooling switch; DEBUG test_thermal_reduction=false (benchmark profiles)"),
        ),
        DevicePolicyEntry(
            id = "low-ram-sender-queue",
            title = "Smaller stream sender queue on low-RAM devices",
            matcher = ALL,
            effects = listOf(SenderQueueByteLimit(MemoryThermalPolicy.LOW_RAM_SENDER_QUEUE_BYTES)),
            behaviour = "When ActivityManager.isLowRamDevice() is true (Android Go and similar), a viewer whose queue passes " +
                "4 MiB is released to reconnect, instead of 8 MiB. The matcher cannot see RAM class, so the entry is " +
                "universal and the effect applies only where the runtime flag is true.",
            sources = listOf(LOW_RAM),
            sourceStatus = PolicySourceStatus.PARTIAL,
            evidence = listOf("none: no owned phone reports isLowRamDevice=true; MemoryThermalPolicyTest covers the selection"),
            confidence = PolicyConfidence.LOW,
            reviewed = REVIEWED,
            origin = "BluetoothVideoSender MAX_QUEUED_BYTES (8 MiB) memory-safety release",
            confidenceNote = "The flag is official; 4 MiB is our choice: at 100-200 kbit/s it is minutes of video, far beyond " +
                "any useful age. Unproven on a real Go phone.",
            area = PolicyArea.MEMORY_THERMAL,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            fallback = "The original 8 MiB cap",
        ),
        DevicePolicyEntry(
            id = "trim-memory-release",
            title = "Release rebuildable state on onTrimMemory",
            matcher = ALL,
            effects = listOf(TrimMemoryRelease),
            behaviour = "At RUNNING_LOW/RUNNING_CRITICAL drop expired route suppressions; at BACKGROUND (40) or worse, while " +
                "no stream, call or reconnect is active, also clear the scan-result maps (the next scan rebuilds them). " +
                "UI_HIDDEN (every Home press) does nothing. Route failure counters are kept. Never stops a stream or call.",
            sources = listOf(MEMORY_GUIDE),
            sourceStatus = PolicySourceStatus.OFFICIAL,
            evidence = listOf("none: not exercised under real memory pressure; MemoryThermalPolicyTest covers the level mapping"),
            confidence = PolicyConfidence.MEDIUM,
            reviewed = REVIEWED,
            origin = "new (the app had no onTrimMemory handler)",
            confidenceNote = "The maps are rebuilt by the next scan; their small size makes the saving modest. Android 14+ " +
                "sends no RUNNING_* levels, so there only the BACKGROUND release applies. A screen-off foreground-service " +
                "process is not cached and, per the AOSP cached-apps freezer page, gets at most TRIM_MEMORY_BACKGROUND, so no " +
                "pressure notice is expected there: sizing relies on the memory tier budget (MemoryTier), not on trim levels.",
            area = PolicyArea.MEMORY_THERMAL,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            fallback = "No release (the maps are pruned by their normal freshness checks)",
        ),
        DevicePolicyEntry(
            id = "bounded-control-queues",
            title = "Bounded private-call and group-relay control queues",
            matcher = ALL,
            effects = listOf(
                BoundedControlQueues(
                    MemoryThermalPolicy.CALL_CONTROL_QUEUE_CAPACITY,
                    MemoryThermalPolicy.RELAY_CONTROL_QUEUE_CAPACITY,
                ),
            ),
            behaviour = "Repeatable controls are bounded: one pending keyframe request per call (per child on a group relay), " +
                "the newest display rotation replaces an older one, and at most 256 (call) / 1024 (group relay) keyed " +
                "controls wait; a refused offer is logged. Hang-up, decline, cancel, stream-end ack, receiver-left and " +
                "headers bypass the bound and are never dropped.",
            sources = listOf(BLOCKING_QUEUE),
            sourceStatus = PolicySourceStatus.PARTIAL,
            evidence = listOf("none: legitimate use is one keyframe request or rotation per user action; MemoryThermalPolicyTest covers the bound"),
            confidence = PolicyConfidence.MEDIUM,
            reviewed = REVIEWED,
            origin = "AndroidPrivateVideoCall.controls and BluetoothVideoRelay receiverControls were unbounded LinkedBlockingQueues",
            confidenceNote = "The capacities are ours, far above legitimate use (keyed controls are at most 2 for a call). " +
                "A refused keyed control equals a lost keyframe request or rotation, which repeat or are superseded.",
            area = PolicyArea.MEMORY_THERMAL,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            fallback = "Unbounded queues (the previous behaviour)",
        ),
        DevicePolicyEntry(
            id = "thermal-headroom-forecast",
            title = "Act on the ADPF thermal-headroom forecast",
            matcher = DeviceMatcher(sdkMin = 30),
            effects = listOf(ThermalHeadroomForecastWorkload),
            behaviour = "Proposed: treat getThermalHeadroom(10) above 0.85/0.95/1.0 as LIGHT/MODERATE/SEVERE for the workload " +
                "ladder only (never CRITICAL, never a stream stop), so reduction starts before the reported status rises. " +
                "Corrected sampling (T-01): ThermalHeadroomSampler calls every 2 s while a session runs (never below the " +
                "500 ms client limit), discards the first 3 s of each window, and gives the ladder a value every 10 s; " +
                "NaN twice in a row means unsupported. The original arm (one call per 10 s) stays behind " +
                "test_thermal_forecast_legacy_10s. The default path (status ladder alone) is untouched.",
            sources = listOf(ADPF_STATUSES, POWER_MANAGER, HEADROOM_CALLS, HEADROOM_SERVER),
            sourceStatus = PolicySourceStatus.PARTIAL,
            evidence = listOf(
                NO_RUN,
                "NEEDS RETEST: every earlier forecast A/B ran at the 10 s cadence, where the AOSP watcher is idle or just " +
                    "cleared at each call and returns the current value, so the forecast arm was never active",
            ),
            confidence = PolicyConfidence.LOW,
            reviewed = REVIEWED,
            origin = "new experiment; the app only logged the headroom at debug level. Sampling rewritten in batch T (T-01)",
            confidenceNote = "The mechanism of the cadence fix is read from AOSP (high confidence); the guide thresholds, the " +
                "forecast quality per vendor thermal HAL and the benefit of acting early are not locally tested.",
            area = PolicyArea.MEMORY_THERMAL,
            proposed = true,
            validation = PolicyValidation.EXPERIMENTAL,
            fallback = "The reported status alone drives the ladder",
            legacySwitches = listOf(
                "DEBUG test_thermal_forecast=true enables the code path (default off)",
                "DEBUG test_thermal_forecast_legacy_10s=true selects the original 10 s sampling arm",
            ),
        ),
        DevicePolicyEntry(
            id = "thermal-native-speed-floor",
            title = "Faster libaom preset while the phone is hot",
            matcher = DeviceMatcher(sdkMin = 29),
            effects = listOf(ThermalNativeSpeedFloor(MemoryThermalPolicy.MODERATE_SPEED_FLOOR, NativeEncoderSpeedGovernor.MAX_REALTIME_CPU_USED)),
            behaviour = "Proposed: while thermal reduction is on and the effective status is MODERATE/SEVERE, the software AV1 " +
                "encoder's cpu-used floor is 8/10 and slower trial presets are blocked; the raised preset is not remembered.",
            sources = listOf(
                ADPF_STATUSES,
                PolicySource(
                    PolicySourceKind.GOOGLE_OPEN_SOURCE,
                    "https://aomedia.googlesource.com/aom/+/0006e96cdb595ff88950a2c664115335ca5517bf/aom/aomcx.h",
                    "AOME_SET_CPUUSED: real-time speeds 5..10, higher is faster (less CPU per frame); settable on a running encoder",
                ),
            ),
            sourceStatus = PolicySourceStatus.PARTIAL,
            evidence = listOf(NO_RUN),
            confidence = PolicyConfidence.LOW,
            reviewed = REVIEWED,
            origin = "new experiment; NativeEncoderSpeedGovernor only reacted to measured encode time",
            confidenceNote = "The knob is documented; its power/thermal benefit and quality cost were not measured on any phone.",
            area = PolicyArea.MEMORY_THERMAL,
            proposed = true,
            validation = PolicyValidation.EXPERIMENTAL,
            fallback = "No floor (the governor's own overload stepping is unchanged)",
            legacySwitches = listOf("DEBUG test_thermal_speed_floor=true enables the code path (default off)"),
        ),
        DevicePolicyEntry(
            id = "thermal-battery-temperature-fallback",
            title = "Battery temperature as the thermal signal when headroom has no skin thresholds",
            matcher = DeviceMatcher(sdkMin = 29),
            effects = listOf(ThermalBatteryTemperatureFallback),
            behaviour = "Proposed (Experimental, test_thermal_battery_fallback=true): when ThermalHeadroomSampler sees NaN three " +
                "times in a row about 2 s apart (or, on API 35+, getThermalHeadroomThresholds() is empty), the phone is marked " +
                "'no skin thresholds' and the ladder status becomes the higher of the reported status and the battery " +
                "temperature (40/43/46 degrees Celsius for LIGHT/MODERATE/SEVERE, never CRITICAL). Runtime detection replaces " +
                "the former Unisoc/MediaTek matcher; the log line also says when the reported status stays NONE while the " +
                "battery is hot.",
            sources = listOf(
                PolicySource(
                    PolicySourceKind.PLATFORM_DOC,
                    ADPF_PAGE,
                    "'If the initial value of GetThermalHeadroom() is NaN, the API is not available on the device'; the " +
                        "device model may not have the thermal hardware",
                ),
                HEADROOM_SERVER,
                HEADROOM_CALLS,
                BATTERY,
            ),
            sourceStatus = PolicySourceStatus.PARTIAL,
            evidence = listOf(
                "none: no owned phone is expected to return NaN; the first-call result per owned phone is still to be logged " +
                    "(BENCHMARK thermal_sampler / thermal_fallback)",
            ),
            confidence = PolicyConfidence.LOW,
            reviewed = REVIEWED,
            origin = "new proposal for devices the project does not own; matcher changed from SoC family to runtime detection in batch T (T-02)",
            confidenceNote = "The NaN mechanism is read from AOSP (high); which phones lack skin thresholds is unknown, so no family " +
                "is named. The battery thresholds are ours (battery temperature lags skin temperature) and need per-SoC data " +
                "before promotion.",
            area = PolicyArea.MEMORY_THERMAL,
            proposed = true,
            validation = PolicyValidation.EXPERIMENTAL,
            fallback = "Encode-time overload stepping (NativeEncoderSpeedGovernor) and link-rate control remain the only load shedding",
            legacySwitches = listOf("DEBUG test_thermal_battery_fallback=true enables the code path (default off)"),
        ),
    )
}

/**
 * Control queue with a bound that never costs a lifecycle signal. Must-deliver items (hang-up,
 * decline, cancel, stream-end ack, receiver-left, headers) always enter. Repeatable items are keyed:
 * one pending item per key (a second keyframe request while one is pending is coalesced, not queued),
 * and the keyed items together are limited to [capacity]; a refused offer is logged with its key.
 * With the `bounded-control-queues` entry off, [capacity] is Int.MAX_VALUE and nothing is refused.
 */
internal class BoundedControlQueue<T : Any>(
    private val capacity: Int,
    private val name: String,
    private val onRefused: (String) -> Unit = { android.util.Log.w("BluetoothVideo", it) },
) {
    private class Entry<T>(val item: T, val key: Any?)

    private val lock = Object()
    private val entries = java.util.ArrayDeque<Entry<T>>()
    private val pendingKeys = HashSet<Any>()
    private var refused = 0L

    val size: Int get() = synchronized(lock) { entries.size }
    val refusedOffers: Long get() = synchronized(lock) { refused }

    /**
     * Always accepted, ahead of everything queued, in the given order: must-deliver items a writer took but could not
     * deliver (link renewal re-queues them so they keep their place before newer controls).
     */
    fun offerMustFirst(items: List<T>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            items.asReversed().forEach { entries.addFirst(Entry(it, null)) }
            lock.notifyAll()
        }
    }

    /** Always accepted: terminal and lifecycle messages must reach the peer. */
    fun offerMust(item: T) {
        synchronized(lock) {
            entries.addLast(Entry(item, null))
            lock.notifyAll()
        }
    }

    /**
     * Keyed, repeatable item. A pending item with the same [key] absorbs it (or, with [replace], is
     * replaced in place by the newer one). Returns false only when the keyed items are at capacity.
     */
    fun offerKeyed(key: Any, item: T, replace: Boolean = false): Boolean = synchronized(lock) {
        if (key in pendingKeys) {
            if (replace) {
                val index = entries.indexOfFirst { it.key == key }
                if (index >= 0) {
                    val kept = entries.toMutableList()
                    kept[index] = Entry(item, key)
                    entries.clear()
                    entries.addAll(kept)
                }
            }
            return true
        }
        if (pendingKeys.size >= capacity) {
            refused++
            onRefused("$name control queue full ($capacity keyed controls): refused $key (refused so far $refused)")
            return false
        }
        pendingKeys.add(key)
        entries.addLast(Entry(item, key))
        lock.notifyAll()
        true
    }

    fun poll(): T? = synchronized(lock) { take() }

    /** Waits up to [timeoutMs] for an item. */
    fun poll(timeoutMs: Long, unit: java.util.concurrent.TimeUnit): T? {
        val deadline = System.nanoTime() + unit.toNanos(timeoutMs)
        synchronized(lock) {
            while (true) {
                take()?.let { return it }
                val remainingNs = deadline - System.nanoTime()
                if (remainingNs <= 0L) return null
                lock.wait(remainingNs / 1_000_000L, (remainingNs % 1_000_000L).toInt())
            }
        }
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        pendingKeys.clear()
    }

    private fun take(): T? {
        val entry = entries.pollFirst() ?: return null
        entry.key?.let { pendingKeys.remove(it) }
        return entry.item
    }
}
