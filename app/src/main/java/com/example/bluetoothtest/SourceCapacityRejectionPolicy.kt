package com.example.bluetoothtest

import java.util.concurrent.ConcurrentHashMap

internal class SourceCapacityRejectionPolicy {
    private val rejectedUntilNs = ConcurrentHashMap<String, Long>()

    private val reasons = ConcurrentHashMap<String, String>()

    fun reject(viewerKey: String, nowNs: Long, reason: String = REASON_CAPACITY) {
        rejectedUntilNs[viewerKey] = nowNs + CAPACITY_REJECTION_COOLDOWN_NS
        reasons[viewerKey] = reason
    }

    /**
     * Why [viewerKey] was refused while its cooldown runs, or null. Reasons stay distinct (G-01, T-03): the phone-wide
     * link budget says [REASON_RELAY_FULL], measured capacity [REASON_CAPACITY], memory pressure its own reason.
     */
    fun reasonFor(viewerKey: String): String? = if (rejectedUntilNs.containsKey(viewerKey)) reasons[viewerKey] else null

    fun shouldSuppress(
        viewerKey: String,
        nowNs: Long,
        capacityAvailable: Boolean,
        previousParentStale: Boolean,
    ): Boolean {
        val deadlineNs = rejectedUntilNs[viewerKey] ?: return false
        if (capacityAvailable || previousParentStale || nowNs >= deadlineNs) {
            if (rejectedUntilNs.remove(viewerKey, deadlineNs)) reasons.remove(viewerKey)
            return false
        }
        return true
    }

    fun clear() {
        rejectedUntilNs.clear()
        reasons.clear()
    }

    companion object {
        /** The phone-wide LE link budget is used up ([GattLinkBudget.Admission.RELAY_FULL]). */
        const val REASON_RELAY_FULL = GattLinkBudget.Admission.RELAY_FULL

        /** Measured or learned link capacity (the original reason). */
        const val REASON_CAPACITY = "capacity"

        private const val CAPACITY_REJECTION_COOLDOWN_NS = 30_000_000_000L
    }
}
