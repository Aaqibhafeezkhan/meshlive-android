package com.example.bluetoothtest

/**
 * Design HC keyframe requests (PROTOCOL "Sender rules", review 2 F10): a request must be answered by an IDR, judged from
 * the emitted NAL types. If none follows within [timeoutMs] the request is repeated; after [maxMisses] misses the
 * sender ends video with `no_idr` and the component is ineligible for this process. Pure; the encoder drives it.
 */
internal class IdrRequestDeadline(
    private val timeoutMs: Long = TIMEOUT_MS,
    private val maxMisses: Int = MAX_MISSES,
) {
    enum class Action { NONE, REPEAT, GIVE_UP }

    private var pendingSinceMs: Long? = null
    var misses = 0
        private set

    val pending: Boolean get() = pendingSinceMs != null

    /** A keyframe was requested from the encoder; a request already pending keeps its deadline. */
    @Synchronized
    fun onRequest(nowMs: Long) {
        if (pendingSinceMs == null) pendingSinceMs = nowMs
    }

    /** One emitted access unit; an IDR satisfies the pending request and clears the misses. */
    @Synchronized
    fun onAccessUnit(idr: Boolean) {
        if (!idr) return
        pendingSinceMs = null
        misses = 0
    }

    /** Called at least every [timeoutMs] while [pending]: what the encoder must do now. */
    @Synchronized
    fun poll(nowMs: Long): Action {
        val since = pendingSinceMs ?: return Action.NONE
        if (nowMs - since < timeoutMs) return Action.NONE
        misses++
        if (misses >= maxMisses) {
            pendingSinceMs = null
            return Action.GIVE_UP
        }
        pendingSinceMs = nowMs
        return Action.REPEAT
    }

    companion object {
        const val TIMEOUT_MS = 1_000L
        const val MAX_MISSES = 3
    }
}

/**
 * HC encoder components this process stopped trusting (`no_idr`, `reorder`, `unsupported_bitstream`): Automatic skips
 * them until the app restarts ([VideoCodecChoice.policyExcluded]); a manual choice still may use them.
 */
internal object HcComponentHealth {
    private val ineligible = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun markIneligible(encoderName: String, reason: String) {
        ineligible.putIfAbsent(encoderName, reason)
    }

    fun isIneligible(encoderName: String): Boolean = ineligible.containsKey(encoderName)

    fun reason(encoderName: String): String? = ineligible[encoderName]

    internal fun clearForTest() = ineligible.clear()
}
