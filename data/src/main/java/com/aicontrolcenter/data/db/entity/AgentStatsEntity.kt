package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Track record of one agent. Kept apart from [AgentEntity] because it changes after every task
 * while the profile changes only when the user edits it, and because duplicating an agent must
 * copy configuration only.
 *
 * Not tied to `agents` by a foreign key: the runtime may report the outcome of a task for an
 * agent that is being deleted at that moment, and that write must not fail. The repository
 * removes both rows in one transaction instead.
 */
@Entity(tableName = AgentStatsEntity.TABLE)
internal data class AgentStatsEntity(
    @PrimaryKey @ColumnInfo(name = "agent_id") val agentId: String,
    @ColumnInfo(name = "tasks_assigned") val tasksAssigned: Int,
    @ColumnInfo(name = "tasks_completed") val tasksCompleted: Int,
    @ColumnInfo(name = "tasks_failed") val tasksFailed: Int,
    @ColumnInfo(name = "tasks_timed_out") val tasksTimedOut: Int,
    @ColumnInfo(name = "tasks_cancelled") val tasksCancelled: Int,
    @ColumnInfo(name = "reviews_passed") val reviewsPassed: Int,
    @ColumnInfo(name = "revisions_requested") val revisionsRequested: Int,
    @ColumnInfo(name = "reviews_rejected") val reviewsRejected: Int,
    @ColumnInfo(name = "model_calls") val modelCalls: Int,
    @ColumnInfo(name = "model_errors") val modelErrors: Int,
    @ColumnInfo(name = "tool_calls") val toolCalls: Int,
    @ColumnInfo(name = "tool_errors") val toolErrors: Int,
    @ColumnInfo(name = "input_tokens") val inputTokens: Long,
    @ColumnInfo(name = "output_tokens") val outputTokens: Long,
    @ColumnInfo(name = "total_task_duration_ms") val totalTaskDurationMs: Long,
    @ColumnInfo(name = "last_active_at") val lastActiveAt: Long?,
) {
    companion object {
        const val TABLE = "agent_stats"
    }
}
