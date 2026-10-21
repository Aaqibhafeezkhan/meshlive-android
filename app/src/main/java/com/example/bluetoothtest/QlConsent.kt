package com.example.bluetoothtest

/**
 * Design QL revision 2 (DECISIONS; design decisions Q-QL2a-c): the recognition choice at the scan confirmation. On by
 * default; `test_ql_consent_ui=false` is the DEBUG control arm (today's dialog and texts).
 */
internal object QlConsent {
    @Volatile var enabled: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Writes the user's answer ([recognise]) to "Can see when I'm nearby" for the scanned person, after the shield was
     * stored and before the scanning session may offer a lease. Returns whether the session may go on to the lease
     * exchange: only when the answer is yes and was stored. A refusal, or a write that fails, withholds the lease (fails
     * closed). Without a persistent presence store there is nothing to record: a yes goes on as before, a no withholds.
     */
    fun applyAnswer(recognise: Boolean, vaultUsable: Boolean, write: (Boolean) -> Unit): Boolean {
        if (!vaultUsable) return recognise
        val stored = runCatching { write(recognise) }.isSuccess
        return stored && recognise
    }
}
