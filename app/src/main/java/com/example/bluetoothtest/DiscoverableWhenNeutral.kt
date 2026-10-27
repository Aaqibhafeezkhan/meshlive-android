package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import java.util.Collections
import java.util.WeakHashMap

/**
 * B2 (2026-10-27): the call-offer and viewer-request advertising sets are discoverable while, and only while, the
 * adapter name is the neutral name ([NeutralBluetoothName.NEUTRAL], the "Hide phone name" option).
 *
 * Why: on API 34+ a non-discoverable set ([NonDiscoverableAdvertising]) makes the GAP Device Name (0x2A00)
 * `GATT_PERM_READ_IF_ENCRYPTED_OR_DISCOVERABLE` for the centrals that connect through it (AOSP `gap_ble.cc`,
 * `gatt_db.cc`). A Mac (CoreBluetooth) reads 0x2A00 right after connecting, gets ATT error 0x05 (insufficient
 * authentication) and macOS starts LE pairing, with a prompt on both devices. A discoverable set lets that read
 * succeed without encryption. While the name is "Phone" the read reveals nothing that identifies the phone, so the
 * set may be discoverable; with any other name the set stays non-discoverable exactly as before.
 *
 * Rules: the actual adapter name is read before every start of such a set (not the option's stored value); a
 * name change away from "Phone" ([Intent] `ACTION_LOCAL_NAME_CHANGED`) stops every running set that this rule made
 * discoverable and lets its owner start it again (non-discoverable). The rename consent and restoration of the
 * neutral-name option are unchanged. No wire change.
 *
 * Switch: default on exactly when the neutral-name option is on; DEBUG launch extra
 * `test_discoverable_when_neutral=true|false` forces it (release builds ignore the extra).
 */
object DiscoverableWhenNeutral {
    /** DEBUG launch extra (absent: follow the neutral-name option). */
    const val EXTRA = "test_discoverable_when_neutral"

    /** The sets the rule covers (the log's `set=` value). */
    enum class Role(val label: String) {
        CALL_OFFER("call_offer"),
        VIEWER_REQUEST("viewer_request"),
    }

    /** The log's `reason=` value. */
    enum class Reason(val label: String) {
        /** The adapter name is "Phone" and the switch is on: discoverable. */
        NEUTRAL_NAME("neutral_name"),

        /** The switch is off (default with the neutral-name option off): non-discoverable as before. */
        SWITCH_OFF("switch_off"),

        /** The adapter name is something else: non-discoverable. */
        NAME_NOT_NEUTRAL("name_not_neutral"),

        /** The name cannot be read (Bluetooth off, no permission): non-discoverable. */
        NAME_UNREADABLE("name_unreadable"),

        /** The name changed away from "Phone" while a discoverable set was running: it is restarted hidden. */
        NAME_CHANGED("name_changed"),
    }

    data class Decision(val discoverable: Boolean, val reason: Reason)

    /** A running set that this rule made discoverable; told when the name leaves "Phone". */
    fun interface Listener {
        fun onNameLeftNeutral()
    }

    // ----- pure rules -----

    /** The effective switch: a DEBUG extra wins; otherwise on exactly when the neutral-name option is on. */
    fun switchOn(debug: Boolean, extra: Boolean?, neutralOptionOn: Boolean): Boolean =
        if (debug && extra != null) extra else neutralOptionOn

    /**
     * [currentName] is the adapter's name read now; [lastBroadcastName] the newest name from
     * `ACTION_LOCAL_NAME_CHANGED` (null: none seen), so a stale read cannot outlive a rename the stack announced.
     */
    fun decide(switchOn: Boolean, currentName: String?, lastBroadcastName: String? = null): Decision = when {
        !switchOn -> Decision(false, Reason.SWITCH_OFF)
        currentName == null -> Decision(false, Reason.NAME_UNREADABLE)
        currentName != NeutralBluetoothName.NEUTRAL -> Decision(false, Reason.NAME_NOT_NEUTRAL)
        lastBroadcastName != null && lastBroadcastName != NeutralBluetoothName.NEUTRAL -> Decision(false, Reason.NAME_CHANGED)
        else -> Decision(true, Reason.NEUTRAL_NAME)
    }

    /** Whether a name announced by the stack ends discoverability (anything but "Phone", including unreadable). */
    fun leavesNeutral(newName: String?): Boolean = newName != NeutralBluetoothName.NEUTRAL

    /** The covered role of an advertised announcement, or null for every other set (stream offers, renewals...). */
    fun roleOf(announcement: DiscoveryAnnouncement): Role? = when {
        announcement.isPrivateCallOffer() -> Role.CALL_OFFER
        !announcement.isStreamOffer() &&
            announcement.flags and DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS != 0 -> Role.VIEWER_REQUEST
        else -> null
    }

    // ----- runtime state -----

    /** The neutral-name option as MainActivity resolved it (stored choice, or the DEBUG benchmark extra). */
    @Volatile var neutralOptionOn: Boolean = false

    /** The newest name the stack announced (null: none since the process started). */
    @Volatile var lastBroadcastName: String? = null
        private set

    private val listeners: MutableSet<Listener> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    fun switchOnNow(): Boolean = switchOn(BuildConfig.DEBUG, DebugTunables.discoverableWhenNeutral, neutralOptionOn)

    /** The decision for a set about to start: reads the adapter name now. */
    fun decideNow(adapter: BluetoothAdapter?): Decision =
        decide(switchOnNow(), NeutralBluetoothNameController.currentName(adapter), lastBroadcastName)

    fun register(listener: Listener) {
        listeners.add(listener)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    /** `ACTION_LOCAL_NAME_CHANGED`: any name but "Phone" hides every set this rule made discoverable. */
    fun onLocalNameChanged(newName: String?) {
        lastBroadcastName = newName
        if (!leavesNeutral(newName)) return
        val affected = synchronized(listeners) { listeners.toList().also { listeners.clear() } }
        affected.forEach { runCatching { it.onNameLeftNeutral() } }
    }

    fun log(role: Role, decision: Decision) {
        if (!BuildConfig.DEBUG) return
        Log.i(
            "BluetoothVideo",
            "BENCHMARK adv_discoverable: set=${role.label} | discoverable=${decision.discoverable} | reason=${decision.reason.label}",
        )
    }

    @Volatile private var receiverRegistered = false

    /** Once per process (Application.onCreate): listens for adapter renames. */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Synchronized
    fun install(context: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED) return
                onLocalNameChanged(intent.getStringExtra(BluetoothAdapter.EXTRA_LOCAL_NAME))
            }
        }
        // A protected system broadcast: no export flag is needed (as bluetoothStateReceiver in MainActivity).
        context.applicationContext.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED))
    }
}
