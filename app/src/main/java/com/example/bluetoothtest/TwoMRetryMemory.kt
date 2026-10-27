package com.example.bluetoothtest

/**
 * Review A6 (2026-10-27): which failed first 2M attempts may record the peer bad on 2M after the 1M retry authenticated.
 *
 * Only a setup stall (hop authentication not finished inside the guard) or a link that was lost after its hop
 * authenticated is evidence against the pair's 2M PHY. A connect failure (GATT status 133, service discovery, CoC "no
 * channel", a stale address) fails the same way on 1M, so the retry's success there says nothing about 2M; recording it
 * moved healthy pairs to 1M for the memory's lifetime.
 */
internal object TwoMRetryMemory {
    fun remembers(firstStalled: Boolean, firstHopAuthenticated: Boolean): Boolean = firstStalled || firstHopAuthenticated
}
