package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import kotlin.math.ceil

/** Readable memories, newest first; [bounded] is true when a limit may have left some out. */
internal class Collected(val records: List<MemoryRecord>, val bounded: Boolean)

/**
 * Fetches the memories an actor may read, bounded, for recall and the graph.
 *
 * - Scopes the caller names (private, shared and task scopes alike) are passed to the
 *   repository explicitly, with the kinds filter, so storage selects exactly what was asked
 *   for. They are sent [SCOPE_CHUNK] at a time, well under SQLite's 999 bound variables.
 * - An agent's whole task memory (every task, when no task scopes are named) is read with
 *   [MemoryRepository.taskMemoryOf], never by listing its task scopes and never by scanning
 *   other agents' memory. Only agents the actor may read the task memory of are asked for.
 * - Two bounds apply to the whole read, all parts together: at most `limit` memories, and at
 *   most [MemoryConfig.candidateTextBudget] characters of text (title, content and tags), newest
 *   first. The parts are read in rounds of growing pages and merged; rows that can no longer
 *   be among the newest are dropped after each round, so what is held stays near the limit.
 */
internal class MemoryReader(private val repository: MemoryRepository, private val config: MemoryConfig) {
    /**
     * [scopes]: the scopes to read, each checked against the actor's access; null means every
     * scope and is only allowed for the owner. [taskAgents]: agents whose task memory (all their
     * tasks) is read as well, where the actor may read it.
     */
    suspend fun collect(
        who: ResolvedActor,
        base: MemoryFilter,
        scopes: Set<MemoryScope>?,
        taskAgents: Set<AgentId>,
        limit: Int,
    ): Collected {
        val access = who.access
        if (scopes == null) {
            check(access.everything) { "Only the owner may read every scope." }
            return merged(listOf(Part { n -> repository.list(base, n) }), limit)
        }
        val tasksPossible = base.tier != MemoryTier.LONG_TERM
        val wholeAgents = if (!tasksPossible) emptySet() else taskAgents.filterTo(HashSet()) { access.everything || it == access.taskOwner }
        val explicit = scopes.filter { scope ->
            access.canRead(scope) && (scope !is MemoryScope.Task || (tasksPossible && scope.agentId !in wholeAgents))
        }
        val parts = ArrayList<Part>()
        chunks(explicit).forEach { chunk -> parts += Part { n -> repository.list(base.copy(scopes = chunk), n) } }
        wholeAgents.sortedBy { it.value }.forEach { agentId ->
            // taskMemoryOf cannot filter by kind: the rest of the filter is applied here, and pages count every row read.
            parts += Part(keep = base::matches) { n -> repository.taskMemoryOf(agentId, base.excludeExpiredAt, n) }
        }
        return merged(parts, limit)
    }

    /** One source of memories, newest first: [fetch] returns its newest n rows, of which [keep] are wanted. */
    private class Part(val keep: ((MemoryRecord) -> Boolean)? = null, val fetch: suspend (Int) -> List<MemoryRecord>) {
        /** Rows asked for and returned by the last read, matching or not, and the last of them. */
        var asked = 0
        var returned = 0
        var last: MemoryRecord? = null

        /** Wanted rows of the last read, and how many there were before rows that cannot be kept were dropped. */
        var rows: List<MemoryRecord> = emptyList()
        var matched = 0

        /** The last read was full, so older rows may follow. */
        val more: Boolean get() = asked > 0 && returned == asked

        suspend fun read(n: Int) {
            val page = fetch(n)
            asked = n
            returned = page.size
            last = page.lastOrNull()
            rows = if (keep == null) page else page.filter(keep)
            matched = rows.size
        }
    }

    /**
     * The newest wanted rows of all [parts] together, at most [limit] and within the text budget.
     *
     * Each round reads some parts and merges everything read. Rows down to the *boundary* (the
     * newest last row among parts that may have more) are complete, because every part that
     * still has unread rows has read past it. When the newest `limit` rows (or the rows that
     * fit the budget) all lie within the boundary, the result is exact. Otherwise the parts
     * that may hold the missing rows grow, each by its share of what is still missing (judged
     * by how many of the complete rows it supplied), and the round's reads together grow at
     * most [GROWTH] times over the previous ones, so a store of very long memories behind short
     * new ones is never loaded wholesale. A single part reads 500, 2,000, 5,000 rows for the
     * default limits.
     */
    private suspend fun merged(parts: List<Part>, limit: Int): Collected {
        if (limit <= 0) return Collected(emptyList(), true)
        if (parts.isEmpty()) return Collected(emptyList(), false)
        val budget = config.candidateTextBudget
        val probe = minOf(limit, if (parts.size == 1) FIRST_PAGE else maxOf(MIN_PROBE, FIRST_PAGE / parts.size))
        var reads: Map<Part, Int> = parts.associateWith { probe }
        var round = 0
        while (true) {
            for ((part, n) in reads) part.read(n)
            round++
            val all = parts.flatMap { it.rows }.sortedWith(NEWEST_FIRST)
            val kept = withinBudget(all.take(limit), budget)
            // A row after the last kept one can never be kept: more rows only ever come before it.
            kept.lastOrNull()?.let { cut -> parts.forEach { part -> part.rows = part.rows.filter { NEWEST_FIRST.compare(it, cut) <= 0 } } }
            val growable = parts.filter { it.more && it.asked < limit }
            val boundary = growable.map { it.last!! }.minWithOrNull(NEWEST_FIRST)
            val bounded = parts.any { it.more } || kept.size < all.size
            if (boundary == null) return Collected(kept, bounded)
            val complete = kept.filter { NEWEST_FIRST.compare(it, boundary) <= 0 }
            val saturated = kept.size == limit || kept.size < minOf(limit, all.size)
            if ((saturated && complete.size == kept.size) || round >= MAX_ROUNDS) return Collected(complete, true)
            reads = nextReads(parts, growable, all, boundary, limit, budget)
        }
    }

    /** How many rows each part that may hold missing rows reads next. */
    private fun nextReads(parts: List<Part>, growable: List<Part>, all: List<MemoryRecord>, boundary: MemoryRecord, limit: Int, budget: Long): Map<Part, Int> {
        val complete = all.takeWhile { NEWEST_FIRST.compare(it, boundary) <= 0 }
        val perRecord = if (all.isEmpty()) 1.0 else all.sumOf(::textSize).toDouble() / all.size
        val textLeft = (budget - complete.sumOf(::textSize)).coerceAtLeast(0)
        val fitting = ceil(textLeft / perRecord.coerceAtLeast(1.0)) + 1
        val missing = minOf((limit - complete.size).toDouble(), fitting).toInt().coerceAtLeast(1)
        val supplied = growable.associateWith { part -> part.rows.count { NEWEST_FIRST.compare(it, boundary) <= 0 } }
        val total = supplied.values.sum()
        val first = growable.first { it.last === boundary }
        val wanted = growable.mapNotNull { part ->
            val share = if (total == 0) missing.toDouble() / growable.size else missing.toDouble() * supplied.getValue(part) / total
            val beyond = part.rows.size - supplied.getValue(part)
            val rowsPerWanted = if (part.matched == 0) GROWTH.toDouble() else part.returned.toDouble() / part.matched
            val extra = ceil((share - beyond).coerceAtLeast(0.0) * (1 + MARGIN) * rowsPerWanted).toLong()
            val floor = if (part === first) maxOf(1, missing / growable.size).toLong() else 0L
            val n = minOf(limit.toLong(), part.asked + maxOf(extra, floor) + (if (extra > 0 || floor > 0) 1 else 0))
            if (n > part.asked) part to n else null
        }
        // Together the reads grow at most GROWTH times over what is held now.
        val cap = GROWTH.toLong() * parts.sumOf { it.asked.toLong() }
        val sum = wanted.sumOf { it.second }
        val scale = if (sum <= cap) 1.0 else cap.toDouble() / sum
        return wanted.associate { (part, n) -> part to maxOf(part.asked + 1L, (n * scale).toLong()).coerceAtMost(limit.toLong()).toInt() }
    }

    companion object {
        /** The repository's own order: newest update first, then id. */
        val NEWEST_FIRST: Comparator<MemoryRecord> = compareByDescending<MemoryRecord> { it.updatedAt }.thenBy { it.id.value }

        const val FIRST_PAGE = 500
        const val GROWTH = 4

        /** Scopes per query: at most two bound variables each, so far below SQLite's 999. */
        const val SCOPE_CHUNK = 250

        /** The smallest first page of one of many parts. */
        const val MIN_PROBE = 16

        /** Extra rows asked for over a part's estimated share, so a round rarely falls just short. */
        const val MARGIN = 0.02

        /** Reads after which the complete rows found so far are returned, marked as bounded. */
        const val MAX_ROUNDS = 8

        /** [scopes] in queries of at most [SCOPE_CHUNK], tasks of one agent together. */
        fun chunks(scopes: Collection<MemoryScope>): List<Set<MemoryScope>> =
            scopes.sortedBy { scope ->
                when (scope) {
                    is MemoryScope.Agent -> "a:${scope.agentId.value}"
                    is MemoryScope.Shared -> "s:${scope.spaceId.value}"
                    is MemoryScope.Task -> "t:${scope.agentId.value}:${scope.taskId.value}"
                }
            }.chunked(SCOPE_CHUNK).map { it.toSet() }

        /** Characters of text a memory holds: title, content and tags. */
        fun textSize(record: MemoryRecord): Long =
            record.title.length.toLong() + record.content.length + record.tags.sumOf { it.length.toLong() }

        /** The longest prefix of [records] whose text fits [budget]; the first record is always kept. */
        fun withinBudget(records: List<MemoryRecord>, budget: Long): List<MemoryRecord> {
            var used = 0L
            records.forEachIndexed { i, record ->
                used += textSize(record)
                // A copy, so the records left out can be collected while the kept ones are scored.
                if (used > budget && i > 0) return records.subList(0, i).toList()
            }
            return records
        }
    }
}
