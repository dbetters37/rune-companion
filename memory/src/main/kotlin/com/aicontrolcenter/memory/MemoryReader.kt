package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier

/** Readable memories, newest first; [bounded] is true when a limit may have left some out. */
internal class Collected(val records: List<MemoryRecord>, val bounded: Boolean)

/**
 * Fetches the memories an actor may read, bounded, for recall and the graph.
 *
 * - Private and shared scopes are passed to the repository explicitly, so storage only loads
 *   what is readable.
 * - Task memory is read per owning agent with [MemoryRepository.taskMemoryOf], never by
 *   listing task scopes (an agent may have thousands of tasks) and never by scanning other
 *   agents' memory. Only agents the actor may read the task memory of are asked for.
 * - Two bounds apply to what is returned: at most `limit` memories, and at most
 *   [MemoryConfig.candidateTextBudget] characters of text (title, content and tags) in total,
 *   newest first. Memories are fetched in growing pages, so a store of very long memories is
 *   never loaded wholesale just to be cut down afterwards.
 */
internal class MemoryReader(private val repository: MemoryRepository, private val config: MemoryConfig) {
    /**
     * [scopes]: the scopes to read, each checked against the actor's access; null means every
     * scope and is only allowed for the owner. Task scopes in it are read through their agent's
     * task memory. [taskAgents]: agents whose task memory (all their tasks) is read as well,
     * where the actor may read it.
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
            return paged(limit) { n -> repository.list(base, n) }
        }
        val allowed = scopes.filterTo(HashSet(), access::canRead)
        val explicit = allowed.filterNotTo(HashSet()) { it is MemoryScope.Task }
        val parts = ArrayList<Collected>()
        if (explicit.isNotEmpty()) parts += paged(limit) { n -> repository.list(base.copy(scopes = explicit), n) }

        if (base.tier != MemoryTier.LONG_TERM) {
            val requestedTasks = allowed.filterIsInstance<MemoryScope.Task>().groupBy { it.agentId }.mapValues { (_, list) -> list.toSet() }
            val wholeAgents = taskAgents.filterTo(HashSet()) { access.everything || it == access.taskOwner }
            for (agentId in (wholeAgents + requestedTasks.keys).sortedBy { it.value }) {
                val only = if (agentId in wholeAgents) null else requestedTasks.getValue(agentId)
                val tasks = paged(limit) { n -> repository.taskMemoryOf(agentId, base.excludeExpiredAt, n) }
                val kept = tasks.records.filter { base.matches(it) && (only == null || it.scope in only) }
                parts += Collected(kept, tasks.bounded)
            }
        }
        return when (parts.size) {
            0 -> Collected(emptyList(), false)
            1 -> parts.single()
            else -> {
                val merged = parts.flatMap { it.records }.distinctBy { it.id }.sortedWith(NEWEST_FIRST)
                val kept = withinBudget(merged.take(limit), config.candidateTextBudget)
                Collected(kept, parts.any { it.bounded } || kept.size < merged.size)
            }
        }
    }

    /**
     * The newest memories [fetch] returns, at most [limit] and within the text budget. Starts
     * with a small page and grows it (at most [GROWTH] times per step, and no further than the
     * text seen so far suggests fits the budget), so each read is bounded by what the previous
     * one showed. Reads at most three times for the default limits.
     */
    suspend fun paged(limit: Int, fetch: suspend (Int) -> List<MemoryRecord>): Collected {
        if (limit <= 0) return Collected(emptyList(), true)
        val budget = config.candidateTextBudget
        var n = minOf(limit, FIRST_PAGE)
        while (true) {
            val page = fetch(n)
            val kept = withinBudget(page, budget)
            if (kept.size < page.size) return Collected(kept, true)
            if (page.size < n) return Collected(page, false)
            if (n >= limit) return Collected(page, true)
            val perRecord = (page.sumOf(::textSize) / page.size).coerceAtLeast(1L)
            val affordable = budget / perRecord
            n = minOf(limit.toLong(), n.toLong() * GROWTH, maxOf(n + 1L, affordable)).toInt()
        }
    }

    companion object {
        /** The repository's own order: newest update first, then id. */
        val NEWEST_FIRST: Comparator<MemoryRecord> = compareByDescending<MemoryRecord> { it.updatedAt }.thenBy { it.id.value }

        const val FIRST_PAGE = 500
        const val GROWTH = 4

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
