package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per task (`TaskRecord`): an objective, a piece of work, a review or a synthesis.
 *
 * - What is filtered, ordered or joined on is a plain column: ids, kind, status, assignee,
 *   position among siblings, the times and the counters. Kind and status are enum names.
 * - Nested values are JSON (see `DataJson`): completion criteria, dependencies, delegation
 *   chain, reviews, usage, objective options and who requested the task. None of them is ever
 *   queried; JSON lets their classes gain fields with defaults without a migration.
 * - The free text (title, instructions, result, error, status detail) is stored as written.
 * - [sortOrder] is `TaskRecord.order`; `order` is an SQL keyword.
 *
 * There are deliberately no foreign keys: tasks of one objective are written in any order
 * (a child may be stored before its parent, and a plan in one batch), and an objective is
 * removed as a whole by `root_id`. Nothing points from here to agents or conversations either,
 * so a task survives the deletion of the agent that worked on it, as the record of what happened.
 *
 * Indexes, one per read:
 * - objectives newest first: `(kind, created_at DESC, id ASC)`, the listing's exact order;
 * - a tree in creation order: `(root_id, created_at, id)`;
 * - tasks by status across objectives, oldest first (recovery after a crash, the scheduler):
 *   `(status, created_at, id)`;
 * - an agent's most recently updated tasks (schema 6): `(assignee_id, updated_at DESC, id ASC)`,
 *   the exact order of `TaskDao.RECENT_BY_AGENT`.
 *
 * Schema 6 added [retryAt] and [resultChars] with `ALTER TABLE … ADD COLUMN`, which appends a
 * column; they are declared last, in that order, so a new installation and an upgraded phone
 * have the same columns in the same order. [resultChars] is the length of [result] in Unicode
 * code points (what SQLite's `length()` counts, and `SummaryText.length`), kept in step by every
 * write, so a list can show how long a result is without reading it.
 */
@Entity(
    tableName = TaskEntity.TABLE,
    indices = [
        Index(value = ["kind", "created_at", "id"], orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.ASC]),
        Index(value = ["root_id", "created_at", "id"]),
        Index(value = ["status", "created_at", "id"]),
        Index(value = ["assignee_id", "updated_at", "id"], orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.ASC]),
    ],
)
internal data class TaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "root_id") val rootId: String,
    @ColumnInfo(name = "parent_id") val parentId: String?,
    val kind: String,
    val status: String,
    @ColumnInfo(name = "assignee_id") val assigneeId: String?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val attempt: Int,
    @ColumnInfo(name = "revision_round") val revisionRound: Int,
    @ColumnInfo(name = "needs_review") val needsReview: Boolean,
    @ColumnInfo(name = "conversation_id") val conversationId: String?,
    val title: String,
    val instructions: String,
    @ColumnInfo(name = "status_detail") val statusDetail: String?,
    val result: String?,
    val error: String?,
    @ColumnInfo(name = "criteria_json") val criteriaJson: String,
    @ColumnInfo(name = "depends_on_json") val dependsOnJson: String,
    @ColumnInfo(name = "delegation_chain_json") val delegationChainJson: String,
    @ColumnInfo(name = "reviews_json") val reviewsJson: String,
    @ColumnInfo(name = "usage_json") val usageJson: String,
    @ColumnInfo(name = "options_json") val optionsJson: String?,
    @ColumnInfo(name = "requested_by_json") val requestedByJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    @ColumnInfo(name = "deadline_at") val deadlineAt: Long?,
    // Schema 6: appended by Migration5To6, so declared last.
    @ColumnInfo(name = "retry_at") val retryAt: Long? = null,
    @ColumnInfo(name = "result_chars", defaultValue = "0") val resultChars: Int = 0,
) {
    companion object {
        const val TABLE = "tasks"
    }
}
