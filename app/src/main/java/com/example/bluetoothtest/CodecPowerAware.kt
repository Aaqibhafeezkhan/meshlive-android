package com.example.bluetoothtest

import android.content.Context
import android.os.PowerManager

/** The phone's power state when a call or stream starts, as the codec choice reads it (`power=` in `encoder_choice`). */
internal enum class CodecPowerState(val label: String) {
    NORMAL("normal"),
    SAVER("saver"),
    WARM("warm"),
}

/**
 * Design decision 2026-10-28 (DECISIONS "Automatic codec order after the 2026-10-28 phone measurements"), switch
 * `codec_power_aware`: when battery saver is on or the phone is already warm as a session starts, Automatic prefers a
 * hardware encoder over a software one (the bundled libvpx VP9 encoder costs about 4x the CPU of hardware H.264 on the
 * A15s). Only a phone whose best Automatic choice would otherwise be a software encoder changes; it is decided once, at
 * session start, never during a call or stream. Family compatibility rules (family VP9, peer bit 7) and a hand-picked
 * encoder are never overridden.
 *
 * Inputs (Android API reference): `PowerManager.isPowerSaveMode()` and `getCurrentThermalStatus()` (API 29+) at or above
 * `THERMAL_STATUS_LIGHT`; the activity installs a [provider] that reuses the thermal policy's effective status (battery
 * fallback, Experimental headroom forecast) so the codec rule and the thermal adaptation agree.
 */
internal object CodecPowerAware {
    /** DEBUG control arm: `test_codec_power_aware=false` keeps the plain Automatic order whatever the power state. */
    const val EXTRA = "test_codec_power_aware"

    /** Release builds always follow [ConnectivityFeatureDefaults.CODEC_POWER_AWARE]; a DEBUG launch may turn it off. */
    @Volatile var enabled: Boolean = ConnectivityFeatureDefaults.CODEC_POWER_AWARE
        set(value) {
            field = value || (!BuildConfig.DEBUG && ConnectivityFeatureDefaults.CODEC_POWER_AWARE)
        }

    /** Installed by the activity (thermal policy inputs); null reads the platform directly in [read]. */
    @Volatile var provider: (() -> CodecPowerState)? = null

    /** Battery saver first (it names the user's own choice), then heat at or above LIGHT. */
    fun state(powerSave: Boolean, thermalStatus: Int): CodecPowerState = when {
        powerSave -> CodecPowerState.SAVER
        thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT -> CodecPowerState.WARM
        else -> CodecPowerState.NORMAL
    }

    /** The state now (call it once per session start). Any read failure counts as normal. */
    fun read(context: Context): CodecPowerState {
        provider?.let { read -> runCatching(read).getOrNull()?.let { return it } }
        val power = context.getSystemService(PowerManager::class.java) ?: return CodecPowerState.NORMAL
        return state(
            runCatching { power.isPowerSaveMode }.getOrDefault(false),
            runCatching { power.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE),
        )
    }

    private fun isHardware(codec: VideoCodec) = codec.backend == VideoEncoderBackend.MEDIA_CODEC && codec.hardwareAccelerated

    /**
     * The rule on an Automatic list (already filtered for the peer): when [power] is not normal and the first entry is a
     * software encoder, the hardware encoders Automatic may use (not [excluded]) move ahead of the software ones, each
     * group keeping its order. Null when the rule leaves the list as it is.
     */
    fun reorder(
        list: List<VideoCodec>,
        power: CodecPowerState,
        enabled: Boolean,
        excluded: (VideoCodec) -> Boolean,
    ): List<VideoCodec>? {
        if (!enabled || power == CodecPowerState.NORMAL) return null
        val first = list.firstOrNull() ?: return null
        if (isHardware(first)) return null
        val hardware = list.filter { isHardware(it) && !excluded(it) }
        if (hardware.isEmpty()) return null
        return hardware + list.filter { it !in hardware }
    }

    /**
     * `encoder_choice` reason when the rule picked [sent]. Every hardware AV1, HEVC and VP9 encoder already ranks above
     * libvpx, so in practice the rule promotes hardware H.264; any other hardware codec gets the generic label.
     */
    fun reason(sent: VideoCodec): String =
        if (sent.protocolId == StreamProtocol.CODEC_H264) "power_prefers_hw_avc" else "power_prefers_hw"
}
