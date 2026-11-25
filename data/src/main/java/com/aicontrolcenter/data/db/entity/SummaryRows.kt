package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import com.aicontrolcenter.core.tasks.MessageHeader
import com.aicontrolcenter.core.tasks.TaskSummary

/*
 * What the list queries of schema 6 return (Phase 6, rule 16: lists read summaries). Each is a
 * projection: the columns a list shows, never a task's instructions, result, reviews or usage,
 * nor a message's whole body. The statements are constants in the DAOs (`TaskDao.SUMMARIES`,
 * `TaskDao.OBJECTIVE_ROWS`, `AgentMessageDao.HEADERS`, …) so tests can check what they select
 * and that SQLite answers them from an index.
 */

/** A task as a list shows it (`TaskSummary`); [statusDetailShort] is already cut by SQLite's `substr`. */
internal data class TaskSummaryRow(
    val id: String,
    @ColumnInfo(name = "root_id") val rootId: String,
    @ColumnInfo(name = "parent_id") val parentId: String?,
    val kind: String,
    val status: String,
    @ColumnInfo(name = "assignee_id") val assigneeId: String?,
    val title: String,
    @ColumnInfo(name = "status_detail_short") val statusDetailShort: String?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val attempt: Int,
    @ColumnInfo(name = "revision_round") val revisionRound: Int,
    @ColumnInfo(name = "needs_review") val needsReview: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    @ColumnInfo(name = "retry_at") val retryAt: Long?,
    @ColumnInfo(name = "result_chars") val resultChars: Int,
    @ColumnInfo(name = "depends_on_json") val dependsOnJson: String,
) {
    companion object {
        /** The projection, in `TaskSummary` order. */
        const val COLUMNS = "id, root_id, parent_id, kind, status, assignee_id, title, " +
            "substr(COALESCE(status_detail, error), 1, ${TaskSummary.DETAIL_MAX_CHARS}) AS status_detail_short, sort_order, attempt, revision_round, " +
            "needs_review, created_at, updated_at, started_at, finished_at, retry_at, result_chars, depends_on_json"
    }
}

/**
 * One objective of the objectives list (`ObjectiveRow`) without its participants, which
 * [ParticipantRow]s add: the objective's own columns and how many steps of its plan there are
 * and are done, counted by SQLite.
 */
internal data class ObjectivePageRow(
    val id: String,
    val title: String,
    val status: String,
    @ColumnInfo(name = "status_detail_short") val statusDetailShort: String?,
    @ColumnInfo(name = "assignee_id") val assigneeId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    val planned: Int,
    val completed: Int,
)

/** An assignee of a task of an objective, in task creation order. */
internal data class ParticipantRow(
    @ColumnInfo(name = "root_id") val rootId: String,
    @ColumnInfo(name = "assignee_id") val assigneeId: String,
)

/** The plan of one objective, counted by SQLite (`PlanProgress`). */
internal data class ProgressRow(
    @ColumnInfo(name = "root_id") val rootId: String,
    val total: Int,
    val done: Int,
    val running: Int,
    val waiting: Int,
    val failed: Int,
)

/** A message as a list shows it (`MessageHeader`); [preview] is already cut by SQLite's `substr`. */
internal data class MessageHeaderRow(
    val id: String,
    @ColumnInfo(name = "root_id") val rootId: String,
    @ColumnInfo(name = "task_id") val taskId: String?,
    @ColumnInfo(name = "from_kind") val fromKind: String,
    @ColumnInfo(name = "from_agent_id") val fromAgentId: String?,
    @ColumnInfo(name = "to_kind") val toKind: String,
    @ColumnInfo(name = "to_agent_id") val toAgentId: String?,
    @ColumnInfo(name = "to_space_id") val toSpaceId: String?,
    val type: String,
    val preview: String,
    @ColumnInfo(name = "reply_to") val replyTo: String?,
    val hop: Int,
    val at: Long,
) {
    companion object {
        const val COLUMNS = "id, root_id, task_id, from_kind, from_agent_id, to_kind, to_agent_id, to_space_id, type, " +
            "substr(body, 1, ${MessageHeader.PREVIEW_MAX_CHARS}) AS preview, reply_to, hop, at"
    }
}

/** A conversation whose turn was marked started and not ended. */
internal data class InterruptedTurnRow(
    val id: String,
    @ColumnInfo(name = "turn_started_at") val turnStartedAt: Long,
)

/** How many memories one shared space holds. */
internal data class SpaceCountRow(
    @ColumnInfo(name = "space_id") val spaceId: String,
    val count: Int,
)

/** Usage of one model over a range, with the labels of its newest row (the one whose `last_call_at` is [newest]). */
internal data class ModelUsageRow(
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "model_label") val modelLabel: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "provider_label") val providerLabel: String,
    val locality: String,
    val newest: Long,
    @Embedded val counts: UsageCounts,
)

/** Usage of one agent over a range; [agentId] "" for calls made for no agent. */
internal data class AgentUsageRow(
    @ColumnInfo(name = "agent_id") val agentId: String,
    @Embedded val counts: UsageCounts,
)

/** Usage of one provider over a range, with the labels of its newest row (the one whose `last_call_at` is [newest]). */
internal data class ProviderUsageRow(
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "provider_label") val providerLabel: String,
    val locality: String,
    val newest: Long,
    @Embedded val counts: UsageCounts,
)
