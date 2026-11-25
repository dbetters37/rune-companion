package com.aicontrolcenter.framework.routines

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.routineId
import com.aicontrolcenter.core.routines.OwnerRoutines
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.routines.RoutineDraft
import com.aicontrolcenter.core.routines.RoutineLimits
import com.aicontrolcenter.core.routines.RoutineRepository
import com.aicontrolcenter.core.routines.RoutineRun
import com.aicontrolcenter.framework.operator.logSafely
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

/**
 * The owner's Routines screen (Phase 6, owner decision Q1, plan Appendix A, Tier B; WP17): creating, changing,
 * switching and deleting routines over the [RoutineRepository], with every change logged as the owner's action.
 *
 * What it promises (`OwnerRoutinesContract`):
 *  - A new routine is on and due next at its schedule's first time after now, in the owner's [zone]. Screen actions
 *    stay off unless the owner ticked them ([RoutineDraft.allowScreenActions]).
 *  - At most [RoutineLimits.MAX_ROUTINES] routines: changes are serialised under one lock, so two creations at once
 *    cannot pass the cap together.
 *  - Changing a routine computes its next due time again when it is on; switching it on clears the failures and the
 *    pause note and computes the next due time from now; switching it off clears the next due time.
 *  - [Routine.updatedAt] is the owner's last change: the runner counts failed runs in a row only since then, so
 *    switching a paused routine on (or changing it) gives it three fresh tries. The runner never sets it.
 *
 * It starts nothing: only [RoutineRunner] starts a routine's objective, at its due time, with every check of
 * Appendix A. Nothing an agent reaches holds this class (both architecture tests list [OwnerRoutines] and
 * [RoutineRepository] as owner-only).
 */
class DefaultOwnerRoutines(
    private val repository: RoutineRepository,
    private val activity: ActivityRecorder,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : OwnerRoutines {
    private val lock = Mutex()

    override fun observeAll(): Flow<List<Routine>> = repository.observeAll()

    override suspend fun create(draft: RoutineDraft): OwnerResult<Routine> {
        val made = lock.withLock {
            draft.problem()?.let { return OwnerResult.Refused(it) }
            if (repository.getAll().size >= RoutineLimits.MAX_ROUTINES) return OwnerResult.Refused(TOO_MANY)
            val now = clock.now()
            val routine = Routine(
                id = ids.routineId(), title = draft.title.trim(), objectiveText = draft.objectiveText.trim(), options = draft.options,
                schedule = draft.schedule, enabled = true, approvalTimeoutMinutes = draft.approvalTimeoutMinutes,
                allowScreenActions = draft.allowScreenActions, createdAt = now, updatedAt = now,
                nextRunAt = draft.schedule.nextAfter(now, zone()),
            )
            repository.upsert(routine)
            routine
        }
        log("Routine made: ${made.title}", made, screens(made))
        return OwnerResult.Done(made)
    }

    override suspend fun change(id: RoutineId, draft: RoutineDraft): OwnerResult<Routine> {
        val changed = lock.withLock {
            draft.problem()?.let { return OwnerResult.Refused(it) }
            val now = clock.now()
            repository.update(id) { current ->
                current.copy(
                    title = draft.title.trim(), objectiveText = draft.objectiveText.trim(), options = draft.options, schedule = draft.schedule,
                    approvalTimeoutMinutes = draft.approvalTimeoutMinutes, allowScreenActions = draft.allowScreenActions,
                    updatedAt = now, nextRunAt = if (current.enabled) draft.schedule.nextAfter(now, zone()) else null,
                )
            } ?: return OwnerResult.Refused(MISSING)
        }
        log("Routine changed: ${changed.title}", changed, screens(changed))
        return OwnerResult.Done(changed)
    }

    override suspend fun setEnabled(id: RoutineId, enabled: Boolean): OwnerResult<Routine> {
        val changed = lock.withLock {
            val now = clock.now()
            repository.update(id) { current ->
                if (enabled) {
                    current.copy(
                        enabled = true, nextRunAt = current.schedule.nextAfter(now, zone()), consecutiveFailures = 0, pausedNote = null,
                        updatedAt = now,
                    )
                } else {
                    current.copy(enabled = false, nextRunAt = null, updatedAt = now)
                }
            } ?: return OwnerResult.Refused(MISSING)
        }
        log(if (enabled) "Routine switched on: ${changed.title}" else "Routine switched off: ${changed.title}", changed)
        return OwnerResult.Done(changed)
    }

    override suspend fun delete(id: RoutineId): Boolean {
        val gone = lock.withLock {
            val existing = repository.get(id) ?: return false
            repository.delete(id)
            existing
        }
        log("Routine deleted: ${gone.title}", gone, "Objectives it started are kept.")
        return true
    }

    override fun observeRuns(id: RoutineId, limit: Int): Flow<List<RoutineRun>> = repository.observeRuns(id, limit)

    private fun screens(routine: Routine): String =
        if (routine.allowScreenActions) "It may use other apps' screens." else "It may not use other apps' screens."

    private suspend fun log(title: String, routine: Routine, detail: String = "") = logSafely {
        activity.record(
            ActivityType.USER_ACTION, title, detail,
            data = buildJsonObject {
                put("routineId", routine.id.value)
                put("enabled", routine.enabled)
            },
        )
    }

    /** Texts. */
    companion object {
        const val TOO_MANY: String = "You can have at most ${RoutineLimits.MAX_ROUTINES} routines. Delete one first."
        const val MISSING: String = "This routine no longer exists."
    }
}
