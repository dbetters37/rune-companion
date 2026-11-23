package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.memory.Embedder
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.settings.SettingsRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Everything to do with vectors: whether the embedder may be used right now, calling it
 * safely, logging text that leaves the device, and keeping stored vectors in step with content.
 *
 * Rules:
 * - An embedder whose locality leaves the device is not called while local-only mode is on.
 *   Recall then runs on keywords alone, and maintenance catches up once the mode is off.
 * - Before memory text or a query is sent to such an embedder, a DATA_EGRESS event is written,
 *   so nothing leaves unlogged (the event names how much, never what).
 * - An embedder failure never fails the memory operation: the memory is stored without a
 *   vector and [refresh] fills it in later.
 * - When content changes but the new vector cannot be computed, the old vector would describe
 *   text that no longer exists. The repository cannot delete a vector, so an empty vector is
 *   stored as a "stale" marker (it compares as no similarity) and the id is remembered for
 *   [refresh]; the first [refresh] in a process also scans for markers left by earlier runs.
 */
internal class MemoryEmbeddings(
    private val embedder: Embedder,
    private val settings: SettingsRepository,
    private val repository: MemoryRepository,
    private val activity: MemoryActivity,
    private val config: MemoryConfig,
) {
    val embedderId: String get() = embedder.id

    private val stale: MutableSet<MemoryId> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var scannedForMarkers = false

    /** False while local-only mode forbids this embedder. */
    suspend fun usable(): Boolean = !(embedder.locality.leavesDevice && settings.get().localOnlyMode)

    /** The query's vector, or null when semantic search is unavailable for this query. */
    suspend fun embedQuery(text: String, agentId: AgentId?): FloatArray? {
        if (!usable()) return null
        val vector = embedSafely(listOf(text), agentId, "a recall query")?.single() ?: return null
        return vector.takeUnless(VectorMath::isZero)
    }

    /**
     * Computes and stores vectors for [records]. [replacing]: the records' content changed, so an
     * existing vector must not survive a failure. Returns how many vectors were stored.
     */
    suspend fun store(records: List<MemoryRecord>, agentId: AgentId?, replacing: Boolean): Int {
        if (records.isEmpty()) return 0
        val vectors = if (usable()) embedSafely(records.map(::textOf), agentId, "${records.size} memor${if (records.size == 1) "y" else "ies"}") else null
        if (vectors == null) {
            if (replacing) records.forEach { markStale(it.id) }
            return 0
        }
        var stored = 0
        records.forEachIndexed { i, record ->
            // A concurrent update may have changed the memory while we embedded: its own call stores the newer vector.
            if (replacing && repository.get(record.id)?.revision != record.revision) return@forEachIndexed
            repository.putEmbedding(record.id, embedder.id, vectors[i])
            stale.remove(record.id)
            stored++
        }
        return stored
    }

    /** Computes vectors that are missing or stale, at most [budget], in batches. Stops at the first failure. */
    suspend fun refresh(now: Long, budget: Int): Int {
        if (budget <= 0 || !usable()) return 0
        if (!scannedForMarkers) {
            repository.embeddings(embedder.id).forEach { (id, vector) -> if (vector.isEmpty()) stale += id }
            scannedForMarkers = true
        }
        val pending = LinkedHashSet<MemoryId>()
        pending += stale
        pending += repository.missingEmbeddings(embedder.id, MemoryFilter(excludeExpiredAt = now))
        var computed = 0
        for (chunk in pending.take(budget).chunked(config.embeddingBatchSize)) {
            val records = repository.getAll(chunk)
            stale.removeAll((chunk.toSet() - records.map { it.id }.toSet()))
            if (records.isEmpty()) continue
            val vectors = embedSafely(records.map(::textOf), null, "${records.size} memories during maintenance") ?: break
            records.forEachIndexed { i, record ->
                repository.putEmbedding(record.id, embedder.id, vectors[i])
                stale.remove(record.id)
            }
            computed += records.size
        }
        return computed
    }

    private suspend fun markStale(id: MemoryId) {
        repository.putEmbedding(id, embedder.id, FloatArray(0))
        stale += id
    }

    /** Calls the embedder, checking its output. Null on any failure, which is logged without content. */
    private suspend fun embedSafely(texts: List<String>, agentId: AgentId?, what: String): List<FloatArray>? {
        if (embedder.locality.leavesDevice) {
            activity.egress(
                agentId,
                EgressInfo(
                    destinationHost = "embedder:${embedder.id}", locality = embedder.locality,
                    purpose = "memory text for semantic search", approxBytes = texts.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() },
                ),
                "Sent $what to the embedder ${embedder.id}.",
            )
        }
        return try {
            val vectors = embedder.embed(texts)
            check(vectors.size == texts.size) { "returned ${vectors.size} vectors for ${texts.size} texts" }
            check(vectors.all { v -> v.size == embedder.dimensions && v.all(Float::isFinite) }) { "returned malformed vectors" }
            vectors
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The exception's message may quote the text it choked on: log only its type.
            activity.event(
                null, "Embedding failed",
                "The embedder ${embedder.id} failed (${e::class.simpleName}). Affected memories are found by keywords until maintenance computes their vectors.",
                severity = Severity.WARNING,
            )
            null
        }
    }

    fun textOf(record: MemoryRecord): String = buildString {
        append(record.title).append('\n').append(record.content)
        if (record.tags.isNotEmpty()) append('\n').append(record.tags.joinToString(" "))
    }
}
