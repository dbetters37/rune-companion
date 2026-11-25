package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per message between agents, the owner and the orchestrator (`AgentMessage`).
 *
 * - Sender and recipient are spread over typed columns, a kind (`OWNER`, `AGENT`, `SYSTEM`, and
 *   from schema 6 `SPACE`; see `ParticipantKind`) and the agent's id, which is null unless the
 *   kind is `AGENT`. So "what did this agent send or receive" stays a plain column comparison
 *   should a view need it. A memory shared into a space (schema 6) has the recipient kind
 *   `SPACE` and the space's id in [toSpaceId]; shares stored before schema 6 have `SYSTEM`.
 * - The message type is its enum name; [body] is the text as sent. It is data, never
 *   instructions, wherever it is read.
 * - Messages are append-only: a row is never updated, and a repeated id is ignored.
 *
 * No foreign keys: a message may be about a task that was never stored or has gone, and the
 * messages of an objective are removed by `root_id` on their own.
 *
 * Indexes: an objective's messages in sending order, `(root_id, at, id)`, and the newest
 * messages across all objectives, `(at, id)`, which SQLite reads backwards for "newest first".
 */
@Entity(
    tableName = AgentMessageEntity.TABLE,
    indices = [
        Index(value = ["root_id", "at", "id"]),
        Index(value = ["at", "id"]),
    ],
)
internal data class AgentMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "root_id") val rootId: String,
    @ColumnInfo(name = "task_id") val taskId: String?,
    @ColumnInfo(name = "from_kind") val fromKind: String,
    @ColumnInfo(name = "from_agent_id") val fromAgentId: String?,
    @ColumnInfo(name = "to_kind") val toKind: String,
    @ColumnInfo(name = "to_agent_id") val toAgentId: String?,
    val type: String,
    val body: String,
    @ColumnInfo(name = "reply_to") val replyTo: String?,
    val hop: Int,
    val at: Long,
    // Schema 6: appended by Migration5To6, so declared last.
    @ColumnInfo(name = "to_space_id") val toSpaceId: String? = null,
) {
    companion object {
        const val TABLE = "agent_messages"
    }
}
