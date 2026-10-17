package com.example.bluetoothtest

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context

/**
 * Phone-wide LE link budget (G-01). Every LE ACL, including CoC-only links, takes a GATT transport block, and the block
 * count is a stack constant shared by every app and device: GATT_MAX_PHY_CHANNEL is 7 in the android10, 12 and 14
 * `bt_target.h`; Android 15+ reads `bluetooth.core.le.max_number_of_concurrent_connections` clamped to 8..16
 * (`gatt_get_max_phy_channel()`, gatt_utils.cc). A relay with N children plus the user's watch or earbuds can hit the
 * limit while its own child caps (AudioLive 4, AndroidVideoReceiver maxChildren) say there is room: the stack then
 * strands the newest link at the link layer with no ATT bearer. The budget counts all links the stack reports, reserves
 * one for the upstream parent and refuses a child with the distinct reason "relay full" before that happens.
 *
 * Counting is a lower bound: `getConnectedDevices(GATT)` misses CoC-only links and links held by native profiles.
 * The learned ceiling covers the rest: at the first unexplained drop of an existing link the budget stops growing.
 */
internal class GattLinkBudget(
    private val sdk: Int,
    private val readSysprop: () -> Int?,
    private val countSystemLinks: () -> Int?,
    private val ownLinks: () -> Int,
    private val log: (String) -> Unit = GattResourceRegistry.Companion::logQuietly,
) {
    data class Snapshot(
        val statedCeiling: Int,
        val learnedCeiling: Int?,
        val systemLinks: Int?,
        val ownLinks: Int,
        val reservedForParent: Int,
    ) {
        val ceiling: Int get() = minOf(statedCeiling, learnedCeiling ?: statedCeiling)

        /** Links in use: the larger of what the stack reports and what MeshLive itself holds. */
        val used: Int get() = maxOf(systemLinks ?: 0, ownLinks)

        /** New child links the phone can still take (never negative). */
        val childSlotsFree: Int get() = (ceiling - reservedForParent - used).coerceAtLeast(0)
    }

    /**
     * The stack's other hard ceilings, keyed by SDK (AOSP bt_target.h, bta_dm_int.h; aosp-bt-versions GATT-06, COC-03).
     * A relay with a parent, children, control links and probes has to fit all of them, not only the GATT peer count.
     */
    data class HardLimits(
        /** GATT peers, client and server roles together, all apps (GATT_MAX_PHY_CHANNEL; SDK 35+ reads the property). */
        val gattPeers: Int,
        /** Concurrent ACL links: MAX_L2CAP_LINKS 13 up to Android 12, 16 from Android 13. */
        val aclLinks: Int,
        /** L2CAP channel control blocks: 32 up to Android 13, 64 from Android 14 (MAX_L2CAP_CHANNELS). */
        val l2capChannels: Int,
        /** GATT client registrations (GATT_MAX_APPS 32, two used by the stack); one per BluetoothGatt, BluetoothGattServer and, up to Android 12, scanner. */
        val gattClients: Int,
        /** BTA DM peer records: 7 up to Android 12 (an eighth ACL peer gets no record), no such cap after. */
        val dmPeers: Int?,
        /** Dynamic LE PSMs and RFCOMM ports a phone can serve. */
        val lePsms: Int,
        val rfcommPorts: Int,
    )

    /** CCBs left after [links] LE ACLs (3 fixed channels each: ATT, LE signalling, SMP), CoC channels and EATT bearers. */
    fun l2capChannelsFree(links: Int, cocChannels: Int, eattChannels: Int = 0): Int =
        (limits().l2capChannels - links * FIXED_CHANNELS_PER_LE_LINK - cocChannels - eattChannels).coerceAtLeast(0)

    fun limits(): HardLimits = limitsFor(sdk, statedCeiling())

    data class Admission(val allowed: Boolean, val reason: String?, val snapshot: Snapshot) {
        companion object {
            const val RELAY_FULL = "relay full"
        }
    }

    @Volatile private var learned: Int? = null

    /** The ceiling the stack imposes: 7 up to Android 14, the property clamped to 8..16 from Android 15. */
    fun statedCeiling(): Int = ceilingFor(sdk, if (sdk >= SDK_PROPERTY_ERA) readSysprop() else null)

    /**
     * [upstreamParentLinked]: true when this phone's upstream parent link is already up and therefore already counted
     * in [Snapshot.used]; false reserves one slot for it so the relay can still connect (or reconnect) upstream.
     */
    fun snapshot(upstreamParentLinked: Boolean): Snapshot = Snapshot(
        statedCeiling = statedCeiling(),
        learnedCeiling = learned,
        systemLinks = runCatching(countSystemLinks).getOrNull(),
        ownLinks = ownLinks(),
        reservedForParent = if (upstreamParentLinked) 0 else PARENT_RESERVE,
    )

    /**
     * Whether another child link fits. The decision is always computed and logged; [enforce] (the Experimental
     * `test_link_budget_admission`) decides whether the caller may act on a refusal. A refusal names its reason so it
     * stays distinct from "low memory" (T-03) in the user's explanation.
     */
    fun admitChild(upstreamParentLinked: Boolean, enforce: Boolean = GattSwitches.linkBudgetAdmission): Admission {
        val snapshot = snapshot(upstreamParentLinked)
        val fits = snapshot.childSlotsFree > 0
        val allowed = fits || !enforce
        log(line("admit_child", snapshot, "fits=$fits enforced=$enforce"))
        return Admission(allowed, if (allowed) null else Admission.RELAY_FULL, snapshot)
    }

    /**
     * An existing link dropped for a reason that is not ours (not a local close, not a peer's normal termination):
     * [linksBeforeDrop] links were up. The phone can no longer be assumed to hold that many, so the budget stops
     * adding children at one fewer. Kept for the run only, never persisted (other apps' links change).
     */
    fun onUnexplainedDrop(linksBeforeDrop: Int) {
        val ceilingNow = (linksBeforeDrop - 1).coerceAtLeast(MIN_LEARNED)
        val previous = learned
        if (previous == null || ceilingNow < previous) {
            learned = ceilingNow
            log("BENCHMARK gatt_link_budget: event=learned_ceiling | linksBeforeDrop=$linksBeforeDrop | learned=$ceilingNow | stated=${statedCeiling()}")
        }
    }

    fun line(event: String, snapshot: Snapshot = snapshot(upstreamParentLinked = true), extra: String = ""): String =
        "BENCHMARK gatt_link_budget: event=$event | sdk=$sdk | stated=${snapshot.statedCeiling} | learned=${snapshot.learnedCeiling ?: "none"} | " +
            "systemLinks=${snapshot.systemLinks ?: "unread"} | ownLinks=${snapshot.ownLinks} | reserveParent=${snapshot.reservedForParent} | " +
            "childSlotsFree=${snapshot.childSlotsFree} | " + limits().let {
                "aclMax=${it.aclLinks} | l2capChannelsMax=${it.l2capChannels} | gattClientsMax=${it.gattClients} | dmPeersMax=${it.dmPeers ?: "n/a"}"
            } + if (extra.isEmpty()) "" else " | $extra"

    fun resetLearned() {
        learned = null
    }

    companion object {
        /** GATT_MAX_PHY_CHANNEL up to Android 14 (bt_target.h android10/12/14). */
        const val LEGACY_CEILING = 7

        /** `bluetooth.core.le.max_number_of_concurrent_connections` floor and ceiling on Android 15+. */
        const val SYSPROP_FLOOR = 8
        const val SYSPROP_CEILING = 16

        const val SDK_PROPERTY_ERA = 35
        const val PARENT_RESERVE = 1
        private const val MIN_LEARNED = 1
        const val SYSPROP_KEY = "bluetooth.core.le.max_number_of_concurrent_connections"

        const val FIXED_CHANNELS_PER_LE_LINK = 3

        fun limitsFor(sdk: Int, gattPeers: Int): HardLimits = HardLimits(
            gattPeers = gattPeers,
            aclLinks = if (sdk >= 33) 16 else 13,
            l2capChannels = if (sdk >= 34) 64 else 32,
            gattClients = 32,
            dmPeers = if (sdk <= 32) 7 else null,
            lePsms = 128,
            rfcommPorts = 30,
        )

        /** 7 up to SDK 34; from SDK 35 the property clamped to 8..16, 8 (the floor) when it cannot be read. */
        fun ceilingFor(sdk: Int, sysprop: Int?): Int =
            if (sdk < SDK_PROPERTY_ERA) LEGACY_CEILING else (sysprop ?: SYSPROP_FLOOR).coerceIn(SYSPROP_FLOOR, SYSPROP_CEILING)

        @Volatile private var sharedInstance: GattLinkBudget? = null

        /** The process-wide budget, so a learned ceiling is shared by every admission caller (one budget instance). */
        fun shared(context: Context): GattLinkBudget =
            sharedInstance ?: synchronized(this) { sharedInstance ?: forThisPhone(context).also { sharedInstance = it } }

        /** The phone's budget: stack-reported links, this process's registry, reflection for the property. */
        fun forThisPhone(context: Context, registry: GattResourceRegistry = GattResourceRegistry.shared): GattLinkBudget {
            val manager = runCatching { context.applicationContext.getSystemService(BluetoothManager::class.java) }.getOrNull()
            return GattLinkBudget(
                sdk = android.os.Build.VERSION.SDK_INT,
                readSysprop = ::readLeConnectionProperty,
                countSystemLinks = { countConnectedGattDevices(manager) },
                ownLinks = { registry.count(GattResourceRegistry.Kind.CLIENT) + registry.count(GattResourceRegistry.Kind.SERVER) },
            )
        }

        /**
         * Distinct LE devices the stack reports as GATT connected, as client or as server. Needs BLUETOOTH_CONNECT on
         * Android 12+; null when it cannot be read (the caller then relies on its own count).
         */
        @android.annotation.SuppressLint("MissingPermission")
        fun countConnectedGattDevices(manager: BluetoothManager?): Int? = runCatching {
            val addresses = HashSet<String>()
            manager?.getConnectedDevices(BluetoothProfile.GATT)?.forEach { addresses += it.address }
            manager?.getConnectedDevices(BluetoothProfile.GATT_SERVER)?.forEach { addresses += it.address }
            addresses.size
        }.getOrNull()

        /** Reads the property through hidden `SystemProperties.getInt`; null when unavailable (fails closed to the floor). */
        fun readLeConnectionProperty(): Int? = runCatching {
            val method = Class.forName("android.os.SystemProperties").getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
            (method.invoke(null, SYSPROP_KEY, -1) as? Int)?.takeIf { it > 0 }
        }.getOrNull()
    }
}
