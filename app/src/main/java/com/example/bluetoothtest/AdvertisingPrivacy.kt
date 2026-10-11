package com.example.bluetoothtest

import android.bluetooth.BluetoothAdapter
import android.os.Build
import android.util.Log

/**
 * Pre-handshake exposure controls of the experimental rotating discovery IDs (PROTOCOL "Pre-handshake
 * exposure", revised 2026-10-11). Everything here is idle with the rotating switch off.
 *
 * Non-discoverable connectable sets: on API 34+ every connectable app advertising set is started with
 * `AdvertisingSetParameters.Builder.setDiscoverable(false)`. AOSP then leaves the Flags AD out of the PDU and
 * refuses the GAP Device Name (0x2A00) to an unbonded, unencrypted central that connected through such a set
 * (`gap_ble.cc`: `GATT_PERM_READ_IF_ENCRYPTED_OR_DISCOVERABLE`, present from android14-release), without
 * renaming the phone for anything else. Controllers with neither LE extended nor vendor multi-advertising
 * treat every connection as discoverable, and Android 13 and older have no such permission; those phones keep
 * the explicit neutral-name option as their only control.
 */
object NonDiscoverableAdvertising {
    /** DEBUG launch extra; default true while the rotating switch is on, `=false` restores the previous sets. */
    const val EXTRA = "test_adv_non_discoverable"

    /** `AdvertisingSetParameters.Builder.setDiscoverable` is API 34 (Android 14). */
    const val MIN_SDK = 34

    /** Set once a stack refuses a non-discoverable start; every later set of this process is discoverable again. */
    @Volatile var rejected = false

    /** Start failures that point at the parameters rather than at a busy radio (AdvertisingSetCallback codes). */
    private val REJECTION_CODES = setOf(
        android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED,
        android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR,
    )

    /** The pure rule: a set is non-discoverable only for the rotating switch, on API 34+, connectable, control on. */
    fun active(featureOn: Boolean, sdkInt: Int, control: Boolean, connectable: Boolean): Boolean =
        featureOn && connectable && control && sdkInt >= MIN_SDK

    /** Whether a failed start with non-discoverable parameters should be retried discoverable (and the stack remembered as refusing). */
    fun refusedBy(statusOrNull: Int?, threw: Boolean): Boolean = threw || (statusOrNull != null && statusOrNull in REJECTION_CODES)

    /** The rule for a set built now (reads the live switch, SDK and control). */
    fun forNewSet(connectable: Boolean): Boolean = !rejected &&
        active(RotatingDiscoveryFeature.enabled, Build.VERSION.SDK_INT, DebugTunables.advNonDiscoverable, connectable)
}

/** What the rotating-ID path can know about this phone's advertising stack (no address, no identifier). */
object AdvertisingPrivacy {
    enum class AddressReuse {
        /** Extended or vendor multi-advertising, or an Android 10 to 11 stack: a new set gets a new address. */
        FRESH,

        /** Neither capability on Android 13 or newer: the gd stack reuses the host's initiator address. */
        SUSPECTED,

        /** Neither capability on Android 12, where the gd advertiser is only behind a flag: not known. */
        UNKNOWN,
    }

    /** First release whose Bluetooth module always uses the gd advertising manager (`le_advertising_manager.cc`). */
    const val GD_ADVERTISER_SDK = 33

    /** The Android 12 releases (S, S_V2) run the gd advertiser only behind a flag. */
    const val GD_ADVERTISER_FLAGGED_SDK = 31

    /**
     * Suspected only when both capabilities are known to be absent AND the stack is the gd advertiser, whose
     * `new_advertiser_address` reuses the initiator address on a legacy controller. Android 10 and 11
     * (`btm_ble_multi_adv.cc`) give every instance its own address, so there it is fresh; Android 12 is unknown.
     * An unknown capability is not evidence.
     */
    fun addressReuse(extendedAdv: Boolean?, multiAdv: Boolean?, sdkInt: Int = GD_ADVERTISER_SDK): AddressReuse = when {
        extendedAdv != false || multiAdv != false -> AddressReuse.FRESH
        sdkInt >= GD_ADVERTISER_SDK -> AddressReuse.SUSPECTED
        sdkInt >= GD_ADVERTISER_FLAGGED_SDK -> AddressReuse.UNKNOWN
        else -> AddressReuse.FRESH
    }

    /** Why the neutral name is, or is not, worth offering on this phone (the settings text). */
    enum class NeutralNameAdvice {
        /** API 33 or older: no GAP-name permission exists, so the name is readable by any phone that connects. */
        RECOMMENDED_OLD_ANDROID,

        /** The set's discoverable flag is ignored on a legacy-only controller. */
        RECOMMENDED_LEGACY_CONTROLLER,

        /** Classic roles answer name requests with the adapter name and use a permanent address. */
        RECOMMENDED_CLASSIC_IN_USE,

        /** This phone's stack refused the hidden-name parameters, so its sets are discoverable again (logged). */
        RECOMMENDED_STACK_REFUSED,

        /** Rotating IDs are off, so the sets are discoverable and the name is readable on any version. */
        RECOMMENDED_ROTATING_OFF,

        /**
         * API 34+ with extended or multi-advertising and non-discoverable sets active. Phones that connect to this
         * phone cannot read the name; links this phone opens itself (calls it places, a stream it sends to a viewer
         * that asked, a relay's child) still can, so the option stays useful there.
         */
        NOT_NEEDED_FOR_LE,
    }

    fun neutralNameAdvice(
        sdkInt: Int,
        addressReuse: AddressReuse,
        classicInUse: Boolean,
        nonDiscoverableActive: Boolean,
        stackRefused: Boolean = false,
    ): NeutralNameAdvice = when {
        addressReuse == AddressReuse.SUSPECTED -> NeutralNameAdvice.RECOMMENDED_LEGACY_CONTROLLER
        sdkInt < NonDiscoverableAdvertising.MIN_SDK -> NeutralNameAdvice.RECOMMENDED_OLD_ANDROID
        stackRefused -> NeutralNameAdvice.RECOMMENDED_STACK_REFUSED
        classicInUse -> NeutralNameAdvice.RECOMMENDED_CLASSIC_IN_USE
        !nonDiscoverableActive -> NeutralNameAdvice.RECOMMENDED_ROTATING_OFF
        else -> NeutralNameAdvice.NOT_NEEDED_FOR_LE
    }

    /** The two capability bits as the platform reports them (null when the call fails). */
    fun capabilities(adapter: BluetoothAdapter?): Pair<Boolean?, Boolean?> = Pair(
        runCatching { adapter?.isLeExtendedAdvertisingSupported }.getOrNull(),
        runCatching { adapter?.isMultipleAdvertisementSupported }.getOrNull(),
    )

    fun addressReuse(adapter: BluetoothAdapter?): AddressReuse =
        capabilities(adapter).let { addressReuse(it.first, it.second, Build.VERSION.SDK_INT) }

    @Volatile private var loggedFor: BluetoothAdapter? = null

    /**
     * Once per process and adapter, while the rotating switch is on: `rotid_adv` (what the sets will do) and
     * `rotid_address_reuse: source=capability` (whether a boundary restart can keep the address). Neither line
     * carries an address or an identifier.
     */
    @Synchronized
    fun logOnce(adapter: BluetoothAdapter?) {
        if (adapter == null || loggedFor === adapter) return
        loggedFor = adapter
        val (extended, multi) = capabilities(adapter)
        val reuse = addressReuse(extended, multi, Build.VERSION.SDK_INT)
        val prefix = if (BuildConfig.DEBUG) "BENCHMARK " else ""
        Log.i(
            "BluetoothVideo",
            prefix + "rotid_adv: nonDiscoverable=${NonDiscoverableAdvertising.forNewSet(connectable = true)} | " +
                "sdk=${Build.VERSION.SDK_INT} | extendedAdv=$extended | multiAdv=$multi",
        )
        Log.i(
            "BluetoothVideo",
            prefix + "rotid_address_reuse: source=capability | extendedAdv=$extended | multiAdv=$multi | " +
                "sdk=${Build.VERSION.SDK_INT} | status=${reuse.name.lowercase()}",
        )
    }
}

/**
 * Low-risk reductions of the fields a version 5 advert repeats in every epoch (PROTOCOL "Fields that stay
 * constant across epochs"). They apply to version 5 adverts only, so the switch off keeps every byte unchanged,
 * and they change nothing a receiver matches on.
 */
object RotatingAdvertFields {
    /** The unit of the compact UUID's bitrate field; an odd configured rate is rounded to it. */
    const val BITRATE_UNIT_KBPS = CompactDiscoveryAnnouncement.BITRATE_UNIT_KBPS

    /**
     * A version 5 viewer request advertises no codec summary: no source reads the viewer's decode mask before the
     * handshake (it matches flags and the route token), and the link hello carries `decodeCodecMask` anyway.
     */
    const val VIEWER_REQUEST_SUMMARY = 0

    /** [kbps] rounded to the nearest [BITRATE_UNIT_KBPS], never to 0 for a non-zero rate, within the 16-bit field. */
    fun roundedBitrate(kbps: Int): Int {
        if (kbps <= 0) return kbps
        val rounded = ((kbps + BITRATE_UNIT_KBPS / 2) / BITRATE_UNIT_KBPS) * BITRATE_UNIT_KBPS
        return minOf(maxOf(rounded, BITRATE_UNIT_KBPS), 0xffff)
    }

    /** A version 5 stream or forwarder offer with the reductions applied; anything else is returned as is. */
    fun forStreamOffer(offer: DiscoveryAnnouncement): DiscoveryAnnouncement =
        if (offer.rotating && offer.isStreamOffer()) {
            offer.copy(streamBitrateKbps = roundedBitrate(offer.streamBitrateKbps))
        } else {
            offer
        }

    /** A version 5 viewer request with the reductions applied; anything else is returned as is. */
    fun forViewerRequest(request: DiscoveryAnnouncement): DiscoveryAnnouncement =
        if (request.rotating && !request.isStreamOffer() && !request.isPrivateCallOffer()) {
            request.copy(capabilitySummary = VIEWER_REQUEST_SUMMARY)
        } else {
            request
        }
}
