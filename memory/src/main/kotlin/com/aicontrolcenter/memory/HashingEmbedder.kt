package com.aicontrolcenter.memory

import com.aicontrolcenter.core.memory.Embedder
import com.aicontrolcenter.core.registry.Locality
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The embedder that ships with the app: offline, deterministic, no model download, nothing
 * leaves the phone. It does not understand synonyms the way a neural model does, but it makes
 * texts that share words, inflections of words, or misspellings of words land close together.
 *
 * Features, each counted per text with sublinear weighting (1 + ln count):
 * - word unigrams (stemmed, stop words dropped), weight 1.0: the topical core;
 * - adjacent-word bigrams of those terms, weight 0.6: some word order and phrase identity;
 * - character trigrams of each unstemmed word padded with boundary markers, weight 0.3:
 *   robustness to typos and inflections the stemmer misses.
 *
 * Each feature is hashed with [StableHash] into one of [dimensions] buckets with a hash-derived
 * sign (signed feature hashing, so collisions cancel out on average instead of piling up), and
 * the vector is L2-normalised. A text with no meaningful words gives the zero vector, which has
 * no similarity to anything.
 *
 * The id names the algorithm and size; any change to tokenisation, weighting or hashing must
 * come with a new id, because stored vectors from different ids are never compared.
 */
class HashingEmbedder : Embedder {
    override val id: String = ID
    override val dimensions: Int = DIMENSIONS
    override val locality: Locality = Locality.ON_DEVICE

    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map(::vectorOf)

    /** The vector of one text; synchronous because it is pure computation. */
    fun vectorOf(text: String): FloatArray {
        // Insertion order (the order features occur in the text), so float sums run in the same order on every platform.
        val features = LinkedHashMap<String, Int>()
        fun add(feature: String) {
            features[feature] = (features[feature] ?: 0) + 1
        }
        val contentWords = TextAnalyzer.words(text).filterNot(TextAnalyzer::isStopWord)
        val terms = contentWords.map(TextAnalyzer::stem)
        terms.forEach { add("u:$it") }
        for (i in 0 until terms.size - 1) add("b:${terms[i]} ${terms[i + 1]}")
        contentWords.forEach { word -> trigrams(word).forEach { add("c:$it") } }

        val vector = DoubleArray(dimensions)
        for ((feature, count) in features) {
            val weight = typeWeight(feature[0]) * (1.0 + ln(count.toDouble()))
            val h = StableHash.hash64(feature)
            val bucket = ((h and Long.MAX_VALUE) % dimensions).toInt()
            vector[bucket] += if (h < 0) -weight else weight
        }
        var norm = 0.0
        for (v in vector) norm += v * v
        val out = FloatArray(dimensions)
        if (norm == 0.0) return out
        val scale = 1.0 / sqrt(norm)
        for (i in vector.indices) out[i] = (vector[i] * scale).toFloat()
        return out
    }

    private fun typeWeight(prefix: Char): Double = when (prefix) {
        'u' -> 1.0
        'b' -> 0.6
        else -> 0.3
    }

    /** Character trigrams of "<word>", by code point so letters outside the BMP stay whole. */
    private fun trigrams(word: String): List<String> {
        val cps = ("<$word>").codePoints().toArray()
        if (cps.size < 3) return emptyList()
        return (0..cps.size - 3).map { i -> String(cps, i, 3) }
    }

    companion object {
        const val ID: String = "hash-v1-384"
        const val DIMENSIONS: Int = 384
    }
}
