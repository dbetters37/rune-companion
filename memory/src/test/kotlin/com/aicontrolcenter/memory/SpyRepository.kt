package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryLink
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.testkit.InMemoryMemoryRepository
import kotlinx.coroutines.CompletableDeferred

/**
 * A [MemoryRepository] that records what the engine asks of it and can hold a call open, the
 * way a slow disk or a busy dispatcher would, to let a test interleave operations.
 */
class SpyRepository(val inner: InMemoryMemoryRepository) : MemoryRepository by inner {
    /** Every call, as "name(arguments)" with only what the test needs to see. */
    val calls = mutableListOf<String>()

    /** Every filter passed to a query, for checking that no task scope reaches SQL. */
    val filters = mutableListOf<MemoryFilter>()

    /** How many records each list-like call returned. */
    val loaded = mutableListOf<Pair<String, Int>>()

    private var heldGet: Pair<MemoryId, CompletableDeferred<Unit>>? = null
    private var heldSpace: Pair<SpaceId, CompletableDeferred<Unit>>? = null
    private var spaceReadsToSkip = 0

    /** Runs after the next `get` of [id] has read it, before it returns. */
    var afterGet: (suspend (MemoryId) -> Unit)? = null

    /** Runs once, at the next `getAll`, before it reads. */
    var beforeGetAll: (suspend () -> Unit)? = null

    override suspend fun getAll(ids: Collection<MemoryId>): List<MemoryRecord> {
        beforeGetAll?.let { hook ->
            beforeGetAll = null
            hook()
        }
        return inner.getAll(ids)
    }

    /** Called on every `embeddingsOf`, before it returns. */
    var onEmbeddingsOf: (suspend (Collection<MemoryId>) -> Unit)? = null

    /** The next `get(id)` reads the record and then waits until the gate completes. */
    fun holdNextGet(id: MemoryId): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { heldGet = id to it }

    /** After [skip] further reads of space [id], the next `getSpace(id)` reads it and then waits until the gate completes. */
    fun holdGetSpace(id: SpaceId, skip: Int = 0): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also {
            heldSpace = id to it
            spaceReadsToSkip = skip
        }

    override suspend fun get(id: MemoryId): MemoryRecord? {
        val record = inner.get(id)
        heldGet?.takeIf { it.first == id }?.let { (_, gate) ->
            heldGet = null
            gate.await()
        }
        afterGet?.let { hook ->
            afterGet = null
            hook(id)
        }
        return record
    }

    override suspend fun getSpace(id: SpaceId): KnowledgeSpace? {
        val space = inner.getSpace(id)
        heldSpace?.takeIf { it.first == id }?.let { (_, gate) ->
            if (spaceReadsToSkip-- > 0) return space
            heldSpace = null
            gate.await()
        }
        return space
    }

    override suspend fun list(filter: MemoryFilter, limit: Int): List<MemoryRecord> {
        calls += "list"
        filters += filter
        return inner.list(filter, limit).also { loaded += "list" to it.size }
    }

    override suspend fun searchText(query: String, filter: MemoryFilter, limit: Int): List<MemoryRecord> {
        calls += "searchText"
        filters += filter
        return inner.searchText(query, filter, limit).also { loaded += "searchText" to it.size }
    }

    override suspend fun links(filter: MemoryFilter): List<MemoryLink> {
        calls += "links"
        filters += filter
        return inner.links(filter)
    }

    override suspend fun linksAmong(ids: Collection<MemoryId>): List<MemoryLink> {
        calls += "linksAmong(${ids.size})"
        return inner.linksAmong(ids)
    }

    override suspend fun embeddings(embedderId: String, filter: MemoryFilter): Map<MemoryId, FloatArray> {
        calls += "embeddings($embedderId)"
        filters += filter
        return inner.embeddings(embedderId, filter).also { loaded += "embeddings($embedderId)" to it.size }
    }

    override suspend fun embeddingsOf(embedderId: String, ids: Collection<MemoryId>): Map<MemoryId, FloatArray> {
        calls += "embeddingsOf(${ids.size})"
        val vectors = inner.embeddingsOf(embedderId, ids)
        onEmbeddingsOf?.invoke(ids)
        return vectors
    }

    override suspend fun taskMemoryOf(agentId: AgentId, excludeExpiredAt: Long?, limit: Int): List<MemoryRecord> {
        calls += "taskMemoryOf(${agentId.value})"
        return inner.taskMemoryOf(agentId, excludeExpiredAt, limit).also { loaded += "taskMemoryOf(${agentId.value})" to it.size }
    }

    override suspend fun deleteAgentMemory(agentId: AgentId): Int {
        calls += "deleteAgentMemory(${agentId.value})"
        return inner.deleteAgentMemory(agentId)
    }

    override suspend fun upsertAll(records: List<MemoryRecord>, links: List<MemoryLink>) {
        calls += "upsertAll(${records.size}, ${links.size})"
        inner.upsertAll(records, links)
    }

    /** True when some query the engine sent named a task scope (one SQL condition per task). */
    fun sawTaskScopeInAFilter(): Boolean = filters.any { filter -> filter.scopes.orEmpty().any { it is MemoryScope.Task } }
}
