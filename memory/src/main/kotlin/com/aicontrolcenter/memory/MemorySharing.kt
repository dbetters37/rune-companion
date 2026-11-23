package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryLink
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceAction
import com.aicontrolcenter.core.memoryId
import com.aicontrolcenter.core.memoryLinkId

/**
 * Moving knowledge between scopes without ever moving the original:
 * - sharing copies a memory into a knowledge space, linked to its original;
 * - promotion copies a finished task's important working memory into the agent's long-term
 *   private memory, linked to the task memory it came from.
 *
 * Copies start fresh (revision 1, never accessed). Their provenance is the original's,
 * unchanged, with the original added to `derivedFrom`: it still names who first wrote the text
 * and where it came from, whoever shares or promotes it. Their history is the original's
 * history followed by a SHARED_FROM or PROMOTED_FROM event naming who copied it, when, and the
 * original. A copy and its link are written in one transaction, so a failure leaves neither.
 */
internal class MemorySharing(private val ctx: MemoryContext) {
    private sealed interface Shared {
        val record: MemoryRecord

        class Unchanged(override val record: MemoryRecord) : Shared
        class KeptEdited(override val record: MemoryRecord, val original: MemoryRecord) : Shared
        class Created(override val record: MemoryRecord, val original: MemoryRecord) : Shared
        class Refreshed(override val record: MemoryRecord, val original: MemoryRecord) : Shared
    }

    /**
     * Copies [id] into [spaceId]. The actor must read the original and write the space. The copy
     * keeps kind, tier, tags, importance and confidence; a short-term copy expires with its
     * original. Sharing a memory into the space it already lives in returns it unchanged.
     *
     * Sharing the same memory into the same space again returns the existing copy; when the
     * original changed since that copy was made (a newer revision), the copy is refreshed to the
     * original's current text first, but only while nobody has edited the copy since it was
     * shared: an edited copy is returned as it is, never overwritten, and the activity log says
     * so. An expired copy is ignored and a new one made.
     */
    suspend fun share(actor: MemoryActor, id: MemoryId, spaceId: SpaceId): MemoryRecord {
        val target = MemoryScope.Shared(spaceId)
        val (who, outcome) = ctx.writing(actor) { who ->
            val now = ctx.clock.now()
            val original = ctx.existing(id)
            ctx.requireRead(who, original.scope)
            ctx.requireScopeExists(target)
            ctx.requireWrite(who, target)
            if (original.scope == target) return original
            val existing = currentCopy(original, target, now)
            who to when {
                existing == null -> create(who, original, target, now)
                !isOutdated(existing.first, original) -> Shared.Unchanged(existing.first)
                original.id in sourcesOf(existing.first) -> refresh(who, existing.first, existing.second, original, now)
                else -> Shared.KeptEdited(existing.first, original)
            }
        }
        when (outcome) {
            is Shared.Unchanged -> Unit
            is Shared.KeptEdited -> ctx.log.changed(
                who, "Memory not re-shared", outcome.record,
                "The original in ${ctx.log.describe(outcome.original.scope)} changed, but the shared copy was edited since; left unchanged.",
            )
            is Shared.Created -> {
                ctx.log.changed(who, "Memory shared", outcome.record, "Copied from ${ctx.log.describe(outcome.original.scope)}.")
                ctx.vectors.store(listOf(outcome.record), who.agentId, replacing = false)
            }
            is Shared.Refreshed -> {
                ctx.log.changed(who, "Shared memory refreshed", outcome.record, "Updated to the current text of the original in ${ctx.log.describe(outcome.original.scope)}.")
                ctx.vectors.store(listOf(outcome.record), who.agentId, replacing = true)
            }
        }
        return outcome.record
    }

    /** The newest unexpired copy of [original] in [target], with the link that makes it one. */
    private suspend fun currentCopy(original: MemoryRecord, target: MemoryScope, now: Long): Pair<MemoryRecord, MemoryLink>? {
        val links = ctx.repository.linksOf(original.id).filter { it.to == original.id && it.relation == LinkRelation.SHARED_COPY_OF }
        val copies = ctx.repository.getAll(links.map { it.from })
        val copy = copies.firstOrNull { it.scope == target && !it.isExpired(now) } ?: return null
        return copy to links.first { it.from == copy.id }
    }

    private suspend fun create(who: ResolvedActor, original: MemoryRecord, target: MemoryScope, now: Long): Shared {
        val copy = original.copy(
            id = ctx.ids.memoryId(), scope = target,
            provenance = original.provenance.derivedAlsoFrom(original.id),
            history = original.history + sharedEvent(who, original, now),
            createdAt = now, updatedAt = now, lastAccessedAt = null, accessCount = 0, revision = 1,
        )
        val link = MemoryLink(ctx.ids.memoryLinkId(), copy.id, original.id, LinkRelation.SHARED_COPY_OF, 1.0, ctx.provenanceOf(who, now), now)
        ctx.writeAll(listOf(copy), listOf(link), required = original.id)
        return Shared.Created(copy, original)
    }

    /**
     * Brings [copy] up to [original]'s current text. Events of the original the copy does not
     * have yet (edits since the copy was made) are appended before the new SHARED_FROM event.
     * The link is written again with the copy, so the refresh is refused as a whole if the
     * original was deleted meanwhile.
     */
    private suspend fun refresh(who: ResolvedActor, copy: MemoryRecord, link: MemoryLink, original: MemoryRecord, now: Long): Shared {
        val refreshed = copy.copy(
            kind = original.kind, title = original.title, content = original.content, tags = original.tags,
            importance = original.importance, confidence = original.confidence, tier = original.tier, expiresAt = original.expiresAt,
            history = copy.history + original.history.filterNot { it in copy.history } + sharedEvent(who, original, now),
            updatedAt = now, revision = copy.revision + 1,
        )
        ctx.writeAll(listOf(refreshed), listOf(link), required = original.id)
        return Shared.Refreshed(refreshed, original)
    }

    private fun sharedEvent(who: ResolvedActor, original: MemoryRecord, now: Long) =
        ctx.event(ProvenanceAction.SHARED_FROM, who, now, original.id, "$REVISION_NOTE${original.revision}")

    /**
     * Whether [original] changed after [copy] was last made from it. The revision copied is in
     * the copy's last SHARED_FROM event for this original; a copy without one (made by
     * something else) is compared by time.
     */
    private fun isOutdated(copy: MemoryRecord, original: MemoryRecord): Boolean {
        val event = copy.history.lastOrNull { it.action == ProvenanceAction.SHARED_FROM && it.relatedMemory == original.id }
        val copied = event?.note?.takeIf { it.startsWith(REVISION_NOTE) }?.removePrefix(REVISION_NOTE)?.toIntOrNull()
        return if (copied != null) original.revision > copied else original.updatedAt > copy.updatedAt
    }

    /**
     * Promotes task [taskId] of [agentId]: each unexpired task memory with importance of at
     * least [minImportance] is copied into the agent's private memory as a long-term memory
     * (the original's provenance, derived from the task memory, with a PROMOTED_FROM event and
     * a DERIVED_FROM link to it), and the task memory it came from expires at once, so recall
     * never returns both. The rest keep their expiry and fade. All copies, links and expiries
     * are written in one transaction. Calling it again promotes nothing twice: promoted
     * originals have already expired.
     *
     * The actor must be able to write that task memory (the owner, or the agent itself). An
     * agent with private memory switched off keeps nothing: the call returns an empty list.
     */
    suspend fun promote(actor: MemoryActor, taskId: TaskId, agentId: AgentId, minImportance: Double): List<MemoryRecord> {
        MemoryRules.unit("The minimum importance", minImportance)
        val taskScope = MemoryScope.Task(taskId, agentId)
        val (who, promoted) = ctx.writing(actor) { who ->
            ctx.requireScopeExists(taskScope)
            ctx.requireWrite(who, taskScope)
            val profile = ctx.agents.get(agentId) ?: MemoryRules.fail("There is no agent with id ${agentId.value}.")
            val now = ctx.clock.now()
            if (!profile.memory.privateMemoryEnabled) {
                ctx.log.event(who, "Task memory not kept", "${profile.name} has private memory switched off, so nothing from task ${taskId.value} was kept; its task memory expires as usual.")
                return emptyList()
            }
            val important = ctx.repository.list(MemoryFilter(scopes = setOf(taskScope), excludeExpiredAt = now))
                .filter { it.importance >= minImportance }
                .sortedWith(compareBy<MemoryRecord> { it.createdAt }.thenBy { it.id.value })
            val copies = important.map { original ->
                original.copy(
                    id = ctx.ids.memoryId(), scope = MemoryScope.Agent(agentId), tier = MemoryTier.LONG_TERM, expiresAt = null,
                    provenance = original.provenance.derivedAlsoFrom(original.id),
                    history = original.history + ctx.event(ProvenanceAction.PROMOTED_FROM, who, now, original.id, "Kept from task ${taskId.value}"),
                    createdAt = now, updatedAt = now, lastAccessedAt = null, accessCount = 0, revision = 1,
                )
            }
            if (copies.isNotEmpty()) {
                val links = copies.zip(important) { copy, original ->
                    MemoryLink(ctx.ids.memoryLinkId(), copy.id, original.id, LinkRelation.DERIVED_FROM, 1.0, ctx.provenanceOf(who, now), now)
                }
                val expired = important.map { it.copy(expiresAt = now, updatedAt = now, revision = it.revision + 1) }
                ctx.writeAll(copies + expired, links, required = important.first().id)
            }
            who to copies
        }
        promoted.forEach { ctx.log.changed(who, "Task memory promoted", it, "Kept from task ${taskId.value}.") }
        ctx.vectors.store(promoted, who.agentId, replacing = false)
        return promoted
    }

    private fun Provenance.derivedAlsoFrom(id: MemoryId): Provenance = if (id in derivedFrom) this else copy(derivedFrom = derivedFrom + id)

    companion object {
        /** The note of a SHARED_FROM event: the revision of the original that was copied. */
        const val REVISION_NOTE = "Copied revision "

        /**
         * The memories [record] is still an untouched copy of: those named by SHARED_FROM events
         * after its last EDITED event. Empty when it is no copy, or when it was changed after it
         * was last shared: an EDITED event after its last SHARED_FROM event, or a revision above
         * the one sharing gave it (1 when made, one more for each refresh, so one per SHARED_FROM
         * event of its own original), which catches a change written without an event.
         */
        fun sourcesOf(record: MemoryRecord): Set<MemoryId> {
            val history = record.history
            val lastShare = history.indexOfLast { it.action == ProvenanceAction.SHARED_FROM }
            if (lastShare < 0) return emptySet()
            val lastEdit = history.indexOfLast { it.action == ProvenanceAction.EDITED }
            if (lastEdit > lastShare) return emptySet()
            val own = history[lastShare].relatedMemory ?: return emptySet()
            if (record.revision > history.count { it.action == ProvenanceAction.SHARED_FROM && it.relatedMemory == own }) return emptySet()
            return history.subList(lastEdit + 1, history.size)
                .mapNotNullTo(HashSet()) { event -> event.relatedMemory.takeIf { event.action == ProvenanceAction.SHARED_FROM } }
        }
    }
}
