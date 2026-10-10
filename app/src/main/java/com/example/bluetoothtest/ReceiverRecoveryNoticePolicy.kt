package com.example.bluetoothtest

internal class ReceiverRecoveryNoticePolicy {
    private var recoveryStartedAtMs: Long? = null

    val recovering: Boolean
        get() = recoveryStartedAtMs != null

    fun update(recovering: Boolean, nowMs: Long) {
        if (!recovering) recoveryStartedAtMs = null
        else if (recoveryStartedAtMs == null) recoveryStartedAtMs = nowMs
    }

    fun noticeDelayRemainingMs(nowMs: Long): Long? = recoveryStartedAtMs?.let {
        (NOTICE_DELAY_MS - (nowMs - it)).coerceAtLeast(0L)
    }

    fun noticeAlpha(nowMs: Long): Float {
        val started = recoveryStartedAtMs ?: return 0f
        val progress = ((nowMs - started - NOTICE_DELAY_MS).toFloat() / NOTICE_REVEAL_MS)
            .coerceIn(0f, 1f)
        return progress * progress * progress * progress
    }

    private companion object {
        const val NOTICE_DELAY_MS = 350L
        const val NOTICE_REVEAL_MS = 350L
    }
}

internal fun receiverStartAllowed(
    hasSelectedStreamRoute: Boolean,
    isFindingRoute: Boolean,
    isDirectDebugReceiver: Boolean,
): Boolean = !isFindingRoute && (hasSelectedStreamRoute || isDirectDebugReceiver)
