package com.aicontrolcenter.testkit

import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.MemoryLinkId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryLink
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.memory.MemoryScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Reference implementation of [MemoryRepository]; defines the behaviour the Room version must match. */
class InMemoryMemoryRepository : MemoryRepository {
    private data class State(
        val records: Map<MemoryId, MemoryRecord> = emptyMap(),
        val links: Map<MemoryLinkId, MemoryLink> = emptyMap(),
        /** memory -> embedder -> vector */
        val vectors: Map<MemoryId, Map<String, List<Float>>> = emptyMap(),
        val spaces: Map<SpaceId, KnowledgeSpace> = emptyMap(),
    )

    private val state = MutableStateFlow(State())

    private val newestFirst = compareByDescending<MemoryRecord> { it.updatedAt }.thenBy { it.id.value }

    private fun State.select(filter: MemoryFilter) = records.values.filter(filter::matches).sortedWith(newestFirst)

    private fun State.withoutMemories(ids: Set<MemoryId>) = copy(
        records = records - ids,
        links = links.filterValues { it.from !in ids && it.to !in ids },
        vectors = vectors - ids,
    )

    override fun observe(filter: MemoryFilter): Flow<List<MemoryRecord>> = state.map { it.select(filter) }.distinctUntilChanged()
    override suspend fun list(filter: MemoryFilter, limit: Int): List<MemoryRecord> = state.value.select(filter).take(limit)
    override suspend fun get(id: MemoryId): MemoryRecord? = state.value.records[id]
    override suspend fun getAll(ids: Collection<MemoryId>): List<MemoryRecord> =
        state.value.records.let { all -> ids.distinct().mapNotNull { all[it] } }.sortedWith(newestFirst)

    override suspend fun upsert(record: MemoryRecord) = state.update { it.copy(records = it.records + (record.id to record)) }
    override suspend fun delete(id: MemoryId) = state.update { it.withoutMemories(setOf(id)) }

    override suspend fun deleteScope(scope: MemoryScope): Int {
        var count = 0
        state.update { current ->
            val ids = current.records.values.filter { it.scope == scope }.map { it.id }.toSet()
            count = ids.size
            current.withoutMemories(ids)
        }
        return count
    }

    override suspend fun deleteExpired(now: Long): Int {
        var count = 0
        state.update { current ->
            val ids = current.records.values.filter { it.isExpired(now) }.map { it.id }.toSet()
            count = ids.size
            current.withoutMemories(ids)
        }
        return count
    }

    override suspend fun recordAccess(ids: Collection<MemoryId>, at: Long) = state.update { current ->
        val touched = ids.toSet().mapNotNull { id -> current.records[id]?.let { id to it.copy(accessCount = it.accessCount + 1, lastAccessedAt = at) } }
        current.copy(records = current.records + touched)
    }

    override suspend fun searchText(query: String, filter: MemoryFilter, limit: Int): List<MemoryRecord> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return state.value.select(filter).filter { record ->
            val haystack = (record.title + "\n" + record.content + "\n" + record.tags.joinToString(" ")).lowercase()
            words.all { it in haystack }
        }.take(limit)
    }

    private fun State.linksWithin(filter: MemoryFilter): List<MemoryLink> {
        val inside = records.values.filter(filter::matches).map { it.id }.toSet()
        return links.values.filter { it.from in inside && it.to in inside }.sortedWith(compareBy({ it.createdAt }, { it.id.value }))
    }

    override fun observeLinks(filter: MemoryFilter): Flow<List<MemoryLink>> = state.map { it.linksWithin(filter) }.distinctUntilChanged()
    override suspend fun links(filter: MemoryFilter): List<MemoryLink> = state.value.linksWithin(filter)
    override suspend fun linksOf(id: MemoryId): List<MemoryLink> =
        state.value.links.values.filter { it.from == id || it.to == id }.sortedWith(compareBy({ it.createdAt }, { it.id.value }))

    override suspend fun upsertLink(link: MemoryLink) = state.update { current ->
        require(link.from in current.records && link.to in current.records) { "Both ends of a link must exist." }
        current.copy(links = current.links + (link.id to link))
    }

    override suspend fun deleteLink(id: MemoryLinkId) = state.update { it.copy(links = it.links - id) }

    override suspend fun putEmbedding(id: MemoryId, embedderId: String, vector: FloatArray) = state.update { current ->
        if (id !in current.records) return@update current
        val forMemory = current.vectors[id].orEmpty() + (embedderId to vector.toList())
        current.copy(vectors = current.vectors + (id to forMemory))
    }

    override suspend fun embeddings(embedderId: String, filter: MemoryFilter): Map<MemoryId, FloatArray> {
        val current = state.value
        return current.select(filter).mapNotNull { record ->
            current.vectors[record.id]?.get(embedderId)?.let { record.id to it.toFloatArray() }
        }.toMap()
    }

    override suspend fun missingEmbeddings(embedderId: String, filter: MemoryFilter): List<MemoryId> {
        val current = state.value
        return current.select(filter).filter { current.vectors[it.id]?.containsKey(embedderId) != true }.map { it.id }
    }

    override fun observeSpaces(): Flow<List<KnowledgeSpace>> =
        state.map { s -> s.spaces.values.sortedWith(compareBy({ it.createdAt }, { it.id.value })) }.distinctUntilChanged()

    override suspend fun getSpace(id: SpaceId): KnowledgeSpace? = state.value.spaces[id]
    override suspend fun upsertSpace(space: KnowledgeSpace) = state.update { it.copy(spaces = it.spaces + (space.id to space)) }

    override suspend fun deleteSpace(id: SpaceId): Int {
        var count = 0
        state.update { current ->
            val ids = current.records.values.filter { it.scope == MemoryScope.Shared(id) }.map { it.id }.toSet()
            count = ids.size
            current.withoutMemories(ids).copy(spaces = current.spaces - id)
        }
        return count
    }
}
