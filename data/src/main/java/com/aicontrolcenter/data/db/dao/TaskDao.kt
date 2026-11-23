package com.aicontrolcenter.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.aicontrolcenter.data.db.entity.TaskEntity
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

    /**
     * Removes the objective [rootId] and every task under it. A single statement, which SQLite
     * runs as one transaction: either the whole tree goes or none of it.
     */
    @Query("DELETE FROM tasks WHERE root_id = :rootId")
    suspend fun deleteTree(rootId: String)

    /** The listing queries, as constants so a test can check that SQLite runs them from their indexes. */
    companion object {
        /** `OBJECTIVE` is the stored name of `TaskKind.OBJECTIVE`, pinned by `StoredNamesGoldenTest`. */
        const val OBJECTIVES = "SELECT * FROM tasks WHERE kind = 'OBJECTIVE' ORDER BY created_at DESC, id ASC"
        const val TREE = "SELECT * FROM tasks WHERE root_id = :rootId ORDER BY created_at ASC, id ASC"
        const val WITH_STATUS = "SELECT * FROM tasks WHERE status IN (:statuses) ORDER BY created_at ASC, id ASC"
    }
}
