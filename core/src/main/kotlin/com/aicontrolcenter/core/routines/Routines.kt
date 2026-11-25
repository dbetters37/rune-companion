package com.aicontrolcenter.core.routines

import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.RoutineRunId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/*
 * Routines: objectives the owner scheduled (Phase 6, owner decision Q1, Appendix A, Tier B; WP17
 * builds the runner and the alarms). They act while nobody watches, so every limit of Appendix A
 * holds: the master switch (`FrameworkSettings.routinesEnabled`) is off after installing; at most
 * [RoutineLimits.MAX_ROUTINES] routines; runs at least an hour apart; never two runs of one
 * routine at once; a routine pauses itself after [RoutineLimits.PAUSE_AFTER_FAILURES] failed runs
 * in a row; daily limits and local-only mode are checked at run time; screen actions are refused
 * unless the owner ticked them for that routine; the permission broker is unchanged (every
 * permission is asked, every consequential action approved). Only the owner creates, changes or
 * deletes a routine ([OwnerRoutines]); no agent can reach [RoutineRepository] or [OwnerRoutines]
 * (both architecture tests list them).
 */

/**
 * When a routine is due, in the owner's time zone. Due times are local times, so a routine "at
 * 7:00" stays at 7:00 across daylight-saving changes; [nextAfter] says what happens on the days
 * the clocks change.
 */
@Serializable
sealed interface RoutineSchedule {
    /** Once a day at [minuteOfDay] (0 = midnight) on each of [days]. */
    @Serializable
    @SerialName("daily")
    data class Daily(val minuteOfDay: Int, val days: Set<DayOfWeek> = DayOfWeek.entries.toSet()) : RoutineSchedule {
        init {
            require(minuteOfDay in 0 until MINUTES_PER_DAY) { "A time of day is between 0:00 and 23:59." }
            require(days.isNotEmpty()) { "Choose at least one day." }
        }
    }

    /**
     * Every [hours] hours on each of [days], starting at [startMinuteOfDay] each day: with
     * hours = 5 and a start at 6:00, at 6:00, 11:00, 16:00 and 21:00. The count starts again
     * each day, so two runs are never less than an hour apart ([RoutineLimits.MIN_HOURS_APART]).
     */
    @Serializable
    @SerialName("every_hours")
    data class EveryHours(
        val hours: Int,
        val startMinuteOfDay: Int = 0,
        val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    ) : RoutineSchedule {
        init {
            require(hours in RoutineLimits.MIN_HOURS_APART..RoutineLimits.MAX_HOURS_APART) {
                "Every 1 to ${RoutineLimits.MAX_HOURS_APART} hours; once a day is a daily routine."
            }
            require(startMinuteOfDay in 0 until MINUTES_PER_DAY) { "A time of day is between 0:00 and 23:59." }
            require(days.isNotEmpty()) { "Choose at least one day." }
        }
    }

    /**
     * The first due time strictly after [afterMs] (epoch ms) in [zone]. A local time that does
     * not exist on a day (the hour the clocks skip) is due when that hour has been skipped
     * (02:30 becomes 03:30); a local time that happens twice (the hour the clocks go back) is due
     * the first time. Never more than 8 days after [afterMs].
     */
    fun nextAfter(afterMs: Long, zone: ZoneId): Long {
        val start = Instant.ofEpochMilli(afterMs).atZone(zone).toLocalDate()
        for (offset in -1L..8L) {
            val date = start.plusDays(offset)
            for (minute in minutesOn(date)) {
                val due = LocalDateTime.of(date, LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant().toEpochMilli()
                if (due > afterMs) return due
            }
        }
        error("A routine schedule is due at least once a week.")
    }

    /** The local minutes of [date] at which this schedule is due, in order; empty on a day it skips. */
    fun minutesOn(date: LocalDate): List<Int> = when (this) {
        is Daily -> if (date.dayOfWeek in days) listOf(minuteOfDay) else emptyList()
        is EveryHours -> if (date.dayOfWeek in days) (startMinuteOfDay until MINUTES_PER_DAY step hours * 60).toList() else emptyList()
    }

    /** Constants. */
    companion object {
        const val MINUTES_PER_DAY: Int = 24 * 60
    }
}

/** The bounds of Appendix A. */
object RoutineLimits {
    const val MAX_ROUTINES: Int = 10
    const val MIN_HOURS_APART: Int = 1
    const val MAX_HOURS_APART: Int = 12
    const val PAUSE_AFTER_FAILURES: Int = 3
    const val MAX_TITLE_CHARS: Int = 80
    const val MAX_OBJECTIVE_CHARS: Int = 4_000

    /** A routine may give the owner longer to answer its requests than the usual 30 minutes, up to this. */
    const val MAX_APPROVAL_TIMEOUT_MINUTES: Int = 240

    /** What a routine says when it paused itself. */
    const val PAUSED_AFTER_FAILURES: String = "Paused after 3 failed runs in a row. Switch it on again when the problem is fixed."
}

/**
 * One routine. [nextRunAt] is when it is next due (null while it is off), kept by the owner's
 * port when the schedule or the switch changes and by the runner after each due time.
 * [consecutiveFailures] counts failed runs in a row; at [RoutineLimits.PAUSE_AFTER_FAILURES] the
 * runner switches the routine off with [pausedNote]. Switching it on again clears both.
 */
@Serializable
data class Routine(
    val id: RoutineId,
    val title: String,
    /** The objective the routine gives the Director, as the owner wrote it. */
    val objectiveText: String,
    val options: ObjectiveOptions = ObjectiveOptions(),
    val schedule: RoutineSchedule,
    val enabled: Boolean = true,
    /** Minutes the routine's requests wait for the owner; null: the usual `approvalTimeoutMinutes`. */
    val approvalTimeoutMinutes: Int? = null,
    /** "May use other apps' screens": off by default; when off, every screen action of a run is refused. */
    val allowScreenActions: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val nextRunAt: Long? = null,
    val consecutiveFailures: Int = 0,
    /** Why the routine switched itself off, in plain words; null when the owner switched it or it is on. */
    val pausedNote: String? = null,
) {
    init {
        RoutineDraft.problem(title, objectiveText, approvalTimeoutMinutes)?.let { throw IllegalArgumentException(it) }
        require(consecutiveFailures >= 0) { "consecutiveFailures cannot be negative." }
        require(pausedNote == null || !enabled) { "A routine that is on is not paused." }
    }

    /** What the owner edits. */
    val draft: RoutineDraft get() = RoutineDraft(title, objectiveText, options, schedule, approvalTimeoutMinutes, allowScreenActions)

    /**
     * The options of the objective a run of this routine starts (security review of wave 1, F12): the
     * routine's own options, marked as started by it, with its "May use other apps' screens" and its
     * approval time-out, which the broker reads. The runner (WP17) starts every run with exactly these.
     */
    fun objectiveOptions(): ObjectiveOptions =
        options.copy(routineId = id, screenActionsAllowed = allowScreenActions, approvalTimeoutMinutes = approvalTimeoutMinutes)
}

/** What the owner fills in to create or change a routine. */
data class RoutineDraft(
    val title: String,
    val objectiveText: String,
    val options: ObjectiveOptions = ObjectiveOptions(),
    val schedule: RoutineSchedule,
    val approvalTimeoutMinutes: Int? = null,
    val allowScreenActions: Boolean = false,
) {
    /** Why this draft cannot be saved (a sentence for the owner), or null when it can. */
    fun problem(): String? = problem(title, objectiveText, approvalTimeoutMinutes)

    /** Validation shared with [Routine]. */
    companion object {
        fun problem(title: String, objectiveText: String, approvalTimeoutMinutes: Int?): String? = when {
            title.isBlank() -> "Give the routine a name."
            title.length > RoutineLimits.MAX_TITLE_CHARS -> "A routine's name can be at most ${RoutineLimits.MAX_TITLE_CHARS} characters long."
            objectiveText.isBlank() -> "Write what the team should do."
            objectiveText.length > RoutineLimits.MAX_OBJECTIVE_CHARS -> "What the team should do can be at most ${RoutineLimits.MAX_OBJECTIVE_CHARS} characters long."
            approvalTimeoutMinutes != null && approvalTimeoutMinutes !in 1..RoutineLimits.MAX_APPROVAL_TIMEOUT_MINUTES ->
                "Requests can wait between 1 and ${RoutineLimits.MAX_APPROVAL_TIMEOUT_MINUTES} minutes."
            else -> null
        }
    }
}

/** What became of one due time of a routine. Stored by name; new values are only appended. */
enum class RoutineRunOutcome(
    /** True when it counts towards [RoutineLimits.PAUSE_AFTER_FAILURES]. */
    val countsAsFailure: Boolean,
) {
    /** The objective was started. Whether it then failed is counted when it ends. */
    STARTED(false),

    /** The previous run's objective was still running: never two at once. */
    SKIPPED_STILL_RUNNING(false),

    /** Today's limit was reached. */
    SKIPPED_DAILY_LIMIT(false),

    /** Local-only mode is on and the team would send data off the phone. */
    SKIPPED_LOCAL_ONLY(false),

    /** No Director, or it cannot run. */
    SKIPPED_DIRECTOR_UNAVAILABLE(true),

    /** Android did not let the app start in the background: "Routine 'Morning news' is due. Tap to start it." */
    WAITING_FOR_TAP(false),

    /** The due time passed while the phone was off or the app could not run. */
    MISSED(false),
}

/** One due time of a routine. [startedAt] and [objectiveId] are set when an objective started (at once, or after a tap). */
@Serializable
data class RoutineRun(
    val id: RoutineRunId,
    val routineId: RoutineId,
    val dueAt: Long,
    val outcome: RoutineRunOutcome,
    val startedAt: Long? = null,
    val objectiveId: TaskId? = null,
    /** An app-written sentence about the run; never agent text. */
    val note: String? = null,
) {
    init {
        require((objectiveId != null) == (startedAt != null)) { "A started run records both when it started and its objective." }
        require(objectiveId == null || outcome == RoutineRunOutcome.STARTED) { "Only a started run has an objective." }
    }
}

/**
 * Storage of routines and their runs (schema 6: `routines`, `routine_runs`). Owner-capable: it can
 * create and change routines, so nothing an agent reaches may hold it. Routines are listed oldest
 * first (created time, then id); runs newest first (due time descending, then id descending).
 * Flows emit at once and after every change. Implementations: Room (WP1b) and in-memory
 * (`:testkit`); both pass `RoutineRepositoryContract`.
 */
interface RoutineRepository {
    fun observeAll(): Flow<List<Routine>>

    suspend fun getAll(): List<Routine>

    suspend fun get(id: RoutineId): Routine?

    /** Inserts or replaces by id. */
    suspend fun upsert(routine: Routine)

    /** Atomically applies [transform] to the stored routine and returns the result, or null when it does not exist. */
    suspend fun update(id: RoutineId, transform: (Routine) -> Routine): Routine?

    /** Deletes the routine and its runs. No-op when absent. */
    suspend fun delete(id: RoutineId)

    /** Stores a run. A run whose id is stored already is ignored. @throws IllegalArgumentException when its routine does not exist. */
    suspend fun appendRun(run: RoutineRun)

    /** Atomically applies [transform] to the stored run (a tap starting a WAITING_FOR_TAP run) and returns the result, or null. */
    suspend fun updateRun(id: RoutineRunId, transform: (RoutineRun) -> RoutineRun): RoutineRun?

    /** The newest [limit] runs of [routineId]. */
    fun observeRuns(routineId: RoutineId, limit: Int): Flow<List<RoutineRun>>

    suspend fun runs(routineId: RoutineId, limit: Int): List<RoutineRun>

    /** Deletes runs due before [beforeMs], of every routine. Returns how many. */
    suspend fun pruneRuns(beforeMs: Long): Int
}

/**
 * The owner's Routines screen (WP10) and nothing else. Every change is logged. Implementation:
 * WP17 (`framework/routines`); the reference in `:testkit` and `OwnerRoutinesContract` define
 * the behaviour.
 */
interface OwnerRoutines {
    fun observeAll(): Flow<List<Routine>>

    /**
     * Creates a routine, switched on, due next at [RoutineSchedule.nextAfter] now. Refused, with a
     * sentence, when the draft is not valid ([RoutineDraft.problem]) or [RoutineLimits.MAX_ROUTINES]
     * exist. It runs only while the master switch is on.
     */
    suspend fun create(draft: RoutineDraft): OwnerResult<Routine>

    /** Replaces the routine's draft; its next due time is computed again when it is on. Refused when missing or invalid. */
    suspend fun change(id: RoutineId, draft: RoutineDraft): OwnerResult<Routine>

    /**
     * Switches the routine on (next due time computed from now, failures and pause note
     * cleared) or off (no next due time). Refused when missing.
     */
    suspend fun setEnabled(id: RoutineId, enabled: Boolean): OwnerResult<Routine>

    /** Deletes the routine and its runs; an objective it started is kept. Returns false when it did not exist. */
    suspend fun delete(id: RoutineId): Boolean

    /** The newest [limit] runs of a routine, for its history. */
    fun observeRuns(id: RoutineId, limit: Int): Flow<List<RoutineRun>>
}
