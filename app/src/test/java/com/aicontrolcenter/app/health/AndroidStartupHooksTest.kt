package com.aicontrolcenter.app.health

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.job.JobScheduler
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.work.UpkeepJobService
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.health.ExitKind
import com.aicontrolcenter.core.health.ExitRecord
import com.aicontrolcenter.core.health.StorageLevel
import com.aicontrolcenter.device.health.AndroidExitHistory
import com.aicontrolcenter.testkit.FakeBackgroundHealth
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.FakeExitHistory
import com.aicontrolcenter.testkit.FakeStorageWatch
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The start-up hooks of the phone (Phase 6, WP2 items 6, 7, 10, 11; rules 10 and 31). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidStartupHooksTest {
    @get:Rule val folder = TemporaryFolder()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock(10 * 60 * 60_000L)
    private val log = InMemoryActivityLog()
    private val storage = FakeStorageWatch(freeBytes = 50L * 1024 * 1024)
    private val health = FakeBackgroundHealth()
    private var scheduled = 0

    private fun hooks(exits: com.aicontrolcenter.core.health.ExitHistory = FakeExitHistory(), version: Long = 9) = AndroidStartupHooks(
        ledger = StartLedger.inFolder(folder.root),
        exits = exits,
        storage = storage,
        health = health,
        crashNotes = CrashNotes.inFolder(folder.root),
        versionCode = version,
        clock = clock,
        scheduleUpkeep = { scheduled++ },
        recorder = ActivityRecorder(log, SequentialIdGenerator(), clock),
        io = Dispatchers.Unconfined,
    )

    private val ledger get() = StartLedger.inFolder(folder.root).read()

    @Test
    fun `a normal start notes started and ok, measures the room, reads the background check and schedules the upkeep job`() = runTest {
        val hooks = hooks()
        hooks.beforeSteps()
        assertEquals(1, ledger.startsWithoutOk)
        assertEquals(9L, ledger.lastStartedVersion)
        assertEquals(StorageLevel.CRITICAL, storage.level.value)
        assertEquals(1, health.refreshes)
        assertNull(hooks.interruptCause())
        assertTrue(hooks.resumeAllowed())
        assertEquals(0, scheduled)
        hooks.afterSteps()
        assertEquals(0, ledger.startsWithoutOk)
        assertEquals(9L, ledger.lastOkVersion)
        assertEquals(1, scheduled)
        assertFalse(hooks.safeStart.state.value.on)
    }

    @Test
    fun `the cause of an interruption is the last exit's, in the app's own words, and each exit is read once`() = runTest {
        val exits = FakeExitHistory(ExitRecord(clock.current - 5_000, ExitKind.LOW_MEMORY, 400, 1, 1))
        val first = hooks(exits)
        first.beforeSteps()
        assertEquals("Android closed the app to free memory", first.interruptCause())
        first.afterSteps()
        val second = hooks(exits)
        second.beforeSteps()
        assertNull(second.interruptCause(), "the same exit is not reported twice")
    }

    @Test
    fun `after two starts in a row that did not finish, nothing resumes by itself until the owner chooses`() = runTest {
        hooks().beforeSteps()
        hooks().beforeSteps()
        val third = hooks()
        third.beforeSteps()
        assertFalse(third.resumeAllowed())
        assertEquals(SafeStartState(true, SafeStartReason.STARTS_WITHOUT_OK), third.safeStart.state.value)
        assertTrue(log.all.any { it.title == "Safe start is on" && it.severity == Severity.WARNING })
        third.afterSteps()
        val fourth = hooks()
        fourth.beforeSteps()
        assertFalse(fourth.resumeAllowed(), "still on after a start that finished")
        fourth.safeStart.leave()
        assertTrue(fourth.resumeAllowed())
        assertFalse(fourth.safeStart.state.value.on)
    }

    @Test
    fun `three crashes of the main process turn safe start on, three of the script process never do`() = runTest {
        val manager = context.getSystemService(ActivityManager::class.java)
        val main = context.applicationInfo.processName ?: context.packageName
        fun exit(process: String, reason: Int, at: Long) = shadowOf(manager).addApplicationExitInfo(
            ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder().setProcessName(process).setReason(reason).setTimestamp(at).build(),
        )
        val now = clock.current
        for (i in 1..3) exit("$main:scripts:com.aicontrolcenter.app.scripts.ScriptProcessService:js0", ApplicationExitInfo.REASON_CRASH, now - i * 60_000)
        val scripts = hooks(AndroidExitHistory(context, io = Dispatchers.Unconfined))
        scripts.beforeSteps()
        assertTrue(scripts.resumeAllowed(), "script crashes are not the app's (rule 31)")
        assertNull(scripts.interruptCause())
        scripts.afterSteps()

        for (i in 1..3) exit(main, ApplicationExitInfo.REASON_CRASH, now + i * 60_000)
        clock.current = now + 5 * 60_000
        val app = hooks(AndroidExitHistory(context, io = Dispatchers.Unconfined))
        app.beforeSteps()
        assertFalse(app.resumeAllowed())
        assertEquals(SafeStartReason.CRASHES, app.safeStart.state.value.reason)
        assertEquals(ExitKind.CRASH.cause, app.interruptCause())
    }

    @Test
    fun `a crash note left by the last run is logged with classes and places only`() = runTest {
        val notes = CrashNotes.inFolder(folder.root)
        hooks().apply { beforeSteps(); afterSteps() }
        clock.current += 60_000
        notes.write(IllegalStateException("secret-marker sk-abcdef"), clock.current)
        clock.current += 60_000
        hooks().beforeSteps()
        val logged = assertNotNull(log.all.singleOrNull { it.title == "The app stopped because of an error" })
        assertTrue("java.lang.IllegalStateException" in logged.detail)
        assertFalse("secret-marker" in logged.detail)
    }

    @Test
    fun `the upkeep job is scheduled once, idle, on a charged battery, daily, and not across a restart of the phone`() {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        UpkeepJobService.schedule(context)
        UpkeepJobService.schedule(context)
        val job = scheduler.allPendingJobs.single()
        assertEquals(UpkeepJobService.JOB_ID, job.id)
        assertTrue(job.isPeriodic)
        assertEquals(UpkeepJobService.PERIOD_MS, job.intervalMillis)
        assertTrue(job.isRequireDeviceIdle)
        assertTrue(job.isRequireBatteryNotLow)
        assertFalse(job.isPersisted)
        assertEquals(UpkeepJobService::class.java.name, job.service.className)
    }
}
