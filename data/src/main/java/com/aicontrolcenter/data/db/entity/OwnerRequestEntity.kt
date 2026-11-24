package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per question to the owner (`OwnerRequest`). A row leaves `PENDING` exactly once, by
 * one conditional `UPDATE … WHERE id = ? AND status = 'PENDING'`, and is never deleted: the
 * rows are the history of what was asked and answered.
 *
 * - Kind, status, risk and decision scope are enum names; [constraintKey] is
 *   `CapabilityConstraint.stored`.
 * - [detailsJson] and [delegationChainJson] are JSON arrays whose order is kept.
 * - The texts (summary, details, the agent's reason, the owner's note, the names captured when
 *   the request was raised) are stored exactly as written.
 * - [askedAgain] counts, on a refused request, the asks of the same agent for the same
 *   permission that were refused during the cool-down without a new row.
 *
 * Indexes, one per listing, each in that listing's order: pending `(status, created_at, id)`,
 * a chat `(conversation_id, created_at, id)`, an objective `(root_id, created_at, id)`, the
 * history `(created_at, id)`; and `task_id` for the asks cancelled when a task finishes.
 */
@Entity(
    tableName = OwnerRequestEntity.TABLE,
    indices = [
        Index(value = ["status", "created_at", "id"]),
        Index(value = ["conversation_id", "created_at", "id"]),
        Index(value = ["root_id", "created_at", "id"]),
        Index(value = ["task_id"]),
        Index(value = ["created_at", "id"]),
    ],
)
internal data class OwnerRequestEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val status: String,
    @ColumnInfo(name = "agent_id") val agentId: String,
    @ColumnInfo(name = "tool_id") val toolId: String?,
    val capability: String,
    @ColumnInfo(name = "constraint_key") val constraintKey: String,
    val risk: String,
    val summary: String,
    @ColumnInfo(name = "details_json") val detailsJson: String,
    @ColumnInfo(name = "data_goes_to") val dataGoesTo: String?,
    val reason: String?,
    @ColumnInfo(name = "task_id") val taskId: String?,
    @ColumnInfo(name = "root_id") val rootId: String?,
    @ColumnInfo(name = "conversation_id") val conversationId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    @ColumnInfo(name = "decided_at") val decidedAt: Long?,
    @ColumnInfo(name = "decision_scope") val decisionScope: String?,
    @ColumnInfo(name = "decision_note") val decisionNote: String?,
    @ColumnInfo(name = "agent_name") val agentName: String?,
    @ColumnInfo(name = "tool_title") val toolTitle: String?,
    @ColumnInfo(name = "target_label") val targetLabel: String?,
    @ColumnInfo(name = "delegation_chain_json") val delegationChainJson: String,
    @ColumnInfo(name = "asked_again", defaultValue = "0") val askedAgain: Int = 0,
) {
    companion object {
        const val TABLE = "owner_requests"
    }
}
