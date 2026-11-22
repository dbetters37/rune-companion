package com.aicontrolcenter.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.aicontrolcenter.data.db.entity.ConversationEntity
import com.aicontrolcenter.data.db.entity.ConversationEntryEntity
import com.aicontrolcenter.data.db.entity.ConversationEntryRow
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updated_at DESC, id ASC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE agent_id = :agentId ORDER BY updated_at DESC, id ASC")
    fun observeByAgent(agentId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    /** Insert or update in place. The row is never deleted and re-inserted, so its entries stay. */
    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM conversations WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationRow(id: String)

    @Query("DELETE FROM conversation_entries WHERE conversation_id = :conversationId")
    suspend fun deleteEntries(conversationId: String)

    /** Removes the conversation and its entries together, so neither can outlive the other. */
    @Transaction
    suspend fun delete(id: String) {
        deleteEntries(id)
        deleteConversationRow(id)
    }

    @Query("DELETE FROM conversations WHERE agent_id = :agentId")
    suspend fun deleteConversationRowsByAgent(agentId: String)

    @Query(
        "DELETE FROM conversation_entries WHERE conversation_id IN " +
            "(SELECT id FROM conversations WHERE agent_id = :agentId)",
    )
    suspend fun deleteEntriesByAgent(agentId: String)

    @Transaction
    suspend fun deleteByAgent(agentId: String) {
        deleteEntriesByAgent(agentId)
        deleteConversationRowsByAgent(agentId)
    }

    @Query(ENTRY_ROWS)
    fun observeEntryRows(conversationId: String): Flow<List<ConversationEntryRow>>

    @Query(ENTRY_ROWS)
    suspend fun entryRows(conversationId: String): List<ConversationEntryRow>

    /**
     * Up to [count] bytes of an entry's message, starting at the 1-based [firstByte]; null
     * when the entry does not exist. For messages longer than [ConversationEntryRow.HEAD_BYTES].
     */
    @Query(
        "SELECT substr(CAST(message_json AS BLOB), :firstByte, :count) FROM conversation_entries " +
            "WHERE conversation_id = :conversationId AND seq = :seq",
    )
    suspend fun messageBytes(conversationId: String, seq: Int, firstByte: Int, count: Int): ByteArray?

    @Query("SELECT COALESCE(MAX(seq), -1) + 1 FROM conversation_entries WHERE conversation_id = :conversationId")
    suspend fun nextSeq(conversationId: String): Int

    @Insert
    suspend fun insertEntry(entry: ConversationEntryEntity)

    /**
     * Stores [entry] under the next sequence number of its conversation; the `seq` it carries
     * is ignored. The number is read and the row written inside one write transaction, so
     * concurrent appends queue up and each gets its own number. The check that the
     * conversation exists is part of the same transaction, and so is the removal in [delete],
     * so an entry can never be added to a conversation that is being deleted.
     *
     * @return the assigned sequence number, or null when no conversation has this id.
     */
    @Transaction
    suspend fun append(entry: ConversationEntryEntity): Int? {
        if (!exists(entry.conversationId)) return null
        val seq = nextSeq(entry.conversationId)
        insertEntry(entry.copy(seq = seq))
        return seq
    }

    private companion object {
        /**
         * The message is cast to a BLOB so that it is cut by bytes, at a known cost and size,
         * instead of by characters. The database is UTF-8, SQLite's default, which no code here
         * changes, so those bytes are the UTF-8 text.
         */
        const val ENTRY_ROWS =
            "SELECT conversation_id, seq, at, model_name, usage_json, error, " +
                "substr(CAST(message_json AS BLOB), 1, ${ConversationEntryRow.HEAD_BYTES}) AS message_head, " +
                "length(CAST(message_json AS BLOB)) AS message_bytes " +
                "FROM conversation_entries WHERE conversation_id = :conversationId ORDER BY seq ASC"
    }
}
