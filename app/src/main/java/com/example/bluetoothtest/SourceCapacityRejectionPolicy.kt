package com.example.bluetoothtest

import java.util.concurrent.ConcurrentHashMap

internal class SourceCapacityRejectionPolicy {
    private val rejectedUntilNs = ConcurrentHashMap<String, Long>()

    fun reject(viewerKey: String, nowNs: Long) {
        rejectedUntilNs[viewerKey] = nowNs + CAPACITY_REJECTION_COOLDOWN_NS
    }

    fun shouldSuppress(
        viewerKey: String,
        nowNs: Long,
        capacityAvailable: Boolean,
        previousParentStale: Boolean,
    ): Boolean {
        val deadlineNs = rejectedUntilNs[viewerKey] ?: return false
        if (capacityAvailable || previousParentStale || nowNs >= deadlineNs) {
            rejectedUntilNs.remove(viewerKey, deadlineNs)
            return false
        }
        return true
    }

    fun clear() = rejectedUntilNs.clear()

    private companion object {
        const val CAPACITY_REJECTION_COOLDOWN_NS = 30_000_000_000L
    }
}
