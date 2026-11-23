package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.memory.GraphRequest
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryGraphSnapshot
import com.aicontrolcenter.core.memory.MemoryScope
import kotlinx.coroutines.flow.first

/**
 * Selects what a graph shows, always within what the actor may read:
 * - [GraphRequest.agentIds] narrows private (and task) memory to those agents, and
 *   [GraphRequest.spaceIds] narrows shared memory to those spaces; null leaves that kind of
 *   memory unfiltered, an empty set leaves it out. So `agentIds = {a}, spaceIds = {}` is
 *   "only a's own memory".
 * - Task memory appears only with [GraphRequest.includeTaskMemory].
 * - [GraphRequest.since] keeps memories updated at or after that time.
 * - Expired memories never appear. At most [MemoryConfig.candidateLimit] readable memories are
 *   considered (newest first); beyond that the snapshot is marked truncated.
 */
internal class MemoryGraphExporter(
    private val ctx: MemoryContext,
    private val reader: MemoryReader,
    private val builder: MemoryGraphBuilder,
) {
    suspend fun graph(who: ResolvedActor, request: GraphRequest): MemoryGraphSnapshot {
        val now = ctx.clock.now()
        val base = MemoryFilter(excludeExpiredAt = now)
        val agentIds = request.agentIds
        val spaceIds = request.spaceIds
        val taskAgents = if (request.includeTaskMemory) { id: AgentId -> agentIds == null || id in agentIds } else null
        val collected = if (who.access.everything && agentIds == null && spaceIds == null) {
            reader.collect(who, base, null, null, ctx.config.candidateLimit)
        } else {
            val scopes: Set<MemoryScope> = if (who.access.everything) {
                val agentScopes = (agentIds ?: ctx.agents.getAll().map { it.id }.toSet()).map { MemoryScope.Agent(it) }
                val spaceScopes = (spaceIds ?: ctx.repository.observeSpaces().first().map { it.id }.toSet()).map { MemoryScope.Shared(it) }
                (agentScopes + spaceScopes).toSet()
            } else {
                who.access.readable.filterTo(HashSet()) { scope ->
                    when (scope) {
                        is MemoryScope.Agent -> agentIds == null || scope.agentId in agentIds
                        is MemoryScope.Shared -> spaceIds == null || scope.spaceId in spaceIds
                        is MemoryScope.Task -> false
                    }
                }
            }
            reader.collect(who, base, scopes, taskAgents, ctx.config.candidateLimit)
        }
        val since = request.since
        val records = collected.records.filter { record ->
            val scope = record.scope
            (scope !is MemoryScope.Task || (taskAgents != null && taskAgents(scope.agentId))) &&
                (since == null || record.updatedAt >= since)
        }
        val scopes = records.mapTo(HashSet()) { it.scope }
        val links = if (scopes.isEmpty()) emptyList() else ctx.repository.links(base.copy(scopes = scopes))
        return builder.build(
            records = records,
            links = links,
            agentNames = ctx.agents.getAll().associate { it.id to it.name },
            spaceNames = ctx.repository.observeSpaces().first().associate { it.id to it.name },
            maxNodes = request.maxNodes,
            alreadyTruncated = collected.bounded,
            now = now,
        )
    }
}
