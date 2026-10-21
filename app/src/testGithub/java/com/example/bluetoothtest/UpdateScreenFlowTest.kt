package com.example.bluetoothtest

import android.content.pm.PackageInstaller
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The update path as the user lives it: switch the check on (the daily job is armed), press Check now, be offline, find a
 * newer version, download, be refused or have the installer fail. The screen's decisions are in [UpdateScreenModel] and the
 * job's in [UpdateJobService.arm], so they run here without a phone.
 *
 * Regression: "Check for updates, Yes" crashed the app on Android 14+ (2026-10-21) because the system refused the
 * network-constrained job and nothing caught it, then again at every start while the switch stayed on.
 */
class UpdateScreenFlowTest {
    private lateinit var phone: FakePhone

    @Before
    fun setUp() {
        phone = FakePhone(Files.createTempDirectory("update-flow").toFile())
    }

    @After
    fun tearDown() {
        phone.root.deleteRecursively()
    }

    // ---- the daily job ----

    private class FakeScheduler(var pending: Boolean = false, var refusal: Throwable? = null) : DailyJobPort {
        var scheduled = 0
        var cancelled = 0
        override fun isPending(): Boolean = pending
        override fun cancel() {
            cancelled++
            pending = false
        }
        override fun schedule() {
            refusal?.let { throw it }
            scheduled++
            pending = true
        }
    }

    @Test
    fun aSchedulerThatRefusesTheJobNeverTakesTheAppDownAtAnyStart() {
        // Android 14+: a network-constrained job without ACCESS_NETWORK_STATE throws SecurityException from schedule().
        val scheduler = FakeScheduler(refusal = SecurityException("Requested network constraint requires ACCESS_NETWORK_STATE"))
        val refusals = mutableListOf<Throwable>()
        repeat(3) { start ->
            val result = UpdateJobService.arm(scheduler, enabled = true) { refusals += it }
            assertEquals("start $start", DailyJobResult.REFUSED, result)
        }
        assertEquals("each refusal is reported, not thrown", 3, refusals.size)
        assertTrue(refusals.all { it is SecurityException })
        assertEquals(0, scheduler.scheduled)
    }

    @Test
    fun turningTheCheckOnArmsTheJobOnceAndOffCancelsIt() {
        val scheduler = FakeScheduler()
        assertEquals(DailyJobResult.SCHEDULED, UpdateJobService.arm(scheduler, enabled = true) { throw it })
        assertEquals("a restart does not arm a second job", DailyJobResult.ALREADY_ARMED, UpdateJobService.arm(scheduler, enabled = true) { throw it })
        assertEquals(1, scheduler.scheduled)
        assertEquals(DailyJobResult.CANCELLED, UpdateJobService.arm(scheduler, enabled = false) { throw it })
        assertFalse(scheduler.pending)
        assertEquals("off stays off", DailyJobResult.CANCELLED, UpdateJobService.arm(scheduler, enabled = false) { throw it })
    }

    // ---- the screen ----

    private fun state(
        busy: Boolean = false,
        cancellable: Boolean = false,
        offered: Boolean = true,
        onlineOn: Boolean = true,
        fileReady: Boolean = false,
        newer: Boolean = false,
        checked: Boolean = false,
    ) = UpdateScreenModel.stateOf(busy, cancellable, offered, onlineOn, fileReady, newer, checked)

    @Test
    fun theScreenGuidesTheUserFromSwitchOffToInstall() {
        assertEquals("a copy that cannot update itself says so", UpdateScreenState.NOT_OFFERED, state(offered = false))
        assertEquals("before opting in", UpdateScreenState.ONLINE_OFF, state(onlineOn = false))
        assertEquals("said yes, nothing checked yet", UpdateScreenState.NOT_CHECKED, state())
        assertEquals("checked, nothing newer", UpdateScreenState.UP_TO_DATE, state(checked = true))
        assertEquals("a newer release is known", UpdateScreenState.NEWER, state(checked = true, newer = true))
        assertEquals("downloaded and verified", UpdateScreenState.READY, state(checked = true, newer = true, fileReady = true))
        assertEquals("working", UpdateScreenState.BUSY, state(busy = true))
        assertEquals("a download can be cancelled", UpdateScreenState.CANCELLABLE_BUSY, state(busy = true, cancellable = true))
    }

    @Test
    fun noStateOfferADownloadOrInstallWithoutTheUsersYes() {
        // Nothing here can contact GitHub or install while the switch is off, whatever else is true.
        listOf(true, false).forEach { newer ->
            listOf(true, false).forEach { ready ->
                val shown = state(onlineOn = false, newer = newer, fileReady = ready, checked = true)
                assertEquals(UpdateScreenState.ONLINE_OFF, shown)
            }
        }
    }

    @Test
    fun everyWayADownloadCanFailHasItsOwnSentence() {
        val kinds = PrepareResult.Kind.entries.filter { it != PrepareResult.Kind.REFUSED }
        val texts = kinds.map { UpdateScreenModel.failureTextRes(PrepareResult.Failed(it, "log")) }
        assertTrue(texts.all { it != 0 })
        assertEquals("offline, server, rate limit, no file, no hash, hash mismatch, storage, cancelled, unreadable and checks-off differ", kinds.size, texts.toSet().size)
    }

    @Test
    fun anInstallRefusedByTheRulesSaysWhichRuleAndEveryRuleHasASentence() {
        val refusals = UpdateRefusal.entries.map { UpdateScreenModel.failureTextRes(PrepareResult.Failed(PrepareResult.Kind.REFUSED, "log", it)) }
        assertTrue(refusals.all { it != 0 })
        val notNewer = UpdateScreenModel.failureTextRes(PrepareResult.Failed(PrepareResult.Kind.REFUSED, "log", UpdateRefusal.NOT_NEWER))
        val wrongSigner = UpdateScreenModel.failureTextRes(PrepareResult.Failed(PrepareResult.Kind.REFUSED, "log", UpdateRefusal.SIGNER_NOT_PINNED))
        assertNotEquals("a file that is not newer and a file signed by someone else are told apart", notNewer, wrongSigner)
        // A refusal with no named rule still says something.
        assertTrue(UpdateScreenModel.failureTextRes(PrepareResult.Failed(PrepareResult.Kind.REFUSED, "log", null)) != 0)
    }

    @Test
    fun theInstallersAnswerIsExplainedUnlessTheUserSaidNo() {
        assertNull("no answer on record", UpdateScreenModel.installFailureDetail(0, ""))
        assertNull("the user declined Android's confirmation: nothing to explain", UpdateScreenModel.installFailureDetail(PackageInstaller.STATUS_FAILURE_ABORTED, "User cancelled"))
        assertEquals("INSTALL_FAILED_VERSION_DOWNGRADE", UpdateScreenModel.installFailureDetail(PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_VERSION_DOWNGRADE"))
        assertEquals("no message: the code", PackageInstaller.STATUS_FAILURE_STORAGE.toString(), UpdateScreenModel.installFailureDetail(PackageInstaller.STATUS_FAILURE_STORAGE, ""))
    }

    // ---- check now: offline, then a newer version ----

    private val fixture: String by lazy {
        listOf("src/test/resources/github-latest-release-v0.1.0-beta.json", "app/src/test/resources/github-latest-release-v0.1.0-beta.json")
            .map { File(it) }.first { it.isFile }.readText()
    }

    @Test
    fun checkNowOfflineThenNewerFoundThenTheScreenOffersTheDownload() {
        val checks = UpdateCheckStore(PrefsUpdateStore(phone.preferences(UpdateSettings.PREFERENCES_FILE)))
        var now = 10 * UpdateSchedule.DAY_MS

        // Check now with no network: the attempt is recorded, nothing is learned, the screen still says "not checked".
        checks.save(UpdateSchedule.afterNetworkFailure(checks.load(), now))
        assertNull(checks.load().latestTag)
        assertEquals(UpdateScreenState.NOT_CHECKED, state(checked = checks.load().latestTag != null))
        assertTrue("an hour later the automatic check may retry", UpdateSchedule.automaticDue(true, checks.load(), now + UpdateSchedule.NETWORK_RETRY_MS))
        assertFalse("pressing Check now again at once waits", UpdateSchedule.manualAllowed(checks.load(), now + 1_000))

        // Later, with a network, GitHub names a newer release.
        now += UpdateSchedule.MANUAL_MIN_GAP_MS + 1
        val newerBody = fixture.replace("v0.1.0-beta", "v0.2.0").replace("MeshLive-0.1.0-beta-", "MeshLive-0.2.0-")
        val outcome = UpdateFlow.evaluate(newerBody, "0.1.0-beta", 12, listOf("arm64-v8a"))
        assertTrue(outcome is CheckOutcome.Available)
        checks.save(UpdateSchedule.afterAnswer(checks.load(), now, (outcome as CheckOutcome.Available).release.tag))

        val known = checks.load().latestTag?.let(ReleaseVersion::parse)
        val installed = ReleaseVersion.parse("0.1.0-beta")
        val newer = known != null && installed != null && known > installed
        assertEquals(UpdateScreenState.NEWER, state(checked = true, newer = newer))
    }

    @Test
    fun anAnswerWithNothingNewerLeavesTheScreenSayingUpToDate() {
        val checks = UpdateCheckStore(PrefsUpdateStore(phone.preferences(UpdateSettings.PREFERENCES_FILE)))
        val outcome = UpdateFlow.evaluate(fixture, "0.1.0-beta", 12, listOf("arm64-v8a"))
        assertTrue(outcome is CheckOutcome.UpToDate)
        checks.save(UpdateSchedule.afterAnswer(checks.load(), 1_000L, (outcome as CheckOutcome.UpToDate).tag))
        val known = checks.load().latestTag?.let(ReleaseVersion::parse)
        val installed = ReleaseVersion.parse("0.1.0-beta")
        assertEquals(UpdateScreenState.UP_TO_DATE, state(checked = true, newer = known != null && installed != null && known > installed))
    }
}
