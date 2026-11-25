package com.aicontrolcenter.framework.llm

/**
 * Whether two model names a provider uses mean the same model (Phase 6, D29).
 *
 * Providers answer under a dated or aliased name: "grok-4-latest" is answered as "grok-4-0709", "gpt-4o" as
 * "gpt-4o-2024-08-06", "gemma3" as "gemma3:latest". Those are the same model and the owner should not be told that
 * "another model answered". So before comparing, both names lose their vendor folder ("models/", "x-ai/"), the
 * Ollama tag ":latest", an "@date" (Vertex), and from the end, repeatedly, "-latest", "-beta", "-preview" and date
 * stamps (2026-01-01, 20260101, 01-01 and four-digit month-day or year-month stamps such as 0709 or 2411). Only
 * plausible dates count, so "llama3-70b-8192" keeps its "8192". Case is ignored.
 *
 * The comparison is information only (the journal's `modelSubstituted`, the chat's "answered by"): a missed
 * difference costs little, a false alarm worries the owner for nothing.
 */
object ModelNames {
    private val suffixes = listOf("-latest", "-beta", "-preview")
    private val dated = listOf(
        Regex("-(\\d{4})-(\\d{2})-(\\d{2})$") to { m: MatchResult -> validYear(m, 1) && validMonth(m, 2) && validDay(m, 3) },
        Regex("-(\\d{4})(\\d{2})(\\d{2})$") to { m: MatchResult -> validYear(m, 1) && validMonth(m, 2) && validDay(m, 3) },
        Regex("-(\\d{2})-(\\d{2})$") to { m: MatchResult -> validMonth(m, 1) && validDay(m, 2) },
        // Four digits: a month and day ("0709") or a year and month ("2411").
        Regex("-(\\d{2})(\\d{2})$") to { m: MatchResult -> (validMonth(m, 1) && validDay(m, 2)) || (validShortYear(m, 1) && validMonth(m, 2)) },
    )

    /** [name] without what does not change the model: vendor folder, ":latest", "@date", "-latest", "-beta", "-preview", date stamps. */
    fun base(name: String): String {
        var current = name.trim().lowercase().substringAfterLast('/')
        current = current.substringBefore('@')
        if (current.endsWith(":latest")) current = current.removeSuffix(":latest")
        while (true) {
            val shorter = stripOnce(current) ?: break
            if (shorter.isEmpty()) break
            current = shorter
        }
        return current
    }

    /** True when [a] and [b] name the same model once aliases and date stamps are set aside. Blank names are never the same. */
    fun sameModel(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        return base(a) == base(b)
    }

    private fun stripOnce(name: String): String? {
        suffixes.firstOrNull { name.endsWith(it) }?.let { return name.removeSuffix(it) }
        for ((pattern, valid) in dated) {
            val match = pattern.find(name) ?: continue
            if (valid(match)) return name.substring(0, match.range.first)
        }
        return null
    }

    private fun MatchResult.number(group: Int): Int = groupValues[group].toInt()

    private fun validYear(m: MatchResult, group: Int) = m.number(group) in 2000..2099

    private fun validShortYear(m: MatchResult, group: Int) = m.number(group) in 20..39

    private fun validMonth(m: MatchResult, group: Int) = m.number(group) in 1..12

    private fun validDay(m: MatchResult, group: Int) = m.number(group) in 1..31
}
