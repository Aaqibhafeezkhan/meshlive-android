package com.example.bluetoothtest

/*
 * Stack-family helpers. Family membership and behaviour now live in DevicePolicyRegistry; these
 * functions keep their former names and answer from the resolved device policy, so an entry's
 * off-switch (DEBUG `test_device_policy_off=<id>`) also turns off what they report.
 */

/**
 * Qualcomm msmnile (SM8150) on API <= 32 with its `qcom-msmnile-call` entry on for this view (off
 * under the legacy relief or `test_device_policy_off`).
 */
internal fun isLegacyQualcommMsmnileStack(fingerprint: BluetoothStackFingerprint): Boolean =
    DeviceFamilyPolicy.resolve(fingerprint).isActive("qcom-msmnile-call")

/** Qualcomm atoll (SM7125) on API <= 32 with its `qcom-atoll-coc-call` entry on for this view. */
internal fun isLegacyQualcommAtollStack(fingerprint: BluetoothStackFingerprint): Boolean =
    DeviceFamilyPolicy.resolve(fingerprint).isActive("qcom-atoll-coc-call")

internal object LegacyQualcommClassicCompatibility {
    /** Compatibility rules are stack-family based. Model must never be an input. */
    fun matches(
        transport: BluetoothTransportOption,
        fingerprint: BluetoothStackFingerprint,
    ): Boolean {
        if (transport != BluetoothTransportOption.CLASSIC_RFCOMM) return false
        return DeviceFamilyPolicy.resolve(fingerprint).has<ClassicSourceBalancedViewerScan>()
    }
}

internal fun useContinuousLowLatencyViewerDiscovery(
    transport: BluetoothTransportOption,
    fingerprint: BluetoothStackFingerprint,
    sourceSlotsFull: Boolean = false,
): Boolean = !LegacyQualcommClassicCompatibility.matches(transport, fingerprint) &&
    !(transport == BluetoothTransportOption.CLASSIC_RFCOMM && sourceSlotsFull)

/**
 * A full source stops scanning for new viewer requests until a slot frees. LE sources share one
 * controller with their media links: continuous low-latency scanning while full cost 342 vs 27
 * lost source frames (VMAF 75 vs 97) in the matched GATT A/B, and full chain/fan-out sources
 * dropped 110-934 packets. Legacy msmnile Classic sources are included: their balanced scan still
 * left 300-530 ms delivery gaps at 500 kbps (ASUS -> S24, coverage input), the same symptom its
 * low-latency scan caused; the balanced rule still applies while slots remain. Every stack.
 */
@Suppress("UNUSED_PARAMETER")
internal fun pauseClassicViewerDiscoveryAtCapacity(
    transport: BluetoothTransportOption,
    fingerprint: BluetoothStackFingerprint,
): Boolean = true

/**
 * The device traits that private-call compatibility rules see: the single call-path gate for the
 * legacy Qualcomm call protections, the [DevicePolicyRegistry.LEGACY_QUALCOMM_CALL] entries
 * (hardware `qcom` and API <= 32, with msmnile/atoll board entries; sources, evidence and
 * confidence are in the registry). No official source names these limits. Current
 * counter-evidence: the same ASUS received native AV1 640x480 at 300-1000 kbps over CoC and GATT
 * with 0 freezes and a 14.3-14.8 FPS worst window (bmaraw-ab-stream-9c121b30-m31asus-r1, 12 cases;
 * c2.android.av1.decoder), while its calls are held to 426x240-class geometry, 110 kbps on CoC,
 * Opus 16, 400 ms audio batches and 500-800 ms playout.
 *
 * Experimental legacy relief (default off; DEBUG `test_call_legacy_relief`): call paths get a view
 * of this phone with every LEGACY_QUALCOMM_CALL entry switched off, so none of the legacy call
 * protections applies and this phone advertises its normal receive limits. Relay and stream rules
 * keep the real traits. Confidence: low until the phone A/B in docs/TESTING.md.
 */
internal fun privateCallStackFingerprint(
    current: BluetoothStackFingerprint = BluetoothStackFingerprint.current(),
    relief: Boolean = DebugTunables.callLegacyRelief,
): BluetoothStackFingerprint = if (relief) {
    current.copy(policyOff = current.policyOff + DevicePolicyRegistry.LEGACY_QUALCOMM_CALL)
} else {
    current
}

/**
 * GATT call media write coalescing for this stack (40 ms on the legacy Qualcomm GATT call entry,
 * else 0). Kept for its former callers; [privateCallMediaFlushIntervalMs] is the full rule.
 */
internal fun legacyQualcommGattCallFlushIntervalMs(fingerprint: BluetoothStackFingerprint): Long =
    DeviceFamilyPolicy.resolve(fingerprint).callMediaFlushIntervalMs(BluetoothTransportOption.BLE_GATT) ?: 0L

internal fun privateCallPreferredDecoderName(
    codecId: Int,
    fingerprint: BluetoothStackFingerprint,
): String? = DeviceFamilyPolicy.resolve(fingerprint).callPreferredDecoder(codecId)
