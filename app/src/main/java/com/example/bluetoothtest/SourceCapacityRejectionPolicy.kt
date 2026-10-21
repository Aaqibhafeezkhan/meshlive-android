package com.example.bluetoothtest

/**
 * Per-request capacity refusals of a source. Design QV review 1, R3: the table is bounded ([MAX_ENTRIES], the
 * soonest-expiring entry goes first) and expired entries are swept on every refusal, so a stream of fresh request keys
 * (new RDI/address pairs) cannot grow it; before, an entry left only when the same key was looked up again.
 */
internal class SourceCapacityRejectionPolicy(maxEntries: Int = MAX_ENTRIES) {
    private class Refusal(val reason: String)

    private val refusals = BoundedExpiringMap<Refusal>(maxEntries)

    val size: Int @Synchronized get() = refusals.size

    @Synchronized
    fun reject(viewerKey: String, nowNs: Long, reason: String = REASON_CAPACITY) {
        refusals.sweep(nowNs)
        refusals.put(viewerKey, Refusal(reason), nowNs + CAPACITY_REJECTION_COOLDOWN_NS, nowNs)
    }

    /**
     * Why [viewerKey] was refused while its cooldown runs, or null. Reasons stay distinct (G-01, T-03): the phone-wide
     * link budget says [REASON_RELAY_FULL], measured capacity [REASON_CAPACITY], memory pressure its own reason.
     */
    @Synchronized
    fun reasonFor(viewerKey: String): String? = refusals.peek(viewerKey)?.reason

    @Synchronized
    fun shouldSuppress(
        viewerKey: String,
        nowNs: Long,
        capacityAvailable: Boolean,
        previousParentStale: Boolean,
    ): Boolean {
        val deadlineNs = refusals.deadline(viewerKey) ?: return false
        if (capacityAvailable || previousParentStale || nowNs >= deadlineNs) {
            refusals.remove(viewerKey)
            return false
        }
        return true
    }

    @Synchronized
    fun clear() = refusals.clear()

    companion object {
        /** The phone-wide LE link budget is used up ([GattLinkBudget.Admission.RELAY_FULL]). */
        const val REASON_RELAY_FULL = GattLinkBudget.Admission.RELAY_FULL

        /** Measured or learned link capacity (the original reason). */
        const val REASON_CAPACITY = "capacity"

        private const val CAPACITY_REJECTION_COOLDOWN_NS = 30_000_000_000L

        /** Far above a real crowd (each refused request is one entry for 30 s), small in memory (about 100 KiB). */
        const val MAX_ENTRIES = 512
    }
}
