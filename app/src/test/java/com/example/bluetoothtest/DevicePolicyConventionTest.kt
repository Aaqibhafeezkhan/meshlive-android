package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The metadata every DevicePolicyEntry must carry, as one reusable check: batch tests call
 * `DevicePolicyEntryRules.violations(entry, strict = true)` on their own `entries` list, so each
 * batch is held to the same rules the registry's own entries are.
 */
internal object DevicePolicyEntryRules {
    private val ID = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

    /**
     * [strict] is for entries written from now on (camera, audio and every feature batch): they also
     * need a confidenceNote and an http(s) URL or a named specification for every source.
     */
    fun violations(entry: DevicePolicyEntry, strict: Boolean): List<String> = buildList {
        if (!ID.matches(entry.id)) add("id '${entry.id}' is not lower-case kebab")
        if (entry.title.isBlank() || entry.behaviour.isBlank() || entry.origin.isBlank()) add("title, behaviour and origin are required")
        if (!DATE.matches(entry.reviewed)) add("reviewed '${entry.reviewed}' is not yyyy-MM-dd")
        if (entry.evidence.isEmpty()) add("evidence must say 'none' or name a run")
        if (entry.offSwitch != "test_device_policy_off=${entry.id}") add("off switch must be test_device_policy_off=<id>")
        if (entry.sourceStatus != PolicySourceStatus.EVIDENCE_ONLY && entry.sources.isEmpty()) add("needs at least one source")
        if (entry.sourceStatus == PolicySourceStatus.EVIDENCE_ONLY && entry.confidence == PolicyConfidence.HIGH) {
            add("an evidence-only rule cannot claim high confidence")
        }
        entry.sources.forEach { source ->
            if (source.reference.isBlank() || source.note.isBlank()) add("source needs a reference and a note: $source")
            if (strict && source.kind != PolicySourceKind.BLUETOOTH_SPEC && source.kind != PolicySourceKind.CDD &&
                !source.reference.startsWith("http")
            ) add("source reference must be a URL: ${source.reference}")
        }
        if (entry.validation == PolicyValidation.LOCALLY_REPRODUCED &&
            entry.evidence.none { it.startsWith("benchmarks/") || it.startsWith("docs/") }
        ) add("locally reproduced needs a benchmarks/ or docs/ evidence path")
        if (strict && entry.proposed && entry.effects.isNotEmpty()) add("a proposal has no effects")
        if (!entry.proposed && entry.effects.isEmpty()) add("an implemented entry needs effects")
        if (strict && entry.confidenceNote.isBlank()) add("strict entries state what the confidence rests on (confidenceNote)")
        if (strict && entry.validation == PolicyValidation.LOCALLY_REPRODUCED && entry.evidence == listOf("none")) {
            add("locally reproduced with evidence 'none'")
        }
    }
}

class DevicePolicyConventionTest {
    @Test
    fun everyRegistryEntryMeetsTheBaselineRules() {
        val problems = DeviceFamilyPolicy.entries.flatMap { entry ->
            DevicePolicyEntryRules.violations(entry, strict = false).map { "${entry.id}: $it" }
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
        assertEquals(DeviceFamilyPolicy.entries.size, DeviceFamilyPolicy.entries.map { it.id }.toSet().size)
    }

    @Test
    fun theStrictRulesCatchWhatABatchEntryMustNotOmit() {
        val good = DevicePolicyEntry(
            id = "camera-example",
            title = "t",
            matcher = DeviceMatcher(),
            effects = listOf(CameraSessionRebuildMode(CameraRebuildMode.WAIT_SESSION_CLOSED)),
            behaviour = "b",
            sources = listOf(PolicySource(PolicySourceKind.AOSP_COMMIT, "https://android.googlesource.com/x", "n")),
            sourceStatus = PolicySourceStatus.OFFICIAL,
            evidence = listOf("none"),
            confidence = PolicyConfidence.MEDIUM,
            reviewed = "2026-10-14",
            origin = "o",
            confidenceNote = "mechanism documented upstream; unmeasured on owned phones",
            area = PolicyArea.CAMERA,
        )
        assertEquals(emptyList<String>(), DevicePolicyEntryRules.violations(good, strict = true))
        val bad = good.copy(id = "Camera_Example", confidenceNote = "", sources = emptyList(), reviewed = "yesterday")
        val problems = DevicePolicyEntryRules.violations(bad, strict = true)
        assertTrue(problems.toString(), problems.any { "kebab" in it })
        assertTrue(problems.toString(), problems.any { "confidenceNote" in it })
        assertTrue(problems.toString(), problems.any { "at least one source" in it })
        assertTrue(problems.toString(), problems.any { "yyyy-MM-dd" in it })
        assertTrue(DevicePolicyEntryRules.violations(good.copy(sources = good.sources.map { it.copy(reference = "see commit") }), strict = true)
            .any { "must be a URL" in it })
        assertFalse(DevicePolicyEntryRules.violations(good.copy(sources = good.sources.map { it.copy(reference = "see commit") }), strict = false)
            .any { "must be a URL" in it })
    }

    @Test
    fun textCorrectionsFromTheResearchAreInTheEntries() {
        val byId = DeviceFamilyPolicy.entries.associateBy { it.id }
        val qp = byId.getValue("omx-encoder-qp-bounds-ignored")
        assertTrue(qp.behaviour, "vendor.sec-ext-enc-qp-range" in qp.behaviour)
        assertTrue(qp.behaviour, "qti-ext-enc-qp-range" in qp.behaviour)
        assertTrue(qp.sources.any { it.reference.contains("Exynos_OMX_Vp9enc.c") })
        assertTrue(qp.confidenceNote.isNotBlank())
        assertTrue("still gated on the standard keys", "FEATURE_QpBounds" in qp.behaviour)
        val mtu = byId.getValue("android-14-gatt-mtu-517")
        assertTrue(mtu.behaviour, "512 bytes" in mtu.behaviour)
        assertTrue(mtu.sources.any { it.kind == PolicySourceKind.BLUETOOTH_SPEC && "3.2.9" in it.reference })
        assertTrue(mtu.confidenceNote.isNotBlank())
        assertTrue("both stay proposals", qp.proposed && mtu.proposed)
    }

    @Test
    fun cameraAndAudioAreAreasAndTheirEffectsReachTheResolver() {
        assertTrue(PolicyArea.entries.containsAll(listOf(PolicyArea.CAMERA, PolicyArea.AUDIO)))
        val camera = DevicePolicyEntry(
            id = "camera-rebuild-example", title = "t", matcher = DeviceMatcher(sdkMin = 29),
            effects = listOf(
                CameraSessionRebuildMode(CameraRebuildMode.REOPEN_DEVICE),
                CameraCaptureIntent(CameraTemplate.PREVIEW),
            ),
            behaviour = "b", sources = listOf(PolicySource(PolicySourceKind.AOSP_COMMIT, "https://x", "n")),
            sourceStatus = PolicySourceStatus.OFFICIAL, evidence = listOf("none"), confidence = PolicyConfidence.MEDIUM,
            reviewed = "2026-10-14", origin = "o", area = PolicyArea.CAMERA,
        )
        val audio = camera.copy(
            id = "audio-effects-example", area = PolicyArea.AUDIO,
            effects = listOf(
                AudioEffectPolicy(echoCanceler = AudioEffectStance.ENABLE),
                AudioCaptureSourceChain(listOf(AudioCaptureSource.VOICE_COMMUNICATION, AudioCaptureSource.MIC)),
            ),
        )
        val resolved = ResolvedDevicePolicy(
            traits = DeviceTraits("qcom", "taro", 34),
            disabled = emptySet(), active = listOf(camera, audio), switchedOff = emptyList(), matchingProposals = emptyList(),
        )
        assertEquals(CameraRebuildMode.REOPEN_DEVICE, resolved.effects<CameraSessionRebuildMode>().single().mode)
        assertEquals(CameraTemplate.PREVIEW, resolved.effects<CameraCaptureIntent>().single().template)
        assertEquals(AudioEffectStance.ENABLE, resolved.effects<AudioEffectPolicy>().single().echoCanceler)
        assertEquals(
            listOf(AudioCaptureSource.VOICE_COMMUNICATION, AudioCaptureSource.MIC),
            resolved.effects<AudioCaptureSourceChain>().single().sources,
        )
        // The call-video queries of other areas ignore them.
        assertEquals(emptyList<CallVideoCap>(), resolved.callVideoCaps(BluetoothTransportOption.BLE_GATT))
        assertEquals(null, resolved.callAudioBitRate(BluetoothTransportOption.BLE_GATT))
        assertFalse(resolved.has<CallConstrainedPlayback>())
    }

    @Test
    fun effectDescriptionsAreReadable() {
        assertEquals("camera session rebuild: WAIT_SESSION_CLOSED", CameraSessionRebuildMode(CameraRebuildMode.WAIT_SESSION_CLOSED).describe())
        assertEquals("camera capture request template: RECORD", CameraCaptureIntent(CameraTemplate.RECORD).describe())
        assertEquals("audio effects: AEC=LEAVE_DEFAULT, NS=DISABLE, AGC=LEAVE_DEFAULT",
            AudioEffectPolicy(noiseSuppressor = AudioEffectStance.DISABLE).describe())
        assertEquals("audio capture source order: VOICE_COMMUNICATION > MIC",
            AudioCaptureSourceChain(listOf(AudioCaptureSource.VOICE_COMMUNICATION, AudioCaptureSource.MIC)).describe())
    }

    @Test
    fun aSourceChainNeedsDistinctSources() {
        assertThrows(IllegalArgumentException::class.java) { AudioCaptureSourceChain(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            AudioCaptureSourceChain(listOf(AudioCaptureSource.MIC, AudioCaptureSource.MIC))
        }
    }

    @Test
    fun theAggregateContainsEveryAreaListAndStaysLazy() {
        val all = DevicePolicyRegistry.entries
        for (area in listOf(
            CodecPolicy.entries, YuvPolicy.entries, BluetoothTransportPolicy.entries, BackgroundPolicy.entries,
            MemoryThermalPolicy.entries, NativeCompat.policyEntries, PeerLabelPolicy.entries, ScanPolicy.entries,
            ServiceFamilyPolicy.entries, AudioPolicyEntries.entries, CocPolicy.entries, CameraPolicy.entries,
            AdvertisingPolicy.entries, PhyLinkPolicy.entries,
        )) {
            assertTrue(all.containsAll(area))
        }
        assertEquals(all.map { it.id }, DeviceFamilyPolicy.entries.map { it.id })
    }
}
