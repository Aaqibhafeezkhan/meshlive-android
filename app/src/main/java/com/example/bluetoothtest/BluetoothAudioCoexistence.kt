package com.example.bluetoothtest

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Bluetooth audio shares the radio with MeshLive's LE links (G-10), as 2.4 GHz Wi-Fi does ([WifiCoexistence]). When
 * A2DP streams, the stack raises the earbud ACL priority (bta_av_act.cc:1101-1115 -> l2cu_set_acl_priority, a vendor
 * command on Broadcom, Synaptics and Unisoc controllers); eSCO reserves fixed slots and an LE Audio stream reserves
 * isochronous intervals. LE video then slows or its links time out the moment the user wears earbuds, and recovers when
 * audio stops; without a record this looks like a phone-family regression (xDrip#1061: a CGM link stable only while
 * Bluetooth headphones were connected is the same airtime effect from the other side).
 *
 * This object detects an active Bluetooth audio output, reports it in the link diagnostics and the device policy line,
 * and gives the advice text. It reduces nothing by default. Two Experimental arms: `test_bt_audio_capacity_key` keeps
 * learned link capacity apart while Bluetooth audio is active (like ".wifi24"); `test_bt_audio_load_cut` offers less
 * load ([loadFactor]).
 */
internal enum class BluetoothAudioKind(val label: String) {
    A2DP("a2dp"),
    SCO("sco"),
    LE_AUDIO("le_audio"),
    HEARING_AID("hearing_aid"),
}

internal data class BluetoothAudioState(val kinds: Set<BluetoothAudioKind>, val scoOn: Boolean) {
    val active: Boolean get() = kinds.isNotEmpty() || scoOn

    fun describe(): String =
        if (!active) "none" else kinds.sortedBy { it.ordinal }.joinToString("+") { it.label } + if (scoOn) "+sco_on" else ""
}

internal object BluetoothAudioCoexistence {
    /** AudioDeviceInfo output types that put a Bluetooth audio stream on the radio. */
    internal fun classifyOutputType(type: Int): BluetoothAudioKind? = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> BluetoothAudioKind.A2DP
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> BluetoothAudioKind.SCO
        TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_BLE_BROADCAST -> BluetoothAudioKind.LE_AUDIO
        TYPE_HEARING_AID -> BluetoothAudioKind.HEARING_AID
        else -> null
    }

    /** Pure core: the state for a list of output device types and the SCO flag. */
    internal fun stateFor(outputTypes: Collection<Int>, scoOn: Boolean): BluetoothAudioState =
        BluetoothAudioState(outputTypes.mapNotNull(::classifyOutputType).toSet(), scoOn)

    @Volatile private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun state(): BluetoothAudioState {
        val manager = appContext?.getSystemService(AudioManager::class.java) ?: return BluetoothAudioState(emptySet(), false)
        val types = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type } }.getOrDefault(emptyList())
        @Suppress("DEPRECATION")
        val sco = runCatching { manager.isBluetoothScoOn }.getOrDefault(false)
        return stateFor(types, sco)
    }

    /** A connected-and-routable device is not proof of streaming; this says "a Bluetooth audio output exists now". */
    fun active(): Boolean = state().active

    /** Plain-language advice when Bluetooth audio is active, or null. */
    fun advice(state: BluetoothAudioState = state()): String? = if (state.active) {
        "Bluetooth headphones or a speaker are in use. They share the radio with MeshLive and can slow video or drop links. " +
            "Use the phone speaker or wired headphones for the smoothest video."
    } else {
        null
    }

    /** Suffix for the learned-capacity key: ".btaudio" only with the Experimental arm and an active Bluetooth audio output. */
    fun capacityKeySuffix(): String = if (GattSwitches.btAudioCapacityKey && state().active) ".btaudio" else ""

    internal fun capacityKeySuffix(state: BluetoothAudioState): String =
        if (GattSwitches.btAudioCapacityKey && state.active) ".btaudio" else ""

    /**
     * Offered-load factor: 1.0 (no change) unless the Experimental `test_bt_audio_load_cut` is on and Bluetooth audio
     * is active, then [LOAD_CUT_FACTOR]. Callers that scale a relay or fan-out budget multiply by it.
     */
    fun loadFactor(): Double = if (GattSwitches.btAudioLoadCut && state().active) LOAD_CUT_FACTOR else 1.0

    internal fun loadFactor(state: BluetoothAudioState): Double =
        if (GattSwitches.btAudioLoadCut && state.active) LOAD_CUT_FACTOR else 1.0

    /** Field for the device_policy / link lines: `btAudio=a2dp+sco_on`. */
    fun benchmarkField(state: BluetoothAudioState = state()): String = "btAudio=${state.describe()}"

    const val LOAD_CUT_FACTOR = 0.6

    // AudioDeviceInfo types added after the project's compile SDK floor are spelled as constants (values from the SDK).
    private const val TYPE_HEARING_AID = 23
    private const val TYPE_BLE_HEADSET = 26
    private const val TYPE_BLE_SPEAKER = 27
    private const val TYPE_BLE_BROADCAST = 30
}
