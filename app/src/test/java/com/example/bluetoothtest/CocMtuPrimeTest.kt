package com.example.bluetoothtest

import android.bluetooth.BluetoothProfile
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MTU-02 (default on for fresh-ACL centrals since Q49): one GATT MTU exchange on the fresh CoC ACL before CoC traffic. */
class CocMtuPrimeTest {
    private val lines = ArrayList<String>()
    private var clock = 0L
    private val connected = BluetoothProfile.STATE_CONNECTED
    private val disconnected = BluetoothProfile.STATE_DISCONNECTED

    private fun prime(connectTimeoutMs: Long = 300L, mtuTimeoutMs: Long = 300L) = CocMtuPrime(
        owner = "test",
        nowMs = { clock },
        log = { lines += it },
        connectTimeoutMs = connectTimeoutMs,
        mtuTimeoutMs = mtuTimeoutMs,
    )

    @Test
    fun aCompletedExchangeReportsTheEffectiveMtuAndTheInferredDataLength() {
        val prime = prime()
        val teardowns = AtomicInteger()
        var requested = 0
        val result = prime.execute(
            startConnect = { thread { prime.onConnectionState(0, connected) }; true },
            requestMtu = { mtu -> requested = mtu; thread { prime.onMtu(517, 0) }; true },
            teardown = { teardowns.incrementAndGet() },
        )
        assertEquals(CocMtuPrime.Outcome.OK, result.outcome)
        assertEquals("the stack is asked for 517, not a smaller user value that would cap the data length", 517, requested)
        assertEquals(517, result.effectiveMtu)
        assertEquals("MTU 517 + 4 header, capped at the 251-byte maximum", 251, result.inferredDataLength)
        assertEquals("the client is always torn down", 1, teardowns.get())
        val line = lines.single()
        assertTrue(line, "outcome=ok" in line && "requestedMtu=517" in line && "effectiveMtu=517" in line)
        assertTrue(line, "inferredDataLength=251" in line && "dataLengthObservable=false" in line && "placement=after_connect" in line)
    }

    @Test
    fun aSmallEffectiveMtuInfersASmallerDataLength() {
        val prime = prime()
        val result = prime.execute(
            startConnect = { prime.onConnectionState(0, connected); true },
            requestMtu = { prime.onMtu(23, 0); true },
            teardown = {},
        )
        assertEquals(CocMtuPrime.Outcome.OK, result.outcome)
        assertEquals(27, result.inferredDataLength)
    }

    @Test
    fun aClientThatNeverConnectsTimesOutAndStillTearsDown() {
        val prime = prime(connectTimeoutMs = 60L)
        val teardowns = AtomicInteger()
        val result = prime.execute(startConnect = { true }, requestMtu = { true }, teardown = { teardowns.incrementAndGet() })
        assertEquals(CocMtuPrime.Outcome.CONNECT_TIMEOUT, result.outcome)
        assertNull(result.effectiveMtu)
        assertEquals(1, teardowns.get())
        assertTrue(lines.single(), "outcome=connect_timeout" in lines.single())
    }

    @Test
    fun aFailedConnectionStateIsAConnectFailure() {
        val prime = prime()
        val failed = prime.execute(
            startConnect = { prime.onConnectionState(133, disconnected); true },
            requestMtu = { error("no MTU request after a failed connect") },
            teardown = {},
        )
        assertEquals(CocMtuPrime.Outcome.CONNECT_FAILED, failed.outcome)
        assertEquals(133, failed.gattStatus)
        val lost = prime().let { p ->
            p.execute(startConnect = { p.onConnectionState(0, disconnected); true }, requestMtu = { true }, teardown = {})
        }
        assertEquals(CocMtuPrime.Outcome.CONNECT_FAILED, lost.outcome)
    }

    @Test
    fun aRefusedConnectIsReportedWithoutWaiting() {
        val prime = prime()
        val teardowns = AtomicInteger()
        val result = prime.execute(startConnect = { false }, requestMtu = { true }, teardown = { teardowns.incrementAndGet() })
        assertEquals(CocMtuPrime.Outcome.CONNECT_REFUSED, result.outcome)
        assertEquals("nothing was opened, so there is nothing to tear down", 0, teardowns.get())
        val thrown = prime().let { p -> p.execute(startConnect = { error("boom") }, requestMtu = { true }, teardown = {}) }
        assertEquals("an exception from the platform call is a refusal, never a crash", CocMtuPrime.Outcome.CONNECT_REFUSED, thrown.outcome)
    }

    @Test
    fun aRefusedMtuRequestIsReported() {
        val prime = prime()
        val result = prime.execute(
            startConnect = { prime.onConnectionState(0, connected); true },
            requestMtu = { false },
            teardown = {},
        )
        assertEquals(CocMtuPrime.Outcome.MTU_REFUSED, result.outcome)
    }

    @Test
    fun anMtuCallbackThatNeverComesTimesOutAndAFailedStatusIsReported() {
        val timeout = prime(mtuTimeoutMs = 60L).let { p ->
            p.execute(startConnect = { p.onConnectionState(0, connected); true }, requestMtu = { true }, teardown = {})
        }
        assertEquals(CocMtuPrime.Outcome.MTU_TIMEOUT, timeout.outcome)
        val failed = prime().let { p ->
            p.execute(
                startConnect = { p.onConnectionState(0, connected); true },
                requestMtu = { p.onMtu(23, 4); true },
                teardown = {},
            )
        }
        assertEquals(CocMtuPrime.Outcome.MTU_FAILED, failed.outcome)
        assertNull("an MTU that failed is not reported as effective", failed.effectiveMtu)
        assertEquals(4, failed.gattStatus)
    }

    @Test
    fun aLinkLostDuringTheMtuWaitIsAFailureNotATimeout() {
        val prime = prime()
        val result = prime.execute(
            startConnect = { prime.onConnectionState(0, connected); true },
            requestMtu = { prime.onConnectionState(8, disconnected); true },
            teardown = {},
        )
        assertEquals(CocMtuPrime.Outcome.MTU_FAILED, result.outcome)
    }

    @Test
    fun theArmNeedsTheSwitchAndTheRegistryEntry() {
        val on = { CocPolicy.resolve(DeviceTraits("qcom", "x", 33), emptySet()) }
        assertFalse("the control arm (test_coc_mtu_prime=false)", CocMtuPrime.enabled(switchOn = false, policy = on))
        assertTrue(CocMtuPrime.enabled(switchOn = true, policy = on))
        CocSwitches.configure()
        assertTrue("default on", CocMtuPrime.enabled(policy = on))
        val offByRegistry = { CocPolicy.resolve(DeviceTraits("qcom", "x", 33), setOf("coc-mtu-exchange-before-traffic")) }
        assertFalse("the registry off-switch wins over the launch extra", CocMtuPrime.enabled(switchOn = true, policy = offByRegistry))
    }

    @Test
    fun theArmAppliesOnlyToTheFreshAclAndroid12And13Centrals() {
        fun enabled(sdk: Int) = CocMtuPrime.enabled(switchOn = true, policy = { CocPolicy.resolve(DeviceTraits("qcom", "x", sdk), emptySet()) })
        assertFalse("Android 10-11 keep the control GATT client alive", enabled(29) || enabled(30))
        assertTrue("Android 12 and 13 close it before the CoC connect", enabled(31) && enabled(32) && enabled(33))
        assertFalse("Android 14+ keep the control GATT client alive", enabled(34) || enabled(36))
        // The default-on arm follows the same stack rule as the fresh-ACL client it belongs to.
        val freshAclMatcher = DevicePolicyRegistry.entries.first { it.id == "le-coc-eatt-stranded-channels" }.matcher
        assertEquals(freshAclMatcher.sdkMin, CocPolicy.COC_MTU_EXCHANGE_BEFORE_TRAFFIC.matcher.sdkMin)
        assertEquals(freshAclMatcher.sdkMax, CocPolicy.COC_MTU_EXCHANGE_BEFORE_TRAFFIC.matcher.sdkMax)
    }

    @Test
    fun theSkippedLineNamesTheReason() {
        val line = CocMtuPrime.skippedLine("o", "control-link-alive")
        assertTrue(line, "outcome=skipped" in line && "reason=control-link-alive" in line)
    }
}
