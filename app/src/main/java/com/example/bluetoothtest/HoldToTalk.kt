package com.example.bluetoothtest

/**
 * The hold-Mute-to-talk rules, free of Android so they are unit-tested (the gesture in UiWidgets.kt
 * maps touch events and a Handler onto this).
 *
 * While muted, pressing Mute and holding for [holdMs] starts "talking" (the microphone opens) until the
 * finger lifts or the touch is cancelled; the click that would toggle Mute is swallowed. A short tap
 * stays an ordinary click. For screen-reader users, who cannot hold, [toggleLatched] starts and stops
 * talking with two activations of one accessibility action.
 */
internal class HoldToTalkCore(
    private val scheduler: Scheduler,
    private val canStart: () -> Boolean,
    private val onTalk: (Boolean) -> Unit,
    val holdMs: Long = DEFAULT_HOLD_MS,
) {
    /** One pending delayed action; scheduling replaces nothing (the core cancels first). */
    interface Scheduler {
        fun schedule(delayMs: Long, action: () -> Unit)
        fun cancel()
    }

    var talking = false
        private set

    /** Finger down. */
    fun down() {
        talking = false
        scheduler.cancel()
        if (canStart()) scheduler.schedule(holdMs, ::onHoldElapsed)
    }

    /**
     * Finger up or touch cancelled. Returns true when this ended a hold: the caller must swallow the
     * click (no Mute toggle). A short press returns false and stays a normal click.
     */
    fun up(): Boolean {
        scheduler.cancel()
        if (!talking) return false
        talking = false
        onTalk(false)
        return true
    }

    /** Ends talking from outside (screen paused) as if the finger lifted; [onTalk] hears it. */
    fun release() {
        up()
    }

    /** Forgets a hold in progress without telling anyone (the call it belonged to is gone). */
    fun reset() {
        scheduler.cancel()
        talking = false
    }

    /** The screen-reader path: starts talking, or stops it when already talking. Returns the new state. */
    fun toggleLatched(): Boolean {
        scheduler.cancel()
        if (talking) {
            talking = false
            onTalk(false)
        } else if (canStart()) {
            talking = true
            onTalk(true)
        }
        return talking
    }

    private fun onHoldElapsed() {
        if (talking || !canStart()) return
        talking = true
        onTalk(true)
    }

    companion object {
        const val DEFAULT_HOLD_MS = 350L
    }
}
