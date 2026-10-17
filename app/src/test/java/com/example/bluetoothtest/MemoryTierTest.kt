package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTierTest {
    private val phone = DeviceTraits("", "", 37)

    @After
    fun restore() {
        ThermalSwitches.memoryTierSizing = false
        MemoryThermalPolicy.configureForDevice(false, DeviceFamilyPolicy.resolve(phone, emptySet()))
    }

    @Test
    fun tierBoundariesFollowTheAndroid17MemoryLimiterTable() {
        val cases = listOf(
            0L to MemoryTier.BELOW_LIMITER,
            3_199L to MemoryTier.BELOW_LIMITER,
            3_200L to MemoryTier.TIER_4GB,
            4_799L to MemoryTier.TIER_4GB,
            4_800L to MemoryTier.TIER_6GB,
            6_799L to MemoryTier.TIER_6GB,
            6_800L to MemoryTier.TIER_8GB,
            9_215L to MemoryTier.TIER_8GB,
            9_216L to MemoryTier.TIER_12GB,
            14_335L to MemoryTier.TIER_12GB,
            14_336L to MemoryTier.TIER_16GB,
            18_431L to MemoryTier.TIER_16GB,
            18_432L to MemoryTier.ABOVE_LIMITER,
            65_536L to MemoryTier.ABOVE_LIMITER,
        )
        cases.forEach { (mib, tier) -> assertEquals("$mib MiB", tier, MemoryTier.fromTotalMiB(mib)) }
        assertEquals(MemoryTier.TIER_4GB, MemoryTier.fromTotalBytes(3_200L * 1024 * 1024))
    }

    @Test
    fun capsMatchTheTableAndTheTiersCoverEveryValueOnce() {
        assertEquals(1_024, MemoryTier.TIER_4GB.limiterNotVisibleMiB)
        assertEquals(2_048, MemoryTier.TIER_4GB.limiterVisibleMiB)
        assertEquals(2_048, MemoryTier.TIER_6GB.limiterNotVisibleMiB)
        assertEquals(3_072, MemoryTier.TIER_8GB.limiterNotVisibleMiB)
        assertEquals(4_096, MemoryTier.TIER_12GB.limiterNotVisibleMiB)
        assertEquals(5_120, MemoryTier.TIER_16GB.limiterNotVisibleMiB)
        assertNull(MemoryTier.BELOW_LIMITER.limiterNotVisibleMiB)
        assertNull(MemoryTier.ABOVE_LIMITER.limiterNotVisibleMiB)
        val tiers = MemoryTier.entries
        for (i in 1 until tiers.size) assertEquals(tiers[i - 1].maxMiB, tiers[i].minMiB)
    }

    @Test
    fun theLimiterAppliesOnlyFromAndroid17() {
        assertEquals(
            1_024L * 1024 * 1024 * 70 / 100,
            MemoryTierPolicy.nativeBudgetBytes(MemoryTier.TIER_4GB, 37),
        )
        assertNull(MemoryTierPolicy.nativeBudgetBytes(MemoryTier.TIER_4GB, 36))
        assertNull(MemoryTierPolicy.nativeBudgetBytes(MemoryTier.BELOW_LIMITER, 37))
        assertTrue(MemoryTierPolicy.limiterExpected(MemoryTier.TIER_6GB, 37))
        assertFalse(MemoryTierPolicy.limiterExpected(MemoryTier.TIER_6GB, 35))
    }

    @Test
    fun sizingIsTheOriginalConstantsUnlessTheSwitchIsOn() {
        val off = MemoryTierPolicy.sizing(MemoryTier.TIER_4GB, 37, isLowRamDevice = false, sizingEnabled = false)
        assertEquals(MemoryThermalPolicy.DEFAULT_SENDER_QUEUE_BYTES, off.senderQueueBytes)
        assertEquals(MemoryTierPolicy.DEFAULT_MAX_LAG_FRAMES, off.maxLagFrames)
        val on = MemoryTierPolicy.sizing(MemoryTier.TIER_4GB, 37, isLowRamDevice = false, sizingEnabled = true)
        assertEquals(4L * 1024 * 1024, on.senderQueueBytes)
        assertEquals(8, on.maxLagFrames)
        // Before Android 17 there is no limiter, so a 4 GB phone keeps the defaults.
        assertEquals(
            MemoryThermalPolicy.DEFAULT_SENDER_QUEUE_BYTES,
            MemoryTierPolicy.sizing(MemoryTier.TIER_4GB, 35, false, true).senderQueueBytes,
        )
        // Larger tiers keep the defaults; a low-RAM phone below 3200 MiB takes the tight values.
        assertEquals(MemoryTierPolicy.DEFAULT_MAX_LAG_FRAMES, MemoryTierPolicy.sizing(MemoryTier.TIER_8GB, 37, false, true).maxLagFrames)
        assertEquals(8, MemoryTierPolicy.sizing(MemoryTier.BELOW_LIMITER, 33, true, true).maxLagFrames)
        assertEquals(
            MemoryTierPolicy.DEFAULT_MAX_LAG_FRAMES,
            MemoryTierPolicy.sizing(MemoryTier.BELOW_LIMITER, 33, false, true).maxLagFrames,
        )
    }

    @Test
    fun lagRequestsAreOnlyEverLowered() {
        val tight = MemorySizing(4L * 1024 * 1024, 8, "x")
        assertEquals(8, MemoryTierPolicy.boundedLagFrames(32, tight))
        assertEquals(3, MemoryTierPolicy.boundedLagFrames(3, tight))
        assertEquals(0, MemoryTierPolicy.boundedLagFrames(0, tight))
    }

    @Test
    fun configureForDeviceAppliesTheTierOnlyWithTheSwitchAndTheEntryOn() {
        val policy = DeviceFamilyPolicy.resolve(phone, emptySet())
        MemoryThermalPolicy.configureForDevice(false, policy, totalMiB = 3_800, sdkInt = 37, sizingSwitch = false)
        assertEquals(MemoryTier.TIER_4GB, MemoryThermalPolicy.memoryTier)
        assertEquals(MemoryThermalPolicy.DEFAULT_SENDER_QUEUE_BYTES, MemoryThermalPolicy.senderQueueBytes)
        MemoryThermalPolicy.configureForDevice(false, policy, totalMiB = 3_800, sdkInt = 37, sizingSwitch = true)
        assertEquals(MemoryTierPolicy.TIGHT_SENDER_QUEUE_BYTES, MemoryThermalPolicy.senderQueueBytes)
        assertEquals(8, MemoryThermalPolicy.memorySizing.maxLagFrames)
        // The experiment's own off-switch wins over the launch extra.
        val off = DeviceFamilyPolicy.resolve(phone, setOf("memory-tier-sizing"))
        MemoryThermalPolicy.configureForDevice(false, off, totalMiB = 3_800, sdkInt = 37, sizingSwitch = true)
        assertEquals(MemoryThermalPolicy.DEFAULT_SENDER_QUEUE_BYTES, MemoryThermalPolicy.senderQueueBytes)
        // The sender queue is the smaller of the low-RAM rule and the tier rule.
        MemoryThermalPolicy.configureForDevice(true, policy, totalMiB = 8_000, sdkInt = 37, sizingSwitch = true)
        assertEquals(MemoryThermalPolicy.LOW_RAM_SENDER_QUEUE_BYTES, MemoryThermalPolicy.senderQueueBytes)
        // Without a MemTotal the behaviour is exactly the former one.
        MemoryThermalPolicy.configureForDevice(false, policy)
        assertEquals(MemoryThermalPolicy.DEFAULT_SENDER_QUEUE_BYTES, MemoryThermalPolicy.senderQueueBytes)
        assertNull(MemoryThermalPolicy.memoryTier)
    }

    @Test
    fun theSwitchDefaultsOffAndTierLoggingHasItsOwnOffSwitch() {
        assertFalse(ThermalSwitches.memoryTierSizing)
        assertTrue(MemoryThermalPolicy.tierLoggingActive(DeviceFamilyPolicy.resolve(phone, emptySet())))
        assertFalse(MemoryThermalPolicy.tierLoggingActive(DeviceFamilyPolicy.resolve(phone, setOf("memory-tier-log"))))
    }

    @Test
    fun exitClassifierSeparatesTheMemoryLimiterFromLmkd() {
        val other = android.app.ApplicationExitInfo.REASON_OTHER
        assertEquals(MemoryTierPolicy.ExitKind.MEMORY_LIMITER, MemoryTierPolicy.classifyExit(other, "MemoryLimiter:AnonSwap"))
        assertEquals(MemoryTierPolicy.ExitKind.MEMORY_LIMITER, MemoryTierPolicy.classifyExit(other, "killed by memorylimiter"))
        assertEquals(MemoryTierPolicy.ExitKind.LOW_MEMORY, MemoryTierPolicy.classifyExit(android.app.ApplicationExitInfo.REASON_LOW_MEMORY, null))
        assertEquals(MemoryTierPolicy.ExitKind.OTHER, MemoryTierPolicy.classifyExit(other, "something else"))
        assertEquals(MemoryTierPolicy.ExitKind.OTHER, MemoryTierPolicy.classifyExit(android.app.ApplicationExitInfo.REASON_USER_REQUESTED, null))
    }

    @Test
    fun logLineStatesTierCapAndSizing() {
        val sizing = MemoryTierPolicy.sizing(MemoryTier.TIER_4GB, 37, false, true)
        val line = MemoryTierPolicy.logLine(3_900, MemoryTier.TIER_4GB, 37, false, 256, sizing, true)
        assertEquals(
            "BENCHMARK memory_tier: totalMiB=3900 | tier=4gb | sdk=37 | lowRam=false | memoryClassMiB=256 | " +
                "limiterExpected=true | notVisibleCapMiB=1024 | nativeBudgetMiB=716 | sizingEnabled=true | " +
                "senderQueueBytes=4194304 | maxLagFrames=8 | sizingReason=limiter_4gb_not_visible_cap_1024mib",
            line,
        )
        val big = MemoryTierPolicy.logLine(24_000, MemoryTier.ABOVE_LIMITER, 34, false, 512, MemoryTierPolicy.sizing(MemoryTier.ABOVE_LIMITER, 34, false, false), false)
        assertTrue(big, "notVisibleCapMiB=none | nativeBudgetMiB=none" in big)
    }
}
