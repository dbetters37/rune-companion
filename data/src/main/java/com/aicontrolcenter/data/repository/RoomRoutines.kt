package com.aicontrolcenter.data.repository

import com.aicontrolcenter.core.PickUpRuleId
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.RoutineRunId
import com.aicontrolcenter.core.pickup.PickUpRule
import com.aicontrolcenter.core.pickup.PickUpRuleRepository
import com.aicontrolcenter.core.pickup.PickedUpFile
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.routines.RoutineRepository
import com.aicontrolcenter.core.routines.RoutineRun
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.dao.PickUpDao
import com.aicontrolcenter.data.db.dao.RecordImport
import com.aicontrolcenter.data.db.dao.RoutineDao
import com.aicontrolcenter.data.db.entity.PickUpImportEntity
import com.aicontrolcenter.data.db.entity.PickUpRuleEntity
import com.aicontrolcenter.data.db.entity.RoutineEntity
import com.aicontrolcenter.data.db.entity.RoutineRunEntity
import com.aicontrolcenter.data.db.mapper.mapReadable
import com.aicontrolcenter.data.db.mapper.readableOrNull
import com.aicontrolcenter.data.db.mapper.toDomain
import com.aicontrolcenter.data.db.mapper.toEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [RoutineRepository] on Room (schema 6, owner decision Q1, Appendix A). It behaves like the
 * reference `InMemoryRoutineRepository` of `:testkit` and runs the same contract suite.
 *
 * Owner-capable: it can create and change routines, so only the owner's port and the routine
 * runner (WP17) hold it; both architecture tests keep it away from everything an agent reaches.
 *
 * - Routines are listed oldest first (created time, then id); a routine's runs newest first
 *   (due time, then id, both descending), from an index.
 * - [update] and [updateRun] are atomic: read, transform and write in one write transaction.
 * - A routine and its runs are deleted together; a run is stored only while its routine exists,
 *   checked in the transaction that stores it, and a repeated run id is ignored.
 * - A row that cannot be decoded is treated as absent, like unreadable rows everywhere in this
 *   module: a routine that cannot be read is never run.
 *
 * All work, including JSON, runs on [dispatcher].
 */
class RoomRoutineRepository internal constructor(
    private val dao: RoutineDao,
    private val dispatcher: CoroutineDispatcher,
) : RoutineRepository {

    constructor(database: AiccDatabase, dispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(database.routineDao(), dispatcher)

    override fun observeAll(): Flow<List<Routine>> =
        dao.observeAll().map { rows -> rows.mapReadable(RoutineEntity::toDomain) }.distinctUntilChanged().flowOn(dispatcher)

    override suspend fun getAll(): List<Routine> = withContext(dispatcher) { dao.all().mapReadable(RoutineEntity::toDomain) }

    override suspend fun get(id: RoutineId): Routine? = withContext(dispatcher) { dao.get(id.value)?.readableOrNull(RoutineEntity::toDomain) }

    override suspend fun upsert(routine: Routine) = withContext(dispatcher) { dao.upsert(routine.toEntity()) }

    /**
     * Atomic; [transform] runs inside the write transaction and must be quick and free of side
     * effects. A stored row that cannot be read counts as missing.
     *
     * @throws IllegalArgumentException, writing nothing, if [transform] changes the id.
     */
    override suspend fun update(id: RoutineId, transform: (Routine) -> Routine): Routine? = withContext(dispatcher) {
        var updated: Routine? = null
        val written = dao.update(id.value) { stored ->
            val current = stored.readableOrNull(RoutineEntity::toDomain) ?: return@update null
            val changed = transform(current)
            require(changed.id == id) { "A routine's id cannot change (routine $id)." }
            changed.toEntity().also { updated = changed }
        }
        if (written != null) updated else null
    }

    override suspend fun delete(id: RoutineId) = withContext(dispatcher) { dao.delete(id.value) }

    /** @throws IllegalArgumentException, storing nothing, when the run's routine does not exist. */
    override suspend fun appendRun(run: RoutineRun) {
        val stored = withContext(dispatcher) { dao.appendRun(run.toEntity()) }
        require(stored) { "No such routine: ${run.routineId}" }
    }

    /** @throws IllegalArgumentException, writing nothing, if [transform] changes the run's id or routine. */
    override suspend fun updateRun(id: RoutineRunId, transform: (RoutineRun) -> RoutineRun): RoutineRun? = withContext(dispatcher) {
        var updated: RoutineRun? = null
        val written = dao.updateRun(id.value) { stored ->
            val current = stored.readableOrNull(RoutineRunEntity::toDomain) ?: return@updateRun null
            val changed = transform(current)
            require(changed.id == id && changed.routineId == current.routineId) { "A run's id and routine cannot change (run $id)." }
            changed.toEntity().also { updated = changed }
        }
        if (written != null) updated else null
    }

    override fun observeRuns(routineId: RoutineId, limit: Int): Flow<List<RoutineRun>> =
        dao.observeRuns(routineId.value, limit.coerceAtLeast(0)).map { rows -> rows.mapReadable(RoutineRunEntity::toDomain) }
            .distinctUntilChanged()
            .flowOn(dispatcher)

    override suspend fun runs(routineId: RoutineId, limit: Int): List<RoutineRun> = withContext(dispatcher) {
        dao.runs(routineId.value, limit.coerceAtLeast(0)).mapReadable(RoutineRunEntity::toDomain)
    }

    override suspend fun pruneRuns(beforeMs: Long): Int = withContext(dispatcher) { dao.pruneRuns(beforeMs) }
}

/**
 * [PickUpRuleRepository] on Room (schema 6, owner decision Q1, Appendix A). It behaves like the
 * reference `InMemoryPickUpRuleRepository` of `:testkit` and runs the same contract suite.
 *
 * Owner-capable: only the owner's port and the pick-up runner (WP17) hold it; both architecture
 * tests keep it away from everything an agent reaches.
 *
 * - One rule per folder: checked in the transaction that writes the rule, with a unique index as
 *   the backstop.
 * - Each content is recorded once per rule: [recordImport] is one `INSERT OR IGNORE` on the
 *   primary key `(rule_id, content_sha256)` inside the transaction that checks the rule exists,
 *   so of two pick-ups racing with the same content exactly one records it.
 * - A rule goes together with its import records; the memories they became stay.
 */
class RoomPickUpRuleRepository internal constructor(
    private val dao: PickUpDao,
    private val dispatcher: CoroutineDispatcher,
) : PickUpRuleRepository {

    constructor(database: AiccDatabase, dispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(database.pickUpDao(), dispatcher)

    override fun observeAll(): Flow<List<PickUpRule>> =
        dao.observeAll().map { rows -> rows.map(PickUpRuleEntity::toDomain) }.distinctUntilChanged().flowOn(dispatcher)

    override suspend fun getAll(): List<PickUpRule> = withContext(dispatcher) { dao.all().map(PickUpRuleEntity::toDomain) }

    override suspend fun get(id: PickUpRuleId): PickUpRule? = withContext(dispatcher) { dao.get(id.value)?.toDomain() }

    /** @throws IllegalArgumentException, storing nothing, when another rule has the same folder. */
    override suspend fun upsert(rule: PickUpRule) {
        val stored = withContext(dispatcher) { dao.upsert(rule.toEntity()) }
        require(stored) { "That folder already has a pick-up rule." }
    }

    /** @throws IllegalArgumentException, writing nothing, if [transform] changes the id or takes another rule's folder. */
    override suspend fun update(id: PickUpRuleId, transform: (PickUpRule) -> PickUpRule): PickUpRule? = withContext(dispatcher) {
        var updated: PickUpRule? = null
        val written = dao.update(id.value) { stored ->
            val changed = transform(stored.toDomain())
            require(changed.id == id) { "A rule's id cannot change." }
            changed.toEntity().also { updated = changed }
        }
        if (written != null) updated else null
    }

    override suspend fun delete(id: PickUpRuleId) = withContext(dispatcher) { dao.delete(id.value) }

    override suspend fun wasImported(ruleId: PickUpRuleId, contentSha256: String): Boolean = withContext(dispatcher) {
        dao.wasImported(ruleId.value, contentSha256)
    }

    /** @throws IllegalArgumentException, recording nothing, when the rule does not exist. */
    override suspend fun recordImport(file: PickedUpFile): Boolean {
        val outcome = withContext(dispatcher) { dao.recordImport(file.toEntity()) }
        require(outcome != RecordImport.NO_RULE) { "No such rule: ${file.ruleId}" }
        return outcome == RecordImport.RECORDED
    }

    override suspend fun bytesImportedSince(fromMs: Long): Long = withContext(dispatcher) { dao.bytesImportedSince(fromMs) }

    override fun observeImports(ruleId: PickUpRuleId, limit: Int): Flow<List<PickedUpFile>> =
        dao.observeImports(ruleId.value, limit.coerceAtLeast(0)).map { rows -> rows.mapReadable(PickUpImportEntity::toDomain) }
            .distinctUntilChanged()
            .flowOn(dispatcher)
}
