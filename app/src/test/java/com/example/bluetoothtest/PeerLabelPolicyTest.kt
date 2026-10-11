package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The title advertising set's registry entry and start decision (PeerLabelPolicy.kt). */
class PeerLabelPolicyTest {
    @After
    fun restore() {
        DebugTunables.devicePolicyOff = emptySet()
        PeerLabels.knownNames = { emptyList() }
    }

    private val anyPhone = DeviceTraits("qcom", "msmnile", 30)

    @Test
    fun theTitleSetIsAnActiveRegistryEntryWithSourcesAndAnOffSwitch() {
        val entry = PeerLabelPolicy.TITLE_ADVERTISING_SET
        assertTrue(DeviceFamilyPolicy.entries.contains(entry))
        assertEquals("test_device_policy_off=peer-label-title-advertising-set", entry.offSwitch)
        assertTrue(entry.sources.isNotEmpty())
        assertTrue(entry.fallback.isNotBlank())
        assertFalse("not a proposal: it changes behaviour", entry.proposed)
        assertTrue(DeviceFamilyPolicy.resolve(anyPhone, emptySet()).isActive(entry.id))
        assertTrue(PeerLabelPolicy.titleSetAllowed(DeviceFamilyPolicy.resolve(anyPhone, emptySet())))
    }

    @Test
    fun theOffSwitchAndTheAllSwitchRemoveOnlyTheTitleSetBehaviour() {
        val id = PeerLabelPolicy.TITLE_ADVERTISING_SET.id
        val off = DeviceFamilyPolicy.resolve(anyPhone, setOf(id))
        assertFalse(PeerLabelPolicy.titleSetAllowed(off))
        assertEquals(listOf(id), off.switchedOff.map { it.id })
        assertFalse(PeerLabelPolicy.titleSetAllowed(DeviceFamilyPolicy.resolve(anyPhone, setOf("all"))))
    }

    @Test
    fun theStartDecisionAsksThePolicyBeforeTheProbe() {
        val start = TitleAdvertisingDecision.Outcome.Start
        assertEquals(start, TitleAdvertisingDecision.decide(policyAllows = true, freeSets = null))
        assertEquals(start, TitleAdvertisingDecision.decide(policyAllows = true, freeSets = 2))
        assertEquals(
            TitleAdvertisingDecision.Outcome.Skip("device_policy"),
            TitleAdvertisingDecision.decide(policyAllows = false, freeSets = 4),
        )
        assertEquals(
            TitleAdvertisingDecision.Outcome.Skip("probe_found_1"),
            TitleAdvertisingDecision.decide(policyAllows = true, freeSets = 1),
        )
    }

    @Test
    fun aSelfClaimedNameThatReadsLikeAContactIsFlaggedButAContactNeverIs() {
        PeerLabels.knownNames = { listOf("Alice", "Bob") }
        SessionPeerLabels.update("aaaaaaaaaaaa") { it.copy(selfName = "Аӏісе") }
        SessionPeerLabels.update("bbbbbbbbbbbb") { it.copy(selfName = "Carol") }
        try {
            val stranger = PeerLabels.forPeer("aaaaaaaaaaaa")
            assertEquals(PeerLabel.NameKind.SELF_NAMED, stranger.nameKind)
            assertEquals("Alice", stranger.lookAlikeOf)
            assertEquals(null, PeerLabels.forPeer("bbbbbbbbbbbb").lookAlikeOf)
            // The person you saved under that name is the real Alice: no warning on a contact.
            assertEquals(null, PeerLabels.forPeer("aaaaaaaaaaaa", "Alice").lookAlikeOf)
        } finally {
            SessionPeerLabels.clearAll()
        }
    }
}
