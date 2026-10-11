package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hardening of the rotating discovery IDs (PROTOCOL "Pre-handshake exposure", "Address reuse on legacy-only
 * controllers", "Fields that stay constant across epochs"): the pure rules.
 */
class AdvertisingPrivacyTest {
    // ----- non-discoverable connectable sets -----

    @Test
    fun aSetIsNonDiscoverableOnlyForTheRotatingSwitchOnApi34ConnectableAndTheDefaultControl() {
        val on = NonDiscoverableAdvertising.active(featureOn = true, sdkInt = 34, control = true, connectable = true)
        assertTrue(on)
        // Switch off: every set is as before, whatever the SDK or control says.
        assertFalse(NonDiscoverableAdvertising.active(featureOn = false, sdkInt = 36, control = true, connectable = true))
        // Android 13 and older have no setDiscoverable and no GAP permission.
        assertFalse(NonDiscoverableAdvertising.active(featureOn = true, sdkInt = 33, control = true, connectable = true))
        // A non-connectable set never carries Flags, so there is nothing to change.
        assertFalse(NonDiscoverableAdvertising.active(featureOn = true, sdkInt = 34, control = true, connectable = false))
        // The DEBUG control arm (test_adv_non_discoverable=false) restores the previous sets.
        assertFalse(NonDiscoverableAdvertising.active(featureOn = true, sdkInt = 35, control = false, connectable = true))
        assertEquals(34, NonDiscoverableAdvertising.MIN_SDK)
    }

    @Test
    fun onlyParameterRefusalsMakeAStackFallBackToDiscoverable() {
        val feature = android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED
        val internal = android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR
        val tooMany = android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
        assertTrue(NonDiscoverableAdvertising.refusedBy(feature, threw = false))
        assertTrue(NonDiscoverableAdvertising.refusedBy(internal, threw = false))
        assertTrue(NonDiscoverableAdvertising.refusedBy(null, threw = true))
        // A busy radio, a timeout and a success are not about the parameters.
        assertFalse(NonDiscoverableAdvertising.refusedBy(tooMany, threw = false))
        assertFalse(NonDiscoverableAdvertising.refusedBy(null, threw = false))
        assertFalse(NonDiscoverableAdvertising.refusedBy(android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_SUCCESS, threw = false))
    }

    // ----- legacy-only controllers -----

    @Test
    fun addressReuseFollowsTheStackThatRunsTheAdvertiser() {
        // Android 13 and newer always run the gd advertiser, which reuses the initiator address on a legacy controller.
        assertEquals(AdvertisingPrivacy.AddressReuse.SUSPECTED, AdvertisingPrivacy.addressReuse(false, false, 33))
        assertEquals(AdvertisingPrivacy.AddressReuse.SUSPECTED, AdvertisingPrivacy.addressReuse(false, false, 36))
        // Android 12 runs it only behind a flag: not known.
        assertEquals(AdvertisingPrivacy.AddressReuse.UNKNOWN, AdvertisingPrivacy.addressReuse(false, false, 31))
        assertEquals(AdvertisingPrivacy.AddressReuse.UNKNOWN, AdvertisingPrivacy.addressReuse(false, false, 32))
        // Android 10 and 11 (btm_ble_multi_adv.cc) give each instance its own address even on a legacy controller.
        assertEquals(AdvertisingPrivacy.AddressReuse.FRESH, AdvertisingPrivacy.addressReuse(false, false, 29))
        assertEquals(AdvertisingPrivacy.AddressReuse.FRESH, AdvertisingPrivacy.addressReuse(false, false, 30))
        // With either capability there is never a suspicion.
        assertEquals(AdvertisingPrivacy.AddressReuse.FRESH, AdvertisingPrivacy.addressReuse(true, false, 34))
    }

    @Test
    fun addressReuseIsSuspectedOnlyWhenBothCapabilitiesAreKnownToBeAbsent() {
        assertEquals(AdvertisingPrivacy.AddressReuse.SUSPECTED, AdvertisingPrivacy.addressReuse(false, false))
        assertEquals(AdvertisingPrivacy.AddressReuse.FRESH, AdvertisingPrivacy.addressReuse(true, false))
        assertEquals(AdvertisingPrivacy.AddressReuse.FRESH, AdvertisingPrivacy.addressReuse(false, true))
        assertEquals(AdvertisingPrivacy.AddressReuse.FRESH, AdvertisingPrivacy.addressReuse(true, true))
        // Unknown is not evidence: no weaker-privacy note on a failed query.
        assertEquals(AdvertisingPrivacy.AddressReuse.FRESH, AdvertisingPrivacy.addressReuse(null, false))
        assertEquals(AdvertisingPrivacy.AddressReuse.FRESH, AdvertisingPrivacy.addressReuse(false, null))
        assertEquals(AdvertisingPrivacy.AddressReuse.FRESH, AdvertisingPrivacy.addressReuse(null, null))
    }

    // ----- neutral-name advice -----

    @Test
    fun theNeutralNameIsRecommendedOnlyWhereTheNameIsStillReadableOrLeaksElsewhere() {
        val fresh = AdvertisingPrivacy.AddressReuse.FRESH
        val legacy = AdvertisingPrivacy.AddressReuse.SUSPECTED
        fun advise(sdk: Int, reuse: AdvertisingPrivacy.AddressReuse, classicInUse: Boolean, nonDiscoverableActive: Boolean) =
            AdvertisingPrivacy.neutralNameAdvice(sdk, reuse, classicInUse, nonDiscoverableActive)
        val old = AdvertisingPrivacy.NeutralNameAdvice.RECOMMENDED_OLD_ANDROID
        // Android 14 or newer, fresh addresses, rotating IDs on: LE connections cannot read the name.
        assertEquals(
            AdvertisingPrivacy.NeutralNameAdvice.NOT_NEEDED_FOR_LE,
            advise(34, fresh, classicInUse = false, nonDiscoverableActive = true),
        )
        // The same phone while the rotating switch is off: the sets are discoverable.
        assertEquals(
            AdvertisingPrivacy.NeutralNameAdvice.RECOMMENDED_ROTATING_OFF,
            advise(36, fresh, classicInUse = false, nonDiscoverableActive = false),
        )
        assertEquals(old, advise(33, fresh, classicInUse = false, nonDiscoverableActive = false))
        assertEquals(old, advise(29, fresh, classicInUse = true, nonDiscoverableActive = false))
        // A legacy-only controller ignores the discoverable flag whatever the Android version is.
        assertEquals(
            AdvertisingPrivacy.NeutralNameAdvice.RECOMMENDED_LEGACY_CONTROLLER,
            advise(35, legacy, classicInUse = false, nonDiscoverableActive = true),
        )
        // A stack that refused the hidden-name parameters is back to discoverable sets (the refusal is logged).
        assertEquals(
            AdvertisingPrivacy.NeutralNameAdvice.RECOMMENDED_STACK_REFUSED,
            AdvertisingPrivacy.neutralNameAdvice(35, fresh, classicInUse = false, nonDiscoverableActive = false, stackRefused = true),
        )
        // Classic roles answer name requests with the adapter name.
        assertEquals(
            AdvertisingPrivacy.NeutralNameAdvice.RECOMMENDED_CLASSIC_IN_USE,
            advise(34, fresh, classicInUse = true, nonDiscoverableActive = true),
        )
    }

    @Test
    fun theNeutralNameOptionIsOffByDefaultAndABenchmarkLaunchNeedsItsOwnExtra() {
        val none = { _: String -> false }
        // A normal launch follows the stored choice; the default is off.
        assertFalse(NeutralNameOption.resolve(false, none, none))
        assertTrue(NeutralNameOption.resolve(false, none) { it == NeutralNameOption.PREF })
        // A benchmark launch ignores the stored choice and renames only with the extra or its alias.
        assertFalse(NeutralNameOption.resolve(true, none) { true })
        assertTrue(NeutralNameOption.resolve(true, { it == NeutralNameOption.EXTRA }, none))
        assertTrue(NeutralNameOption.resolve(true, { it == "test_rotating_neutral_name" }, none))
        assertEquals("neutral_bluetooth_name", NeutralNameOption.PREF)
    }

    @Test
    fun theNeutralNameRulesSaveTheOriginalRestoreItAndKeepAUserChosenName() {
        fun on(current: String?, saved: String?) = NeutralBluetoothName.onEnabled(current, saved)
        fun off(current: String?, saved: String?) = NeutralBluetoothName.onDisabled(current, saved)
        // Turning it on: remember the name, set "Phone".
        assertEquals(NeutralBluetoothName.Action("Phone", "Pixel of Sam", false), on("Pixel of Sam", null))
        // After a write succeeded, another name is the user's later choice and wins: the option pauses (RH12).
        assertEquals(NeutralBluetoothName.Action(null, null, true, pause = true), NeutralBluetoothName.onEnabled("My phone", "Pixel of Sam", applied = true))
        // Also when they renamed it back to the original: nothing is overwritten, the option is shown off.
        assertEquals(NeutralBluetoothName.Action(null, null, true, pause = true), NeutralBluetoothName.onEnabled("Pixel of Sam", "Pixel of Sam", applied = true))
        // Before any successful write the same situation is a failed attempt and is retried.
        assertEquals(NeutralBluetoothName.Action("Phone", "Pixel of Sam", false), NeutralBluetoothName.onEnabled("Pixel of Sam", "Pixel of Sam", applied = false))
        // Already "Phone": nothing to do (also when the saved copy is gone).
        assertEquals(NeutralBluetoothName.Action(null, null, false), on("Phone", "Pixel of Sam"))
        assertEquals(NeutralBluetoothName.Action(null, null, false), on("Phone", null))
        // Bluetooth off or the name unreadable: no change, no guess.
        assertEquals(NeutralBluetoothName.Action(null, null, false), on(null, "Pixel of Sam"))
        // A failed earlier rename left the original in place while the copy exists: write "Phone" again.
        assertEquals(NeutralBluetoothName.Action("Phone", "Pixel of Sam", false), on("Pixel of Sam", "Pixel of Sam"))
        // Turning it off: restore only our own "Phone".
        assertEquals(NeutralBluetoothName.Action("Pixel of Sam", null, true), off("Phone", "Pixel of Sam"))
        // The user chose another name meanwhile: keep it and drop the saved copy.
        assertEquals(NeutralBluetoothName.Action(null, null, true), off("My phone", "Pixel of Sam"))
        // Nothing saved (data cleared): nothing to restore.
        assertEquals(NeutralBluetoothName.Action(null, null, false), off("Phone", null))
    }

    // ----- low-risk field reductions -----

    private fun announcement(
        flags: Int,
        summary: Int,
        bitrate: Int,
        version: Int = DiscoveryAnnouncement.ROTATING_VERSION,
        recovery: ByteArray? = null,
    ) = DiscoveryAnnouncement(
        nodeIdPrefix = ByteArray(6) { 1 },
        flags = flags or (if (recovery != null) DiscoveryAnnouncement.FLAG_ROUTE_RECOVERY else 0),
        capabilitySummary = summary,
        streamToken = 0x1234,
        routeCost = 1,
        sequence = 3,
        streamWidth = 640,
        streamHeight = 360,
        streamFps = 15,
        streamBitrateKbps = bitrate,
        recoveryFromNodeIdPrefix = recovery,
        version = version,
    )

    @Test
    fun bitratesRoundToTheCompactUnitAndNeverToZero() {
        assertEquals(300, RotatingAdvertFields.roundedBitrate(300))
        assertEquals(475, RotatingAdvertFields.roundedBitrate(487))
        assertEquals(500, RotatingAdvertFields.roundedBitrate(488))
        assertEquals(25, RotatingAdvertFields.roundedBitrate(1))
        assertEquals(25, RotatingAdvertFields.roundedBitrate(12))
        assertEquals(0, RotatingAdvertFields.roundedBitrate(0))
        // The field is 16 bits wide: the largest value stays inside it.
        assertEquals(65525, RotatingAdvertFields.roundedBitrate(0xffff))
        assertTrue(RotatingAdvertFields.roundedBitrate(0xffff) <= 0xffff)
        assertEquals(CompactDiscoveryAnnouncement.BITRATE_UNIT_KBPS, RotatingAdvertFields.BITRATE_UNIT_KBPS)
    }

    @Test
    fun aVersion5StreamOfferRoundsOnlyTheBitrate() {
        val offer = announcement(DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE, 3, 487)
        val reduced = RotatingAdvertFields.forStreamOffer(offer)
        assertEquals(475, reduced.streamBitrateKbps)
        assertEquals(offer.copy(streamBitrateKbps = 475), reduced)
        // Version 2 and call offers are untouched (switch-off bytes stay the same).
        val fixed = announcement(DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE, 3, 487, version = DiscoveryAnnouncement.FIXED_PREFIX_VERSION)
        assertSame(fixed, RotatingAdvertFields.forStreamOffer(fixed))
        val call = announcement(DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS, 3, 487)
        assertSame(call, RotatingAdvertFields.forStreamOffer(call))
    }

    @Test
    fun aVersion5ViewerRequestCarriesNoDecodeMaskAndKeepsEverythingASourceMatchesOn() {
        val request = announcement(DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_GATT_TRANSPORT, 3, 300)
        val reduced = RotatingAdvertFields.forViewerRequest(request)
        assertEquals(0, reduced.capabilitySummary)
        assertEquals(request.copy(capabilitySummary = 0), reduced)
        assertTrue(reduced.isViewerRequest(0x1234))
        // The recovery request keeps its hint bytes.
        val hint = ByteArray(6) { 9 }
        val recovering = RotatingAdvertFields.forViewerRequest(
            announcement(DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS, 3, 300, recovery = hint),
        )
        assertEquals(0, recovering.capabilitySummary)
        assertEquals(hint.toList(), recovering.recoveryFromNodeIdPrefix?.toList())
        // Version 2 requests (an older source) and offers keep the summary: nothing changes with the switch off.
        val v2 = announcement(DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS, 3, 300, version = DiscoveryAnnouncement.FIXED_PREFIX_VERSION)
        assertSame(v2, RotatingAdvertFields.forViewerRequest(v2))
        val offer = announcement(DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE, 3, 300)
        assertSame(offer, RotatingAdvertFields.forViewerRequest(offer))
    }
}
