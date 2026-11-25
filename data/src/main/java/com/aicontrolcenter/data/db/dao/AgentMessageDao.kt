package com.aicontrolcenter.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicontrolcenter.data.db.entity.AgentMessageEntity
import com.aicontrolcenter.data.db.entity.MessageHeaderRow
import kotlinx.coroutines.flow.Flow

/**
 * Agent messages, append-only. Sending order is time, then id; "newest first" is the exact
 * reverse of it. Both orders are read from an index (see [AgentMessageEntity]).
 */
@Dao
internal interface AgentMessageDao {
    /**
     * Stores [message] unless a message with its id exists; the stored one is kept unchanged,
     * so a message re-sent after a crash is not duplicated and a sent message is never rewritten.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(message: AgentMessageEntity)

    @Query(BY_ROOT)
    fun observe(rootId: String): Flow<List<AgentMessageEntity>>

    @Query(BY_ROOT)
    suspend fun list(rootId: String): List<AgentMessageEntity>

    @Query(RECENT)
    fun observeRecent(limit: Int): Flow<List<AgentMessageEntity>>

    @Query("SELECT COUNT(*) FROM agent_messages WHERE root_id = :rootId")
    suspend fun count(rootId: String): Int

    /** The newest [limit] messages of one objective as headers, newest first (the caller reverses them). */
    @Query(HEADERS)
    fun observeHeaders(rootId: String, limit: Int): Flow<List<MessageHeaderRow>>

    /** The newest [limit] messages across all objectives as headers, newest first. */
    @Query(RECENT_HEADERS)
    fun observeRecentHeaders(limit: Int): Flow<List<MessageHeaderRow>>

    /** One statement, so all of the objective's messages go together. */
    @Query("DELETE FROM agent_messages WHERE root_id = :rootId")
    suspend fun deleteFor(rootId: String)

    /** The listing queries, as constants so a test can check that SQLite runs them from their indexes. */
    companion object {
        const val BY_ROOT = "SELECT * FROM agent_messages WHERE root_id = :rootId ORDER BY at ASC, id ASC"
        const val RECENT = "SELECT * FROM agent_messages ORDER BY at DESC, id DESC LIMIT :limit"

        /** Headers (schema 6, rule 16): the projection reads the first characters of the body only. */
        const val HEADERS = "SELECT ${MessageHeaderRow.COLUMNS} FROM agent_messages WHERE root_id = :rootId ORDER BY at DESC, id DESC LIMIT :limit"
        const val RECENT_HEADERS = "SELECT ${MessageHeaderRow.COLUMNS} FROM agent_messages ORDER BY at DESC, id DESC LIMIT :limit"
    }
}
