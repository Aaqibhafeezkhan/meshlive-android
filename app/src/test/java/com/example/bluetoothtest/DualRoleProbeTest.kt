package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch P (BACKLOG P-03): the dual-role probe never credits a pass without both links carrying data in one window. */
class DualRoleProbeTest {
    private val time = FakeTime(10_000)
    private val lines = mutableListOf<String>()
    private val results = mutableListOf<Pair<DualRoleResult, Int>>()
    private val probe = DualRoleProbe(
        nowMs = { time.now },
        log = { lines += it },
        onResult = { r, n -> results += r to n },
    )

    private fun childRuns(child: String, seconds: Int, bytesPerSecond: Int = 4_096, heard: Boolean = true, parentActive: Boolean = true) {
        repeat(seconds) {
            probe.onChildData(child, bytesPerSecond, heard)
            if (parentActive) probe.onParentActivity()
            time.advance(1_000)
        }
    }

    @Test
    fun nothingHappenedIsUnproven() {
        assertEquals(DualRoleResult.UNPROVEN, probe.current)
        assertEquals("dual_role=unproven(children=0)", probe.summary())
        assertTrue(lines.isEmpty())
    }

    @Test
    fun aChildThatCarriedDataWhileTheParentWasActiveIsAPass() {
        probe.onParentUp()
        probe.onChildConnectStarted("child1")
        probe.onChildConnected("child1")
        childRuns("child1", seconds = 4)
        assertEquals(DualRoleResult.PASS, probe.current)
        assertEquals(1, probe.maxConcurrentChildren)
        assertEquals(DualRoleResult.PASS to 1, results.single())
        assertTrue(lines.single().startsWith("BENCHMARK dual_role: result=pass"))
        assertEquals("dual_role=pass(children=1)", probe.summary())
    }

    @Test
    fun childDataWithoutAnyParentActivityIsNeverAPass() {
        probe.onParentUp()
        childRuns("child1", seconds = 6, parentActive = false)
        assertEquals(DualRoleResult.UNPROVEN, probe.current)
    }

    @Test
    fun childDataThatAnswersNothingIsNeverAPass() {
        probe.onParentUp()
        childRuns("child1", seconds = 6, heard = false)
        assertEquals(DualRoleResult.UNPROVEN, probe.current)
    }

    @Test
    fun aShortBurstIsNotAConcurrentWindow() {
        probe.onParentUp()
        probe.onChildData("child1", 16_384, true)
        probe.onParentActivity()
        time.advance(500)
        probe.onChildData("child1", 16_384, true)
        assertEquals(DualRoleResult.UNPROVEN, probe.current)
    }

    @Test
    fun childDataBeforeTheParentWasUpDoesNotCount() {
        childRuns("child1", seconds = 4)
        probe.onParentUp()
        probe.onChildData("child1", 100, true)
        assertEquals(DualRoleResult.UNPROVEN, probe.current)
    }

    @Test
    fun aParentLostDuringTheWindowDoesNotCount() {
        probe.onParentUp()
        childRuns("child1", seconds = 1)
        probe.onParentLost()
        childRuns("child1", seconds = 4)
        assertEquals(DualRoleResult.UNPROVEN, probe.current)
    }

    @Test
    fun aChildConnectFailureWhileTheParentIsUpIsAFailOnlyIfNoChildEverCarriedData() {
        probe.onParentUp()
        probe.onChildConnectStarted("child1")
        probe.onChildFailed("child1", "status 62")
        assertEquals(DualRoleResult.FAIL, probe.current)
        assertTrue(lines.single().contains("failedWhileParentUp=status 62"))
        assertTrue(probe.summary().contains("failed=status 62"))
    }

    @Test
    fun aFailureWithoutAParentIsNotDualRoleEvidence() {
        probe.onChildFailed("child1", "status 62")
        assertEquals(DualRoleResult.UNPROVEN, probe.current)
    }

    @Test
    fun aPassIsStickyAndALaterFailureDoesNotTurnItIntoAFail() {
        probe.onParentUp()
        childRuns("child1", seconds = 4)
        assertEquals(DualRoleResult.PASS, probe.current)
        probe.onChildFailed("child2", "status 133")
        assertEquals(DualRoleResult.PASS, probe.current)
        assertEquals(1, results.size)
    }

    @Test
    fun twoChildrenActiveTogetherAreCounted() {
        probe.onParentUp()
        repeat(4) {
            probe.onChildData("a", 4_096, true)
            probe.onChildData("b", 4_096, true)
            probe.onParentActivity()
            time.advance(1_000)
        }
        assertEquals(2, probe.maxConcurrentChildren)
        assertEquals(DualRoleResult.PASS to 2, results.single())
    }

    // ---- learned child-link limit ----

    private var epoch = "e1"
    private val limit = ChildLinkLimit(MemoryRollbackStore(), { epoch }, ttlMs = 10_000)

    @Test
    fun nothingIsLearnedFromSuccessesAlone() {
        limit.recordSuccess(2, 100)
        assertNull(limit.learnedMax(100))
        assertEquals(2, limit.provenOk(100))
    }

    @Test
    fun aFailureWithTheParentUpLearnsOneBelowTheFailedCount() {
        limit.recordSuccess(1, 100)
        limit.recordFailure(openChildren = 1, nowMs = 200) // the second child failed
        assertEquals(1, limit.learnedMax(300))
        limit.recordFailure(openChildren = 2, nowMs = 300) // a later, higher failure does not raise the mark
        assertEquals(1, limit.learnedMax(400))
    }

    @Test
    fun aFirstChildThatFailedIsNotAChildLimit() {
        limit.recordFailure(openChildren = 0, nowMs = 100)
        assertNull("a limit of zero is not a limit", limit.learnedMax(200))
    }

    @Test
    fun aFailureBelowWhatAlreadyWorkedIsIgnored() {
        limit.recordSuccess(2, 100)
        limit.recordFailure(openChildren = 1, nowMs = 200) // a second child failed although two had worked
        assertNull(limit.learnedMax(300))
    }

    @Test
    fun aLaterSuccessAtOrAboveTheFailedCountContradictsItAndClearsTheMark() {
        limit.recordFailure(openChildren = 2, nowMs = 100)
        assertEquals(2, limit.learnedMax(150))
        limit.recordSuccess(3, 200)
        assertNull(limit.learnedMax(250))
    }

    @Test
    fun aMarkExpiresWithTheEpochAndTheTtl() {
        limit.recordFailure(openChildren = 2, nowMs = 100)
        assertEquals(2, limit.learnedMax(9_000))
        assertNull(limit.learnedMax(10_100))
        limit.recordFailure(openChildren = 2, nowMs = 20_000)
        epoch = "e2"
        assertNull(limit.learnedMax(20_100))
    }
}
