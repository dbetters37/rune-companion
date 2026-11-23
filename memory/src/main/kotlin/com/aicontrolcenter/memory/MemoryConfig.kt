package com.aicontrolcenter.memory

/**
 * How much each signal counts in recall. The weights need not sum to one: the score divides by
 * their total, so it always stays within 0..1. When semantic search is unavailable (no usable
 * embedder, or the query has no vector) the semantic weight is left out of the total, which
 * shares it out among the other three in proportion.
 */
data class RecallWeights(
    val semantic: Double = 0.55,
    val keyword: Double = 0.25,
    val recency: Double = 0.10,
    val importance: Double = 0.10,
) {
    init {
        require(listOf(semantic, keyword, recency, importance).all { it.isFinite() && it >= 0.0 }) { "Recall weights must be finite and not negative." }
        require(keyword + recency + importance > 0.0) { "At least one of the keyword, recency and importance weights must be positive." }
    }
}

/**
 * Tunables of the memory engine. The defaults are what the app ships with; tests shorten them.
 */
data class MemoryConfig(
    /** How long a short-term memory lives when its draft names no TTL. Three days. */
    val shortTermTtlMs: Long = 3L * DAY_MS,
    /** After this long without being updated or used, a memory's recency score halves. Thirty days. */
    val recencyHalfLifeMs: Long = 30L * DAY_MS,
    val weights: RecallWeights = RecallWeights(),
    /**
     * Recall and the graph look at no more than this many readable memories, newest first, so
     * one query never loads an unbounded store into memory. Recall additionally adds older
     * memories that contain every word of the query (the repository's text search).
     */
    val candidateLimit: Int = 5_000,
    /** Recall never returns more than this many hits, whatever the query asks for. */
    val maxRecallLimit: Int = 50,
    /**
     * A memory that shares no query word must reach at least this cosine similarity to be
     * recalled at all. Recency and importance only rank relevant memories; without this floor a
     * fresh but unrelated memory would pass the default minimum score on recency alone. Tuned
     * for [HashingEmbedder], whose hash collisions give short unrelated texts up to about 0.18;
     * a neural embedder with a higher similarity baseline needs a higher floor.
     */
    val semanticRelevanceFloor: Double = 0.2,
    /** How many memories one embedder call receives. */
    val embeddingBatchSize: Int = 32,
    /** Upper bound on embeddings computed by one [com.aicontrolcenter.core.memory.MemoryService.maintain] call. */
    val maxEmbeddingsPerMaintenance: Int = 1_000,
    /** The app version written into graph snapshots. */
    val appVersion: String = "0.3.0",
    /**
     * Recall and the graph also stop adding candidates once their text (title, content and
     * tags) reaches this many characters in total, newest first: 5,000 memories of the maximum
     * length would otherwise be about 100 million characters. About 12 MB on Android, where
     * strings take two bytes a character; the text search's additions may add a quarter more.
     */
    val candidateTextBudget: Long = 6_000_000,
) {
    init {
        require(shortTermTtlMs > 0) { "The short-term TTL must be positive." }
        require(recencyHalfLifeMs > 0) { "The recency half-life must be positive." }
        require(candidateLimit > 0) { "The candidate limit must be positive." }
        require(candidateTextBudget > 0) { "The candidate text budget must be positive." }
        require(maxRecallLimit > 0) { "The recall limit must be positive." }
        require(semanticRelevanceFloor in 0.0..1.0) { "The semantic relevance floor must be within 0..1." }
        require(embeddingBatchSize > 0) { "The embedding batch size must be positive." }
        require(maxEmbeddingsPerMaintenance >= 0) { "The maintenance embedding cap must not be negative." }
    }

    companion object {
        const val DAY_MS: Long = 24L * 60 * 60 * 1000
    }
}
