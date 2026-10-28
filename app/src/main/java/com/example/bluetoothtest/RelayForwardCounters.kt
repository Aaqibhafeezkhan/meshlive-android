package com.example.bluetoothtest

/**
 * One private-call relay direction's forwarding counts, logged once when the direction stops (diagnostics only).
 * A relayed call (A15s -> M31 -> ASUS, LE CoC) ended on a tag failure at the ASUS; with these counts the failing hop
 * sequence on the next phone can be matched to the record this relay wrote, and a record read but never written
 * shows up as `read - forwarded`.
 */
internal class RelayForwardCounters {
    var read = 0L
        private set
    var forwarded = 0L
        private set
    var contentBytes = 0L
        private set
    var heartbeats = 0L
        private set

    fun onRead() {
        read++
    }

    fun onForwarded(record: SecureRecord) {
        forwarded++
        if (record.type == SecureRecordType.LINK_HEARTBEAT) heartbeats++ else contentBytes += record.payload.size
    }

    /** [stage]: `read` or `write` when an error ended the direction, `stopped` when the relay was stopped. */
    fun line(direction: String, stage: String, ended: Exception?, sourceRead: Long, destinationWritten: Long): String =
        (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
            "relay_forwarded: direction=$direction | read=$read | forwarded=$forwarded | contentBytes=$contentBytes | " +
            "heartbeats=$heartbeats | sourceRecordsRead=$sourceRead | destinationRecordsWritten=$destinationWritten | " +
            "stage=$stage | ended=${ended?.javaClass?.simpleName ?: "none"}"
}
