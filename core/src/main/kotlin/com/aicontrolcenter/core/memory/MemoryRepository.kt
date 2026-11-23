package com.aicontrolcenter.core.memory

import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.MemoryLinkId
import com.aicontrolcenter.core.SpaceId
import kotlinx.coroutines.flow.Flow

/** Which memories a query covers. Every field narrows; the default covers everything. */
data class MemoryFilter(
    /** Null: every scope. Empty: nothing. */
    val scopes: Set<MemoryScope>? = null,
    /** Empty: every kind. */
    val kinds: Set<MemoryKind> = emptySet(),
    /** Null: both tiers. */
    val tier: MemoryTier? = null,
    /** When set, memories that expired at or before this time are left out. */
    val excludeExpiredAt: Long? = null,
) {
    fun matches(record: MemoryRecord): Boolean =
        (scopes == null || record.scope in scopes) &&
            (kinds.isEmpty() || record.kind in kinds) &&
            (tier == null || record.tier == tier) &&
            (excludeExpiredAt == null || !record.isExpired(excludeExpiredAt))
}

/**
 * Storage of memories, their links, their embeddings and the knowledge spaces. It stores and
 * finds; it does not decide who may see what (that is [MemoryService]'s job). Lists are newest
 * first (updatedAt, then id), and observe flows re-emit after every change.
 */
interface MemoryRepository {
    fun observe(filter: MemoryFilter = MemoryFilter()): Flow<List<MemoryRecord>>
    suspend fun list(filter: MemoryFilter = MemoryFilter(), limit: Int = Int.MAX_VALUE): List<MemoryRecord>
    suspend fun get(id: MemoryId): MemoryRecord?
    suspend fun getAll(ids: Collection<MemoryId>): List<MemoryRecord>
    suspend fun upsert(record: MemoryRecord)

    /** Deletes the memory together with its links (in both directions) and its embeddings. */
    suspend fun delete(id: MemoryId)

    /** Deletes every memory in [scope], with their links and embeddings. Returns how many. */
    suspend fun deleteScope(scope: MemoryScope): Int

    /** Deletes memories that expired at or before [now], with their links and embeddings. */
    suspend fun deleteExpired(now: Long): Int

    /** Increments the access count and sets the last-access time of each memory. */
    suspend fun recordAccess(ids: Collection<MemoryId>, at: Long)

    /**
     * Memories in [filter] whose title, content or tags contain every word of [query]
     * (case-insensitive), newest first. A blank query matches nothing.
     */
    suspend fun searchText(query: String, filter: MemoryFilter = MemoryFilter(), limit: Int = 50): List<MemoryRecord>

    /** Links whose both ends are memories in [filter]. */
    fun observeLinks(filter: MemoryFilter = MemoryFilter()): Flow<List<MemoryLink>>
    suspend fun links(filter: MemoryFilter = MemoryFilter()): List<MemoryLink>

    /** Links that start or end at [id]. */
    suspend fun linksOf(id: MemoryId): List<MemoryLink>
    suspend fun upsertLink(link: MemoryLink)
    suspend fun deleteLink(id: MemoryLinkId)

    /** Stores the vector [embedderId] computed for a memory, replacing an earlier one from the same embedder. */
    suspend fun putEmbedding(id: MemoryId, embedderId: String, vector: FloatArray)

    /** Stored vectors of [embedderId] for the memories in [filter]. */
    suspend fun embeddings(embedderId: String, filter: MemoryFilter = MemoryFilter()): Map<MemoryId, FloatArray>

    /** Ids of memories in [filter] that have no vector from [embedderId] yet. */
    suspend fun missingEmbeddings(embedderId: String, filter: MemoryFilter = MemoryFilter()): List<MemoryId>

    fun observeSpaces(): Flow<List<KnowledgeSpace>>
    suspend fun getSpace(id: SpaceId): KnowledgeSpace?
    suspend fun upsertSpace(space: KnowledgeSpace)

    /** Deletes the space and every memory in it. Returns how many memories went. */
    suspend fun deleteSpace(id: SpaceId): Int
}

/**
 * Turns text into vectors for semantic search. Implementations: an offline one that ships with
 * the app, and later ones backed by an on-device or provider model.
 */
interface Embedder {
    /** Stable identity, including the model and version: vectors from different ids are never compared. */
    val id: String
    val dimensions: Int

    /** Where texts sent to this embedder go. Off-device embedders are refused in local-only mode. */
    val locality: com.aicontrolcenter.core.registry.Locality

    /** One unit-length vector per text, in order. */
    suspend fun embed(texts: List<String>): List<FloatArray>
}
