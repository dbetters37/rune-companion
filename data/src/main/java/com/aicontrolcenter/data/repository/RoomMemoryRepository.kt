package com.aicontrolcenter.data.repository

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.MemoryLinkId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryLink
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.MemoryQueryBuilder
import com.aicontrolcenter.data.db.dao.MemoryDao
import com.aicontrolcenter.data.db.entity.KnowledgeSpaceEntity
import com.aicontrolcenter.data.db.entity.MemoryEmbeddingEntity
import com.aicontrolcenter.data.db.entity.MemoryEmbeddingRow
import com.aicontrolcenter.data.db.entity.MemoryEntity
import com.aicontrolcenter.data.db.entity.MemoryLinkEntity
import com.aicontrolcenter.data.db.entity.MemoryRow
import com.aicontrolcenter.data.db.mapper.EmbeddingCodec
import com.aicontrolcenter.data.db.mapper.RowRef
import com.aicontrolcenter.data.db.mapper.mapReadable
import com.aicontrolcenter.data.db.mapper.memorySearchWords
import com.aicontrolcenter.data.db.mapper.readableOrNull
import com.aicontrolcenter.data.db.mapper.toColumns
import com.aicontrolcenter.data.db.mapper.toDomain
import com.aicontrolcenter.data.db.mapper.toEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [MemoryRepository] on Room, in the app's (encrypted) database. It behaves like the reference
 * `InMemoryMemoryRepository` of `:testkit` and runs the same contract suite.
 *
 * - Filters, ordering and limits are evaluated by SQLite (see [MemoryQueryBuilder]).
 * - Text search: every query word must occur in title, content or tags. Words are matched with
 *   `LIKE ... ESCAPE`, so `%`, `_` and the escape character are literal, against `search_text`,
 *   a folded copy maintained on write (see `memorySearchText`), so case is ignored in every
 *   alphabet and not only in ASCII, and accents are ignored as in recall's keyword matching.
 * - [MemoryRecord.history] is stored as JSON next to the provenance.
 * - An agent's memory is found through the owning-agent indexes: [deleteAgentMemory] and
 *   [agentsWithMemory] read no content, and [taskMemoryOf] reads the newest rows first and stops
 *   at the limit. [linksAmong] and [embeddingsOf] take any number of ids, in chunks.
 * - Removing memories removes their links and embeddings in the same transaction.
 * - A row that cannot be decoded is treated as absent and reported without its content, like
 *   unreadable rows everywhere in this module.
 * - Memory content is private: no message thrown or logged here contains it. Ids and sizes are
 *   named, content and titles are not.
 *
 * All work, including JSON and lower-casing, runs on [dispatcher].
 */
class RoomMemoryRepository internal constructor(
    private val dao: MemoryDao,
    private val dispatcher: CoroutineDispatcher,
) : MemoryRepository {

    constructor(database: AiccDatabase, dispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(database.memoryDao(), dispatcher)

    override fun observe(filter: MemoryFilter): Flow<List<MemoryRecord>> =
        dao.observeMemories(MemoryQueryBuilder.list(filter, limit = null).toRoomRawQuery())
            .map { rows -> rows.mapReadable(MemoryRow::toDomain) }
            .distinctUntilChanged()
            .flowOn(dispatcher)

    override suspend fun list(filter: MemoryFilter, limit: Int): List<MemoryRecord> = withContext(dispatcher) {
        dao.memories(MemoryQueryBuilder.list(filter, limit).toRoomRawQuery()).mapReadable(MemoryRow::toDomain)
    }

    override suspend fun get(id: MemoryId): MemoryRecord? = getAll(listOf(id)).firstOrNull()

    override suspend fun getAll(ids: Collection<MemoryId>): List<MemoryRecord> = withContext(dispatcher) {
        ids.map { it.value }.distinct().chunked(MemoryDao.ID_CHUNK)
            .flatMap { chunk -> dao.byIds(chunk) }
            .mapReadable(MemoryRow::toDomain)
            .sortedWith(NEWEST_FIRST)
    }

    /**
     * Inserts or updates in place; links and embeddings of an existing memory stay.
     *
     * @throws IllegalArgumentException if the memory holds more than [MAX_TEXT_BYTES] of text.
     *   Android reads a row through a cursor window of about 2 MB, and a row that does not fit
     *   could never be read back, nor would any list containing it.
     */
    override suspend fun upsert(record: MemoryRecord) = withContext(dispatcher) {
        val entity = record.toEntity()
        val textBytes = entity.storedTextBytes()
        require(textBytes <= MAX_TEXT_BYTES) {
            "Memory ${record.id} holds $textBytes bytes of text; at most $MAX_TEXT_BYTES can be stored."
        }
        dao.upsert(entity)
    }

    /** @throws IllegalArgumentException, storing nothing, if a link names a missing memory or a memory is too large. */
    override suspend fun upsertAll(records: List<MemoryRecord>, links: List<MemoryLink>) = withContext(dispatcher) {
        val entities = records.map { record ->
            record.toEntity().also { entity ->
                val textBytes = entity.storedTextBytes()
                require(textBytes <= MAX_TEXT_BYTES) {
                    "Memory ${record.id} holds $textBytes bytes of text; at most $MAX_TEXT_BYTES can be stored."
                }
            }
        }
        dao.upsertAll(entities, links.map { it.toEntity() })
    }

    override suspend fun deleteAgentMemory(agentId: AgentId): Int = withContext(dispatcher) { dao.deleteAgentMemory(agentId.value) }

    override suspend fun agentsWithMemory(): Set<AgentId> = withContext(dispatcher) { dao.agentsWithMemory().mapTo(HashSet(), ::AgentId) }

    override suspend fun taskMemoryOf(agentId: AgentId, excludeExpiredAt: Long?, limit: Int): List<MemoryRecord> = withContext(dispatcher) {
        dao.taskMemoryOf(agentId.value, excludeExpiredAt, limit.coerceAtLeast(0)).mapReadable(MemoryRow::toDomain)
    }

    override suspend fun linksAmong(ids: Collection<MemoryId>): List<MemoryLink> = withContext(dispatcher) {
        val wanted = ids.mapTo(HashSet()) { it.value }
        wanted.toList().chunked(MemoryDao.ID_CHUNK)
            .flatMap { chunk -> dao.linksFrom(chunk) }
            .filter { it.toId in wanted }
            .mapReadable(MemoryLinkEntity::toDomain)
            .sortedWith(compareBy<MemoryLink>({ it.createdAt }, { it.id.value }))
    }

    override suspend fun embeddingsOf(embedderId: String, ids: Collection<MemoryId>): Map<MemoryId, FloatArray> = withContext(dispatcher) {
        val rows = ids.map { it.value }.distinct().chunked(MemoryDao.ID_CHUNK).flatMap { chunk -> dao.embeddingsOf(embedderId, chunk) }
        buildMap(rows.size) {
            for (row in rows) {
                row.readableOrNull { it.decode() }?.let { put(MemoryId(row.memoryId), it) }
            }
        }
    }

    override suspend fun delete(id: MemoryId) {
        withContext(dispatcher) { dao.deleteMemories(listOf(id.value)) }
    }

    override suspend fun deleteScope(scope: MemoryScope): Int = withContext(dispatcher) {
        val columns = scope.toColumns()
        dao.deleteScope(columns.type.name, columns.agentId, columns.spaceId, columns.taskId)
    }

    override suspend fun deleteExpired(now: Long): Int = withContext(dispatcher) { dao.deleteExpired(now) }

    /** An id listed more than once counts once; ids of memories that do not exist are ignored. */
    override suspend fun recordAccess(ids: Collection<MemoryId>, at: Long) {
        val distinct = ids.map { it.value }.distinct()
        if (distinct.isEmpty()) return
        withContext(dispatcher) { dao.recordAccess(distinct, at) }
    }

    /**
     * Words SQL can check are matched there, with the limit applied by SQLite. A query with
     * more words than SQL takes (see [MemoryQueryBuilder.planSearch]) reads the candidates'
     * search text and checks the remaining words here before the limit is applied.
     *
     * @throws IllegalArgumentException if [limit] is negative.
     */
    override suspend fun searchText(query: String, filter: MemoryFilter, limit: Int): List<MemoryRecord> = withContext(dispatcher) {
        require(limit >= 0) { "limit must not be negative, was $limit" }
        val words = memorySearchWords(query)
        if (words.isEmpty() || limit == 0) return@withContext emptyList()
        val plan = MemoryQueryBuilder.planSearch(words)
        if (plan.inKotlin.isEmpty()) {
            return@withContext dao.memories(MemoryQueryBuilder.search(plan.inSql, filter, limit).toRoomRawQuery())
                .mapReadable(MemoryRow::toDomain)
        }
        val matching = dao.searchCandidates(MemoryQueryBuilder.searchCandidates(plan.inSql, filter).toRoomRawQuery())
            .filter { candidate -> plan.inKotlin.all { it in candidate.searchText } }
        // Unreadable rows are dropped by getAll, so take more ids than needed and cut afterwards.
        getAll(matching.map { MemoryId(it.id) }).take(limit)
    }

    override fun observeLinks(filter: MemoryFilter): Flow<List<MemoryLink>> =
        dao.observeLinks(MemoryQueryBuilder.links(filter).toRoomRawQuery())
            .map { rows -> rows.mapReadable(MemoryLinkEntity::toDomain) }
            .distinctUntilChanged()
            .flowOn(dispatcher)

    override suspend fun links(filter: MemoryFilter): List<MemoryLink> = withContext(dispatcher) {
        dao.links(MemoryQueryBuilder.links(filter).toRoomRawQuery()).mapReadable(MemoryLinkEntity::toDomain)
    }

    override suspend fun linksOf(id: MemoryId): List<MemoryLink> = withContext(dispatcher) {
        dao.linksOf(id.value).mapReadable(MemoryLinkEntity::toDomain)
    }

    /** @throws IllegalArgumentException if either end is not a stored memory. Nothing is stored then. */
    override suspend fun upsertLink(link: MemoryLink) = withContext(dispatcher) {
        require(dao.upsertLink(link.toEntity())) {
            "Link ${link.id} needs both ends to exist, but memory ${link.from} or ${link.to} does not."
        }
    }

    override suspend fun deleteLink(id: MemoryLinkId) = withContext(dispatcher) { dao.deleteLink(id.value) }

    /** A vector for a memory that does not exist is ignored, as in the reference implementation. */
    override suspend fun putEmbedding(id: MemoryId, embedderId: String, vector: FloatArray) {
        withContext(dispatcher) {
            dao.putEmbedding(MemoryEmbeddingEntity(id.value, embedderId, vector.size, EmbeddingCodec.encode(vector)))
        }
    }

    /** A stored vector whose bytes do not match its dimension count is left out and reported. */
    override suspend fun embeddings(embedderId: String, filter: MemoryFilter): Map<MemoryId, FloatArray> = withContext(dispatcher) {
        val rows = dao.embeddings(MemoryQueryBuilder.embeddings(embedderId, filter).toRoomRawQuery())
        buildMap(rows.size) {
            for (row in rows) {
                row.readableOrNull { it.decode() }?.let { put(MemoryId(row.memoryId), it) }
            }
        }
    }

    override suspend fun missingEmbeddings(embedderId: String, filter: MemoryFilter): List<MemoryId> = withContext(dispatcher) {
        dao.ids(MemoryQueryBuilder.missingEmbeddings(embedderId, filter).toRoomRawQuery()).map(::MemoryId)
    }

    override fun observeSpaces(): Flow<List<KnowledgeSpace>> =
        dao.observeSpaces().map { rows -> rows.map(KnowledgeSpaceEntity::toDomain) }.distinctUntilChanged().flowOn(dispatcher)

    override suspend fun getSpace(id: SpaceId): KnowledgeSpace? = withContext(dispatcher) { dao.getSpace(id.value)?.toDomain() }

    override suspend fun upsertSpace(space: KnowledgeSpace) = withContext(dispatcher) { dao.upsertSpace(space.toEntity()) }

    override suspend fun deleteSpace(id: SpaceId): Int = withContext(dispatcher) { dao.deleteSpace(id.value) }

    private fun MemoryEmbeddingRow.decode(): FloatArray =
        EmbeddingCodec.decode(vector, dimensions, RowRef(MemoryEmbeddingEntity.TABLE, memoryId))

    companion object {
        /**
         * Most UTF-8 bytes of text (title, content, tags, provenance and history together) one
         * memory may hold: half of Android's cursor window, leaving room for the other columns.
         */
        const val MAX_TEXT_BYTES = 1024 * 1024

        private val NEWEST_FIRST = compareByDescending<MemoryRecord> { it.updatedAt }.thenBy { it.id.value }

        private fun MemoryEntity.storedTextBytes(): Long =
            listOf(id, title, content, tagsJson, provenanceJson, historyJson).sumOf { it.utf8Length() }

        /** UTF-8 length without encoding the text; unpaired surrogates count as the 3 bytes SQLite stores. */
        private fun String.utf8Length(): Long {
            var bytes = 0L
            var i = 0
            while (i < length) {
                val c = this[i]
                bytes += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate() -> { i++; 4 }
                    else -> 3
                }
                i++
            }
            return bytes
        }
    }
}
