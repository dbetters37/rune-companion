package com.aicontrolcenter.framework.routines

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.backup.LocalMaintenanceGate
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.routines.RoutineDraft
import com.aicontrolcenter.core.routines.RoutineLimits
import com.aicontrolcenter.core.routines.RoutineRun
import com.aicontrolcenter.core.routines.RoutineRunOutcome
import com.aicontrolcenter.core.routines.RoutineSchedule
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.Orchestrator
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.usage.CallOutcome
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.UsageSource
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.InMemoryModelPrices
import com.aicontrolcenter.testkit.InMemoryRoutineRepository
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import com.aicontrolcenter.testkit.InMemoryTaskRepository
import com.aicontrolcenter.testkit.InMemoryUsageStore
import com.aicontrolcenter.testkit.SequentialIdGenerator
import com.aicontrolcenter.testkit.contract.UsageSamples
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records the objectives a routine starts, as the orchestrator would store them, and can refuse like it. */
class FakeRoutineOrchestrator(
    private val tasks: InMemoryTaskRepository,
    private val clock: FakeClock,
    /** The id of the n-th objective started. */
    private val idOf: (Int) -> TaskId = { TaskId("tsk_$it") },
) : Orchestrator {
    val started = CopyOnWriteArrayList<Pair<String, ObjectiveOptions>>()

    /** Set to refuse the next starts as the orchestrator does when the Director cannot run. */
    @Volatile
    var refusal: Exception? = null

    override val running: StateFlow<Set<TaskId>> = MutableStateFlow(emptySet())
    override val assignments: StateFlow<Map<AgentId, TaskId>> = MutableStateFlow(emptyMap())

    override suspend fun startObjective(text: String, options: ObjectiveOptions): TaskRecord {
        refusal?.let { throw it }
        started += text to options
        val id = idOf(started.size)
        val now = clock.now()
        val record = TaskRecord(
            id = id, rootId = id, kind = TaskKind.OBJECTIVE, title = text.take(40), instructions = text, requestedBy = TaskRequester.Owner,
            status = TaskStatus.RUNNING, options = options, createdAt = now, updatedAt = now, startedAt = now,
        )
        tasks.upsert(record)
        return record
    }

    /** Ends objective [id] with [status], as its work would. */
    suspend fun end(id: TaskId, status: TaskStatus) {
        tasks.update(id) { it.copy(status = status, finishedAt = clock.now()) }
    }

    override suspend fun pause(rootId: TaskId) = Unit
    override suspend fun resume(rootId: TaskId) = Unit
    override suspend fun cancel(rootId: TaskId) = Unit
    override suspend fun cancelTask(taskId: TaskId) = Unit
    override suspend fun retryTask(taskId: TaskId) = Unit
    override suspend fun delete(rootId: TaskId) = Unit
    override suspend fun recoverInterrupted(): List<TaskId> = emptyList()
}

class RoutineRunnerTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(berlin).toInstant().toEpochMilli()

    private class Notices : RoutineNotices {
        val waiting = CopyOnWriteArrayList<String>()
        var cleared = 0

        override fun waitingForTap(routineTitle: String) {
            waiting += routineTitle
        }

        override fun noneWaiting() {
            cleared++
        }
    }

    /** A runner over in-memory stores, with the routines master switch on unless [settings] says otherwise. */
    private inner class World(
        settings: FrameworkSettings = FrameworkSettings(routinesEnabled = true, directorAgentId = AgentId("agt_director")),
        start: String = "2026-11-25T06:00",
    ) {
        val clock = FakeClock(at(start))
        val store = InMemoryRoutineRepository()
        val tasks = InMemoryTaskRepository()
        val settings = InMemorySettingsRepository(settings)
        val orchestrator = FakeRoutineOrchestrator(tasks, clock)
        val log = InMemoryActivityLog()
        val gate = LocalMaintenanceGate()
        val notices = Notices()
        var limit: String? = null
        var limitFails = false
        var localOnlyBlocks = false
        val ids = SequentialIdGenerator()

        /** The phone's zone: the owner may travel. */
        var zone: ZoneId = berlin
        val routineClock = RoutineClock(clock) { zone }
        val owner = DefaultOwnerRoutines(store, ActivityRecorder(log, ids, clock), clock, ids) { zone }
        val runner = RoutineRunner(
            routines = store, tasks = tasks, orchestrator = orchestrator, settings = this.settings,
            dailyLimits = { if (limitFails) error("unreadable") else limit },
            localOnly = { localOnlyBlocks },
            gate = gate, activity = ActivityRecorder(log, ids, clock), clock = routineClock, ids = ids, notices = notices,
        )

        suspend fun routine(
            title: String = "Morning news",
            schedule: RoutineSchedule = RoutineSchedule.Daily(7 * 60),
            screens: Boolean = false,
            timeout: Int? = 90,
        ): Routine {
            val draft = RoutineDraft(
                title = title, objectiveText = "Summarise the news in five lines.", options = ObjectiveOptions(reviewResults = false, maxTasks = 3),
                schedule = schedule, approvalTimeoutMinutes = timeout, allowScreenActions = screens,
            )
            return assertIs<OwnerResult.Done<Routine>>(owner.create(draft)).value
        }

        suspend fun runs(routine: Routine): List<RoutineRun> = store.runs(routine.id, 50)

        suspend fun get(routine: Routine): Routine = store.get(routine.id)!!

        /** Moves the clock to [text] and lets the alarm's service run the routines due. */
        suspend fun alarmAt(text: String): List<RoutineRun> {
            clock.current = at(text)
            return runner.runDue()
        }
    }

    @Test
    fun `a routine due now starts its objective with exactly the routine's options, and the run is recorded`() = runTest {
        val w = World()
        val routine = w.routine()
        assertEquals(at("2026-11-25T07:00"), routine.nextRunAt)
        assertEquals(emptyList(), w.alarmAt("2026-11-25T06:59"), "not due yet")
        val run = w.alarmAt("2026-11-25T07:00").single()
        assertEquals(RoutineRunOutcome.STARTED, run.outcome)
        assertEquals(at("2026-11-25T07:00"), run.dueAt)
        val (text, options) = w.orchestrator.started.single()
        assertEquals(routine.objectiveText, text)
        assertEquals(routine.objectiveOptions(), options, "Routine.objectiveOptions(), never options built anew")
        assertEquals(routine.id, options.routineId)
        assertFalse(options.screenActionsAllowed, "screens are off unless the owner ticked them")
        assertEquals(90, options.approvalTimeoutMinutes)
        assertEquals(3, options.maxTasks)
        assertEquals(TaskId("tsk_1"), run.objectiveId)
        assertEquals(listOf(run), w.runs(routine))
        assertEquals(at("2026-11-26T07:00"), w.get(routine).nextRunAt, "due next tomorrow")
        assertEquals(emptyList(), w.runner.runDue(), "the same due time never runs twice")
        assertTrue(w.log.observe(limit = 50).first().any { it.title == "Routine started: Morning news" && it.rootTaskId == TaskId("tsk_1") })
        // A routine the owner ticked lets its objective use screens.
        val ticked = w.routine(title = "Check the shop app", screens = true)
        w.alarmAt("2026-11-26T07:00")
        assertTrue(w.orchestrator.started.last().second.screenActionsAllowed)
        assertEquals(ticked.id, w.orchestrator.started.last().second.routineId)
    }

    @Test
    fun `with the master switch off nothing starts and nothing is recorded, and switching it on starts nothing that was due before`() = runTest {
        val w = World(settings = FrameworkSettings(routinesEnabled = false, directorAgentId = AgentId("agt_director")))
        val routine = w.routine()
        assertEquals(emptyList(), w.alarmAt("2026-11-25T07:00"))
        assertEquals(emptyList(), w.runner.catchUp().let { w.runs(routine) })
        assertTrue(w.orchestrator.started.isEmpty())
        assertEquals(at("2026-11-26T07:00"), w.get(routine).nextRunAt, "the due time passed without a run")
        // Off for days while nothing ran the routines: switching on moves the stale due time on, it does not run it.
        w.clock.current = at("2026-11-28T09:00")
        w.settings.update { it.copy(routinesEnabled = true) }
        w.runner.masterSwitchedOn()
        assertEquals(at("2026-11-29T07:00"), w.get(routine).nextRunAt)
        assertEquals(emptyList(), w.runner.runDue())
        assertTrue(w.orchestrator.started.isEmpty())
        assertEquals(emptyList(), w.runs(routine))
    }

    @Test
    fun `never two runs at once, so an unfinished objective of the routine skips the run`() = runTest {
        val w = World()
        val routine = w.routine(schedule = RoutineSchedule.EveryHours(1))
        w.alarmAt("2026-11-25T07:00")
        // Still running an hour later, then paused (by the owner or today's limit): skipped both times.
        val second = w.alarmAt("2026-11-25T08:00").single()
        assertEquals(RoutineRunOutcome.SKIPPED_STILL_RUNNING, second.outcome)
        assertEquals(RoutineRunner.STILL_RUNNING_NOTE, second.note)
        w.orchestrator.end(TaskId("tsk_1"), TaskStatus.PAUSED)
        assertEquals(RoutineRunOutcome.SKIPPED_STILL_RUNNING, w.alarmAt("2026-11-25T09:00").single().outcome)
        w.orchestrator.end(TaskId("tsk_1"), TaskStatus.COMPLETED)
        assertEquals(RoutineRunOutcome.STARTED, w.alarmAt("2026-11-25T10:00").single().outcome)
        assertEquals(2, w.orchestrator.started.size)
        // Also an objective whose run was never recorded (the app stopped right after starting it) is found by its routine id.
        w.orchestrator.end(TaskId("tsk_2"), TaskStatus.COMPLETED)
        w.tasks.upsert(
            TaskRecord(
                id = TaskId("tsk_x"), rootId = TaskId("tsk_x"), kind = TaskKind.OBJECTIVE, title = "x", instructions = "x", requestedBy = TaskRequester.Owner,
                status = TaskStatus.INTERRUPTED, options = routine.objectiveOptions(), createdAt = 0, updatedAt = 0,
            ),
        )
        assertEquals(RoutineRunOutcome.SKIPPED_STILL_RUNNING, w.alarmAt("2026-11-25T11:00").single().outcome)
        assertEquals(2, w.orchestrator.started.size)
    }

    @Test
    fun `today's limits, local-only mode and a restore are checked at run time, and an unreadable use starts nothing`() = runTest {
        val w = World()
        val routine = w.routine(schedule = RoutineSchedule.EveryHours(1))
        w.limit = "Today's limit of 200 model answers is reached."
        val limited = w.alarmAt("2026-11-25T07:00").single()
        assertEquals(RoutineRunOutcome.SKIPPED_DAILY_LIMIT, limited.outcome)
        assertEquals(w.limit, limited.note)
        w.limit = null
        w.limitFails = true
        assertEquals(RoutineRunner.USE_UNREADABLE_NOTE, w.alarmAt("2026-11-25T08:00").single().note)
        w.limitFails = false
        w.localOnlyBlocks = true
        val local = w.alarmAt("2026-11-25T09:00").single()
        assertEquals(RoutineRunOutcome.SKIPPED_LOCAL_ONLY, local.outcome)
        w.localOnlyBlocks = false
        w.gate.runExclusive {
            val restoring = w.alarmAt("2026-11-25T10:00").single()
            assertEquals(RoutineRunOutcome.MISSED, restoring.outcome)
            assertEquals(RoutineRunner.RESTORING_NOTE, restoring.note)
        }
        assertTrue(w.orchestrator.started.isEmpty())
        assertEquals(0, w.get(routine).consecutiveFailures, "skipped runs are not failures")
        assertTrue(w.get(routine).enabled)
        assertEquals(RoutineRunOutcome.STARTED, w.alarmAt("2026-11-25T11:00").single().outcome)
    }

    @Test
    fun `three failed runs in a row pause the routine, and switching it on gives it three fresh tries`() = runTest {
        val w = World()
        val routine = w.routine(schedule = RoutineSchedule.EveryHours(1))
        w.orchestrator.refusal = IllegalStateException("No Director is designated. Designate a Director agent before giving the team an objective.")
        val first = w.alarmAt("2026-11-25T07:00").single()
        assertEquals(RoutineRunOutcome.SKIPPED_DIRECTOR_UNAVAILABLE, first.outcome)
        assertEquals("No Director is designated. Designate a Director agent before giving the team an objective.", first.note)
        assertEquals(1, w.get(routine).consecutiveFailures)
        w.alarmAt("2026-11-25T08:00")
        assertTrue(w.get(routine).enabled)
        w.alarmAt("2026-11-25T09:00")
        val paused = w.get(routine)
        assertFalse(paused.enabled)
        assertNull(paused.nextRunAt)
        assertEquals(3, paused.consecutiveFailures)
        assertEquals(RoutineLimits.PAUSED_AFTER_FAILURES, paused.pausedNote)
        assertEquals(emptyList(), w.alarmAt("2026-11-25T10:00"), "a paused routine does not run")
        assertTrue(w.log.observe(limit = 50).first().any { it.title == "Routine paused: Morning news" })
        // The owner fixes the Director and switches it on: the count starts again.
        w.orchestrator.refusal = null
        assertIs<OwnerResult.Done<Routine>>(w.owner.setEnabled(routine.id, true))
        assertEquals(RoutineRunOutcome.STARTED, w.alarmAt("2026-11-25T11:00").single().outcome)
        assertEquals(0, w.get(routine).consecutiveFailures)
    }

    @Test
    fun `objectives that failed or timed out count as failures, a finished one ends the streak, paused and cancelled ones count neither way`() = runTest {
        val w = World()
        val routine = w.routine(schedule = RoutineSchedule.EveryHours(1))
        w.alarmAt("2026-11-25T07:00")
        w.orchestrator.end(TaskId("tsk_1"), TaskStatus.FAILED)
        w.alarmAt("2026-11-25T08:00")
        w.orchestrator.end(TaskId("tsk_2"), TaskStatus.TIMED_OUT)
        w.alarmAt("2026-11-25T09:00")
        assertEquals(2, w.get(routine).consecutiveFailures)
        w.orchestrator.end(TaskId("tsk_3"), TaskStatus.COMPLETED)
        w.alarmAt("2026-11-25T10:00")
        assertEquals(0, w.get(routine).consecutiveFailures, "a finished objective ends the streak")
        w.orchestrator.end(TaskId("tsk_4"), TaskStatus.CANCELLED)
        w.alarmAt("2026-11-25T11:00")
        w.orchestrator.end(TaskId("tsk_5"), TaskStatus.FAILED)
        w.alarmAt("2026-11-25T12:00")
        w.orchestrator.end(TaskId("tsk_6"), TaskStatus.FAILED)
        w.alarmAt("2026-11-25T13:00")
        assertEquals(2, w.get(routine).consecutiveFailures, "the cancelled one counts neither way")
        w.orchestrator.end(TaskId("tsk_7"), TaskStatus.FAILED)
        // The third failure is noticed at the next due time: the routine pauses instead of starting.
        assertEquals(emptyList(), w.alarmAt("2026-11-25T14:00"))
        assertFalse(w.get(routine).enabled)
        assertEquals(RoutineLimits.PAUSED_AFTER_FAILURES, w.get(routine).pausedNote)
        assertEquals(7, w.orchestrator.started.size)
    }

    private fun local(ms: Long, zone: ZoneId) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()

    @Test
    fun `after the owner flies to New York, the alarm set for 7 00 in Berlin starts nothing, and the routine runs at 7 00 in New York (AND-W2B-5)`() = runTest {
        val newYork = ZoneId.of("America/New_York")
        val w = World(start = "2026-11-24T20:00")
        val routine = w.routine()
        val stored = w.get(routine).nextRunAt!!
        assertEquals(LocalDateTime.parse("2026-11-25T07:00"), local(stored, berlin))
        // The owner lands in New York; the alarm (an instant) goes off at 7:00 Berlin, 1:00 in the night in New York.
        w.zone = newYork
        w.clock.current = stored
        assertEquals(emptyList(), w.runner.runDue(), "nothing starts at 1:00 in the night")
        assertTrue(w.orchestrator.started.isEmpty())
        val next = w.get(routine).nextRunAt!!
        assertEquals(LocalDateTime.parse("2026-11-25T07:00"), local(next, newYork))
        // At 7:00 in New York it runs.
        w.clock.current = next
        val run = w.runner.runDue().single()
        assertEquals(RoutineRunOutcome.STARTED, run.outcome)
        assertEquals(7, local(run.dueAt, newYork).hour)
        assertEquals(LocalDateTime.parse("2026-11-26T07:00"), local(w.get(routine).nextRunAt!!, newYork))
    }

    @Test
    fun `flying east, the alarm set for 7 00 in New York starts nothing at 13 00 in Berlin, and the next 7 00 in Berlin is due (AND-W2B-5)`() = runTest {
        val newYork = ZoneId.of("America/New_York")
        val w = World(start = "2026-11-24T20:00")
        w.zone = newYork
        val routine = w.routine()
        val stored = w.get(routine).nextRunAt!!
        assertEquals(7, local(stored, newYork).hour)
        w.zone = berlin
        w.clock.current = stored
        assertEquals(emptyList(), w.runner.runDue())
        assertTrue(w.orchestrator.started.isEmpty())
        assertEquals(LocalDateTime.parse("2026-11-26T07:00"), local(w.get(routine).nextRunAt!!, berlin))
    }

    @Test
    fun `a due time made in another zone that passed while the phone was off counts from the routine's local times (AND-W2B-5)`() = runTest {
        val newYork = ZoneId.of("America/New_York")
        val w = World(start = "2026-11-24T20:00")
        val routine = w.routine()
        // The phone was off from Berlin to New York; it opens at 9:00 New York, two hours after 7:00 there.
        w.zone = newYork
        w.clock.current = LocalDateTime.parse("2026-11-25T09:00").atZone(newYork).toInstant().toEpochMilli()
        val run = w.runner.runDue().single()
        assertEquals(RoutineRunOutcome.WAITING_FOR_TAP, run.outcome, "late: it waits for the owner's tap")
        assertEquals(LocalDateTime.parse("2026-11-25T07:00"), local(run.dueAt, newYork))
        assertTrue(w.orchestrator.started.isEmpty())
    }

    @Test
    fun `a run the alarm brings late waits for a tap, one too late is missed, and several missed are one record`() = runTest {
        val w = World()
        val routine = w.routine()
        // Android held the alarm back 45 minutes: nothing starts by itself; the owner is told.
        val late = w.alarmAt("2026-11-25T07:45").single()
        assertEquals(RoutineRunOutcome.WAITING_FOR_TAP, late.outcome)
        assertEquals(RoutineRunner.LATE_NOTE, late.note)
        assertEquals(listOf("Morning news"), w.notices.waiting)
        assertTrue(w.orchestrator.started.isEmpty())
        // The phone was off for three days: the three due times before the latest are one missed run; the latest was too long ago.
        w.runner.dismissWaiting(routine.id, late.id)
        val missed = w.alarmAt("2026-11-29T12:00")
        assertEquals(listOf(RoutineRunOutcome.MISSED), missed.map { it.outcome })
        assertEquals(RoutineRunner.missedNote(4), missed.single().note)
        assertEquals(at("2026-11-26T07:00"), missed.single().dueAt)
        assertEquals(at("2026-11-30T07:00"), w.get(routine).nextRunAt)
        // Two days off, back an hour after the latest due time: the earlier one is missed, the latest waits for a tap.
        val back = w.alarmAt("2026-12-01T08:00")
        assertEquals(listOf(RoutineRunOutcome.MISSED, RoutineRunOutcome.WAITING_FOR_TAP), back.map { it.outcome })
        assertEquals(RoutineRunner.missedNote(1), back.first().note)
        assertTrue(w.orchestrator.started.isEmpty())
    }

    @Test
    fun `when the owner opens the app, a due time that passed waits for a tap, and nothing starts without it`() = runTest {
        val w = World()
        val routine = w.routine()
        w.clock.current = at("2026-11-25T07:05")
        w.runner.catchUp()
        val waiting = w.runner.observeWaiting().first().single()
        assertEquals(WaitingRoutine(routine.id, waiting.runId, "Morning news", routine.objectiveText, at("2026-11-25T07:00"), false), waiting)
        assertEquals(RoutineRunner.WAITING_NOTE, w.runs(routine).single().note)
        assertTrue(w.notices.waiting.isEmpty(), "the owner is in the app: no notification")
        // The alarm comes after all, and time passes: still nothing starts without the tap.
        w.alarmAt("2026-11-25T07:06")
        w.runner.catchUp()
        assertTrue(w.orchestrator.started.isEmpty())
        // The tap starts exactly that run.
        w.clock.current = at("2026-11-25T07:30")
        val started = assertIs<OwnerResult.Done<RoutineRun>>(w.runner.startWaiting(routine.id, waiting.runId)).value
        assertEquals(waiting.runId, started.id)
        assertEquals(RoutineRunOutcome.STARTED, started.outcome)
        assertEquals(at("2026-11-25T07:30"), started.startedAt)
        assertEquals(RoutineRunner.TAPPED_NOTE, started.note)
        assertEquals(routine.objectiveOptions(), w.orchestrator.started.single().second)
        assertEquals(listOf(started), w.runs(routine))
        assertEquals(emptyList(), w.runner.observeWaiting().first())
        // A second tap starts nothing.
        assertEquals(OwnerResult.Refused(RoutineRunner.NOT_WAITING), w.runner.startWaiting(routine.id, waiting.runId))
        assertEquals(1, w.orchestrator.started.size)
    }

    @Test
    fun `a waiting run is checked again at the tap, expires, and can be put aside`() = runTest {
        val w = World()
        val routine = w.routine(schedule = RoutineSchedule.EveryHours(1))
        w.clock.current = at("2026-11-25T07:10")
        w.runner.catchUp()
        val first = w.runner.observeWaiting().first().single()
        // Today's limit was reached meanwhile: the tap is refused with the limit's sentence, and the run says so.
        w.limit = "Today's limit of 200 model answers is reached."
        assertEquals(OwnerResult.Refused(w.limit!!), w.runner.startWaiting(routine.id, first.runId))
        assertEquals(RoutineRunOutcome.SKIPPED_DAILY_LIMIT, w.runs(routine).single().outcome)
        w.limit = null
        // The next due time comes and goes without a tap: the waiting run is missed.
        w.clock.current = at("2026-11-25T08:05")
        w.runner.catchUp()
        val second = w.runner.observeWaiting().first().single()
        w.clock.current = at("2026-11-25T09:05")
        w.runner.catchUp()
        assertEquals(RoutineRunner.EXPIRED_NOTE, w.runs(routine).single { it.id == second.runId }.note)
        assertEquals(RoutineRunOutcome.MISSED, w.runs(routine).single { it.id == second.runId }.outcome)
        val third = w.runner.observeWaiting().first().single()
        assertTrue(w.runner.dismissWaiting(routine.id, third.runId))
        assertFalse(w.runner.dismissWaiting(routine.id, third.runId))
        assertEquals(RoutineRunner.DISMISSED_NOTE, w.runs(routine).single { it.id == third.runId }.note)
        // A run whose routine was switched off, or with routines switched off, is not started by a tap.
        w.clock.current = at("2026-11-25T10:05")
        w.runner.catchUp()
        val fourth = w.runner.observeWaiting().first().single()
        w.settings.update { it.copy(routinesEnabled = false) }
        assertEquals(OwnerResult.Refused(RoutineRunner.MASTER_OFF), w.runner.startWaiting(routine.id, fourth.runId))
        assertTrue(w.orchestrator.started.isEmpty())
    }

    @Test
    fun `at most ten routines are looked at, and two passes at once run a due time once`() = runTest {
        val w = World()
        repeat(RoutineLimits.MAX_ROUTINES) { n -> w.routine(title = "Routine $n") }
        // One more stored behind the owner's back (no port allows it) is never run.
        w.store.upsert(
            Routine(
                id = RoutineId("rtn_extra"), title = "Extra", objectiveText = "x", schedule = RoutineSchedule.Daily(7 * 60), createdAt = Long.MAX_VALUE,
                updatedAt = 0, nextRunAt = at("2026-11-25T07:00"),
            ),
        )
        val results = withContext(Dispatchers.Default) {
            w.clock.current = at("2026-11-25T07:00")
            (1..5).map { async { w.runner.runDue() } }.awaitAll()
        }
        assertEquals(RoutineLimits.MAX_ROUTINES, results.sumOf { it.size })
        assertEquals(RoutineLimits.MAX_ROUTINES, w.orchestrator.started.size)
        assertTrue(w.orchestrator.started.none { it.second.routineId == RoutineId("rtn_extra") })
    }

    @Test
    fun `the daily limit check counts today's use as the gateway does`() = runTest {
        val clock = FakeClock(at("2026-11-25T12:00"))
        val usage = InMemoryUsageStore(clock) { berlin }
        val prices = InMemoryModelPrices(clock)
        val check = UsageDailyLimitCheck(usage, prices, clock) { berlin }
        val settings = FrameworkSettings(maxModelCallsPerDay = 2)
        assertNull(check.reached(settings))
        usage.record(UsageSamples.call(at = at("2026-11-25T08:00")))
        // Yesterday's calls and calls to a model on this phone do not count.
        usage.record(UsageSamples.call(at = at("2026-11-24T08:00")))
        usage.record(UsageSamples.call(at = at("2026-11-25T08:30"), model = "mdl_phone", locality = com.aicontrolcenter.core.registry.Locality.ON_DEVICE))
        assertNull(check.reached(settings))
        usage.record(UsageSamples.call(at = at("2026-11-25T09:00"), outcome = CallOutcome.FAILED, error = com.aicontrolcenter.core.usage.ErrorClass.TIMEOUT))
        assertEquals(settings.dailyLimits.sentence(com.aicontrolcenter.core.usage.DailyLimitKind.CALLS), check.reached(settings))
        // Money: one dollar at the owner's price.
        val priced = FrameworkSettings(maxModelCallsPerDay = 1_000, maxSpendPerDayMicros = 1_000)
        prices.set(ModelPrice(ModelId("mdl_grok"), inputPerMTokMicros = 1_000_000, outputPerMTokMicros = 1_000_000))
        assertEquals(priced.dailyLimits.sentence(com.aicontrolcenter.core.usage.DailyLimitKind.SPEND), check.reached(priced))
        assertNull(check.reached(FrameworkSettings(maxModelCallsPerDay = 1_000, maxSpendPerDayMicros = null, maxTokensPerDay = 1_000_000)))
        assertEquals(UsageSource.OBJECTIVE, UsageSamples.call(at = 0).source)
        assertEquals(ProviderId("prv_mdl_grok"), UsageSamples.call(at = 0).providerId)
    }
}
