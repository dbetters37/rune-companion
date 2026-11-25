package com.aicontrolcenter.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.aicontrolcenter.core.tasks.TaskSummary
import com.aicontrolcenter.data.db.entity.ObjectivePageRow
import com.aicontrolcenter.data.db.entity.ParticipantRow
import com.aicontrolcenter.data.db.entity.ProgressRow
import com.aicontrolcenter.data.db.entity.TaskEntity
import com.aicontrolcenter.data.db.entity.TaskSummaryRow
import kotlinx.coroutines.flow.Flow

/**
 * Tasks. Rows are inserted or updated in place (`@Upsert`), never deleted and re-inserted.
 *
 * The orderings here are the contract's: objectives newest first, everything else by creation
 * time; equal times are ordered by id so every listing is stable. Each query has an index
 * that matches its filter and order (see [TaskEntity]).
 */
@Dao
internal interface TaskDao {
    @Query(OBJECTIVES)
    fun observeObjectives(): Flow<List<TaskEntity>>

    @Query(TREE)
    fun observeTree(rootId: String): Flow<List<TaskEntity>>

    @Query(TREE)
    suspend fun tree(rootId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    /** [statuses] must not be empty; there are only ten, so the list is never long. */
    @Query(WITH_STATUS)
    suspend fun withStatus(statuses: List<String>): List<TaskEntity>

    @Upsert
    suspend fun upsert(task: TaskEntity)

    /** All or nothing: a plan of several tasks is never half stored. */
    @Transaction
    suspend fun upsertAll(tasks: List<TaskEntity>) {
        tasks.forEach { upsert(it) }
    }

    /**
     * Reads the task, lets [transform] compute its new row and writes that, all inside one
     * write transaction, so concurrent updates queue up and none is lost. [transform] returns
     * null to write nothing (the stored row cannot be read); if it throws, nothing is written.
     *
     * @return the row written, or null when no task has this id or nothing was written.
     */
    @Transaction
    suspend fun update(id: String, transform: (TaskEntity) -> TaskEntity?): TaskEntity? {
        val stored = get(id) ?: return null
        val changed = transform(stored) ?: return null
        upsert(changed)
        return changed
    }

    @Query("DELETE FROM tasks WHERE root_id = :rootId")
    suspend fun deleteTaskRows(rootId: String)

    /** The cost rows of the tree's tasks (schema 6), found through the tree's index. */
    @Query("DELETE FROM work_usage WHERE work_kind = 'TASK' AND work_id IN (SELECT id FROM tasks WHERE root_id = :rootId)")
    suspend fun deleteTaskWorkUsage(rootId: String)

    @Query("DELETE FROM work_usage WHERE work_kind = 'OBJECTIVE' AND work_id = :rootId")
    suspend fun deleteObjectiveWorkUsage(rootId: String)

    /**
     * Removes the objective [rootId], every task under it, and what their model calls cost
     * (their `work_usage` rows), in one transaction: either all of it goes or none of it. The
     * hourly usage stays: it counts calls, not tasks.
     */
    @Transaction
    suspend fun deleteTree(rootId: String) {
        deleteTaskWorkUsage(rootId)
        deleteObjectiveWorkUsage(rootId)
        deleteTaskRows(rootId)
    }

    // --- Summaries for lists (schema 6, rule 16): projections and counts, never whole rows. ---

    @Query(SUMMARIES)
    fun observeSummaries(rootId: String): Flow<List<TaskSummaryRow>>

    @Query(OBJECTIVE_ROWS)
    fun observeObjectivePage(limit: Int): Flow<List<ObjectivePageRow>>

    @Query(OBJECTIVE_ROWS_AFTER)
    fun observeObjectivePageAfter(createdAt: Long, id: String, limit: Int): Flow<List<ObjectivePageRow>>

    /** [rootIds] holds at most [ID_CHUNK] ids (one page of objectives). */
    @Query(PARTICIPANTS)
    suspend fun participants(rootIds: List<String>): List<ParticipantRow>

    @Query(RECENT_BY_AGENT)
    fun observeRecentByAgent(agentId: String, limit: Int): Flow<List<TaskSummaryRow>>

    /** [rootIds] holds at most [ID_CHUNK] ids. */
    @Query(PROGRESS)
    fun observeProgress(rootIds: List<String>): Flow<List<ProgressRow>>

    /** The listing queries, as constants so a test can check that SQLite runs them from their indexes. */
    companion object {
        /** `OBJECTIVE` is the stored name of `TaskKind.OBJECTIVE`, pinned by `StoredNamesGoldenTest`. */
        const val OBJECTIVES = "SELECT * FROM tasks WHERE kind = 'OBJECTIVE' ORDER BY created_at DESC, id ASC"
        const val TREE = "SELECT * FROM tasks WHERE root_id = :rootId ORDER BY created_at ASC, id ASC"
        const val WITH_STATUS = "SELECT * FROM tasks WHERE status IN (:statuses) ORDER BY created_at ASC, id ASC"

        /** Most ids one `IN (…)` of the summary queries takes; below SQLite's 999 bound variables. */
        const val ID_CHUNK = 500

        /** One objective's tasks as summaries, in the order of [TREE]. */
        const val SUMMARIES = "SELECT ${TaskSummaryRow.COLUMNS} FROM tasks WHERE root_id = :rootId ORDER BY created_at ASC, id ASC"

        /**
         * A step of the Director's plan (`PlanSteps.isStep`) of the objective `o`, as a condition
         * on the task `s`: a WORK or SYNTHESIS task directly under the objective. The stored
         * names are pinned by `StoredNamesGoldenTest`.
         */
        const val IS_STEP_OF_O = "s.root_id = o.id AND s.parent_id = o.id AND s.id <> o.id AND s.kind IN ('WORK', 'SYNTHESIS')"

        private const val OBJECTIVE_ROW_COLUMNS =
            "o.id AS id, o.title AS title, o.status AS status, " +
                "substr(COALESCE(o.status_detail, o.error), 1, ${TaskSummary.DETAIL_MAX_CHARS}) AS status_detail_short, " +
                "o.assignee_id AS assignee_id, o.created_at AS created_at, o.updated_at AS updated_at, o.finished_at AS finished_at, " +
                "(SELECT COUNT(*) FROM tasks AS s WHERE $IS_STEP_OF_O) AS planned, " +
                "(SELECT COUNT(*) FROM tasks AS s WHERE $IS_STEP_OF_O AND s.status = 'COMPLETED') AS completed"

        /** The newest objectives as rows, in the order of [OBJECTIVES], from the same index. */
        const val OBJECTIVE_ROWS = "SELECT $OBJECTIVE_ROW_COLUMNS FROM tasks AS o WHERE o.kind = 'OBJECTIVE' AND o.root_id = o.id " +
            "ORDER BY o.created_at DESC, o.id ASC LIMIT :limit"

        /** The next page after the objective created at `:createdAt` with `:id` (`ObjectiveCursor`). */
        const val OBJECTIVE_ROWS_AFTER = "SELECT $OBJECTIVE_ROW_COLUMNS FROM tasks AS o WHERE o.kind = 'OBJECTIVE' AND o.root_id = o.id " +
            "AND o.created_at <= :createdAt AND (o.created_at < :createdAt OR o.id > :id) ORDER BY o.created_at DESC, o.id ASC LIMIT :limit"

        /** Who works on the objectives of one page, in task creation order. */
        const val PARTICIPANTS = "SELECT root_id, assignee_id FROM tasks WHERE root_id IN (:rootIds) AND assignee_id IS NOT NULL " +
            "ORDER BY root_id ASC, created_at ASC, id ASC"

        /** One agent's most recently updated tasks, from the index `(assignee_id, updated_at DESC, id ASC)`. */
        const val RECENT_BY_AGENT = "SELECT ${TaskSummaryRow.COLUMNS} FROM tasks WHERE assignee_id = :agentId " +
            "ORDER BY updated_at DESC, id ASC LIMIT :limit"

        /**
         * How far each objective's plan has got (`PlanProgress`), counted by SQLite. Waiting is
         * `TaskStatus.isWaiting`; the stored names are pinned by `StoredNamesGoldenTest`.
         */
        const val PROGRESS = "SELECT o.id AS root_id, COUNT(s.id) AS total, " +
            "COALESCE(SUM(s.status = 'COMPLETED'), 0) AS done, " +
            "COALESCE(SUM(s.status IN ('RUNNING', 'IN_REVIEW')), 0) AS running, " +
            "COALESCE(SUM(s.status IN ('WAITING_FOR_AGENT', 'WAITING_FOR_OWNER')), 0) AS waiting, " +
            "COALESCE(SUM(s.status IN ('FAILED', 'TIMED_OUT')), 0) AS failed " +
            "FROM tasks AS o LEFT JOIN tasks AS s ON $IS_STEP_OF_O " +
            "WHERE o.id IN (:rootIds) AND o.kind = 'OBJECTIVE' AND o.root_id = o.id GROUP BY o.id"
    }
}
