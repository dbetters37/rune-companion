package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * One stored message of a conversation.
 *
 * - The primary key `(conversation_id, seq)` is the entry's identity and makes a sequence
 *   number unique within its conversation; it is also the index every read uses.
 * - [messageJson] is the whole provider-neutral message. It can be large (attached images,
 *   long tool results), so reads never fetch it in one piece; see [ConversationEntryRow].
 * - The foreign key is a backstop: no entry can exist without its conversation, and whatever
 *   removes a conversation row takes the entries with it. The DAO does not rely on it and
 *   removes entries itself in the same transaction, so that a deleted conversation's content
 *   is gone even on a connection where foreign key enforcement is off.
 */
@Entity(
    tableName = ConversationEntryEntity.TABLE,
    primaryKeys = ["conversation_id", "seq"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ConversationEntryEntity(
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val seq: Int,
    val at: Long,
    @ColumnInfo(name = "model_name") val modelName: String?,
    @ColumnInfo(name = "message_json") val messageJson: String,
    @ColumnInfo(name = "usage_json") val usageJson: String?,
    val error: String?,
) {
    companion object {
        const val TABLE = "conversation_entries"
    }
}
