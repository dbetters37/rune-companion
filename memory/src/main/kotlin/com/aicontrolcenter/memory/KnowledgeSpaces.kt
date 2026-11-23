package com.aicontrolcenter.memory

import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.spaceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Shared knowledge spaces; only the owner manages them. Names are unique ignoring case, so two
 * spaces can never look the same in an agent's memory settings.
 *
 * Deleting a space first removes it from every agent's readable and writable spaces (through
 * [com.aicontrolcenter.core.registry.AgentRepository]) and then deletes it with its memories.
 * That order means a crash in between leaves agents without access to a space that still
 * exists (harmless, the owner can delete it again) rather than with access to one that is gone.
 */
internal class KnowledgeSpaces(private val ctx: MemoryContext) {
    suspend fun create(who: ResolvedActor, name: String, description: String): KnowledgeSpace {
        ctx.requireOwner(who)
        val n = MemoryRules.spaceName(name)
        val d = MemoryRules.spaceDescription(description)
        val space = ctx.writeLock.withLock {
            requireUniqueName(n, except = null)
            val now = ctx.clock.now()
            KnowledgeSpace(ctx.ids.spaceId(), n, d, now, now).also { ctx.repository.upsertSpace(it) }
        }
        ctx.log.event(who, "Knowledge space created: ${space.name}", "Agents can use it once it is added to their memory settings.", data(space.id))
        return space
    }

    suspend fun update(who: ResolvedActor, id: SpaceId, name: String, description: String): KnowledgeSpace {
        ctx.requireOwner(who)
        val n = MemoryRules.spaceName(name)
        val d = MemoryRules.spaceDescription(description)
        val (before, after) = ctx.writeLock.withLock {
            val current = ctx.repository.getSpace(id) ?: MemoryRules.fail("There is no knowledge space with id ${id.value}.")
            requireUniqueName(n, except = id)
            val updated = current.copy(name = n, description = d, updatedAt = ctx.clock.now())
            ctx.repository.upsertSpace(updated)
            current to updated
        }
        val renamed = if (before.name != after.name) " (was \"${before.name}\")" else ""
        ctx.log.event(who, "Knowledge space updated: ${after.name}", "Name and description saved$renamed.", data(id))
        return after
    }

    suspend fun delete(who: ResolvedActor, id: SpaceId): Int {
        ctx.requireOwner(who)
        val (space, removedFrom, deleted) = ctx.writeLock.withLock {
            val space = ctx.repository.getSpace(id) ?: return 0
            val now = ctx.clock.now()
            var removedFrom = 0
            for (agent in ctx.agents.getAll()) {
                val policy = agent.memory
                if (id !in policy.readableSpaces && id !in policy.writableSpaces) continue
                ctx.agents.upsert(
                    agent.copy(memory = policy.copy(readableSpaces = policy.readableSpaces - id, writableSpaces = policy.writableSpaces - id), updatedAt = now),
                )
                removedFrom++
            }
            Triple(space, removedFrom, ctx.repository.deleteSpace(id))
        }
        ctx.log.event(
            who, "Knowledge space deleted: ${space.name}",
            "Deleted $deleted memor${if (deleted == 1) "y" else "ies"} and removed the space from $removedFrom agent${if (removedFrom == 1) "" else "s"}' memory settings.",
            buildJsonObject {
                put("spaceId", id.value)
                put("scope", MemoryScope.Shared(id).key)
                put("memoriesDeleted", deleted)
                put("agentsUpdated", removedFrom)
            },
        )
        return deleted
    }

    private suspend fun requireUniqueName(name: String, except: SpaceId?) {
        val key = name.lowercase(Locale.ROOT)
        if (ctx.repository.observeSpaces().first().any { it.id != except && it.name.lowercase(Locale.ROOT) == key }) {
            MemoryRules.fail("A knowledge space named \"$name\" already exists.")
        }
    }

    private fun data(id: SpaceId) = buildJsonObject {
        put("spaceId", id.value)
        put("scope", MemoryScope.Shared(id).key)
    }
}
