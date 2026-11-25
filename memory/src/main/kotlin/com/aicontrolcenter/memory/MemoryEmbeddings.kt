package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.memory.Embedder
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.settings.SettingsView
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Everything to do with vectors: whether the embedder may be used right now, calling it
 * safely, logging text that leaves the device, and keeping stored vectors in step with content.
 *
 * Rules:
 * - An embedder whose locality leaves the device is not called while local-only mode is on.
 *   This is checked again immediately before every call, so switching the mode on stops even a
 *   maintenance pass that is already sending batches. Recall then runs on keywords alone, and
 *   maintenance catches up once the mode is off.
 * - Before memory text or a query is sent to such an embedder, a DATA_EGRESS event is written,
 *   so nothing leaves unlogged (the event names how much, never what).
 * - An embedder failure never fails the memory operation: the memory is stored without a
 *   vector and [refresh] fills it in later.
 * - When content changes but the new vector cannot be computed, the old vector would describe
 *   text that no longer exists. The repository cannot delete a vector, so an empty vector is
 *   stored in its place (it compares as no similarity) and the id is remembered for [refresh].
 *   So that a later process finds these too without reading every vector, each such memory
 *   also gets a marker under its own embedder id, `<embedder id>#stale`: empty while the
 *   vector is pending, one value once it was recomputed (vectors cannot be deleted). The first
 *   [refresh] in a process reads only those markers.
 */
internal class MemoryEmbeddings(
    private val embedder: Embedder,
    private val settings: SettingsView,
    private val repository: MemoryRepository,
    private val activity: MemoryActivity,
    private val config: MemoryConfig,
) {
    val embedderId: String get() = embedder.id

    /** Where stale markers are kept; never compared with real vectors. */
    private val staleKey: String get() = "${embedder.id}$STALE_SUFFIX"

    private val stale: MutableSet<MemoryId> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var scannedForMarkers = false

    /** False while local-only mode forbids this embedder. */
    suspend fun usable(): Boolean = !(embedder.locality.leavesDevice && settings.get().localOnlyMode)

    /** The query's vector, or null when semantic search is unavailable for this query. */
    suspend fun embedQuery(text: String, agentId: AgentId?): FloatArray? {
        val vector = embedSafely(listOf(text), agentId, "a recall query")?.single() ?: return null
        return vector.takeUnless(VectorMath::isZero)
    }

    /**
     * Computes and stores vectors for [records]. [replacing]: the records' content changed, so an
     * existing vector must not survive a failure. Returns how many vectors were stored.
     */
    suspend fun store(records: List<MemoryRecord>, agentId: AgentId?, replacing: Boolean): Int {
        if (records.isEmpty()) return 0
        val vectors = embedSafely(records.map(::textOf), agentId, "${records.size} memor${if (records.size == 1) "y" else "ies"}")
        if (vectors == null) {
            if (replacing) records.forEach { markStale(it.id) }
            return 0
        }
        var stored = 0
        records.forEachIndexed { i, record ->
            // A concurrent update may have changed the memory while we embedded: its own call stores the newer vector.
            if (replacing && repository.get(record.id)?.revision != record.revision) return@forEachIndexed
            repository.putEmbedding(record.id, embedder.id, vectors[i])
            clearStale(record.id)
            stored++
        }
        return stored
    }

    /**
     * Computes vectors that are missing or stale, at most [budget], in batches. Stops at the
     * first failure, and as soon as the embedder may no longer be used (local-only mode was
     * switched on meanwhile), which is logged.
     */
    suspend fun refresh(now: Long, budget: Int): Int {
        if (budget <= 0 || !usable()) return 0
        if (!scannedForMarkers) {
            repository.embeddings(staleKey).forEach { (id, marker) -> if (marker.isEmpty()) stale += id }
            scannedForMarkers = true
        }
        val pending = LinkedHashSet<MemoryId>()
        pending += stale
        pending += repository.missingEmbeddings(embedder.id, MemoryFilter(excludeExpiredAt = now))
        var computed = 0
        for (chunk in pending.take(budget).chunked(config.embeddingBatchSize)) {
            if (!usable()) {
                stoppedForLocalOnly(computed)
                break
            }
            val records = repository.getAll(chunk)
            stale.removeAll((chunk.toSet() - records.map { it.id }.toSet()))
            if (records.isEmpty()) continue
            val vectors = embedSafely(records.map(::textOf), null, "${records.size} memories during maintenance")
            if (vectors == null) {
                if (!usable()) stoppedForLocalOnly(computed)
                break
            }
            // A memory changed while it was embedded keeps its stale state: the newer text gets its own vector.
            val current = repository.getAll(records.map { it.id }).associate { it.id to it.revision }
            records.forEachIndexed { i, record ->
                if (current[record.id] != record.revision) return@forEachIndexed
                repository.putEmbedding(record.id, embedder.id, vectors[i])
                clearStale(record.id)
                computed++
            }
        }
        return computed
    }

    private suspend fun stoppedForLocalOnly(computed: Int) {
        activity.event(
            null, "Memory embedding stopped",
            "Local-only mode was switched on, so maintenance stopped sending memory text to the embedder ${embedder.id} " +
                "after $computed memor${if (computed == 1) "y" else "ies"}. It continues once local-only mode is off.",
            severity = Severity.WARNING,
        )
    }

    /** The marker goes first: if the process dies in between, the next run still finds the memory. */
    private suspend fun markStale(id: MemoryId) {
        repository.putEmbedding(id, staleKey, PENDING)
        repository.putEmbedding(id, embedder.id, FloatArray(0))
        stale += id
    }

    private suspend fun clearStale(id: MemoryId) {
        if (stale.remove(id)) repository.putEmbedding(id, staleKey, DONE)
    }

    /**
     * Calls the embedder, checking its output. Null when the embedder may not be used right now
     * (checked immediately before the call) and on any failure, which is logged without content.
     */
    private suspend fun embedSafely(texts: List<String>, agentId: AgentId?, what: String): List<FloatArray>? {
        if (!usable()) return null
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

    companion object {
        const val STALE_SUFFIX = "#stale"
        private val PENDING = FloatArray(0)
        private val DONE = floatArrayOf(1f)
    }
}
