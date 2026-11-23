package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryLink
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.memoryId
import com.aicontrolcenter.core.memoryLinkId
import kotlinx.coroutines.sync.withLock

/**
 * Moving knowledge between scopes without ever moving the original:
 * - sharing copies a memory into a knowledge space, linked to its original;
 * - promotion copies a finished task's important working memory into the agent's long-term
 *   private memory.
 * Copies start fresh (revision 1, never accessed) and their provenance names the original in
 * `derivedFrom`, so their history is never lost.
 */
internal class MemorySharing(private val ctx: MemoryContext) {
    /**
     * Copies [id] into [spaceId]. The actor must read the original and write the space. The copy
     * keeps kind, tier, tags, importance and confidence; a short-term copy expires with its
     * original. Sharing the same memory into the same space again returns the existing copy, and
     * sharing a memory into the space it already lives in returns it unchanged.
     */
    suspend fun share(who: ResolvedActor, id: MemoryId, spaceId: SpaceId): MemoryRecord {
        val target = MemoryScope.Shared(spaceId)
        val now = ctx.clock.now()
        val (original, copy, created) = ctx.writeLock.withLock {
            val original = ctx.existing(id)
            ctx.requireRead(who, original.scope)
            ctx.requireScopeExists(target)
            ctx.requireWrite(who, target)
            if (original.scope == target) return original
            val copies = ctx.repository.linksOf(id).filter { it.to == id && it.relation == LinkRelation.SHARED_COPY_OF }.map { it.from }
            ctx.repository.getAll(copies).firstOrNull { it.scope == target }?.let { return it }
            val copy = original.copy(
                id = ctx.ids.memoryId(), scope = target,
                provenance = Provenance(
                    origin = ProvenanceOrigin.DERIVED, agentId = who.agentId, taskId = original.provenance.taskId,
                    conversationId = original.provenance.conversationId, modelId = original.provenance.modelId,
                    modelName = original.provenance.modelName, derivedFrom = listOf(original.id),
                    source = "Shared copy of memory ${original.id.value}", recordedAt = now,
                ),
                createdAt = now, updatedAt = now, lastAccessedAt = null, accessCount = 0, revision = 1,
            )
            ctx.repository.upsert(copy)
            ctx.repository.upsertLink(
                MemoryLink(ctx.ids.memoryLinkId(), copy.id, original.id, LinkRelation.SHARED_COPY_OF, 1.0, ctx.provenanceOf(who, now), now),
            )
            Triple(original, copy, true)
        }
        if (created) {
            ctx.log.changed(who, "Memory shared", copy, "Copied from ${ctx.log.describe(original.scope)}.")
            ctx.vectors.store(listOf(copy), who.agentId, replacing = false)
        }
        return copy
    }

    /**
     * Promotes task [taskId] of [agentId]: each unexpired task memory with importance of at
     * least [minImportance] is copied into the agent's private memory as a long-term memory
     * (provenance SYSTEM, derived from the task memory), and the task memory it came from
     * expires at once, so recall never returns both. The rest keep their expiry and fade.
     * Calling it again promotes nothing twice: promoted originals have already expired.
     *
     * The actor must be able to write that task memory (the owner, or the agent itself). An
     * agent with private memory switched off keeps nothing: the call returns an empty list.
     */
    suspend fun promote(who: ResolvedActor, taskId: TaskId, agentId: AgentId, minImportance: Double): List<MemoryRecord> {
        MemoryRules.unit("The minimum importance", minImportance)
        val taskScope = MemoryScope.Task(taskId, agentId)
        ctx.requireScopeExists(taskScope)
        ctx.requireWrite(who, taskScope)
        val profile = ctx.agents.get(agentId) ?: MemoryRules.fail("There is no agent with id ${agentId.value}.")
        val now = ctx.clock.now()
        if (!profile.memory.privateMemoryEnabled) {
            ctx.log.event(who, "Task memory not kept", "${profile.name} has private memory switched off, so nothing from task ${taskId.value} was kept; its task memory expires as usual.")
            return emptyList()
        }
        val promoted = ctx.writeLock.withLock {
            val important = ctx.repository.list(MemoryFilter(scopes = setOf(taskScope), excludeExpiredAt = now))
                .filter { it.importance >= minImportance }
                .sortedWith(compareBy<MemoryRecord> { it.createdAt }.thenBy { it.id.value })
            important.map { original ->
                val copy = original.copy(
                    id = ctx.ids.memoryId(), scope = MemoryScope.Agent(agentId), tier = MemoryTier.LONG_TERM, expiresAt = null,
                    provenance = Provenance(
                        origin = ProvenanceOrigin.SYSTEM, agentId = agentId, taskId = taskId,
                        conversationId = original.provenance.conversationId, modelId = original.provenance.modelId,
                        modelName = original.provenance.modelName, derivedFrom = listOf(original.id),
                        source = "Promoted from task ${taskId.value}", recordedAt = now,
                    ),
                    createdAt = now, updatedAt = now, lastAccessedAt = null, accessCount = 0, revision = 1,
                )
                ctx.repository.upsert(copy)
                ctx.repository.upsert(original.copy(expiresAt = now, updatedAt = now, revision = original.revision + 1))
                copy
            }
        }
        promoted.forEach { ctx.log.changed(who, "Task memory promoted", it, "Kept from task ${taskId.value}.") }
        ctx.vectors.store(promoted, who.agentId, replacing = false)
        return promoted
    }
}
