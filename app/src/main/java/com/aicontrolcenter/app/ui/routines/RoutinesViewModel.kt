package com.aicontrolcenter.app.ui.routines

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.routines.ExactAlarmAccess
import com.aicontrolcenter.app.routines.ExactAlarmState
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.putText
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.RoutineRunId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.routines.OwnerRoutines
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.routines.RoutineDraft
import com.aicontrolcenter.core.routines.RoutineLimits
import com.aicontrolcenter.core.routines.RoutineRun
import com.aicontrolcenter.core.routines.RoutineSchedule
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.framework.routines.WaitingRoutines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** One routine as the list shows it. */
@Immutable
data class RoutineRowUi(
    val id: RoutineId,
    val title: String,
    /** "Every day at 07:00". */
    val schedule: String,
    /** "On", "Off" or "Paused", always with [tone]'s icon. */
    val status: String,
    val tone: StatusTone,
    /** "Next: tomorrow at 07:00", or why it does not run; null when it is off. */
    val next: String?,
    /** Why it switched itself off (after 3 failed runs), and how to switch it on again. */
    val pausedNote: String?,
    /** "1 failed run in a row. …", or null. */
    val failures: String?,
    /** "May use other apps' screens" or "No screens". */
    val screens: String,
)

/** What the Routines list shows. */
@Immutable
data class RoutinesUiState(
    val isLoading: Boolean = true,
    /** The master switch (Settings › Routines and folder pick-up): off, no routine starts. */
    val masterOn: Boolean = false,
    val routines: List<RoutineRowUi> = emptyList(),
    /** "1 routine is waiting for you to tap Start.", or null when none waits. */
    val waiting: String? = null,
    /** Fewer than [RoutineLimits.MAX_ROUTINES] exist. */
    val canAdd: Boolean = true,
    /** "3 of 10 routines". */
    val count: String = "",
    /** Whether Android lets routines start on time ("Alarms and reminders"). */
    val exactAlarms: ExactAlarmState = ExactAlarmState.ALWAYS,
    val message: UserMessage? = null,
)

/**
 * The Routines list (Phase 6, owner decision Q1, Appendix A, Tier B; WP10c): each routine with its schedule, its next
 * time, its pause note and failures in a row; whether the master switch is on (it is changed in Settings); the runs that
 * wait for a tap (the screen links to the confirmation step); and whether Android lets routines start on time ("Alarms
 * and reminders", read through [exactAlarms] and again when the owner comes back from Android's page). Owner-only: it
 * holds the owner's routines port; it changes nothing itself.
 *
 * @param waiting the runs that wait for a tap (the count only; starting one is the confirmation step's).
 * @param settings read only: the master switch.
 * @param computation where the list is built (the app's background pool; the caller's dispatcher in tests).
 */
class RoutinesViewModel(
    private val routines: OwnerRoutines,
    private val waiting: WaitingRoutines,
    private val settings: SettingsView,
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val exactAlarms: ExactAlarmAccess = ExactAlarmAccess.Fixed(ExactAlarmState.ALWAYS),
    private val computation: CoroutineContext = EmptyCoroutineContext,
) : ViewModel() {
    private data class Local(val exact: ExactAlarmState = ExactAlarmState.ALWAYS, val message: UserMessage? = null)

    private val local = MutableStateFlow(Local())
    private var messages = 0L

    init {
        refreshExactAlarms()
    }

    val uiState: StateFlow<RoutinesUiState> = combine(
        routines.observeAll(),
        waiting.observeWaiting().map { it.size }.distinctUntilChanged(),
        settings.settings.map { it.routinesEnabled }.distinctUntilChanged(),
    ) { list, waitingCount, on -> Triple(list, waitingCount, on) }
        .flowOn(computation)
        .let { source -> combine(source, local) { (list, waitingCount, on), local -> build(list, waitingCount, on, local) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutinesUiState())

    private fun build(list: List<Routine>, waitingCount: Int, masterOn: Boolean, local: Local): RoutinesUiState {
        val now = clock.now()
        return RoutinesUiState(
            isLoading = false,
            masterOn = masterOn,
            routines = list.map { rowOf(it, masterOn, now, zone()) },
            waiting = when (waitingCount) {
                0 -> null
                1 -> "1 routine is waiting for you to tap Start."
                else -> "$waitingCount routines are waiting for you to tap Start."
            },
            canAdd = list.size < RoutineLimits.MAX_ROUTINES,
            count = "${list.size} of ${RoutineLimits.MAX_ROUTINES} routines",
            exactAlarms = local.exact,
            message = local.message,
        )
    }

    /** Reads "Alarms and reminders" again (when the screen opens, and when the owner comes back from Android's page). */
    fun refreshExactAlarms() {
        viewModelScope.launch {
            val state = try {
                withContext(computation) { exactAlarms.state() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ExactAlarmState.NOT_ALLOWED
            }
            local.update { it.copy(exact = state) }
        }
    }

    /** Android's "Alarms and reminders" page could not be opened on this phone. */
    fun couldNotOpenAlarmSettings() = local.update { it.copy(message = UserMessage(NO_ALARM_PAGE, ++messages)) }

    fun messageShown() = local.update { it.copy(message = null) }

    companion object {
        /** When Android has no "Alarms and reminders" page to open. */
        const val NO_ALARM_PAGE: String =
            "Android's \"Alarms and reminders\" page could not be opened on this phone. You find it in Android's Settings, " +
                "under Apps, AI Control Center."

        /** One routine as the list shows it. */
        fun rowOf(routine: Routine, masterOn: Boolean, now: Long, zone: ZoneId): RoutineRowUi {
            val paused = !routine.enabled && routine.pausedNote != null
            return RoutineRowUi(
                id = routine.id,
                title = routine.title,
                schedule = RoutineWords.schedule(routine.schedule),
                status = when {
                    paused -> "Paused"
                    routine.enabled -> "On"
                    else -> "Off"
                },
                tone = when {
                    paused -> StatusTone.ATTENTION
                    routine.enabled -> StatusTone.READY
                    else -> StatusTone.DISABLED
                },
                next = nextOf(routine, masterOn, now, zone),
                pausedNote = routine.pausedNote?.takeIf { !routine.enabled },
                failures = RoutineWords.failures(routine.consecutiveFailures).takeIf { routine.enabled },
                screens = if (routine.allowScreenActions) "May use other apps' screens" else "No screens",
            )
        }

        /** "Next: tomorrow at 07:00", or why it does not run now; null when the routine is off. */
        fun nextOf(routine: Routine, masterOn: Boolean, now: Long, zone: ZoneId): String? {
            if (!routine.enabled) return null
            if (!masterOn) return "Does not run while routines are off in Settings."
            val at = routine.nextRunAt ?: return null
            return if (at <= now) "Next: due now" else "Next: " + RoutineWords.at(at, now, zone)
        }
    }
}

/** The two kinds of schedule the editor offers. */
enum class ScheduleKind { DAILY, EVERY_HOURS }

/** One run of the routine's history. */
@Immutable
data class RoutineRunUi(
    val id: RoutineRunId,
    /** "Today at 07:00". */
    val due: String,
    /** "Started", "Skipped: today's limit", … (handoff-wp17.md §6). */
    val outcome: String,
    val tone: StatusTone,
    /** The app's own sentence about the run, or null. */
    val note: String?,
    /** The objective a started run began, to open it. */
    val objectiveId: TaskId?,
)

/** What the routine editor shows. */
@Immutable
data class RoutineEditorUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
    /** The routine was deleted meanwhile. */
    val missing: Boolean = false,
    val kind: ScheduleKind = ScheduleKind.DAILY,
    /** The time of a daily routine, or the first time of the day of an every-few-hours one (minutes after midnight). */
    val minuteOfDay: Int = DEFAULT_MINUTE,
    val everyHours: Int = DEFAULT_EVERY_HOURS,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    /** "Every day at 07:00". */
    val scheduleText: String = "",
    /** "At 06:00, 09:00 … and 21:00" for an every-few-hours routine. */
    val timesText: String? = null,
    /** "With this schedule it next runs tomorrow at 07:00." */
    val preview: String? = null,
    /** The next clock change that touches this schedule, or null. */
    val clockChange: String? = null,
    val reviewResults: Boolean = true,
    val allowScreenActions: Boolean = false,
    /** Minutes the routine's requests wait for the owner; null: as usual. */
    val approvalTimeoutMinutes: Int? = null,
    val timeoutChoices: List<Int?> = TIMEOUT_CHOICES,
    /** An existing routine: switched on or off. */
    val enabled: Boolean = true,
    val pausedNote: String? = null,
    val next: String? = null,
    val failures: String? = null,
    val titleError: String? = null,
    val objectiveError: String? = null,
    val daysError: String? = null,
    /** Why the last save, switch or delete was refused, as the app says it. */
    val problem: String? = null,
    val runs: List<RoutineRunUi> = emptyList(),
    val isSaving: Boolean = false,
    val confirmDelete: Boolean = false,
    /** The owner switched "May use other apps' screens" on: asked once more. */
    val confirmScreens: Boolean = false,
    /** Saved: the screen goes back (once). */
    val saved: RoutineId? = null,
    /** Deleted: the screen closes (once). */
    val deleted: Boolean = false,
    val message: UserMessage? = null,
) {
    companion object {
        const val DEFAULT_MINUTE: Int = 7 * 60
        const val DEFAULT_EVERY_HOURS: Int = 3

        /** "As usual" (null) and a few lengths up to [RoutineLimits.MAX_APPROVAL_TIMEOUT_MINUTES]. */
        val TIMEOUT_CHOICES: List<Int?> = listOf(null, 15, 30, 60, 120, RoutineLimits.MAX_APPROVAL_TIMEOUT_MINUTES)
    }
}

/**
 * A new routine ([routineId] null), or one to change (Phase 6, owner decision Q1, Appendix A, Tier B; WP10c): its title
 * (at most 80 characters) and what the team should do (at most 4,000), in [title] and [objective] (rule 27: the fields'
 * text lives here and is read when the owner taps Save); the schedule in the owner's local time (daily, or every few
 * hours, on chosen weekdays), with what happens on the nights the clocks change; "Have results reviewed"; how long its
 * requests wait for the owner (as usual, or 15 minutes to 4 hours); "May use other apps' screens", off by default, asked
 * once more when switched on. An existing routine also shows whether it is on, its next time, its failures in a row, its
 * pause note ("Switch it on again") and its run history with each outcome in words (handoff-wp17.md §6).
 *
 * Every change goes through the owner's port ([OwnerRoutines]), which checks the draft again and logs it.
 */
class RoutineEditorViewModel(
    private val routineId: RoutineId?,
    private val routines: OwnerRoutines,
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val computation: CoroutineContext = EmptyCoroutineContext,
) : ViewModel() {
    /** The routine's title and objective (rule 27: the fields' text lives here). */
    val title: TextFieldState = TextFieldState()
    val objective: TextFieldState = TextFieldState()

    /** What the owner chose that is not text, and what the last action did. */
    private data class Form(
        val filled: Boolean = false,
        val kind: ScheduleKind = ScheduleKind.DAILY,
        val minuteOfDay: Int = RoutineEditorUiState.DEFAULT_MINUTE,
        val everyHours: Int = RoutineEditorUiState.DEFAULT_EVERY_HOURS,
        val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
        val reviewResults: Boolean = true,
        val allowScreenActions: Boolean = false,
        val approvalTimeoutMinutes: Int? = null,
        /** The routine's options as stored, kept as they are but for "Have results reviewed". */
        val options: ObjectiveOptions = ObjectiveOptions(),
        val titleError: String? = null,
        val objectiveError: String? = null,
        val daysError: String? = null,
        val problem: String? = null,
        val isSaving: Boolean = false,
        val confirmDelete: Boolean = false,
        val confirmScreens: Boolean = false,
        val saved: RoutineId? = null,
        val deleted: Boolean = false,
        val message: UserMessage? = null,
    )

    private val form = MutableStateFlow(Form(filled = routineId == null))
    private var messages = 0L

    /** The stored routine, live (null for a new one, or once it was deleted). */
    private val stored: Flow<Routine?> =
        if (routineId == null) flowOf(null) else routines.observeAll().map { list -> list.firstOrNull { it.id == routineId } }.distinctUntilChanged()

    private val runs: Flow<List<RoutineRun>> =
        if (routineId == null) flowOf(emptyList()) else routines.observeRuns(routineId, HISTORY)

    init {
        if (routineId != null) {
            viewModelScope.launch {
                val routine = try {
                    stored.first()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                fill(routine)
            }
        }
    }

    val uiState: StateFlow<RoutineEditorUiState> = combine(stored, runs, form) { routine, runs, form -> build(routine, runs, form) }
        .flowOn(computation)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutineEditorUiState(isNew = routineId == null))

    private fun fill(routine: Routine?) {
        if (routine == null) {
            form.update { it.copy(filled = true) }
            return
        }
        title.putText(routine.title)
        objective.putText(routine.objectiveText)
        form.update {
            val schedule = routine.schedule
            it.copy(
                filled = true,
                kind = if (schedule is RoutineSchedule.EveryHours) ScheduleKind.EVERY_HOURS else ScheduleKind.DAILY,
                minuteOfDay = when (schedule) {
                    is RoutineSchedule.Daily -> schedule.minuteOfDay
                    is RoutineSchedule.EveryHours -> schedule.startMinuteOfDay
                },
                everyHours = (schedule as? RoutineSchedule.EveryHours)?.hours ?: RoutineEditorUiState.DEFAULT_EVERY_HOURS,
                days = when (schedule) {
                    is RoutineSchedule.Daily -> schedule.days
                    is RoutineSchedule.EveryHours -> schedule.days
                },
                reviewResults = routine.options.reviewResults,
                allowScreenActions = routine.allowScreenActions,
                approvalTimeoutMinutes = routine.approvalTimeoutMinutes,
                options = routine.options,
            )
        }
    }

    private fun build(routine: Routine?, runs: List<RoutineRun>, form: Form): RoutineEditorUiState {
        val now = clock.now()
        val zone = zone()
        val schedule = scheduleOf(form)
        return RoutineEditorUiState(
            isLoading = !form.filled,
            isNew = routineId == null,
            missing = routineId != null && form.filled && routine == null && !form.deleted,
            kind = form.kind,
            minuteOfDay = form.minuteOfDay,
            everyHours = form.everyHours,
            days = form.days,
            scheduleText = schedule?.let(RoutineWords::schedule) ?: "Choose at least one day.",
            timesText = (schedule as? RoutineSchedule.EveryHours)?.let { "At " + RoutineWords.timesOfDay(it) + "." },
            preview = schedule?.let { "With this schedule it next runs " + RoutineWords.at(it.nextAfter(now, zone), now, zone) + "." },
            clockChange = schedule?.let { RoutineWords.clockChange(it, now, zone) },
            reviewResults = form.reviewResults,
            allowScreenActions = form.allowScreenActions,
            approvalTimeoutMinutes = form.approvalTimeoutMinutes,
            timeoutChoices = (RoutineEditorUiState.TIMEOUT_CHOICES + form.approvalTimeoutMinutes).distinct()
                .sortedWith(compareBy(nullsFirst()) { it }),
            enabled = routine?.enabled ?: true,
            pausedNote = routine?.pausedNote?.takeIf { !routine.enabled },
            next = routine?.let { r ->
                when {
                    !r.enabled -> null
                    r.nextRunAt == null -> null
                    r.nextRunAt!! <= now -> "Next: due now"
                    else -> "Next: " + RoutineWords.at(r.nextRunAt!!, now, zone)
                }
            },
            failures = routine?.takeIf { it.enabled }?.let { RoutineWords.failures(it.consecutiveFailures) },
            titleError = form.titleError,
            objectiveError = form.objectiveError,
            daysError = form.daysError,
            problem = form.problem,
            runs = runs.map { run ->
                RoutineRunUi(
                    id = run.id,
                    due = RoutineWords.at(run.dueAt, now, zone).replaceFirstChar { it.uppercase() },
                    outcome = RoutineWords.outcome(run.outcome),
                    tone = RoutineWords.tone(run.outcome),
                    note = run.note?.takeIf { it.isNotBlank() },
                    objectiveId = run.objectiveId,
                )
            },
            isSaving = form.isSaving,
            confirmDelete = form.confirmDelete,
            confirmScreens = form.confirmScreens,
            saved = form.saved,
            deleted = form.deleted,
            message = form.message,
        )
    }

    // --- the schedule ---

    fun setKind(kind: ScheduleKind) = form.update { it.copy(kind = kind, problem = null) }

    /** The hour of the time (0 to 23), keeping the minutes. */
    fun setHour(hour: Int) = form.update { it.copy(minuteOfDay = hour.coerceIn(0, 23) * 60 + it.minuteOfDay % 60, problem = null) }

    /** The minutes of the time (0 to 59), keeping the hour. */
    fun setMinute(minute: Int) = form.update { it.copy(minuteOfDay = it.minuteOfDay / 60 * 60 + minute.coerceIn(0, 59), problem = null) }

    fun setEveryHours(hours: Int) =
        form.update { it.copy(everyHours = hours.coerceIn(RoutineLimits.MIN_HOURS_APART, RoutineLimits.MAX_HOURS_APART), problem = null) }

    fun toggleDay(day: DayOfWeek) = form.update {
        val days = if (day in it.days) it.days - day else it.days + day
        it.copy(days = days, daysError = null, problem = null)
    }

    fun setDays(days: Set<DayOfWeek>) = form.update { it.copy(days = days, daysError = null, problem = null) }

    // --- the options ---

    fun setReviewResults(on: Boolean) = form.update { it.copy(reviewResults = on) }

    /** "May use other apps' screens": switching it on asks once more; switching it off is at once. */
    fun requestScreenActions(on: Boolean) = form.update { if (on) it.copy(confirmScreens = true) else it.copy(allowScreenActions = false) }

    fun confirmScreenActions() = form.update { it.copy(allowScreenActions = true, confirmScreens = false) }

    fun cancelScreenActions() = form.update { it.copy(confirmScreens = false) }

    fun setApprovalTimeout(minutes: Int?) = form.update { it.copy(approvalTimeoutMinutes = minutes) }

    // --- saving ---

    /** Saves the fields as they are now; each field's problem is shown under it and nothing is saved. */
    fun save() {
        val current = form.value
        if (current.isSaving || !current.filled) return
        val title = title.text.toString().trim()
        val objective = objective.text.toString().trim()
        val titleError = RoutineDraft.problem(title, PLACEHOLDER, null)
        val objectiveError = RoutineDraft.problem(PLACEHOLDER, objective, null)
        val schedule = scheduleOf(current)
        val daysError = if (schedule == null) "Choose at least one day." else null
        if (titleError != null || objectiveError != null || daysError != null) {
            form.update { it.copy(titleError = titleError, objectiveError = objectiveError, daysError = daysError, problem = null) }
            return
        }
        val draft = RoutineDraft(
            title = title,
            objectiveText = objective,
            options = current.options.copy(reviewResults = current.reviewResults),
            schedule = schedule!!,
            approvalTimeoutMinutes = current.approvalTimeoutMinutes,
            allowScreenActions = current.allowScreenActions,
        )
        form.update { it.copy(isSaving = true, titleError = null, objectiveError = null, daysError = null, problem = null) }
        viewModelScope.launch {
            val result = try {
                if (routineId == null) routines.create(draft) else routines.change(routineId, draft)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                OwnerResult.Refused(COULD_NOT_SAVE)
            }
            form.update {
                when (result) {
                    is OwnerResult.Done -> it.copy(isSaving = false, saved = result.value.id)
                    is OwnerResult.Refused -> it.copy(isSaving = false, problem = result.reason)
                }
            }
        }
    }

    /** The screen went back after a save. */
    fun savedHandled() = form.update { it.copy(saved = null) }

    /** Switches an existing routine on (failures and pause note cleared, next time from now) or off. */
    fun setEnabled(on: Boolean) {
        val id = routineId ?: return
        if (form.value.isSaving) return
        form.update { it.copy(isSaving = true, problem = null) }
        viewModelScope.launch {
            val result = try {
                routines.setEnabled(id, on)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                OwnerResult.Refused(COULD_NOT_SAVE)
            }
            form.update {
                when (result) {
                    is OwnerResult.Done -> it.copy(
                        isSaving = false,
                        message = UserMessage(if (on) "Switched on." else "Switched off: it does not run until you switch it on again.", ++messages),
                    )
                    is OwnerResult.Refused -> it.copy(isSaving = false, problem = result.reason)
                }
            }
        }
    }

    fun askDelete() = form.update { it.copy(confirmDelete = true) }

    fun cancelDelete() = form.update { it.copy(confirmDelete = false) }

    /** Deletes the routine and its history; objectives it started are kept. */
    fun confirmDelete() {
        val id = routineId ?: return
        form.update { it.copy(confirmDelete = false, isSaving = true, problem = null) }
        viewModelScope.launch {
            val failed = try {
                routines.delete(id)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                true
            }
            form.update { if (failed) it.copy(isSaving = false, problem = COULD_NOT_DELETE) else it.copy(isSaving = false, deleted = true) }
        }
    }

    fun messageShown() = form.update { it.copy(message = null) }

    private fun scheduleOf(form: Form): RoutineSchedule? {
        if (form.days.isEmpty()) return null
        return when (form.kind) {
            ScheduleKind.DAILY -> RoutineSchedule.Daily(form.minuteOfDay, form.days)
            ScheduleKind.EVERY_HOURS -> RoutineSchedule.EveryHours(form.everyHours, form.minuteOfDay, form.days)
        }
    }

    companion object {
        /** Runs shown in the history. */
        const val HISTORY: Int = 20

        const val COULD_NOT_SAVE: String = "The routine could not be saved. Try again."
        const val COULD_NOT_DELETE: String = "The routine could not be deleted. Try again."

        /** Stands in for the other field when one field is checked on its own (never saved). */
        private const val PLACEHOLDER = "x"

        /** "As usual", "15 minutes", "1 hour", "4 hours", "90 minutes". */
        fun timeoutLabel(minutes: Int?): String = when {
            minutes == null -> "As usual"
            minutes % 60 == 0 -> if (minutes == 60) "1 hour" else "${minutes / 60} hours"
            else -> "$minutes minutes"
        }
    }
}
