package com.aicontrolcenter.memory

import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryHit
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.ProvenanceAction
import com.aicontrolcenter.core.memory.RecallQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Hybrid recall: meaning, keywords, recency and importance (see [RecallScorer]).
 *
 * Bounds: candidates are the newest [MemoryConfig.candidateLimit] memories the actor may read
 * (5,000 by default) and no more than [MemoryConfig.candidateTextBudget] characters of text,
 * plus, when a bound was reached, up to [TEXT_SEARCH_EXTRA] older ones that contain every word
 * of the query (and a quarter of the text budget), so an old memory named exactly is still
 * found; their whole text counts for keywords, the others' first
 * [RecallScorer.KEYWORD_TEXT_CHARS] characters. Expired memories are never candidates. A blank query, or a limit of zero, recalls
 * nothing; the limit is capped at [MemoryConfig.maxRecallLimit].
 *
 * Vectors are loaded for the candidates only, [VECTOR_CHUNK] at a time. Scoring runs on
 * [Dispatchers.Default] and checks for cancellation between chunks and every [YIELD_EVERY]
 * candidates, so a caller's time-out stops it instead of waiting for the result.
 *
 * A memory and its shared copies count once: of each such group, the hit in the most specific
 * scope (task, then private, then shared) is kept, so copies never crowd out other memories.
 */
internal class MemoryRecall(private val ctx: MemoryContext, private val reader: MemoryReader, private val scorer: RecallScorer) {
    suspend fun recall(who: ResolvedActor, query: RecallQuery): List<MemoryHit> {
        val limit = query.limit.coerceAtMost(ctx.config.maxRecallLimit)
        if (limit <= 0 || query.text.isBlank()) return emptyList()
        val now = ctx.clock.now()
        val base = MemoryFilter(kinds = query.kinds, excludeExpiredAt = now)
        val (candidates, searched) = candidates(who, query, base)
        if (candidates.isEmpty()) return emptyList()

        val terms = scorer.queryTerms(query.text)
        val queryVector = ctx.vectors.embedQuery(query.text, who.agentId)
        val scored = score(candidates, searched, terms, queryVector, now).filter { it.score >= query.minScore }
        val hits = collapseCopies(scored).sortedWith(RecallScorer.ORDER).take(limit)

        if (!query.recordAccess || hits.isEmpty()) return hits
        ctx.repository.recordAccess(hits.map { it.record.id }, now)
        return hits.map { it.copy(record = it.record.copy(accessCount = it.record.accessCount + 1, lastAccessedAt = now)) }
    }

    /** [searched]: candidates the text search added, whose whole text is analysed. */
    private suspend fun score(
        candidates: List<MemoryRecord>,
        searched: Set<MemoryId>,
        terms: Set<String>,
        queryVector: FloatArray?,
        now: Long,
    ): List<MemoryHit> =
        withContext(Dispatchers.Default) {
            val hits = ArrayList<MemoryHit>()
            for (chunk in candidates.chunked(VECTOR_CHUNK)) {
                ensureActive()
                val stored = if (queryVector == null) emptyMap() else ctx.repository.embeddingsOf(ctx.vectors.embedderId, chunk.map { it.id })
                chunk.forEachIndexed { i, record ->
                    if (i > 0 && i % YIELD_EVERY == 0) yield()
                    val semantic = queryVector?.let { q -> stored[record.id]?.let { VectorMath.cosine(q, it) } ?: 0.0 }
                    scorer.hit(record, semantic, scorer.keyword(terms, record, fullText = record.id in searched), now)?.let(hits::add)
                }
            }
            hits
        }

    /** The candidates, and the ids of those the text search added. */
    private suspend fun candidates(who: ResolvedActor, query: RecallQuery, base: MemoryFilter): Pair<List<MemoryRecord>, Set<MemoryId>> {
        val requested = query.scopes
        val ownTasks = setOfNotNull(who.access.taskOwner)
        val collected = if (who.access.everything || requested != null) {
            reader.collect(who, base, requested, emptySet(), ctx.config.candidateLimit)
        } else {
            reader.collect(who, base, who.access.readable, ownTasks, ctx.config.candidateLimit)
        }
        if (!collected.bounded) return collected.records to emptySet()
        val known = collected.records.mapTo(HashSet()) { it.id }
        // Task memory is short-term and fully covered by the candidates' own bound; leaving its
        // scopes out keeps the search one condition per kind of scope, however many tasks there are.
        val searchScopes = when {
            who.access.everything -> requested?.filterNotTo(HashSet()) { it is MemoryScope.Task }
            else -> (requested ?: who.access.readable).filterTo(HashSet()) { it !is MemoryScope.Task && who.access.canRead(it) }
        }
        // The repository matches raw substrings: give it the query's words without punctuation or stop words.
        val words = TextAnalyzer.words(query.text).filterNot(TextAnalyzer::isStopWord)
        if (words.isEmpty() || (searchScopes != null && searchScopes.isEmpty())) return collected.records to emptySet()
        val older = ctx.repository.searchText(words.joinToString(" "), base.copy(scopes = searchScopes), TEXT_SEARCH_EXTRA)
            .filter { it.id !in known && who.access.canRead(it.scope) }
        val added = MemoryReader.withinBudget(older, ctx.config.candidateTextBudget / 4)
        return collected.records + added to added.mapTo(HashSet()) { it.id }
    }

    /**
     * Keeps one hit per memory and its shared copies. A copy names its original in a
     * SHARED_FROM event of its history (written together with its SHARED_COPY_OF link), and
     * carries the original's own history, so copies of copies and copies of the same original
     * in different spaces end up in one group even when the original itself is not a hit.
     */
    private fun collapseCopies(hits: List<MemoryHit>): List<MemoryHit> {
        if (hits.size < 2) return hits
        val parent = HashMap<MemoryId, MemoryId>()
        fun root(id: MemoryId): MemoryId {
            var r = id
            while (true) r = parent[r] ?: break
            var c = id
            while (c != r) {
                val next = parent.getValue(c)
                parent[c] = r
                c = next
            }
            return r
        }
        var linked = false
        for (hit in hits) {
            for (event in hit.record.history) {
                val original = event.relatedMemory?.takeIf { event.action == ProvenanceAction.SHARED_FROM } ?: continue
                val a = root(hit.record.id)
                val b = root(original)
                if (a != b) parent[a] = b
                linked = true
            }
        }
        if (!linked) return hits
        return hits.groupBy { root(it.record.id) }.values.map { group ->
            group.minWith(compareByDescending<MemoryHit> { specificity(it.record.scope) }.then(RecallScorer.ORDER))
        }
    }

    private fun specificity(scope: MemoryScope): Int = when (scope) {
        is MemoryScope.Task -> 3
        is MemoryScope.Agent -> 2
        is MemoryScope.Shared -> 1
    }

    companion object {
        const val TEXT_SEARCH_EXTRA = 200

        /** Candidates whose vectors are loaded with one repository call. */
        const val VECTOR_CHUNK = 500

        /** Scoring gives way to other coroutines, and notices cancellation, this often. */
        const val YIELD_EVERY = 250
    }
}
