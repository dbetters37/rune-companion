package com.aicontrolcenter.memory

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryLink
import com.aicontrolcenter.core.memory.MemoryPermissionException
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceAction
import com.aicontrolcenter.core.memory.ProvenanceEvent
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.registry.AgentRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the parts of [DefaultMemoryService] share: the ports, the access rules and the guards
 * every operation goes through. A refusal is logged as a PERMISSION event and thrown as a
 * [MemoryPermissionException] carrying the same actionable sentence.
 */
internal class MemoryContext(
    val repository: MemoryRepository,
    val agents: AgentRepository,
    val ids: IdGenerator,
    val clock: Clock,
    val config: MemoryConfig,
    val resolver: AccessResolver,
    val log: MemoryActivity,
    val vectors: MemoryEmbeddings,
) {
    /**
     * Serialises every write within this process: read-modify-write sequences (revision
     * increments, "does a copy exist yet" checks, unique space names) and every deletion
     * (forget, forgetting an agent, deleting a space, expiry and the clean-up in maintenance).
     * So a memory checked to exist inside the lock still exists when it is written, an update or
     * share never recreates a memory forgotten meanwhile, and concurrent calls never lose an
     * update or create duplicates. Embedding happens outside it, as embedders may be slow.
     */
    val writeLock = Mutex()

    /**
     * Runs [block] holding [writeLock], with [actor]'s access resolved inside the lock: a policy
     * change or deletion that finished while this call waited for the lock already applies.
     */
    suspend inline fun <T> writing(actor: MemoryActor, block: (ResolvedActor) -> T): T =
        writeLock.withLock { block(resolver.resolve(actor)) }

    /** A history entry saying that [who] did [action] at [at]. */
    fun event(action: ProvenanceAction, who: ResolvedActor, at: Long, related: MemoryId? = null, note: String? = null) =
        ProvenanceEvent(
            action = action, byOrigin = if (who.isOwner) ProvenanceOrigin.OWNER else ProvenanceOrigin.AGENT,
            byAgentId = who.agentId, at = at, relatedMemory = related, note = note,
        )

    /**
     * Stores [records] and [links] together or not at all. When the repository refuses because
     * [required] no longer exists (it was deleted by something outside this engine), the
     * caller gets the same refusal as for a memory that never existed.
     */
    suspend fun writeAll(records: List<MemoryRecord>, links: List<MemoryLink>, required: MemoryId) {
        try {
            repository.upsertAll(records, links)
        } catch (e: IllegalArgumentException) {
            if (repository.get(required) == null) MemoryRules.fail("There is no memory with id ${required.value}.")
            throw e
        }
    }

    suspend fun deny(who: ResolvedActor, message: String): Nothing {
        log.refused(who, message)
        throw MemoryPermissionException(message)
    }

    suspend fun requireRead(who: ResolvedActor, scope: MemoryScope) {
        if (!who.access.canRead(scope)) deny(who, resolver.refusal(who, scope, write = false))
    }

    suspend fun requireWrite(who: ResolvedActor, scope: MemoryScope) {
        if (!who.access.canWrite(scope)) deny(who, resolver.refusal(who, scope, write = true))
    }

    suspend fun requireOwner(who: ResolvedActor) {
        if (!who.isOwner) deny(who, "Only the owner can create, rename or delete knowledge spaces. Ask the owner to do it in Memory settings.")
    }

    /** A shared scope must name a space that exists; a private or task scope an agent that exists. */
    suspend fun requireScopeExists(scope: MemoryScope) {
        when (scope) {
            is MemoryScope.Shared -> if (repository.getSpace(scope.spaceId) == null) MemoryRules.fail("There is no knowledge space with id ${scope.spaceId.value}.")
            is MemoryScope.Agent -> if (agents.get(scope.agentId) == null) MemoryRules.fail("There is no agent with id ${scope.agentId.value}.")
            is MemoryScope.Task -> if (agents.get(scope.agentId) == null) MemoryRules.fail("There is no agent with id ${scope.agentId.value}.")
        }
    }

    suspend fun existing(id: MemoryId): MemoryRecord = repository.get(id) ?: MemoryRules.fail("There is no memory with id ${id.value}.")

    /** Provenance for something [who] creates directly, such as a link. */
    fun provenanceOf(who: ResolvedActor, now: Long, source: String? = null) = Provenance(
        origin = if (who.isOwner) ProvenanceOrigin.OWNER else ProvenanceOrigin.AGENT,
        agentId = who.agentId, source = source, recordedAt = now,
    )

    /** Expiry time [ttlMs] from [now], saturating instead of overflowing. */
    fun expiry(now: Long, ttlMs: Long): Long = if (ttlMs > Long.MAX_VALUE - now) Long.MAX_VALUE else now + ttlMs
}
