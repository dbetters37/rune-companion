package com.aicontrolcenter.testkit

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.PickUpRuleId
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.RoutineRunId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.pickUpRuleId
import com.aicontrolcenter.core.pickup.OwnerPickUpRules
import com.aicontrolcenter.core.pickup.PickUpLimits
import com.aicontrolcenter.core.pickup.PickUpRule
import com.aicontrolcenter.core.pickup.PickUpRuleRepository
import com.aicontrolcenter.core.pickup.PickedUpFile
import com.aicontrolcenter.core.routineId
import com.aicontrolcenter.core.routines.OwnerRoutines
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.routines.RoutineDraft
import com.aicontrolcenter.core.routines.RoutineLimits
import com.aicontrolcenter.core.routines.RoutineRepository
import com.aicontrolcenter.core.routines.RoutineRun
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/** Reference implementation of [RoutineRepository]. */
class InMemoryRoutineRepository : RoutineRepository {
    private data class State(val routines: Map<RoutineId, Routine> = emptyMap(), val runs: Map<RoutineRunId, RoutineRun> = emptyMap())

    private val state = MutableStateFlow(State())
    private val lock = Any()

    private fun State.ordered() = routines.values.sortedWith(compareBy({ it.createdAt }, { it.id.value }))

    private fun State.runsOf(id: RoutineId, limit: Int) = runs.values.filter { it.routineId == id }
        .sortedWith(compareByDescending<RoutineRun> { it.dueAt }.thenByDescending { it.id.value }).take(limit.coerceAtLeast(0))

    override fun observeAll(): Flow<List<Routine>> = state.map { it.ordered() }.distinctUntilChanged()

    override suspend fun getAll(): List<Routine> = state.value.ordered()

    override suspend fun get(id: RoutineId): Routine? = state.value.routines[id]

    override suspend fun upsert(routine: Routine) = synchronized(lock) { state.update { it.copy(routines = it.routines + (routine.id to routine)) } }

    override suspend fun update(id: RoutineId, transform: (Routine) -> Routine): Routine? = synchronized(lock) {
        val current = state.value.routines[id] ?: return null
        val changed = transform(current)
        require(changed.id == id) { "A routine's id cannot change." }
        state.update { it.copy(routines = it.routines + (id to changed)) }
        changed
    }

    override suspend fun delete(id: RoutineId) = synchronized(lock) {
        state.update { current -> State(current.routines - id, current.runs.filterValues { it.routineId != id }) }
    }

    override suspend fun appendRun(run: RoutineRun) = synchronized(lock) {
        require(run.routineId in state.value.routines) { "No such routine: ${run.routineId}" }
        state.update { if (run.id in it.runs) it else it.copy(runs = it.runs + (run.id to run)) }
    }

    override suspend fun updateRun(id: RoutineRunId, transform: (RoutineRun) -> RoutineRun): RoutineRun? = synchronized(lock) {
        val current = state.value.runs[id] ?: return null
        val changed = transform(current)
        require(changed.id == id && changed.routineId == current.routineId) { "A run's id and routine cannot change." }
        state.update { it.copy(runs = it.runs + (id to changed)) }
        changed
    }

    override fun observeRuns(routineId: RoutineId, limit: Int): Flow<List<RoutineRun>> = state.map { it.runsOf(routineId, limit) }.distinctUntilChanged()

    override suspend fun runs(routineId: RoutineId, limit: Int): List<RoutineRun> = state.value.runsOf(routineId, limit)

    override suspend fun pruneRuns(beforeMs: Long): Int {
        var removed = 0
        state.update { current ->
            val kept = current.runs.filterValues { it.dueAt >= beforeMs }
            removed = current.runs.size - kept.size
            current.copy(runs = kept)
        }
        return removed
    }
}

/**
 * Reference implementation of [OwnerRoutines] over a [RoutineRepository]: the rules of Appendix A
 * that the owner's side applies. [zone] is the owner's time zone. Changes are serialised so the
 * routine cap cannot be passed by two creations at once.
 */
class ReferenceOwnerRoutines(
    private val repository: RoutineRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : OwnerRoutines {
    private val mutex = Mutex()

    override fun observeAll(): Flow<List<Routine>> = repository.observeAll()

    override suspend fun create(draft: RoutineDraft): OwnerResult<Routine> = mutex.withLock {
        draft.problem()?.let { return OwnerResult.Refused(it) }
        if (repository.getAll().size >= RoutineLimits.MAX_ROUTINES) {
            return OwnerResult.Refused("You can have at most ${RoutineLimits.MAX_ROUTINES} routines. Delete one first.")
        }
        val now = clock.now()
        val routine = Routine(
            id = ids.routineId(), title = draft.title, objectiveText = draft.objectiveText, options = draft.options,
            schedule = draft.schedule, enabled = true, approvalTimeoutMinutes = draft.approvalTimeoutMinutes,
            allowScreenActions = draft.allowScreenActions, createdAt = now, updatedAt = now,
            nextRunAt = draft.schedule.nextAfter(now, zone()),
        )
        repository.upsert(routine)
        OwnerResult.Done(routine)
    }

    override suspend fun change(id: RoutineId, draft: RoutineDraft): OwnerResult<Routine> = mutex.withLock {
        draft.problem()?.let { return OwnerResult.Refused(it) }
        val now = clock.now()
        val changed = repository.update(id) { current ->
            current.copy(
                title = draft.title, objectiveText = draft.objectiveText, options = draft.options, schedule = draft.schedule,
                approvalTimeoutMinutes = draft.approvalTimeoutMinutes, allowScreenActions = draft.allowScreenActions,
                updatedAt = now, nextRunAt = if (current.enabled) draft.schedule.nextAfter(now, zone()) else null,
            )
        } ?: return OwnerResult.Refused(MISSING)
        OwnerResult.Done(changed)
    }

    override suspend fun setEnabled(id: RoutineId, enabled: Boolean): OwnerResult<Routine> = mutex.withLock {
        val now = clock.now()
        val changed = repository.update(id) { current ->
            if (enabled) {
                current.copy(enabled = true, nextRunAt = current.schedule.nextAfter(now, zone()), consecutiveFailures = 0, pausedNote = null, updatedAt = now)
            } else {
                current.copy(enabled = false, nextRunAt = null, updatedAt = now)
            }
        } ?: return OwnerResult.Refused(MISSING)
        OwnerResult.Done(changed)
    }

    override suspend fun delete(id: RoutineId): Boolean = mutex.withLock {
        val existed = repository.get(id) != null
        repository.delete(id)
        existed
    }

    override fun observeRuns(id: RoutineId, limit: Int): Flow<List<RoutineRun>> = repository.observeRuns(id, limit)

    private companion object {
        const val MISSING = "This routine no longer exists."
    }
}

/** Reference implementation of [PickUpRuleRepository]. */
class InMemoryPickUpRuleRepository : PickUpRuleRepository {
    private data class State(val rules: Map<PickUpRuleId, PickUpRule> = emptyMap(), val files: List<PickedUpFile> = emptyList())

    private val state = MutableStateFlow(State())
    private val lock = Any()

    private fun State.ordered() = rules.values.sortedWith(compareBy({ it.createdAt }, { it.id.value }))

    override fun observeAll(): Flow<List<PickUpRule>> = state.map { it.ordered() }.distinctUntilChanged()

    override suspend fun getAll(): List<PickUpRule> = state.value.ordered()

    override suspend fun get(id: PickUpRuleId): PickUpRule? = state.value.rules[id]

    override suspend fun upsert(rule: PickUpRule) = synchronized(lock) {
        require(state.value.rules.values.none { it.folderId == rule.folderId && it.id != rule.id }) { "That folder already has a pick-up rule." }
        state.update { it.copy(rules = it.rules + (rule.id to rule)) }
    }

    override suspend fun update(id: PickUpRuleId, transform: (PickUpRule) -> PickUpRule): PickUpRule? = synchronized(lock) {
        val current = state.value.rules[id] ?: return null
        val changed = transform(current)
        require(changed.id == id) { "A rule's id cannot change." }
        require(state.value.rules.values.none { it.folderId == changed.folderId && it.id != id }) { "That folder already has a pick-up rule." }
        state.update { it.copy(rules = it.rules + (id to changed)) }
        changed
    }

    override suspend fun delete(id: PickUpRuleId) = synchronized(lock) {
        state.update { current -> State(current.rules - id, current.files.filter { it.ruleId != id }) }
    }

    override suspend fun wasImported(ruleId: PickUpRuleId, contentSha256: String): Boolean =
        state.value.files.any { it.ruleId == ruleId && it.contentSha256 == contentSha256 }

    override suspend fun recordImport(file: PickedUpFile): Boolean = synchronized(lock) {
        require(file.ruleId in state.value.rules) { "No such rule: ${file.ruleId}" }
        if (state.value.files.any { it.ruleId == file.ruleId && it.contentSha256 == file.contentSha256 }) return false
        state.update { it.copy(files = it.files + file) }
        true
    }

    override suspend fun bytesImportedSince(fromMs: Long): Long = state.value.files.filter { it.importedAt >= fromMs }.sumOf { it.sizeBytes }

    override fun observeImports(ruleId: PickUpRuleId, limit: Int): Flow<List<PickedUpFile>> = state.map { current ->
        current.files.filter { it.ruleId == ruleId }
            .sortedWith(compareByDescending<PickedUpFile> { it.importedAt }.thenBy { it.contentSha256 }).take(limit.coerceAtLeast(0))
    }.distinctUntilChanged()
}

/**
 * Reference implementation of [OwnerPickUpRules] over a [PickUpRuleRepository], checking folders
 * against [folders] and spaces against [memory]. Changes are serialised so the rule cap and the
 * one-rule-per-folder rule cannot be passed by two creations at once.
 */
class ReferenceOwnerPickUpRules(
    private val repository: PickUpRuleRepository,
    private val folders: SharedFolderRepository,
    private val memory: MemoryRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
) : OwnerPickUpRules {
    private val mutex = Mutex()

    override fun observeAll(): Flow<List<PickUpRule>> = repository.observeAll()

    override suspend fun create(folderId: FolderId, spaceId: SpaceId): OwnerResult<PickUpRule> = mutex.withLock {
        if (folders.get(folderId) == null) return OwnerResult.Refused(NO_FOLDER)
        if (memory.getSpace(spaceId) == null) return OwnerResult.Refused(NO_SPACE)
        val rules = repository.getAll()
        if (rules.any { it.folderId == folderId }) return OwnerResult.Refused("This folder already has a pick-up rule.")
        if (rules.size >= PickUpLimits.MAX_RULES) return OwnerResult.Refused("You can have at most ${PickUpLimits.MAX_RULES} pick-up rules. Delete one first.")
        val now = clock.now()
        val rule = PickUpRule(id = ids.pickUpRuleId(), folderId = folderId, spaceId = spaceId, enabled = true, createdAt = now, updatedAt = now)
        repository.upsert(rule)
        OwnerResult.Done(rule)
    }

    override suspend fun changeSpace(id: PickUpRuleId, spaceId: SpaceId): OwnerResult<PickUpRule> = mutex.withLock {
        if (memory.getSpace(spaceId) == null) return OwnerResult.Refused(NO_SPACE)
        val now = clock.now()
        repository.update(id) { it.copy(spaceId = spaceId, updatedAt = now) }?.let { OwnerResult.Done(it) } ?: OwnerResult.Refused(MISSING)
    }

    override suspend fun setEnabled(id: PickUpRuleId, enabled: Boolean): OwnerResult<PickUpRule> = mutex.withLock {
        val now = clock.now()
        repository.update(id) { it.copy(enabled = enabled, updatedAt = now) }?.let { OwnerResult.Done(it) } ?: OwnerResult.Refused(MISSING)
    }

    override suspend fun delete(id: PickUpRuleId): Boolean = mutex.withLock {
        val existed = repository.get(id) != null
        repository.delete(id)
        existed
    }

    private companion object {
        const val NO_FOLDER = "That shared folder is no longer there. Add it again in Tools > Folders."
        const val NO_SPACE = "That memory space is no longer there."
        const val MISSING = "This pick-up rule no longer exists."
    }
}
