package com.aicontrolcenter.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import com.aicontrolcenter.data.db.entity.KnowledgeSpaceEntity
import com.aicontrolcenter.data.db.entity.MemoryEmbeddingEntity
import com.aicontrolcenter.data.db.entity.MemoryEmbeddingRow
import com.aicontrolcenter.data.db.entity.MemoryEntity
import com.aicontrolcenter.data.db.entity.MemoryLinkEntity
import com.aicontrolcenter.data.db.entity.MemoryRow
import com.aicontrolcenter.data.db.entity.MemorySearchCandidate
import com.aicontrolcenter.data.db.entity.SpaceCountRow
import com.aicontrolcenter.data.db.mapper.MemoryScopeType
import kotlinx.coroutines.flow.Flow

/**
 * Memories, their links and embeddings, and the knowledge spaces.
 *
 * Every write that touches more than one row is one transaction. Rows are only ever inserted or
 * updated in place (`@Upsert`), never replaced: `REPLACE` deletes the old row first, and the
 * foreign keys would take the memory's links and embeddings with it.
 *
 * Removing memories removes their links and embeddings explicitly in the same transaction; the
 * foreign keys' cascade is a backstop, not the mechanism. Lists of ids are processed in chunks
 * of [ID_CHUNK] so no statement exceeds SQLite's limit on bound variables.
 */
@Dao
internal interface MemoryDao {
    // --- reading, with queries built by MemoryQueryBuilder ---

    @RawQuery(observedEntities = [MemoryEntity::class])
    fun observeMemories(query: RoomRawQuery): Flow<List<MemoryRow>>

    @RawQuery
    suspend fun memories(query: RoomRawQuery): List<MemoryRow>

    /** A `COUNT(*)` built by `MemoryQueryBuilder.count` (schema 6, rule 16): counted without reading a memory. */
    @RawQuery(observedEntities = [MemoryEntity::class])
    fun observeCount(query: RoomRawQuery): Flow<Int>

    @Query(COUNTS_BY_SPACE)
    fun observeCountsBySpace(): Flow<List<SpaceCountRow>>

    @RawQuery
    suspend fun searchCandidates(query: RoomRawQuery): List<MemorySearchCandidate>

    @RawQuery
    suspend fun ids(query: RoomRawQuery): List<String>

    @RawQuery(observedEntities = [MemoryLinkEntity::class, MemoryEntity::class])
    fun observeLinks(query: RoomRawQuery): Flow<List<MemoryLinkEntity>>

    @RawQuery
    suspend fun links(query: RoomRawQuery): List<MemoryLinkEntity>

    @RawQuery
    suspend fun embeddings(query: RoomRawQuery): List<MemoryEmbeddingRow>

    @Query("SELECT ${MemoryRow.COLUMNS} FROM memories WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<MemoryRow>

    @Query("SELECT EXISTS(SELECT 1 FROM memories WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    // --- writing memories ---

    @Upsert
    suspend fun upsert(memory: MemoryEntity)

    @Query("UPDATE memories SET access_count = access_count + 1, last_accessed_at = :at WHERE id IN (:ids)")
    suspend fun incrementAccess(ids: List<String>, at: Long)

    /**
     * Counts one access for each of [ids] (which must not repeat). The increment happens inside
     * SQLite, not as read-modify-write, so concurrent calls never lose a count.
     */
    @Transaction
    suspend fun recordAccess(ids: List<String>, at: Long) {
        ids.chunked(ID_CHUNK).forEach { incrementAccess(it, at) }
    }

    // --- removing memories ---

    /** `IS` compares nulls as equal, so this is the exact scope: unused scope columns are null. */
    @Query(
        "SELECT id FROM memories WHERE scope_type = :type AND scope_agent_id IS :agentId " +
            "AND scope_space_id IS :spaceId AND scope_task_id IS :taskId",
    )
    suspend fun idsInScope(type: String, agentId: String?, spaceId: String?, taskId: String?): List<String>

    @Query("SELECT id FROM memories WHERE expires_at IS NOT NULL AND expires_at <= :now")
    suspend fun expiredIds(now: Long): List<String>

    @Query("DELETE FROM memory_links WHERE from_id IN (:ids) OR to_id IN (:ids)")
    suspend fun deleteLinksTouching(ids: List<String>)

    @Query("DELETE FROM memory_embeddings WHERE memory_id IN (:ids)")
    suspend fun deleteEmbeddingsOf(ids: List<String>)

    @Query("DELETE FROM memories WHERE id IN (:ids)")
    suspend fun deleteMemoryRows(ids: List<String>): Int

    /** Removes the memories with their links (both directions) and embeddings. Returns how many memories went. */
    @Transaction
    suspend fun deleteMemories(ids: List<String>): Int {
        var deleted = 0
        // Links name each id twice in their statement, hence half a chunk.
        ids.chunked(ID_CHUNK / 2).forEach { chunk ->
            deleteLinksTouching(chunk)
            deleteEmbeddingsOf(chunk)
            deleted += deleteMemoryRows(chunk)
        }
        return deleted
    }

    /** Finding and removing are one transaction, so a memory written meanwhile is either gone or untouched. */
    @Transaction
    suspend fun deleteScope(type: String, agentId: String?, spaceId: String?, taskId: String?): Int =
        deleteMemories(idsInScope(type, agentId, spaceId, taskId))

    @Transaction
    suspend fun deleteExpired(now: Long): Int = deleteMemories(expiredIds(now))

    // --- links ---

    /** Stores [memories] and then [links] in one transaction; throws, storing nothing, if a link has a missing end. */
    @Transaction
    suspend fun upsertAll(memories: List<MemoryEntity>, links: List<MemoryLinkEntity>) {
        memories.forEach { upsert(it) }
        links.forEach { link ->
            require(upsertLink(link)) { "Link ${link.id} needs both ends to exist, but memory ${link.fromId} or ${link.toId} does not." }
        }
    }

    @Query("SELECT id FROM memories WHERE scope_agent_id = :agentId AND scope_type IN ('AGENT', 'TASK')")
    suspend fun agentMemoryIds(agentId: String): List<String>

    /** One transaction: an agent's private and task memory goes together with its links and embeddings. */
    @Transaction
    suspend fun deleteAgentMemory(agentId: String): Int = deleteMemories(agentMemoryIds(agentId))

    @Query("SELECT DISTINCT scope_agent_id FROM memories WHERE scope_type IN ('AGENT', 'TASK') AND scope_agent_id IS NOT NULL")
    suspend fun agentsWithMemory(): List<String>

    @Query(
        "SELECT ${MemoryRow.COLUMNS} FROM memories WHERE scope_type = 'TASK' AND scope_agent_id = :agentId " +
            "AND (:excludeExpiredAt IS NULL OR expires_at IS NULL OR expires_at > :excludeExpiredAt) " +
            "ORDER BY updated_at DESC, id ASC LIMIT :limit",
    )
    suspend fun taskMemoryOf(agentId: String, excludeExpiredAt: Long?, limit: Int): List<MemoryRow>

    @Query("SELECT * FROM memory_links WHERE from_id IN (:ids) ORDER BY created_at ASC, id ASC")
    suspend fun linksFrom(ids: List<String>): List<MemoryLinkEntity>

    @Query("SELECT memory_id, dimensions, vector FROM memory_embeddings WHERE embedder_id = :embedderId AND memory_id IN (:ids)")
    suspend fun embeddingsOf(embedderId: String, ids: List<String>): List<MemoryEmbeddingRow>

    @Query("SELECT * FROM memory_links WHERE from_id = :id OR to_id = :id ORDER BY created_at ASC, id ASC")
    suspend fun linksOf(id: String): List<MemoryLinkEntity>

    @Upsert
    suspend fun upsertLinkRow(link: MemoryLinkEntity)

    /**
     * Stores [link] if both its ends exist, checked in the same transaction. Returns false and
     * stores nothing otherwise. (The foreign keys would refuse it too, with a less useful error.)
     */
    @Transaction
    suspend fun upsertLink(link: MemoryLinkEntity): Boolean {
        if (!exists(link.fromId) || !exists(link.toId)) return false
        upsertLinkRow(link)
        return true
    }

    @Query("DELETE FROM memory_links WHERE id = :id")
    suspend fun deleteLink(id: String)

    // --- embeddings ---

    @Upsert
    suspend fun upsertEmbeddingRow(embedding: MemoryEmbeddingEntity)

    /** Stores [embedding] if its memory exists; returns false and stores nothing otherwise. */
    @Transaction
    suspend fun putEmbedding(embedding: MemoryEmbeddingEntity): Boolean {
        if (!exists(embedding.memoryId)) return false
        upsertEmbeddingRow(embedding)
        return true
    }

    // --- knowledge spaces ---

    @Query("SELECT * FROM knowledge_spaces ORDER BY created_at ASC, id ASC")
    fun observeSpaces(): Flow<List<KnowledgeSpaceEntity>>

    @Query("SELECT * FROM knowledge_spaces WHERE id = :id")
    suspend fun getSpace(id: String): KnowledgeSpaceEntity?

    @Upsert
    suspend fun upsertSpace(space: KnowledgeSpaceEntity)

    @Query("DELETE FROM knowledge_spaces WHERE id = :id")
    suspend fun deleteSpaceRow(id: String)

    /** Removes the space and every memory in it, with their links and embeddings. Returns how many memories went. */
    @Transaction
    suspend fun deleteSpace(id: String): Int {
        val deleted = deleteScope(MemoryScopeType.SHARED.name, agentId = null, spaceId = id, taskId = null)
        deleteSpaceRow(id)
        return deleted
    }

    companion object {
        /** Ids per statement; below the 999 bound variables of the oldest SQLite this runs on. */
        const val ID_CHUNK = 500

        /** Memories per shared space, expired ones included; `SHARED` is pinned by `StoredNamesGoldenTest`. */
        const val COUNTS_BY_SPACE = "SELECT scope_space_id AS space_id, COUNT(*) AS count FROM memories " +
            "WHERE scope_type = 'SHARED' AND scope_space_id IS NOT NULL GROUP BY scope_space_id"
    }
}
