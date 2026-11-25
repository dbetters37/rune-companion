package com.aicontrolcenter.data.repository

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.ObjectiveCursor
import com.aicontrolcenter.core.tasks.ObjectiveRow
import com.aicontrolcenter.core.tasks.PlanProgress
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tasks.TaskSummary
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.dao.TaskDao
import com.aicontrolcenter.data.db.entity.ObjectivePageRow
import com.aicontrolcenter.data.db.entity.ProgressRow
import com.aicontrolcenter.data.db.entity.TaskEntity
import com.aicontrolcenter.data.db.entity.TaskSummaryRow
import com.aicontrolcenter.data.db.mapper.mapReadable
import com.aicontrolcenter.data.db.mapper.readableOrNull
import com.aicontrolcenter.data.db.mapper.storedTextBytes
import com.aicontrolcenter.data.db.mapper.toDomain
import com.aicontrolcenter.data.db.mapper.toEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [TaskRepository] on Room, in the app's (encrypted) database. It behaves like the reference
 * `InMemoryTaskRepository` of `:testkit` and runs the same contract suite.
 *
 * This is what makes orchestration crash-safe: the orchestrator writes every state change here
 * before acting on it, and after a restart finds what was running with [withStatus].
 *
 * - Objectives are listed newest first; a tree, and tasks found by status, oldest first. Equal
 *   times are ordered by id. SQLite does the filtering and ordering, from an index.
 * - [update] is atomic: read, transform and write happen in one write transaction.
 * - [upsertAll] stores a whole plan or nothing; [deleteTree] removes a whole objective or nothing.
 * - A row that cannot be decoded is treated as absent and reported without its content, like
 *   unreadable rows everywhere in this module. It stays stored until it is written again or
 *   its objective is deleted.
 * - Lists read summaries (schema 6, rule 16): [observeSummaries], [observeObjectiveRows],
 *   [observeRecentByAgent] and [observeProgress] are projections and counts made by SQLite from
 *   the indexes; none reads a task's instructions, result, reviews or usage. They give exactly
 *   what the defaults build from whole records (`TaskSummaryContract`).
 * - Deleting a tree also deletes what its model calls cost (`work_usage`), in the same transaction.
 * - No message thrown or logged here contains a task's text; ids and sizes are named.
 *
 * All work, including JSON, runs on [dispatcher].
 */
class RoomTaskRepository internal constructor(
    private val dao: TaskDao,
    private val dispatcher: CoroutineDispatcher,
) : TaskRepository {

    constructor(database: AiccDatabase, dispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(database.taskDao(), dispatcher)

    override fun observeObjectives(): Flow<List<TaskRecord>> =
        dao.observeObjectives().map { rows -> rows.mapReadable(TaskEntity::toDomain) }.distinctUntilChanged().flowOn(dispatcher)

    override fun observeTree(rootId: TaskId): Flow<List<TaskRecord>> =
        dao.observeTree(rootId.value).map { rows -> rows.mapReadable(TaskEntity::toDomain) }.distinctUntilChanged().flowOn(dispatcher)

    override suspend fun get(id: TaskId): TaskRecord? = withContext(dispatcher) {
        dao.get(id.value)?.readableOrNull(TaskEntity::toDomain)
    }

    override suspend fun getTree(rootId: TaskId): List<TaskRecord> = withContext(dispatcher) {
        dao.tree(rootId.value).mapReadable(TaskEntity::toDomain)
    }

    /** @throws IllegalArgumentException, storing nothing, if the task holds more than [MAX_TEXT_BYTES] of text. */
    override suspend fun upsert(task: TaskRecord) = withContext(dispatcher) {
        dao.upsert(task.toStorableEntity())
    }

    /** @throws IllegalArgumentException, storing none of them, if a task holds more than [MAX_TEXT_BYTES] of text. */
    override suspend fun upsertAll(tasks: List<TaskRecord>) {
        if (tasks.isEmpty()) return
        withContext(dispatcher) { dao.upsertAll(tasks.map { it.toStorableEntity() }) }
    }

    /**
     * Atomic: the stored task is read, [transform]ed and written inside one write transaction,
     * so concurrent updates are applied one after another and none is lost. [transform] runs
     * inside that transaction, on the database's thread, and must be quick and free of side
     * effects. A stored row that cannot be read counts as missing: [transform] is not called
     * and nothing is written.
     *
     * @return the task as written, or null when there is no (readable) task with this id.
     * @throws IllegalArgumentException, writing nothing, if [transform] changes the id or the
     *   result holds more than [MAX_TEXT_BYTES] of text. Whatever [transform] throws is
     *   rethrown, and nothing is written then either.
     */
    override suspend fun update(id: TaskId, transform: (TaskRecord) -> TaskRecord): TaskRecord? = withContext(dispatcher) {
        var updated: TaskRecord? = null
        val written = dao.update(id.value) { stored ->
            val current = stored.readableOrNull(TaskEntity::toDomain) ?: return@update null
            val changed = transform(current)
            require(changed.id == id) { "A task's id cannot change (task $id)." }
            changed.toStorableEntity().also { updated = changed }
        }
        if (written != null) updated else null
    }

    override suspend fun withStatus(statuses: Set<TaskStatus>): List<TaskRecord> {
        if (statuses.isEmpty()) return emptyList()
        return withContext(dispatcher) { dao.withStatus(statuses.map { it.name }).mapReadable(TaskEntity::toDomain) }
    }

    override suspend fun deleteTree(rootId: TaskId) = withContext(dispatcher) { dao.deleteTree(rootId.value) }

    override fun observeSummaries(rootId: TaskId): Flow<List<TaskSummary>> =
        dao.observeSummaries(rootId.value).map { rows -> rows.mapReadable(TaskSummaryRow::toDomain) }.distinctUntilChanged().flowOn(dispatcher)

    /** @throws IllegalArgumentException if [limit] is more than [TaskDao.ID_CHUNK] (a page, not the whole list). */
    override fun observeObjectiveRows(limit: Int, olderThan: ObjectiveCursor?): Flow<List<ObjectiveRow>> {
        val size = limit.coerceAtLeast(0)
        require(size <= TaskDao.ID_CHUNK) { "At most ${TaskDao.ID_CHUNK} objectives a page, not $limit." }
        if (size == 0) return flowOf(emptyList())
        val page = if (olderThan == null) dao.observeObjectivePage(size) else dao.observeObjectivePageAfter(olderThan.createdAt, olderThan.id.value, size)
        return page.map { rows -> rows.withParticipants() }.distinctUntilChanged().flowOn(dispatcher)
    }

    /** The rows of one page with their participants, read by one more query over the page's objectives only. */
    private suspend fun List<ObjectivePageRow>.withParticipants(): List<ObjectiveRow> {
        if (isEmpty()) return emptyList()
        val byObjective = dao.participants(map { it.id }).groupBy({ it.rootId }, { it.assigneeId })
        return mapReadable { row -> row.toDomain(byObjective[row.id].orEmpty()) }
    }

    override fun observeRecentByAgent(agentId: AgentId, limit: Int): Flow<List<TaskSummary>> =
        dao.observeRecentByAgent(agentId.value, limit.coerceAtLeast(0))
            .map { rows -> rows.mapReadable(TaskSummaryRow::toDomain) }
            .distinctUntilChanged()
            .flowOn(dispatcher)

    /** More objectives than [TaskDao.ID_CHUNK] at once are counted by the default, from their trees. */
    override fun observeProgress(rootIds: Set<TaskId>): Flow<Map<TaskId, PlanProgress>> {
        if (rootIds.isEmpty()) return flowOf(emptyMap())
        if (rootIds.size > TaskDao.ID_CHUNK) return super.observeProgress(rootIds)
        return dao.observeProgress(rootIds.map { it.value })
            .map { rows -> rows.associate { row -> TaskId(row.rootId) to row.toDomain() } }
            .distinctUntilChanged()
            .flowOn(dispatcher)
    }

    private fun TaskRecord.toStorableEntity(): TaskEntity = toEntity().also { entity ->
        val textBytes = entity.storedTextBytes()
        require(textBytes <= MAX_TEXT_BYTES) {
            "Task $id holds $textBytes bytes of text; at most $MAX_TEXT_BYTES can be stored."
        }
    }

    companion object {
        /**
         * Most UTF-8 bytes of text (title, instructions, result, reviews and the other text and
         * JSON columns together) one task may hold. A larger row could not be read back through
         * Android's cursor window of about 2 MB, and would break every list it belongs to, the
         * crash recovery included. Half the window leaves room for the other columns; a final
         * document is far smaller than this.
         */
        const val MAX_TEXT_BYTES = 1024 * 1024
    }
}
