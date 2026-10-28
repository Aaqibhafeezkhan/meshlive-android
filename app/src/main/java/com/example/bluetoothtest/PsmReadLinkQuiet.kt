package com.example.bluetoothtest

/**
 * Experimental (default off; A/B arm `psm_read_link_quiet`): the GATT link that only reads the LE CoC PSM before a fresh
 * ACL ([BleCocFreshAcl]) asks for nothing: no connection priority, no PHY and no MTU exchange. The CoC client closes that
 * link right after the read, so none of those requests can serve the call.
 *
 * Why it is an arm, not a fix. On an Android 12 M31 relay helper the PSM-read link to the callee sometimes died one
 * supervision timeout after its discovery-end update to the requested 15 ms (phyprio-ab-20261028: 3 of 12 relayed CoC
 * calls in the a15s>m31>asus direction). Its ACL then stayed up past the 2 s fresh-ACL wait (`coc_fresh_acl ... aclDown=false`)
 * and the CoC connect waited for that link's timeout. Without the priority request AOSP's discovery lock moves the link to
 * 7.5 ms and its unlock returns it to the creation interval instead of 15 ms (l2c_ble.cc `l2cble_conn_comp` sets
 * `L2C_BLE_NOT_DEFAULT_PARAM`; l2c_ble_conn_params.cc `l2cble_start_conn_update`), so this link no longer reaches the
 * 15 ms stage. Every logged death of that kind also killed the call's next link at its own update to 15 ms, the signature
 * of "Relay dual-role scheduling" (DECISIONS); the arm tells the two readings apart on phones: if only the fresh ACL's
 * wait was at fault, the call should now pass; if the standing dual-role phase is, the call still fails, about 5 s sooner.
 * DEBUG launch extra `test_psm_read_link_quiet=true`. Line: `BENCHMARK psm_read_link_quiet: ...`.
 */
internal object PsmReadLinkQuiet {
    /** What a PSM-read GATT link requests after it connects. */
    data class Requests(val priority: Boolean, val phy: Boolean, val mtu: Boolean)

    /** Quiet only when the switch is on and the CoC client will close this link for a fresh ACL. */
    fun appliesTo(switchOn: Boolean, freshAcl: Boolean): Boolean = switchOn && freshAcl

    fun enabled(freshAcl: Boolean): Boolean = appliesTo(DebugTunables.psmReadLinkQuiet, freshAcl)

    /**
     * The requests of a control link: a quiet PSM-read link (no known PSM yet) asks for nothing; every other link keeps
     * its priority and PHY requests, and the MTU exchange when the caller primes it.
     */
    fun requests(quiet: Boolean, knownPsm: Boolean, primeMtu: Boolean): Requests =
        if (quiet && !knownPsm) Requests(priority = false, phy = false, mtu = false) else Requests(true, true, primeMtu)

    fun line(peerSuffix: String?): String =
        "BENCHMARK psm_read_link_quiet: link=ble-control | event=skip | peer=...${peerSuffix ?: "?"} | " +
            "priority=skipped | phy=skipped | mtu=skipped"
}
