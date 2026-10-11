package com.example.bluetoothtest

import java.util.TreeMap

/**
 * Experimental pseudonymous stranger identities on top of BMH3 (PROTOCOL "Phase 2: pseudonymous stranger
 * identities"): the switch, the throwaway keys, and the lease source that supplies the contact tags. Pure Kotlin,
 * JVM-tested. With the switch off nothing here runs on a call path and BMH3 behaves exactly as in Phase 1.
 */
object Bmh3PseudonymFeature {
    /** DEBUG benchmark launch extra of the switch (it also needs `test_bmh3` and rotating IDs). */
    const val EXTRA = "test_bmh3_pseudonyms"
    const val PREF = "experimental_bmh3_pseudonyms"

    /** The switch alone (ANDed with its parent `bmh3` by the feature catalog). */
    val switchOn: Boolean get() = FeatureCatalog.isEnabled(ConnectivityFeatures.bmh3Pseudonyms.id)

    /**
     * Strangers meet a pseudonym only while BMH3 and the switch are on and the phone runs rotating discovery IDs (with a
     * fixed prefix in the advertisement a pseudonym would hide nothing).
     */
    fun active(rotatingIds: Boolean): Boolean = Bmh3Feature.enabled && switchOn && rotatingIds
}

/**
 * The throwaway signing keys: one fresh key per connection for a responder and per attempt for a caller (review PR7: an
 * epoch-wide responder key would let one accepted reveal attribute every session of the epoch, and would tie the call
 * roles together). Software ECDSA P-256 keys, never persisted, never in the Keystore, nothing derives one from the node
 * ID, a lease key or another key.
 */
internal class Bmh3PseudonymKeys(private val factory: () -> SigningIdentity = { KeyPairSigningIdentity(generateEcKeyPair()) }) {
    fun fresh(): SigningIdentity = factory()
}

/**
 * The replay cache and the signing budget of stranger-facing responders, one pair per discovery epoch and dropped two
 * epochs later (review PR3): a process-wide cache would let a prober replay one msg1 across a boundary and read "same
 * phone" from the silent close, and a drained global budget carries over the boundary the same way.
 */
internal class Bmh3EpochGuards(
    private val replayCapacity: Int = 4_096,
    private val newBudget: () -> Bmh3SigningBudget = { Bmh3SigningBudget() },
) {
    class Guards(val replayCache: Bmh3ReplayCache, val budget: Bmh3SigningBudget)

    private val byEpoch = TreeMap<Long, Guards>()

    @Synchronized
    fun forEpoch(epoch: Long): Guards {
        byEpoch.headMap(epoch - 1).clear()
        return byEpoch.getOrPut(epoch) { Guards(Bmh3ReplayCache(replayCapacity), newBudget()) }
    }
}

/** One contact the selected advert was recognised as, with the lease key of the advert's day. */
class Bmh3LeaseGrant(val contact: NodeId, leaseKey: ByteArray) {
    val leaseKey: ByteArray = leaseKey.copyOf()
}

/**
 * Where the contact tags come from (PROTOCOL "Real lease keys"). Production: the recognition table and the presence
 * vault ([Bmh3Android]); tests inject their own.
 */
interface Bmh3LeaseSource {
    /** The one contact whose lease-derived ID equals [selected], with that day's lease key; null for none or several. */
    fun leaseFor(selected: DiscoveryAnnouncement): Bmh3LeaseGrant?

    /** This phone's own day key `L_d` for the day of [entry]'s epoch (a copy the caller zeroes), or null. */
    fun ownDayKey(entry: OpeningEntry): ByteArray?

    /** True when this phone delivered a lease of its current generation to [peer] (the only legitimate tag holders). */
    fun deliveredTo(peer: NodeId): Boolean
}
