package com.aicontrolcenter.memory

import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.MemoryLinkId
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryLink
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memoryLinkId
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Typed, weighted relationships between memories. To link or unlink, an actor must be able to
 * read both memories and write the scope of the link's source, which is where the link
 * conceptually lives. Linking the same pair with the same relation again updates the weight
 * rather than adding a duplicate edge.
 */
internal class MemoryLinks(private val ctx: MemoryContext) {
    suspend fun link(who: ResolvedActor, from: MemoryId, to: MemoryId, relation: LinkRelation, weight: Double): MemoryLink {
        MemoryRules.unit("A link's weight", weight)
        if (from == to) MemoryRules.fail("A memory cannot be linked to itself.")
        val now = ctx.clock.now()
        val (link, ends) = ctx.writeLock.withLock {
            val ends = guardEnds(who, ctx.existing(from), ctx.existing(to))
            val existing = ctx.repository.linksOf(from).firstOrNull { it.from == from && it.to == to && it.relation == relation }
            val link = existing?.copy(weight = weight) ?: MemoryLink(
                id = ctx.ids.memoryLinkId(), from = from, to = to, relation = relation, weight = weight,
                provenance = ctx.provenanceOf(who, now), createdAt = now,
            )
            ctx.repository.upsertLink(link)
            link to ends
        }
        record(who, "Memories linked", link, ends)
        return link
    }

    suspend fun unlink(who: ResolvedActor, linkId: MemoryLinkId) {
        // The repository has no lookup by link id; links carry no content, so scanning them is cheap.
        val link = ctx.repository.links().firstOrNull { it.id == linkId } ?: return
        val from = ctx.repository.get(link.from) ?: return
        val to = ctx.repository.get(link.to) ?: return
        val ends = guardEnds(who, from, to)
        ctx.repository.deleteLink(linkId)
        record(who, "Memories unlinked", link, ends)
    }

    /** Links of a memory the actor may read, leaving out those whose other end it may not read. */
    suspend fun linksOf(who: ResolvedActor, id: MemoryId): List<MemoryLink> {
        val record = ctx.repository.get(id) ?: return emptyList()
        if (!who.access.canRead(record.scope)) return emptyList()
        val links = ctx.repository.linksOf(id)
        val others = ctx.repository.getAll(links.map { if (it.from == id) it.to else it.from }).associateBy { it.id }
        return links.filter { link ->
            val other = others[if (link.from == id) link.to else link.from]
            other != null && who.access.canRead(other.scope)
        }
    }

    private suspend fun guardEnds(who: ResolvedActor, from: MemoryRecord, to: MemoryRecord): Pair<MemoryRecord, MemoryRecord> {
        ctx.requireRead(who, from.scope)
        ctx.requireRead(who, to.scope)
        ctx.requireWrite(who, from.scope)
        return from to to
    }

    private suspend fun record(who: ResolvedActor, action: String, link: MemoryLink, ends: Pair<MemoryRecord, MemoryRecord>) {
        val (from, to) = ends
        ctx.log.event(
            who, "$action: ${from.title} → ${to.title}",
            "${link.relation.name} link, weight ${"%.2f".format(java.util.Locale.ROOT, link.weight)}, by ${if (who.isOwner) "the owner" else who.name}.",
            buildJsonObject {
                put("linkId", link.id.value)
                put("from", from.id.value)
                put("to", to.id.value)
                put("relation", link.relation.name)
                put("scope", from.scope.key)
            },
        )
    }
}
