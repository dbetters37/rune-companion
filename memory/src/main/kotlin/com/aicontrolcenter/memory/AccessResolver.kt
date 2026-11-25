package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.memory.MemoryAccess
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.registry.AgentDirectory

/** An actor with its permissions worked out for one call. */
internal class ResolvedActor(
    val access: MemoryAccess,
    /** Null for the owner. */
    val agentId: AgentId?,
    /** "The owner" or the agent's current name, for messages and the activity log. */
    val name: String,
    /** When set, the agent may use no memory at all, and this sentence says why. */
    val unavailable: String? = null,
) {
    val isOwner: Boolean get() = agentId == null

    /** "owner" or "agent:<id>", for structured activity data. */
    val key: String get() = agentId?.let { "agent:${it.value}" } ?: "owner"
}

/**
 * Works out what an actor may read and write, and explains refusals.
 *
 * An agent's permissions are always read from its stored profile, re-loaded on every call:
 * the profile inside [MemoryActor.Agent] may be minutes old, and a space the owner took away
 * a moment ago must not stay usable. A missing or disabled agent may use nothing. Spaces in the
 * policy that no longer exist are ignored, so a deleted space never "comes back" if a new one
 * happened to reuse its id.
 */
internal class AccessResolver(
    private val agents: AgentDirectory,
    private val repository: MemoryRepository,
) {
    suspend fun resolve(actor: MemoryActor): ResolvedActor = when (actor) {
        MemoryActor.Owner -> OWNER
        is MemoryActor.Agent -> {
            val id = actor.profile.id
            val profile = agents.get(id)
            val existing = profile?.memory?.let { policy ->
                (policy.readableSpaces + policy.writableSpaces).filterTo(HashSet()) { repository.getSpace(it) != null }
            }.orEmpty()
            forProfile(id, profile, existing, actor.profile.name)
        }
    }

    /** Pure: the access of agent [id] given its current [profile] (null: deleted) and the spaces that exist. */
    fun forProfile(id: AgentId, profile: AgentProfile?, existingSpaces: Set<SpaceId>, fallbackName: String): ResolvedActor {
        if (profile == null) {
            return ResolvedActor(NOTHING, id, fallbackName.ifBlank { "This agent" }, "This agent no longer exists, so it cannot use memory.")
        }
        val name = profile.name.ifBlank { "This agent" }
        if (!profile.enabled) {
            return ResolvedActor(NOTHING, id, name, "$name is switched off, so it cannot use memory. Switch it on in its agent settings first.")
        }
        val policy = profile.memory
        val own = if (policy.privateMemoryEnabled) setOf<MemoryScope>(MemoryScope.Agent(id)) else emptySet()
        val readable = own + policy.readableSpaces.filter { it in existingSpaces }.map { MemoryScope.Shared(it) }
        val writable = own + policy.writableSpaces.filter { it in existingSpaces }.map { MemoryScope.Shared(it) }
        return ResolvedActor(MemoryAccess(readable = readable, writable = writable, taskOwner = id), id, name)
    }

    /** A sentence telling [actor] why it may not read (or write) [scope], and what would change that. */
    suspend fun refusal(actor: ResolvedActor, scope: MemoryScope, write: Boolean): String {
        actor.unavailable?.let { return it }
        val who = actor.name
        val verb = if (write) "write to" else "read"
        return when (scope) {
            is MemoryScope.Agent ->
                if (scope.agentId == actor.agentId) {
                    "$who has private memory switched off. Turn on private memory in $who's memory settings to let it keep its own memories."
                } else {
                    "$who may not use another agent's private memory. Share the memory into a knowledge space both agents can use instead."
                }
            is MemoryScope.Task -> "$who may not use the task memory of another agent's task."
            is MemoryScope.Shared -> {
                val space = repository.getSpace(scope.spaceId)
                if (space == null) {
                    "The knowledge space ${scope.spaceId.value} does not exist."
                } else {
                    val list = if (write) "writable" else "readable"
                    "$who may not $verb the knowledge space \"${space.name}\". The owner can add it to $who's $list spaces in its memory settings."
                }
            }
        }
    }

    companion object {
        val OWNER = ResolvedActor(MemoryAccess(readable = emptySet(), writable = emptySet(), everything = true), null, "The owner")
        private val NOTHING = MemoryAccess(readable = emptySet(), writable = emptySet())
    }
}
