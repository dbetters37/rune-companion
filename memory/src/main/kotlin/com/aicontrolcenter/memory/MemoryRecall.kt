package com.aicontrolcenter.memory

import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryHit
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.RecallQuery

/**
 * Hybrid recall: meaning, keywords, recency and importance (see [RecallScorer]).
 *
 * Bounds: candidates are the newest [MemoryConfig.candidateLimit] memories the actor may read
 * (5,000 by default), plus, when that bound was reached, up to [TEXT_SEARCH_EXTRA] older ones
 * that contain every word of the query, so an old memory named exactly is still found. Expired
 * memories are never candidates. A blank query, or a limit of zero, recalls nothing; the limit
 * is capped at [MemoryConfig.maxRecallLimit].
 */
internal class MemoryRecall(private val ctx: MemoryContext, private val reader: MemoryReader, private val scorer: RecallScorer) {
    suspend fun recall(who: ResolvedActor, query: RecallQuery): List<MemoryHit> {
        val limit = query.limit.coerceAtMost(ctx.config.maxRecallLimit)
        if (limit <= 0 || query.text.isBlank()) return emptyList()
        val now = ctx.clock.now()
        val base = MemoryFilter(kinds = query.kinds, excludeExpiredAt = now)
        val candidates = candidates(who, query, base)
        if (candidates.isEmpty()) return emptyList()

        val terms = scorer.queryTerms(query.text)
        val queryVector = ctx.vectors.embedQuery(query.text, who.agentId)
        val stored = if (queryVector == null) {
            emptyMap()
        } else {
            ctx.repository.embeddings(ctx.vectors.embedderId, base.copy(scopes = candidates.mapTo(HashSet()) { it.scope }))
        }
        val hits = candidates.mapNotNull { record ->
            val semantic = queryVector?.let { q -> stored[record.id]?.let { VectorMath.cosine(q, it) } ?: 0.0 }
            scorer.hit(record, semantic, scorer.keyword(terms, record), now)
        }.filter { it.score >= query.minScore }.sortedWith(RecallScorer.ORDER).take(limit)

        if (!query.recordAccess || hits.isEmpty()) return hits
        ctx.repository.recordAccess(hits.map { it.record.id }, now)
        return hits.map { it.copy(record = it.record.copy(accessCount = it.record.accessCount + 1, lastAccessedAt = now)) }
    }

    private suspend fun candidates(who: ResolvedActor, query: RecallQuery, base: MemoryFilter): List<MemoryRecord> {
        val requested = query.scopes
        val collected = if (who.access.everything || requested != null) {
            reader.collect(who, base, requested, null, ctx.config.candidateLimit)
        } else {
            reader.collect(who, base, who.access.readable, { true }, ctx.config.candidateLimit)
        }
        if (!collected.bounded) return collected.records
        val known = collected.records.mapTo(HashSet()) { it.id }
        val searchScopes = when {
            who.access.everything -> requested
            else -> (requested ?: who.access.readable).filterTo(HashSet(), who.access::canRead)
        }
        // The repository matches raw substrings: give it the query's words without punctuation or stop words.
        val words = TextAnalyzer.words(query.text).filterNot(TextAnalyzer::isStopWord)
        if (words.isEmpty() || (searchScopes != null && searchScopes.isEmpty())) return collected.records
        val older = ctx.repository.searchText(words.joinToString(" "), base.copy(scopes = searchScopes), TEXT_SEARCH_EXTRA)
            .filter { it.id !in known && who.access.canRead(it.scope) }
        return collected.records + older
    }

    companion object {
        const val TEXT_SEARCH_EXTRA = 200
    }
}
