package com.example.bluetoothtest

import android.content.ComponentCallbacks2
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryThermalPolicyTest {
    @Test
    fun headroomThresholdsFollowTheAdpfGuide() {
        assertEquals(PowerManager.THERMAL_STATUS_NONE, MemoryThermalPolicy.statusFromHeadroom(Float.NaN))
        assertEquals(PowerManager.THERMAL_STATUS_NONE, MemoryThermalPolicy.statusFromHeadroom(0.85f))
        assertEquals(PowerManager.THERMAL_STATUS_LIGHT, MemoryThermalPolicy.statusFromHeadroom(0.9f))
        assertEquals(PowerManager.THERMAL_STATUS_MODERATE, MemoryThermalPolicy.statusFromHeadroom(0.96f))
        assertEquals(PowerManager.THERMAL_STATUS_SEVERE, MemoryThermalPolicy.statusFromHeadroom(1.01f))
        // A forecast alone can never reach the status that stops streaming.
        assertFalse(ThermalGuardPolicy.shouldBlockStreaming(MemoryThermalPolicy.statusFromHeadroom(5f)))
    }

    @Test
    fun forecastRaisesButNeverLowersTheStatusAndOnlyWhenEnabled() {
        val none = PowerManager.THERMAL_STATUS_NONE
        assertEquals(none, MemoryThermalPolicy.effectiveStatus(none, 1.2f, false))
        assertEquals(PowerManager.THERMAL_STATUS_SEVERE, MemoryThermalPolicy.effectiveStatus(none, 1.2f, true))
        assertEquals(
            PowerManager.THERMAL_STATUS_CRITICAL,
            MemoryThermalPolicy.effectiveStatus(PowerManager.THERMAL_STATUS_CRITICAL, 0.1f, true),
        )
        assertEquals(
            PowerManager.THERMAL_STATUS_MODERATE,
            MemoryThermalPolicy.effectiveStatus(PowerManager.THERMAL_STATUS_MODERATE, null, true),
        )
    }

    @Test
    fun speedFloorNeedsReductionAndTheExperiment() {
        val moderate = PowerManager.THERMAL_STATUS_MODERATE
        val severe = PowerManager.THERMAL_STATUS_SEVERE
        assertEquals(0, MemoryThermalPolicy.nativeSpeedFloor(severe, reductionEnabled = false, speedFloorEnabled = true))
        assertEquals(0, MemoryThermalPolicy.nativeSpeedFloor(severe, reductionEnabled = true, speedFloorEnabled = false))
        assertEquals(0, MemoryThermalPolicy.nativeSpeedFloor(PowerManager.THERMAL_STATUS_LIGHT, true, true))
        assertEquals(8, MemoryThermalPolicy.nativeSpeedFloor(moderate, true, true))
        assertEquals(10, MemoryThermalPolicy.nativeSpeedFloor(severe, true, true))
    }

    @Test
    fun benchmarkProfilesWithReductionOffLogANoOpAction() {
        val moderate = PowerManager.THERMAL_STATUS_MODERATE
        assertEquals("none_reduction_disabled", MemoryThermalPolicy.thermalAction(moderate, false, 0))
        assertEquals("limit_workload_90pct", MemoryThermalPolicy.thermalAction(moderate, true, 0))
        assertEquals(
            "limit_workload_80pct_cpu_used_floor_10",
            MemoryThermalPolicy.thermalAction(PowerManager.THERMAL_STATUS_SEVERE, true, 10),
        )
        assertEquals("stop_streaming", MemoryThermalPolicy.thermalAction(PowerManager.THERMAL_STATUS_CRITICAL, false, 0))
        assertEquals("none", MemoryThermalPolicy.thermalAction(PowerManager.THERMAL_STATUS_LIGHT, true, 0))
    }

    @Test
    fun benchmarkLineCarriesStatusActionAndSwitches() {
        val severe = PowerManager.THERMAL_STATUS_SEVERE
        val line = MemoryThermalPolicy.benchmarkLine(severe, 1.04f, severe, true, 0, "listener")
        assertTrue(
            line,
            line.startsWith(
                "BENCHMARK thermal: status=SEVERE | forecastHeadroom=1.04 | effective=SEVERE | " +
                    "action=limit_workload_80pct",
            ),
        )
        assertTrue(line, line.endsWith("source=listener"))
        assertTrue(MemoryThermalPolicy.benchmarkLine(0, null, 0, false, 0, "x").contains("forecastHeadroom=na"))
    }

    @Test
    fun trimLevelsMapToDocumentedActions() {
        val none = MemoryThermalPolicy.TrimAction.NONE
        assertEquals(none, MemoryThermalPolicy.trimAction(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertEquals(
            MemoryThermalPolicy.TrimAction.DROP_REBUILDABLE,
            MemoryThermalPolicy.trimAction(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW),
        )
        assertEquals(
            MemoryThermalPolicy.TrimAction.DROP_REBUILDABLE,
            MemoryThermalPolicy.trimAction(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL),
        )
        // UI_HIDDEN fires on every Home press or screen-off: not a memory signal.
        assertEquals(none, MemoryThermalPolicy.trimAction(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertEquals(
            MemoryThermalPolicy.TrimAction.RELEASE_IDLE_DISCOVERY,
            MemoryThermalPolicy.trimAction(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND),
        )
        assertEquals(
            MemoryThermalPolicy.TrimAction.RELEASE_IDLE_DISCOVERY,
            MemoryThermalPolicy.trimAction(ComponentCallbacks2.TRIM_MEMORY_COMPLETE),
        )
    }

    private val phone = DeviceTraits("", "", 34)

    @Test
    fun lowRamDevicesGetTheSmallerSenderQueueUnlessTheEntryIsSwitchedOff() {
        try {
            val on = DeviceFamilyPolicy.resolve(phone, emptySet())
            MemoryThermalPolicy.configureForDevice(false, on)
            assertEquals(MemoryThermalPolicy.DEFAULT_SENDER_QUEUE_BYTES, MemoryThermalPolicy.senderQueueBytes)
            MemoryThermalPolicy.configureForDevice(true, on)
            assertEquals(MemoryThermalPolicy.LOW_RAM_SENDER_QUEUE_BYTES, MemoryThermalPolicy.senderQueueBytes)
            MemoryThermalPolicy.configureForDevice(true, DeviceFamilyPolicy.resolve(phone, setOf("low-ram-sender-queue")))
            assertEquals(MemoryThermalPolicy.DEFAULT_SENDER_QUEUE_BYTES, MemoryThermalPolicy.senderQueueBytes)
        } finally {
            MemoryThermalPolicy.configureForDevice(false, DeviceFamilyPolicy.resolve(phone, emptySet()))
        }
    }

    @Test
    fun registrySwitchesTurnEachImplementedEntryOffIndividually() {
        val on = DeviceFamilyPolicy.resolve(phone, emptySet())
        assertTrue(MemoryThermalPolicy.ladderActive(on))
        assertTrue(MemoryThermalPolicy.trimReleaseActive(on))
        assertEquals(256, MemoryThermalPolicy.controlQueueCapacity(256, on))
        val off = DeviceFamilyPolicy.resolve(
            phone,
            setOf("thermal-status-ladder", "trim-memory-release", "bounded-control-queues"),
        )
        assertFalse(MemoryThermalPolicy.ladderActive(off))
        assertFalse(MemoryThermalPolicy.trimReleaseActive(off))
        assertEquals(Int.MAX_VALUE, MemoryThermalPolicy.controlQueueCapacity(256, off))
    }

    @Test
    fun budgetSocFamiliesReportTheSocSpecificProposalsWithoutChangingBehaviour() {
        val unisoc = DeviceTraits("ums9230", "ums9230", 34, manufacturer = "Transsion", socManufacturer = "Spreadtrum")
        val resolved = DeviceFamilyPolicy.resolve(unisoc, emptySet())
        val proposals = resolved.matchingProposals.map { it.id }
        assertTrue(proposals.toString(), "thermal-battery-temperature-fallback" in proposals)
        assertTrue(proposals.toString(), "native-armeabi-v7a-optional-build" in proposals)
        assertFalse(resolved.isActive("thermal-battery-temperature-fallback"))
        // A phone that is not Unisoc/MediaTek does not list them.
        val qualcomm = DeviceFamilyPolicy.resolve(DeviceTraits("qcom", "kona", 34, socManufacturer = "QTI"), emptySet())
        assertFalse("thermal-battery-temperature-fallback" in qualcomm.matchingProposals.map { it.id })
    }

    @Test
    fun fullControlQueueRefusesAndLogsKeyedControlsButNeverDropsLifecycleSignals() {
        val refused = mutableListOf<String>()
        val queue = BoundedControlQueue<String>(capacity = 2, name = "call", onRefused = { refused += it })
        assertTrue(queue.offerKeyed("a", "keyframe"))
        assertTrue(queue.offerKeyed("b", "rotation"))
        assertFalse(queue.offerKeyed("c", "other"))
        assertEquals(1, refused.size)
        assertTrue(refused[0], refused[0].contains("refused c"))
        // Hang-up, decline, cancel and the like bypass the bound, even with the queue full.
        queue.offerMust("hang-up")
        queue.offerMust("cancel")
        assertEquals(4, queue.size)
        assertEquals(listOf("keyframe", "rotation", "hang-up", "cancel"), generateSequence { queue.poll() }.toList())
        // Polling frees capacity again.
        assertTrue(queue.offerKeyed("c", "other"))
        assertEquals(1L, queue.refusedOffers)
    }

    @Test
    fun repeatedKeyFrameRequestsCoalesceToOnePendingPerKeyAndRotationKeepsTheNewest() {
        val queue = BoundedControlQueue<String>(capacity = 8, name = "relay", onRefused = { error("not refused") })
        repeat(50) { assertTrue(queue.offerKeyed("childA" to "kf", "kfA")) }
        assertTrue(queue.offerKeyed("childB" to "kf", "kfB"))
        assertEquals(2, queue.size)
        queue.offerKeyed("rotation", "r90")
        queue.offerKeyed("rotation", "r180", replace = true)
        assertEquals(listOf("kfA", "kfB", "r180"), generateSequence { queue.poll() }.toList())
        // After the pending request is taken, the next one queues again.
        assertTrue(queue.offerKeyed("childA" to "kf", "kfA2"))
        assertEquals("kfA2", queue.poll())
    }

    @Test
    fun timedPollWaitsForAnOfferFromAnotherThread() {
        val queue = BoundedControlQueue<String>(capacity = 2, name = "relay")
        val producer = Thread {
            Thread.sleep(50)
            queue.offerMust("left")
        }
        producer.start()
        assertEquals("left", queue.poll(2_000, java.util.concurrent.TimeUnit.MILLISECONDS))
        producer.join()
        assertEquals(null, queue.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun switchingTheEntryOffRemovesTheBound() {
        val phone = DeviceTraits("", "", 34)
        val off = DeviceFamilyPolicy.resolve(phone, setOf("bounded-control-queues"))
        val queue = BoundedControlQueue<Int>(MemoryThermalPolicy.controlQueueCapacity(2, off), "call")
        (0 until 100).forEach { assertTrue(queue.offerKeyed(it, it)) }
        assertEquals(100, queue.size)
    }

    @Test
    fun entriesAreSourcedAndExperimentsStayProposals() {
        val entries = MemoryThermalPolicy.entries + NativeCompat.policyEntries
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        entries.forEach { entry ->
            assertTrue(entry.id, entry.sources.isNotEmpty() && entry.evidence.isNotEmpty())
            assertTrue(entry.id, entry.fallback.isNotBlank())
            assertTrue(entry.id, entry.area == PolicyArea.MEMORY_THERMAL || entry.area == PolicyArea.NATIVE)
            assertTrue(entry.id, entry.sources.all { it.reference.startsWith("https://") })
        }
        // Default-off experiments are inert proposals; nothing here claims a local reproduction.
        val experimental = entries.filter { it.validation == PolicyValidation.EXPERIMENTAL }.map { it.id }.toSet()
        assertTrue(experimental.containsAll(setOf("thermal-headroom-forecast", "thermal-native-speed-floor")))
        entries.filter { it.id in experimental }.forEach { assertTrue(it.id, it.proposed) }
        assertTrue(entries.none { it.validation == PolicyValidation.LOCALLY_REPRODUCED })
    }
}
