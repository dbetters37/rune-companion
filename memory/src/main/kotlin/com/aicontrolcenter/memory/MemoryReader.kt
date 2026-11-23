package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier

/** Readable memories, newest first; [bounded] is true when the limit may have left some out. */
internal class Collected(val records: List<MemoryRecord>, val bounded: Boolean)

/**
 * Fetches the memories an actor may read, bounded, for recall and the graph.
 *
 * Scopes are passed to the repository explicitly, so storage only loads what is readable.
 * The one exception is task memory: an agent may read all of its own tasks' memory, but task
 * ids cannot be listed in advance, so short-term memories are scanned and the agent's own task
 * scopes picked out. This stays cheap because task memory is always short-term and short-term
 * memory expires (three days by default), which bounds that scan.
 */
internal class MemoryReader(private val repository: MemoryRepository) {
    /**
     * [scopes]: the scopes to read, each checked against the actor's access; null means every
     * scope and is only allowed for the owner. [taskAgents]: when set, also the task memory of
     * agents it accepts (and the actor may read).
     */
    suspend fun collect(
        who: ResolvedActor,
        base: MemoryFilter,
        scopes: Set<MemoryScope>?,
        taskAgents: ((AgentId) -> Boolean)?,
        limit: Int,
    ): Collected {
        val access = who.access
        if (scopes == null) {
            check(access.everything) { "Only the owner may read every scope." }
            val all = repository.list(base, limit)
            return Collected(all, all.size >= limit)
        }
        val allowed = scopes.filterTo(HashSet(), access::canRead)
        val explicit = if (allowed.isEmpty()) emptyList() else repository.list(base.copy(scopes = allowed), limit)
        if (taskAgents == null || base.tier == MemoryTier.LONG_TERM) return Collected(explicit, explicit.size >= limit)
        val tasks = repository.list(base.copy(scopes = null, tier = MemoryTier.SHORT_TERM)).filter { record ->
            val scope = record.scope
            scope is MemoryScope.Task && scope !in allowed && access.canRead(scope) && taskAgents(scope.agentId)
        }
        if (tasks.isEmpty()) return Collected(explicit, explicit.size >= limit)
        val merged = (explicit + tasks).sortedWith(NEWEST_FIRST)
        return Collected(merged.take(limit), explicit.size >= limit || merged.size > limit)
    }

    companion object {
        /** The repository's own order: newest update first, then id. */
        val NEWEST_FIRST: Comparator<MemoryRecord> = compareByDescending<MemoryRecord> { it.updatedAt }.thenBy { it.id.value }
    }
}
