package com.example.bluetoothtest

import java.util.concurrent.atomic.AtomicBoolean

internal class UpdateBusyException : IllegalStateException("Another update or media session is active")

/** An installer must never replace this process while it owns media, or supersede a pending user confirmation. */
internal object UpdateInstallPolicy {
    fun allowed(mediaActive: Boolean, pendingSession: Boolean): Boolean = !mediaActive && !pendingSession
}

/** Private candidate paths are shared by update screens, imports and startup cleanup. Never overwrite an active writer. */
internal object UpdateFileWork {
    private val busy = AtomicBoolean(false)
    fun <T> run(onBusy: () -> T, work: () -> T): T {
        if (!busy.compareAndSet(false, true)) return onBusy()
        return try { work() } finally { busy.set(false) }
    }
}
