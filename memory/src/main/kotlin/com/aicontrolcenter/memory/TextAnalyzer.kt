package com.aicontrolcenter.memory

import java.text.Normalizer
import java.util.Locale

/**
 * The one place text is broken into words, shared by the embedder and by keyword matching so
 * the two always agree on what "the same word" means.
 *
 * - Unicode-aware: a word is a run of letters and digits in any script; text is NFKD-normalised
 *   and non-spacing marks are dropped, so "Café" and "cafe" are the same word.
 * - A small English stop-word list removes words that carry no meaning on their own.
 * - A light English suffix stripper folds common inflections ("prefers", "preferred",
 *   "preferring" all become "prefer"). It is deliberately crude: it only has to map inflections
 *   of one word to the same stem consistently, and the embedder's character trigrams cover what
 *   it misses. It only touches words written in Latin letters.
 *
 * Scripts written without spaces (Chinese, Japanese) come out as long runs; character trigrams
 * still make them searchable, just less precisely.
 */
internal object TextAnalyzer {
    private val STOP_WORDS: Set<String> = setOf(
        "a", "about", "after", "all", "also", "am", "an", "and", "any", "are", "as", "at", "be", "been", "before",
        "being", "but", "by", "can", "could", "did", "do", "does", "for", "from", "had", "has", "have", "he", "her",
        "here", "him", "his", "how", "i", "if", "in", "into", "is", "it", "its", "just", "me", "my", "of", "on",
        "or", "our", "she", "so", "than", "that", "the", "their", "them", "then", "there", "these", "they", "this",
        "those", "to", "too", "us", "very", "was", "we", "were", "what", "when", "where", "which", "while", "who",
        "why", "will", "with", "would", "you", "your",
    )

    private val MARKS = Regex("\\p{Mn}+")

    /** Lower-case words in order, stop words included. */
    fun words(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val folded = MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFKD), "").lowercase(Locale.ROOT)
        val words = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < folded.length) {
            val cp = folded.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) {
                current.appendCodePoint(cp)
            } else if (current.isNotEmpty()) {
                words += current.toString()
                current.setLength(0)
            }
            i += Character.charCount(cp)
        }
        if (current.isNotEmpty()) words += current.toString()
        return words
    }

    fun isStopWord(word: String): Boolean = word in STOP_WORDS

    /** The meaningful words of [text], stemmed, in order (duplicates kept). */
    fun terms(text: String): List<String> = words(text).filterNot(::isStopWord).map(::stem)

    /** Folds common English inflections of [word] (already lower case) onto one stem. */
    fun stem(word: String): String {
        if (word.length <= 3 || !word.all { it in 'a'..'z' }) return word
        var w = word
        // Plurals and third person.
        w = when {
            w.endsWith("sses") -> w.dropLast(2)
            w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
            w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") -> w.dropLast(1)
            else -> w
        }
        // Gerunds, past tense and adverbs; then undo the consonant doubling they cause.
        val stripped = when {
            w.endsWith("ing") && w.length > 5 -> w.dropLast(3)
            w.endsWith("ed") && w.length > 4 -> w.dropLast(2)
            w.endsWith("ly") && w.length > 5 -> w.dropLast(2)
            else -> null
        }
        if (stripped != null) {
            w = stripped
            val last = w.last()
            if (w.length >= 3 && last == w[w.length - 2] && last !in "aeioulsz") w = w.dropLast(1)
        }
        // A final silent e: "make" and "making" both become "mak".
        if (w.length > 3 && w.endsWith("e")) w = w.dropLast(1)
        return w
    }
}
