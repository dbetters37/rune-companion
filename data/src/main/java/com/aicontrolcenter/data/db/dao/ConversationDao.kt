package com.aicontrolcenter.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.aicontrolcenter.data.db.entity.ConversationEntity
import com.aicontrolcenter.data.db.entity.ConversationEntryEntity
import com.aicontrolcenter.data.db.entity.ConversationEntryRow
import com.aicontrolcenter.data.db.entity.InterruptedTurnRow
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updated_at DESC, id ASC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE agent_id = :agentId ORDER BY updated_at DESC, id ASC")
    fun observeByAgent(agentId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Query("UPDATE conversations SET agent_id = :agentId, title = :title, created_at = :createdAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateRow(id: String, agentId: String, title: String, createdAt: Long, updatedAt: Long): Int

    @Insert
    suspend fun insertRow(conversation: ConversationEntity)

    /**
     * Insert or update in place. The row is never deleted and re-inserted, so its entries stay;
     * an update writes the conversation's own columns only, so the mark of a turn in progress
     * (`turn_started_at`, schema 6) stays too. A new row starts without a mark.
     */
    @Transaction
    suspend fun upsert(conversation: ConversationEntity) {
        val c = conversation
        if (updateRow(c.id, c.agentId, c.title, c.createdAt, c.updatedAt) == 0) insertRow(c.copy(turnStartedAt = null))
    }

    @Query("SELECT EXISTS(SELECT 1 FROM conversations WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationRow(id: String)

    @Query("DELETE FROM conversation_entries WHERE conversation_id = :conversationId")
    suspend fun deleteEntries(conversationId: String)

    /** What the conversation's model calls cost (schema 6). */
    @Query("DELETE FROM work_usage WHERE work_kind = 'CHAT' AND work_id = :conversationId")
    suspend fun deleteWorkUsage(conversationId: String)

    /** Removes the conversation, its entries and its cost rows together, so none can outlive the others. */
    @Transaction
    suspend fun delete(id: String) {
        deleteEntries(id)
        deleteWorkUsage(id)
        deleteConversationRow(id)
    }

    @Query("DELETE FROM conversations WHERE agent_id = :agentId")
    suspend fun deleteConversationRowsByAgent(agentId: String)

    @Query(
        "DELETE FROM conversation_entries WHERE conversation_id IN " +
            "(SELECT id FROM conversations WHERE agent_id = :agentId)",
    )
    suspend fun deleteEntriesByAgent(agentId: String)

    @Query(
        "DELETE FROM work_usage WHERE work_kind = 'CHAT' AND work_id IN " +
            "(SELECT id FROM conversations WHERE agent_id = :agentId)",
    )
    suspend fun deleteWorkUsageByAgent(agentId: String)

    @Transaction
    suspend fun deleteByAgent(agentId: String) {
        deleteEntriesByAgent(agentId)
        deleteWorkUsageByAgent(agentId)
        deleteConversationRowsByAgent(agentId)
    }

    // --- Turns cut off by the app stopping (schema 6, R3) ---

    /** Marks a turn started at [at]; an unknown conversation is not marked (no row to update). */
    @Query("UPDATE conversations SET turn_started_at = :at WHERE id = :id")
    suspend fun markTurnStarted(id: String, at: Long)

    @Query("UPDATE conversations SET turn_started_at = NULL WHERE id = :id")
    suspend fun markTurnEnded(id: String)

    @Query("SELECT id, turn_started_at FROM conversations WHERE turn_started_at IS NOT NULL ORDER BY turn_started_at ASC, id ASC")
    suspend fun interruptedTurns(): List<InterruptedTurnRow>

    // --- Parts of a long conversation (schema 6, P2): read by sequence number, from the primary key ---

    /** Null while the conversation has no entry (or does not exist). */
    @Query("SELECT MAX(seq) FROM conversation_entries WHERE conversation_id = :conversationId")
    fun observeLatestSeq(conversationId: String): Flow<Int?>

    @Query(ENTRY_ROWS_AFTER)
    suspend fun entryRowsAfter(conversationId: String, seq: Int): List<ConversationEntryRow>

    /** Newest first; the caller reverses them. */
    @Query(ENTRY_ROWS_BEFORE)
    suspend fun entryRowsBefore(conversationId: String, seq: Int, limit: Int): List<ConversationEntryRow>

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

    /** The reading queries, as constants so a test can check that SQLite runs them from the primary key. */
    companion object {
        /**
         * The message is cast to a BLOB so that it is cut by bytes, at a known cost and size,
         * instead of by characters. The database is UTF-8, SQLite's default, which no code here
         * changes, so those bytes are the UTF-8 text.
         */
        private const val ENTRY_COLUMNS =
            "SELECT conversation_id, seq, at, model_name, usage_json, error, " +
                "substr(CAST(message_json AS BLOB), 1, ${ConversationEntryRow.HEAD_BYTES}) AS message_head, " +
                "length(CAST(message_json AS BLOB)) AS message_bytes " +
                "FROM conversation_entries"

        const val ENTRY_ROWS = "$ENTRY_COLUMNS WHERE conversation_id = :conversationId ORDER BY seq ASC"
        const val ENTRY_ROWS_AFTER = "$ENTRY_COLUMNS WHERE conversation_id = :conversationId AND seq > :seq ORDER BY seq ASC"
        const val ENTRY_ROWS_BEFORE =
            "$ENTRY_COLUMNS WHERE conversation_id = :conversationId AND seq < :seq ORDER BY seq DESC LIMIT :limit"
    }
}
