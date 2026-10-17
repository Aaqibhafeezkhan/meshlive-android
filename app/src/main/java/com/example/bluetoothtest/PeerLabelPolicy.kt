package com.example.bluetoothtest

import com.example.bluetoothtest.PolicyConfidence.LOW
import com.example.bluetoothtest.PolicySourceKind.API_REFERENCE
import com.example.bluetoothtest.PolicySourceKind.BLUETOOTH_SPEC
import com.example.bluetoothtest.PolicySourceStatus.PARTIAL

/**
 * The public stream-title page set may run: a second, legacy, non-connectable, scannable advertising
 * set beside the stream's own advertisement ([TitleAdvertiser]).
 */
internal data object TitleAdvertisingSet : DevicePolicyEffect {
    override fun describe(): String = "advertise the stream title in a second legacy advertising set (title pages)"
}

/**
 * Peer-label policy (names and titles, 2026-10-11). One registry entry owns the extra advertising set
 * so it has a matcher, sources, a confidence and an off-switch like every other stack-dependent
 * behaviour. The matcher is every phone today; the entry is where a stack that mishandles two
 * concurrent advertising sets is excluded, once evidence names it. Until then the runtime protections
 * stay: the title set starts only when the probe saw at least [TitleAdvertiser.MIN_FREE_SETS] free
 * sets, and any start failure leaves the stream's own advertising untouched (the viewer then sees the
 * signed title after joining).
 *
 * Off-switch: DEBUG `test_device_policy_off=peer-label-title-advertising-set` (no title pages; the
 * signed title still reaches viewers who join). The user-visible switch is Settings > Features "Names
 * and titles", which turns every label feature off.
 */
internal object PeerLabelPolicy {
    private const val REVIEWED = "2026-10-11"

    val TITLE_ADVERTISING_SET = DevicePolicyEntry(
        id = "peer-label-title-advertising-set",
        title = "Stream titles are advertised in a second legacy advertising set",
        matcher = DeviceMatcher(),
        effects = listOf(TitleAdvertisingSet),
        behaviour = "A phone broadcasting a titled stream (and a forwarder passing a title on) runs a second " +
            "non-connectable legacy advertising set whose scan response carries title pages, so viewers see " +
            "the title in the nearby list before joining. It starts only when the free-set probe found at " +
            "least two free sets, and a failed start changes nothing else. Off: no title pages; the signed " +
            "title still reaches viewers who join.",
        sources = listOf(
            PolicySource(
                API_REFERENCE,
                "https://developer.android.com/reference/android/bluetooth/le/AdvertisingSetCallback#ADVERTISE_FAILED_TOO_MANY_ADVERTISERS",
                "A start can fail because the controller has no free advertising set; other apps and the OS share them",
            ),
            PolicySource(
                API_REFERENCE,
                "https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#isMultipleAdvertisementSupported()",
                "Whether the chipset supports more than one concurrent advertisement",
            ),
            PolicySource(
                BLUETOOTH_SPEC,
                "Bluetooth Core Specification 5.4, Vol 4, Part E, section 7.8.58 (LE Read Number of Supported Advertising Sets)",
                "The controller reports its own limit on advertising sets",
            ),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no title-page run on a phone yet; the probe line BENCHMARK adv_set_probe records each phone's free sets"),
        confidence = LOW,
        confidenceNote = "The mechanism is documented; which stacks mishandle two concurrent legacy sets is unmeasured.",
        reviewed = REVIEWED,
        origin = "Review R13/R15 of the naming design: a title advertised publicly needs its own advertising set; " +
            "the README listed 'no DeviceFamilyPolicy off-switch for extra advertising sets' as open.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.EXPERIMENTAL,
        fallback = "No title pages; viewers see the title after joining, from the signed copy.",
    )

    val entries: List<DevicePolicyEntry> = listOf(TITLE_ADVERTISING_SET)

    /** True when this phone's policy lets the title page set run (entry active). */
    fun titleSetAllowed(policy: ResolvedDevicePolicy = DeviceFamilyPolicy.current()): Boolean =
        policy.has<TitleAdvertisingSet>()
}

/** Whether to start the title set now: the device policy first, then the free-set probe. */
internal object TitleAdvertisingDecision {
    sealed class Outcome {
        data object Start : Outcome()
        data class Skip(val reason: String) : Outcome()
    }

    /**
     * [freeSets] is the last probe result (null: not probed yet, so probe first). [budgetSkip] is the reason the
     * learned advertising-set budget ([AdvertisingBudget.skipReason]) leaves no room for the title set, or null.
     */
    fun decide(
        policyAllows: Boolean,
        freeSets: Int?,
        minFree: Int = TitleAdvertiser.MIN_FREE_SETS,
        budgetSkip: String? = null,
    ): Outcome = when {
        !policyAllows -> Outcome.Skip("device_policy")
        budgetSkip != null -> Outcome.Skip(budgetSkip)
        freeSets != null && freeSets < minFree -> Outcome.Skip("probe_found_$freeSets")
        else -> Outcome.Start
    }
}
