package com.example.bluetoothtest

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Android glue of the experimental BMH3 handshake: the pin memory in no-backup storage, the log sink and the option
 * builders the call endpoint and the DEBUG call relay use. Everything here runs only while [Bmh3Feature.enabled]
 * (PROTOCOL "Experimental BMH3 identity-hiding link handshake").
 */
internal object Bmh3Android {
    @Volatile private var pins: Bmh3Pins? = null

    /**
     * The pin memory of this process: a file in `noBackupFilesDir`, so it is never part of a cloud backup or a device
     * transfer, and the emergency wipe deletes it with the rest of the app data.
     */
    fun pins(context: Context): Bmh3Pins = pins ?: synchronized(this) {
        pins ?: run {
            val file = File(context.applicationContext.noBackupFilesDir, Bmh3Pins.FILE_NAME)
            Bmh3Pins(
                object : Bmh3Pins.Storage {
                    override fun read(): String? = runCatching { if (file.isFile) file.readText() else null }.getOrNull()
                    override fun write(value: String) {
                        runCatching {
                            val temporary = File(file.parentFile, file.name + ".tmp")
                            temporary.writeText(value)
                            if (!temporary.renameTo(file)) {
                                file.writeText(value)
                                temporary.delete()
                            }
                        }
                    }
                },
            ).also { pins = it }
        }
    }

    /** One log line per event; DEBUG builds tag it for the benchmark tools. No identifiers are ever logged. */
    fun log(line: String) {
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + line)
    }
}

/**
 * What a call endpoint (or a DEBUG call relay) passes to the handshakes. Null means BMH2 exactly as before, so with
 * the switch off, or toward a version 2 peer with no hint and no pin, nothing changes on the wire.
 */
internal object Bmh3Wiring {
    /**
     * The initiator's options toward [selected] (null when it has no offer), or null for BMH2. The choice is made once
     * per attempt ([Bmh3Choice.decide]). A success pins the peer's node prefix; the discovery binding of [selected]
     * runs before this phone reveals itself.
     */
    fun initiator(
        context: Context,
        selected: DiscoveryAnnouncement?,
        layer: Bmh3Policy.Layer,
        rotatingIds: Boolean = false,
        pseudonyms: Boolean = true,
        strangerIdentity: SigningIdentity? = null,
    ): Bmh3InitiatorOptions? {
        if (!Bmh3Feature.enabled) return null
        val pins = Bmh3Android.pins(context)
        val pinned = selected != null && pins.isPinned(selected.nodeIdPrefix)
        // In pseudonym mode the caller never speaks BMH2 either: a BMH2 hello hands the real key to whoever answers,
        // before any ring (PROTOCOL "BMH2 is refused"). Cost: phones with BMH3 off cannot be called.
        val pseudonymMode = pseudonyms && Bmh3PseudonymFeature.active(rotatingIds)
        val decision = Bmh3Choice.decide(true, Bmh3Feature.only || pseudonymMode, selected, pinned)
        if (layer == Bmh3Policy.Layer.LINK) {
            Bmh3Android.log(Bmh3Log.choice(decision.version, decision.reason.name.lowercase()))
        }
        if (!decision.bmh3) return null
        return Bmh3Policy.initiatorOptions(
            selected,
            layer,
            log = Bmh3Android::log,
            pinResult = { peer -> runCatching { pins.pin(peer.nodeId.toByteArray()) } },
            leaseSource = if (rotatingIds) PresenceLeaseSource else null,
            pseudonyms = pseudonymMode,
            strangerIdentity = strangerIdentity,
        )
    }

    /**
     * A listener's options, or null while the switch is off (BMH2 only, a BMH3 first frame is rejected as before).
     * [pseudonyms] false for the DEBUG call relay, which stays a real identity in its own right.
     */
    fun responder(
        opening: List<OpeningEntry>,
        fixedPrefix: ByteArray,
        rotatingIds: Boolean = false,
        pseudonyms: Boolean = true,
    ): Bmh3ResponderOptions? {
        if (!Bmh3Feature.enabled) return null
        val pseudonymMode = pseudonyms && Bmh3PseudonymFeature.active(rotatingIds)
        return Bmh3Policy.responderOptions(
            opening = opening,
            fixedPrefix = fixedPrefix,
            admitInitiator = { _, _ -> true },
            log = Bmh3Android::log,
            leaseSource = if (rotatingIds) PresenceLeaseSource else null,
            pseudonyms = pseudonymMode,
            epoch = if (pseudonymMode) runCatching { RotatingDiscovery.currentEpoch() }.getOrNull() else null,
        )
    }
}

/**
 * The real contact tags (PROTOCOL "Real lease keys"): the recognition table says which contact an advert is, the
 * presence vault holds that contact's lease for the day and this phone's own day keys.
 */
internal object PresenceLeaseSource : Bmh3LeaseSource {
    override fun leaseFor(selected: DiscoveryAnnouncement): Bmh3LeaseGrant? {
        val vault = RotatingDiscovery.presenceVault() ?: return null
        val hit = RotatingDiscovery.recognize(selected).singleOrNull() ?: return null
        val lease = vault.contact(hit.contact)?.presence?.leasesByDay?.get(PresenceLeases.dayOf(hit.epoch)) ?: return null
        return Bmh3LeaseGrant(hit.contact, lease)
    }

    override fun ownDayKey(entry: OpeningEntry): ByteArray? {
        val vault = RotatingDiscovery.presenceVault() ?: return null
        val current = runCatching { RotatingDiscovery.currentEpoch() }.getOrNull() ?: return null
        return vault.dayKey(entry.epoch, current)
    }

    override fun deliveredTo(peer: NodeId): Boolean {
        val vault = RotatingDiscovery.presenceVault() ?: return false
        val generation = vault.generation()
        return generation > 0 && vault.contact(peer)?.deliveredGeneration == generation
    }
}
