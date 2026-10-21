package com.example.bluetoothtest

/** A call offers saving a person, never an automatic call back to a stranger's rotating ID. */
internal object StrangerCallFollowup {
    fun offer(call: RecentCall?, contactPrefixes: Set<String>, enabled: Boolean): Boolean =
        enabled && call != null && !call.blocked &&
            (call.strangerName.isNotBlank() || call.nodePrefixHex.isNotBlank()) &&
            (call.nodePrefixHex.isEmpty() || call.nodePrefixHex !in contactPrefixes)
}
