package com.aicontrolcenter.framework.routines

import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.RoutineRunId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.routineRunId
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.routines.RoutineLimits
import com.aicontrolcenter.core.routines.RoutineRepository
import com.aicontrolcenter.core.routines.RoutineRun
import com.aicontrolcenter.core.routines.RoutineRunOutcome
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.tasks.Orchestrator
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.llm.rethrowIfCancelled
import com.aicontrolcenter.framework.operator.logSafely
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Runs the owner's routines at their due times (Phase 6, owner decision Q1, plan Appendix A, Tier B; WP17), with every
 * limit of Appendix A checked at run time. Routines are the one place work starts while nobody watches, so the runner
 * starts an objective only when all of this holds, read afresh at every run:
 *
 *  - The master switch (`FrameworkSettings.routinesEnabled`, off after installing) is on and the routine is on. While
 *    the master switch is off, due times pass without runs and without records ([masterSwitchedOn] moves them on, so
 *    switching it on starts nothing that was due before).
 *  - At most [RoutineLimits.MAX_ROUTINES] routines are looked at (the oldest), each due at least an hour apart (the
 *    schedules guarantee it).
 *  - No objective a run of this routine started is still unfinished (running, waiting, paused or interrupted): never two
 *    at once ([RoutineRunOutcome.SKIPPED_STILL_RUNNING]).
 *  - Today's daily limits are not reached ([DailyLimitCheck]; it fails closed: when today's use cannot be read, nothing
 *    starts) and local-only mode does not keep the Director from working ([LocalOnlyCheck]).
 *  - No backup is being restored ([MaintenanceGate]).
 *  - Fewer than [RoutineLimits.PAUSE_AFTER_FAILURES] failed runs in a row since the owner last changed or switched on
 *    the routine ([Routine.updatedAt]): a run fails when its Director could not start it
 *    ([RoutineRunOutcome.SKIPPED_DIRECTOR_UNAVAILABLE]) or its objective ended FAILED or TIMED_OUT; a COMPLETED one
 *    ends the streak; skipped, missed and waiting runs, cancelled objectives and objectives paused by the daily limit
 *    count neither way. At three the routine switches itself off with [RoutineLimits.PAUSED_AFTER_FAILURES].
 *
 * It starts every run with exactly [Routine.objectiveOptions] (security review of wave 1, F12): the routine's options,
 * its id, its "May use other apps' screens" (off unless ticked; the broker refuses every screen tool otherwise) and its
 * approval time-out. The broker is otherwise unchanged: every permission is asked and every consequential action
 * approved, as for an objective the owner starts by hand.
 *
 * Who calls what:
 *  - [runDue]: the foreground service, when a routine's alarm asked for it (the only path that starts work by itself).
 *    A due time it meets more than [RoutineClock.LATE_START_MS] late (the phone was off, Android held the alarm back)
 *    does not start by itself: it waits for the owner's tap ([RoutineRunOutcome.WAITING_FOR_TAP], with a notice), and
 *    after [RoutineClock.TAP_WINDOW_MS] it is missed.
 *  - [catchUp]: the owner opened the app. Due times that passed without a run wait for a tap (or are missed when too
 *    old); nothing starts.
 *  - [startWaiting]: the owner tapped Start on a waiting run and confirmed. Every check above is made again.
 *
 * Every due time is recorded once ([RoutineRepository.appendRun]) with its outcome, and logged. Several due times
 * missed in a row are recorded as one missed run that says how many. A due time is claimed (the routine's next due
 * time moved on) before anything starts, so it is never run twice. All of it happens under one lock.
 */
class RoutineRunner(
    private val routines: RoutineRepository,
    /** Read only: whether a routine's earlier objective is still unfinished, and how its objectives ended. */
    private val tasks: TaskRepository,
    private val orchestrator: Orchestrator,
    private val settings: SettingsView,
    private val dailyLimits: DailyLimitCheck,
    private val localOnly: LocalOnlyCheck,
    private val gate: MaintenanceGate,
    private val activity: ActivityRecorder,
    private val clock: RoutineClock,
    private val ids: IdGenerator,
    private val notices: RoutineNotices = RoutineNotices.None,
) : WaitingRoutines {
    private val lock = Mutex()

    /** How a pass was asked for. */
    private enum class Mode {
        /** The alarm, through the foreground service: a run on time may start by itself. */
        ALARM,

        /** The owner is looking at the app: nothing starts without a tap. */
        OWNER_PRESENT,
    }

    /** Runs the routines due now (the alarm's foreground service). Returns the runs it recorded. */
    suspend fun runDue(): List<RoutineRun> = pass(Mode.ALARM)

    override suspend fun catchUp() {
        pass(Mode.OWNER_PRESENT)
    }

    /**
     * The master switch was just switched on: due times that passed while it was off are not runs. Each routine that is
     * on is due next at its first time after now.
     */
    suspend fun masterSwitchedOn() = lock.withLock {
        val now = clock.now()
        for (routine in routines.getAll()) {
            if (!routine.enabled) continue
            val stored = routine.nextRunAt
            if (stored != null && stored > now) continue
            val next = clock.nextDue(routine.schedule, now)
            routines.update(routine.id) { current -> if (current.enabled && current.nextRunAt == stored) current.copy(nextRunAt = next) else current }
        }
    }

    /** Moves due times computed in another time zone to the routine's local times in the zone of now. */
    suspend fun realign() = lock.withLock { realignLocked(routines.getAll(), clock.now()) }

    private suspend fun realignLocked(all: List<Routine>, now: Long) {
        for (routine in all) {
            val expected = clock.realigned(routine, now) ?: continue
            val stored = routine.nextRunAt
            routines.update(routine.id) { current -> if (current.enabled && current.nextRunAt == stored) current.copy(nextRunAt = expected) else current }
        }
    }

    private suspend fun pass(mode: Mode): List<RoutineRun> = lock.withLock {
        val now = clock.now()
        val current = settings.get()
        val all = routines.getAll().take(RoutineLimits.MAX_ROUTINES)
        expireWaiting(all, now)
        if (!current.routinesEnabled) {
            // Switched off: due times pass without runs. Nothing is recorded and nothing starts.
            for (routine in all) {
                val stored = routine.nextRunAt ?: continue
                if (!routine.enabled || stored > now) continue
                val next = clock.nextDue(routine.schedule, now)
                routines.update(routine.id) { r -> if (r.enabled && r.nextRunAt == stored) r.copy(nextRunAt = next) else r }
            }
            return@withLock emptyList()
        }
        realignLocked(all, now)
        val recorded = ArrayList<RoutineRun>()
        for (routine in routines.getAll().take(RoutineLimits.MAX_ROUTINES)) {
            val due = routine.nextRunAt ?: continue
            if (!routine.enabled || due > now) continue
            recorded += handleDue(routine, due, now, mode, current)
        }
        recorded
    }

    /** One routine whose stored next due time [due] has come. */
    private suspend fun handleDue(routine: Routine, due: Long, now: Long, mode: Mode, current: FrameworkSettings): List<RoutineRun> {
        val overdue = clock.overdue(routine.schedule, due, now) ?: return emptyList()
        // Too many failures since the owner last touched it (an objective of an earlier run failed meanwhile): it pauses.
        if (pauseIfFailing(routine)) return emptyList()
        // Claim the due time first: whatever happens next, it is never run twice.
        val next = clock.nextDue(routine.schedule, now)
        var claimed = false
        routines.update(routine.id) { r ->
            if (r.enabled && r.nextRunAt == due) {
                claimed = true
                r.copy(nextRunAt = next)
            } else {
                r
            }
        }
        if (!claimed) return emptyList()
        val runs = ArrayList<RoutineRun>()
        val late = now - overdue.latest
        val tooOld = late > RoutineClock.TAP_WINDOW_MS
        if (overdue.count > 1 || tooOld) {
            val missed = if (tooOld) overdue.count else overdue.count - 1
            runs += record(routine, newRun(routine, overdue.first, RoutineRunOutcome.MISSED, note = missedNote(missed)))
        }
        if (tooOld) return runs
        runs += when {
            mode == Mode.OWNER_PRESENT -> record(routine, newRun(routine, overdue.latest, RoutineRunOutcome.WAITING_FOR_TAP, note = WAITING_NOTE))
            late > RoutineClock.LATE_START_MS -> record(routine, newRun(routine, overdue.latest, RoutineRunOutcome.WAITING_FOR_TAP, note = LATE_NOTE))
                .also { notices.waitingForTap(routine.title) }
            else -> record(routine, attempt(routine, current).run(routine, overdue.latest, ids.routineRunId()))
        }
        pauseIfFailing(routine)
        return runs
    }

    /** What one attempt to start a routine's objective came to. */
    private sealed interface Attempt {
        data class Started(val objectiveId: TaskId, val at: Long) : Attempt

        data class Skipped(val outcome: RoutineRunOutcome, val note: String) : Attempt

        fun run(routine: Routine, dueAt: Long, id: RoutineRunId): RoutineRun = when (this) {
            is Started -> RoutineRun(id, routine.id, dueAt, RoutineRunOutcome.STARTED, startedAt = at, objectiveId = objectiveId)
            is Skipped -> RoutineRun(id, routine.id, dueAt, outcome, note = note)
        }
    }

    /** Every check of a run, then the start. */
    private suspend fun attempt(routine: Routine, current: FrameworkSettings): Attempt {
        if (gate.busy.value) return Attempt.Skipped(RoutineRunOutcome.MISSED, RESTORING_NOTE)
        if (stillRunning(routine)) return Attempt.Skipped(RoutineRunOutcome.SKIPPED_STILL_RUNNING, STILL_RUNNING_NOTE)
        val limit = try {
            dailyLimits.reached(current)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            USE_UNREADABLE_NOTE
        }
        if (limit != null) return Attempt.Skipped(RoutineRunOutcome.SKIPPED_DAILY_LIMIT, limit)
        val blocked = try {
            localOnly.blocksDirector(current)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            // Unsure whether the team may work in local-only mode: nothing starts.
            current.localOnlyMode
        }
        if (blocked) return Attempt.Skipped(RoutineRunOutcome.SKIPPED_LOCAL_ONLY, LOCAL_ONLY_NOTE)
        return try {
            val objective = orchestrator.startObjective(routine.objectiveText, routine.objectiveOptions())
            Attempt.Started(objective.id, clock.now())
        } catch (e: IllegalStateException) {
            if (e.message == MaintenanceGate.RESTORING) {
                Attempt.Skipped(RoutineRunOutcome.MISSED, RESTORING_NOTE)
            } else {
                Attempt.Skipped(RoutineRunOutcome.SKIPPED_DIRECTOR_UNAVAILABLE, e.message?.takeIf { it.isNotBlank() } ?: DIRECTOR_NOTE)
            }
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            Attempt.Skipped(RoutineRunOutcome.SKIPPED_DIRECTOR_UNAVAILABLE, (e as? IllegalArgumentException)?.message ?: DIRECTOR_NOTE)
        }
    }

    /** Whether an objective a run of [routine] started is not finished yet (also one whose run was never recorded). */
    private suspend fun stillRunning(routine: Routine): Boolean =
        tasks.withStatus(UNFINISHED).any { it.kind == TaskKind.OBJECTIVE && it.id == it.rootId && it.options?.routineId == routine.id }

    /**
     * Failed runs in a row since the owner last changed or switched on [routine], newest first: a COMPLETED objective
     * ends the streak; skipped, missed and waiting runs, unfinished and cancelled objectives count neither way.
     */
    private suspend fun failuresInARow(routine: Routine): Int {
        var failures = 0
        for (run in routines.runs(routine.id, HISTORY)) {
            if (run.dueAt < routine.updatedAt) break
            when (run.outcome) {
                RoutineRunOutcome.STARTED -> when (run.objectiveId?.let { tasks.get(it)?.status }) {
                    TaskStatus.FAILED, TaskStatus.TIMED_OUT -> failures++
                    TaskStatus.COMPLETED -> return failures
                    else -> Unit
                }
                else -> if (run.outcome.countsAsFailure) failures++
            }
        }
        return failures
    }

    /** Keeps the routine's failure count current; at the limit switches it off with its note. True when it paused. */
    private suspend fun pauseIfFailing(routine: Routine): Boolean {
        val failures = failuresInARow(routine)
        val pause = failures >= RoutineLimits.PAUSE_AFTER_FAILURES
        var paused = false
        routines.update(routine.id) { r ->
            when {
                // The owner changed or switched it meanwhile: the count starts again with them.
                r.updatedAt != routine.updatedAt -> r
                pause && r.enabled -> {
                    paused = true
                    r.copy(enabled = false, nextRunAt = null, consecutiveFailures = failures, pausedNote = RoutineLimits.PAUSED_AFTER_FAILURES)
                }
                r.consecutiveFailures != failures -> r.copy(consecutiveFailures = failures)
                else -> r
            }
        }
        if (paused) {
            log(
                "Routine paused: ${routine.title}", RoutineLimits.PAUSED_AFTER_FAILURES, routine, Severity.WARNING,
            )
        }
        return paused
    }

    /** Waiting runs whose time is over become missed: too old, the routine is off, or its next due time has come. */
    private suspend fun expireWaiting(all: List<Routine>, now: Long) {
        for (routine in all) {
            for (run in routines.runs(routine.id, HISTORY)) {
                if (run.outcome != RoutineRunOutcome.WAITING_FOR_TAP) continue
                val over = now - run.dueAt > RoutineClock.TAP_WINDOW_MS || !routine.enabled || (routine.nextRunAt?.let { it <= now } == true)
                if (!over) continue
                routines.updateRun(run.id) { r ->
                    if (r.outcome == RoutineRunOutcome.WAITING_FOR_TAP) r.copy(outcome = RoutineRunOutcome.MISSED, note = EXPIRED_NOTE) else r
                }
            }
        }
    }

    override fun observeWaiting(): Flow<List<WaitingRoutine>> = waiting()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun waiting(): Flow<List<WaitingRoutine>> = routines.observeAll().flatMapLatest { all ->
        val shown = all.take(RoutineLimits.MAX_ROUTINES)
        if (shown.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(
                shown.map { routine ->
                    routines.observeRuns(routine.id, HISTORY).map { runs ->
                        runs.filter { it.outcome == RoutineRunOutcome.WAITING_FOR_TAP && routine.enabled }.map { run ->
                            WaitingRoutine(routine.id, run.id, routine.title, routine.objectiveText, run.dueAt, routine.allowScreenActions)
                        }
                    }
                },
            ) { lists -> lists.flatMap { it }.sortedBy { it.dueAt } }
        }
    }.distinctUntilChanged()

    override suspend fun startWaiting(routineId: RoutineId, runId: RoutineRunId): OwnerResult<RoutineRun> = lock.withLock {
        val now = clock.now()
        val run = routines.runs(routineId, HISTORY).firstOrNull { it.id == runId }
        if (run == null || run.outcome != RoutineRunOutcome.WAITING_FOR_TAP) return@withLock OwnerResult.Refused(NOT_WAITING)
        val routine = routines.get(routineId)
        val current = settings.get()
        val problem = when {
            routine == null || !routine.enabled -> ROUTINE_OFF
            !current.routinesEnabled -> MASTER_OFF
            now - run.dueAt > RoutineClock.TAP_WINDOW_MS || (routine.nextRunAt?.let { it <= now } == true) -> TOO_LATE
            else -> null
        }
        if (problem != null) {
            closeWaiting(runId, RoutineRunOutcome.MISSED, problem)
            return@withLock OwnerResult.Refused(problem)
        }
        routine!!
        val result = when (val attempt = attempt(routine, current)) {
            is Attempt.Started -> {
                val started = routines.updateRun(runId) { r ->
                    if (r.outcome == RoutineRunOutcome.WAITING_FOR_TAP) {
                        r.copy(outcome = RoutineRunOutcome.STARTED, startedAt = attempt.at, objectiveId = attempt.objectiveId, note = TAPPED_NOTE)
                    } else {
                        r
                    }
                }
                started?.let { logRun(routine, it) }
                started?.let { OwnerResult.Done(it) } ?: OwnerResult.Refused(NOT_WAITING)
            }
            is Attempt.Skipped -> {
                closeWaiting(runId, attempt.outcome, attempt.note)?.let { logRun(routine, it) }
                OwnerResult.Refused(attempt.note)
            }
        }
        pauseIfFailing(routine)
        result
    }

    override suspend fun dismissWaiting(routineId: RoutineId, runId: RoutineRunId): Boolean = lock.withLock {
        val run = routines.runs(routineId, HISTORY).firstOrNull { it.id == runId }
        if (run?.outcome != RoutineRunOutcome.WAITING_FOR_TAP) return@withLock false
        closeWaiting(runId, RoutineRunOutcome.MISSED, DISMISSED_NOTE) != null
    }

    private suspend fun closeWaiting(runId: RoutineRunId, outcome: RoutineRunOutcome, note: String): RoutineRun? {
        var changed = false
        val run = routines.updateRun(runId) { r ->
            if (r.outcome == RoutineRunOutcome.WAITING_FOR_TAP) {
                changed = true
                r.copy(outcome = outcome, note = note)
            } else {
                r
            }
        }
        return run?.takeIf { changed }
    }

    private fun newRun(routine: Routine, dueAt: Long, outcome: RoutineRunOutcome, note: String): RoutineRun =
        RoutineRun(ids.routineRunId(), routine.id, dueAt, outcome, note = note)

    /** Stores [run] and logs it. */
    private suspend fun record(routine: Routine, run: RoutineRun): RoutineRun {
        routines.appendRun(run)
        logRun(routine, run)
        return run
    }

    private suspend fun logRun(routine: Routine, run: RoutineRun) {
        val (title, severity) = when (run.outcome) {
            RoutineRunOutcome.STARTED -> "Routine started: ${routine.title}" to Severity.INFO
            RoutineRunOutcome.WAITING_FOR_TAP -> "Routine waiting for your tap: ${routine.title}" to Severity.INFO
            RoutineRunOutcome.MISSED -> "Routine missed: ${routine.title}" to Severity.INFO
            RoutineRunOutcome.SKIPPED_DIRECTOR_UNAVAILABLE -> "Routine could not start: ${routine.title}" to Severity.WARNING
            else -> "Routine skipped: ${routine.title}" to Severity.INFO
        }
        log(title, run.note.orEmpty(), routine, severity, run)
    }

    private suspend fun log(title: String, detail: String, routine: Routine, severity: Severity, run: RoutineRun? = null) = logSafely {
        activity.record(
            ActivityType.SYSTEM, title, detail, severity = severity, rootTaskId = run?.objectiveId, taskId = run?.objectiveId,
            data = buildJsonObject {
                put("routineId", routine.id.value)
                run?.let {
                    put("routineRunId", it.id.value)
                    put("outcome", it.outcome.name)
                }
            },
        )
    }

    /** Texts (app-written: a run's note never carries agent text). */
    companion object {
        private val UNFINISHED: Set<TaskStatus> = TaskStatus.entries.filterNot { it.isFinished }.toSet()

        /** Runs looked at for failures in a row and waiting runs. */
        private const val HISTORY: Int = 50

        const val WAITING_NOTE: String = "It did not start at its time. It waits for you to tap Start."
        const val LATE_NOTE: String = "Android let the app run it late, so it waits for you to tap Start."
        const val STILL_RUNNING_NOTE: String =
            "The objective of its previous run has not finished yet (it may be paused or interrupted). Finish, resume or stop it first."
        const val LOCAL_ONLY_NOTE: String = "Local-only mode is on, and the Director's model would send data off the phone."
        const val RESTORING_NOTE: String = "A backup was being restored, so the routine did not start."
        const val USE_UNREADABLE_NOTE: String = "Today's use of models could not be read, so the routine did not start."
        const val DIRECTOR_NOTE: String = "The Director could not start the objective."
        const val TAPPED_NOTE: String = "Started after you tapped Start."
        const val DISMISSED_NOTE: String = "You chose not to start it."
        const val EXPIRED_NOTE: String = "It waited for your tap and was not started."
        const val NOT_WAITING: String = "This routine is no longer waiting to start."
        const val ROUTINE_OFF: String = "This routine is switched off or was deleted, so it was not started."
        const val MASTER_OFF: String = "Routines are switched off in Settings, so it was not started."
        const val TOO_LATE: String = "It is too late to start this run. The routine runs again at its next time."

        /** The note of [count] due times missed in a row. */
        fun missedNote(count: Int): String = if (count <= 1) {
            "Missed: the phone was off or the app could not run at that time."
        } else {
            val many = if (count >= RoutineClock.MAX_COUNTED) "more than ${RoutineClock.MAX_COUNTED - 1}" else "$count"
            "Missed $many times in a row: the phone was off or the app could not run at those times."
        }
    }
}
