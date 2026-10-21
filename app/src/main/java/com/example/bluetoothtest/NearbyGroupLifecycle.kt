package com.example.bluetoothtest

/** Keep the channel alive until an in-flight create callback can clean up its own group. */
internal class NearbyGroupLifecycle {
    enum class Cleanup { NONE, REMOVE_GROUP, CLOSE_CHANNEL }
    private var creating = false
    private var owned = false
    private var stopped = false
    fun creating() { check(!stopped); creating = true }
    fun created(): Cleanup {
        creating = false
        owned = true
        return if (stopped) release() else Cleanup.NONE
    }
    fun failed(): Cleanup {
        creating = false
        return if (stopped) Cleanup.CLOSE_CHANNEL else Cleanup.NONE
    }
    fun stop(): Cleanup {
        if (stopped) return Cleanup.NONE
        stopped = true
        return if (creating) Cleanup.NONE else release()
    }
    private fun release(): Cleanup = if (owned) {
        owned = false
        Cleanup.REMOVE_GROUP
    } else Cleanup.CLOSE_CHANNEL
}
