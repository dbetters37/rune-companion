package com.aicontrolcenter.memory

import com.aicontrolcenter.core.memory.MemoryHit
import com.aicontrolcenter.core.memory.MemoryRecord
import kotlin.math.exp
import kotlin.math.ln

/**
 * Scores one memory against one query. Each component is 0..1:
 * - semantic: cosine of the memory's vector to the query's (negative similarity counts as 0);
 * - keyword: the share of the query's terms found in the memory, a term in the title counting
 *   1 and a term only in the content or tags counting [BODY_MATCH], so title matches rank higher;
 * - recency: exponential decay with half-life [MemoryConfig.recencyHalfLifeMs], measured from
 *   the later of the last update and the last use;
 * - importance: as stored.
 *
 * The score is their weighted average ([MemoryConfig.weights]); without a query vector the
 * semantic weight is dropped from the average. A memory is only a hit if it is relevant: it
 * shares a query term, or its semantic similarity reaches [MemoryConfig.semanticRelevanceFloor].
 */
internal class RecallScorer(private val config: MemoryConfig) {
    private val w = config.weights

    /** The distinct terms of a query; an empty set means the query says nothing searchable. */
    fun queryTerms(text: String): Set<String> = TextAnalyzer.terms(text).toSet()

    fun keyword(queryTerms: Set<String>, record: MemoryRecord): Double {
        if (queryTerms.isEmpty()) return 0.0
        val title = TextAnalyzer.terms(record.title).toSet()
        val body = HashSet<String>().apply {
            addAll(TextAnalyzer.terms(record.content))
            record.tags.forEach { addAll(TextAnalyzer.terms(it)) }
        }
        var sum = 0.0
        for (term in queryTerms) {
            sum += when (term) {
                in title -> 1.0
                in body -> BODY_MATCH
                else -> 0.0
            }
        }
        return sum / queryTerms.size
    }

    fun recency(record: MemoryRecord, now: Long): Double {
        val lastUsed = maxOf(record.updatedAt, record.lastAccessedAt ?: Long.MIN_VALUE)
        val age = (now - lastUsed).coerceAtLeast(0L).toDouble()
        return exp(-LN2 * age / config.recencyHalfLifeMs)
    }

    /** The hit for [record], or null when it is not relevant to the query. [semantic] is null without a query vector. */
    fun hit(record: MemoryRecord, semantic: Double?, keyword: Double, now: Long): MemoryHit? {
        val sem = semantic?.coerceIn(0.0, 1.0)
        val relevant = keyword > 0.0 || (sem != null && sem >= config.semanticRelevanceFloor)
        if (!relevant) return null
        val recency = recency(record, now)
        val importance = record.importance.coerceIn(0.0, 1.0)
        val weighted = w.keyword * keyword + w.recency * recency + w.importance * importance
        val total = w.keyword + w.recency + w.importance
        val score = if (sem == null) weighted / total else (weighted + w.semantic * sem) / (total + w.semantic)
        return MemoryHit(record, score.coerceIn(0.0, 1.0), sem ?: 0.0, keyword, recency, importance)
    }

    companion object {
        const val BODY_MATCH = 0.75
        private val LN2 = ln(2.0)

        /** Best first; equal scores fall back to the newer memory, then the smaller id, so results never shuffle. */
        val ORDER: Comparator<MemoryHit> = compareByDescending<MemoryHit> { it.score }
            .thenByDescending { it.record.updatedAt }
            .thenBy { it.record.id.value }
    }
}
