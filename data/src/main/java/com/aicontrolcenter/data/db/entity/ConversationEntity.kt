package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per conversation between the owner and an agent. The messages are in
 * [ConversationEntryEntity].
 *
 * The two indexes serve the two listings, "all, most recently updated first" and the same for
 * one agent. There is deliberately no foreign key to `agents`: the framework removes an agent's
 * conversations itself, and a conversation must not block or be lost by a write to the registry.
 */
@Entity(
    tableName = ConversationEntity.TABLE,
    indices = [
        Index(value = ["updated_at"]),
        Index(value = ["agent_id", "updated_at"]),
    ],
)
internal data class ConversationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "agent_id") val agentId: String,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val TABLE = "conversations"
    }
}
