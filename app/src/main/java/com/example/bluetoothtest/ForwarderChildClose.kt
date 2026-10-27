package com.example.bluetoothtest

/**
 * Review S3 (2026-10-27): how long a closing relay forwarder lets its child writer finish before closing the child link.
 *
 * A route withdrawal waits for the child's acknowledgement, and an ended stream lets the writer deliver the terminal
 * record (graceful). When the upstream link was lost instead, nothing more will arrive for the child: waiting the graceful
 * 2 s only delayed the child's own recovery (it sees the loss when its link closes), so the link closes at once.
 */
internal object ForwarderChildClose {
    fun writerWaitMs(withdrawing: Boolean, terminalForwarded: Boolean, withdrawalAckMs: Long, gracefulMs: Long): Long = when {
        withdrawing -> withdrawalAckMs
        terminalForwarded -> gracefulMs
        else -> 0L
    }
}
