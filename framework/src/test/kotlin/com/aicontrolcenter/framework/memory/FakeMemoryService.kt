package com.aicontrolcenter.framework.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.MemoryLinkId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.memory.GraphRequest
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MaintenanceReport
import com.aicontrolcenter.core.memory.MemoryAccess
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryChange
import com.aicontrolcenter.core.memory.MemoryDraft
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryGraphSnapshot
import com.aicontrolcenter.core.memory.MemoryHit
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryLink
import com.aicontrolcenter.core.memory.MemoryPermissionException
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.MemoryValidationException
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceAction
import com.aicontrolcenter.core.memory.ProvenanceEvent
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.memory.RecallQuery
import com.aicontrolcenter.core.memory.editedNote
import com.aicontrolcenter.core.memoryId
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.spaceId
import com.aicontrolcenter.testkit.InMemoryMemoryRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A small [MemoryService] for the framework's tests, written against the contract rather than
 * the real engine in :memory. Access follows the memory policy as the contract describes it;
 * recall is a plain word match (any word of three or more letters), ranked by how many words
 * matched. It does not look at the clock, so tests that count clock ticks are unaffected.
 * What the framework does not use (links, graph, maintenance) is not implemented.
 */
class FakeMemoryService(
    private val agents: AgentRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
) : MemoryService {
    val repository = InMemoryMemoryRepository()

    /** Set to make every recall throw this. */
    @Volatile
    var recallFailure: Exception? = null

    /** Set to make every recall take this long (virtual time in coroutine tests). */
    @Volatile
    var recallDelayMs: Long = 0

    /** Set to make every recall block its thread this long without ever suspending, like a long computation. */
    @Volatile
    var recallBlockingMs: Long = 0

    val recalls = CopyOnWriteArrayList<Pair<MemoryActor, RecallQuery>>()
    val forgottenAgents = CopyOnWriteArrayList<AgentId>()

    override suspend fun access(actor: MemoryActor): MemoryAccess = when (actor) {
        MemoryActor.Owner -> MemoryAccess(emptySet(), emptySet(), everything = true)
        is MemoryActor.Agent -> {
            val policy = actor.profile.memory
            val own = if (policy.privateMemoryEnabled) setOf<MemoryScope>(MemoryScope.Agent(actor.profile.id)) else emptySet()
            MemoryAccess(
                readable = own + policy.readableSpaces.map { MemoryScope.Shared(it) },
                writable = own + policy.writableSpaces.map { MemoryScope.Shared(it) },
                taskOwner = actor.profile.id,
            )
        }
    }

    override fun observe(actor: MemoryActor, filter: MemoryFilter): Flow<List<MemoryRecord>> =
        repository.observe(filter).map { records -> access(actor).let { access -> records.filter { access.canRead(it.scope) } } }

    override suspend fun get(actor: MemoryActor, id: MemoryId): MemoryRecord? =
        repository.get(id)?.takeIf { access(actor).canRead(it.scope) }

    override suspend fun remember(actor: MemoryActor, draft: MemoryDraft): MemoryRecord {
        if (draft.title.isBlank()) throw MemoryValidationException("A memory needs a title.")
        if (draft.content.length > MAX_CONTENT) throw MemoryValidationException("A memory may hold at most $MAX_CONTENT characters.")
        if (draft.importance !in 0.0..1.0) throw MemoryValidationException("Importance must be between 0 and 1.")
        if (!access(actor).canWrite(draft.scope)) throw MemoryPermissionException("${name(actor)} may not write there: its memory settings do not allow it.")
        val now = clock.now()
        val record = MemoryRecord(
            id = ids.memoryId(), scope = draft.scope, kind = draft.kind, tier = draft.tier, title = draft.title.trim(),
            content = draft.content.trim(), tags = draft.tags, importance = draft.importance, confidence = draft.confidence,
            provenance = draft.provenance.copy(recordedAt = now), createdAt = now, updatedAt = now,
            expiresAt = if (draft.tier == MemoryTier.SHORT_TERM) now + (draft.ttlMs ?: DAY_MS) else null,
        )
        repository.upsert(record)
        return record
    }

    /** Stores a memory as the owner would, for setting up a test. */
    suspend fun seed(
        scope: MemoryScope,
        title: String,
        content: String,
        kind: MemoryKind = MemoryKind.FACT,
        at: Long = clock.now(),
        importance: Double = 0.5,
    ): MemoryRecord {
        val record = MemoryRecord(
            id = ids.memoryId(), scope = scope, kind = kind, tier = MemoryTier.LONG_TERM, title = title, content = content, importance = importance,
            provenance = Provenance(ProvenanceOrigin.OWNER, recordedAt = at), createdAt = at, updatedAt = at,
        )
        repository.upsert(record)
        return record
    }

    /**
     * As the memory store does: a change that changes nothing returns the memory as it is; any
     * other adds a revision and an entry to the history naming who made it and, in its note, the
     * fields it changed ("content, tags changed").
     */
    override suspend fun update(actor: MemoryActor, id: MemoryId, change: MemoryChange): MemoryRecord {
        val current = get(actor, id) ?: throw MemoryValidationException("There is no memory $id.")
        if (!access(actor).canWrite(current.scope)) throw MemoryPermissionException("${name(actor)} may not change that memory.")
        val now = clock.now()
        val by = (actor as? MemoryActor.Agent)?.profile?.id
        val candidate = current.copy(
            title = change.title ?: current.title, content = change.content ?: current.content, kind = change.kind ?: current.kind,
            tags = change.tags ?: current.tags, importance = change.importance ?: current.importance,
            confidence = change.confidence ?: current.confidence, tier = change.tier ?: current.tier,
        )
        val changed = listOfNotNull(
            "title".takeIf { candidate.title != current.title },
            "content".takeIf { candidate.content != current.content },
            "kind".takeIf { candidate.kind != current.kind },
            "tags".takeIf { candidate.tags != current.tags },
            "importance".takeIf { candidate.importance != current.importance },
            "confidence".takeIf { candidate.confidence != current.confidence },
            "tier".takeIf { candidate.tier != current.tier },
        )
        if (changed.isEmpty()) return current
        val updated = candidate.copy(
            updatedAt = now, revision = current.revision + 1,
            history = current.history + ProvenanceEvent(ProvenanceAction.EDITED, if (by == null) ProvenanceOrigin.OWNER else ProvenanceOrigin.AGENT, by, now, note = editedNote(changed)),
        )
        repository.upsert(updated)
        return updated
    }

    override suspend fun forget(actor: MemoryActor, id: MemoryId) {
        val current = repository.get(id) ?: return
        if (!access(actor).canWrite(current.scope)) throw MemoryPermissionException("${name(actor)} may not delete that memory.")
        repository.delete(id)
    }

    override suspend fun recall(actor: MemoryActor, query: RecallQuery): List<MemoryHit> {
        recalls += actor to query
        if (recallDelayMs > 0) delay(recallDelayMs)
        if (recallBlockingMs > 0) Thread.sleep(recallBlockingMs)
        recallFailure?.let { throw it }
        val access = access(actor)
        val words = query.text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.distinct()
        if (words.isEmpty()) return emptyList()
        return repository.list()
            .filter { access.canRead(it.scope) && (query.scopes == null || it.scope in query.scopes!!) }
            .map { record ->
                val haystack = "${record.title}\n${record.content}\n${record.tags.joinToString(" ")}".lowercase()
                record to words.count { it in haystack }.toDouble() / words.size
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(query.limit)
            .map { (record, score) -> MemoryHit(record, score, semantic = 0.0, keyword = score, recency = 0.5, importance = record.importance) }
    }

    override suspend fun link(actor: MemoryActor, from: MemoryId, to: MemoryId, relation: LinkRelation, weight: Double): MemoryLink = unused()

    override suspend fun unlink(actor: MemoryActor, linkId: MemoryLinkId) = unused()

    override suspend fun linksOf(actor: MemoryActor, id: MemoryId): List<MemoryLink> = unused()

    override suspend fun share(actor: MemoryActor, id: MemoryId, space: SpaceId): MemoryRecord {
        val original = get(actor, id) ?: throw MemoryPermissionException("${name(actor)} may not read that memory.")
        if (repository.getSpace(space) == null) throw MemoryValidationException("That space no longer exists.")
        if (!access(actor).canWrite(MemoryScope.Shared(space))) {
            throw MemoryPermissionException("${name(actor)} may not write to that space: its memory settings do not allow it.")
        }
        val now = clock.now()
        val copy = original.copy(
            id = ids.memoryId(), scope = MemoryScope.Shared(space), createdAt = now, updatedAt = now, revision = 1,
            provenance = original.provenance.copy(origin = ProvenanceOrigin.DERIVED, derivedFrom = listOf(original.id), recordedAt = now),
        )
        repository.upsert(copy)
        return copy
    }

    /** Every promotion asked for: who asked, for which task and agent. */
    val promotions = CopyOnWriteArrayList<Triple<MemoryActor, TaskId, AgentId>>()

    /**
     * As the memory store promotes (MemorySharing.promote in :memory): each unexpired task memory
     * of the task and agent with importance of at least [minImportance] is copied into the
     * agent's own long-term memory (its provenance derived from the original), and the original
     * expires at once, so promoting again keeps nothing twice. An agent with private memory
     * switched off keeps nothing. Only the owner or the agent itself may promote.
     */
    override suspend fun promoteTaskMemory(actor: MemoryActor, taskId: TaskId, agentId: AgentId, minImportance: Double): List<MemoryRecord> {
        if (actor is MemoryActor.Agent && actor.profile.id != agentId) throw MemoryPermissionException("${name(actor)} may not promote another agent's task memory.")
        promotions += Triple(actor, taskId, agentId)
        val profile = agents.get(agentId) ?: throw MemoryValidationException("There is no agent with id ${agentId.value}.")
        if (!profile.memory.privateMemoryEnabled) return emptyList()
        val now = clock.now()
        val important = repository.list().filter {
            it.scope == MemoryScope.Task(taskId, agentId) && !it.isExpired(now) && it.importance >= minImportance
        }
        val copies = important.map {
            it.copy(
                id = ids.memoryId(), scope = MemoryScope.Agent(agentId), tier = MemoryTier.LONG_TERM, expiresAt = null,
                provenance = it.provenance.copy(derivedFrom = it.provenance.derivedFrom + it.id), createdAt = now, updatedAt = now, revision = 1,
            )
        }
        copies.forEach { repository.upsert(it) }
        important.forEach { repository.upsert(it.copy(expiresAt = now, updatedAt = now, revision = it.revision + 1)) }
        return copies
    }

    override fun observeSpaces(): Flow<List<KnowledgeSpace>> = repository.observeSpaces()

    override suspend fun createSpace(actor: MemoryActor, name: String, description: String): KnowledgeSpace {
        if (actor != MemoryActor.Owner) throw MemoryPermissionException("Only the owner can create spaces.")
        if (name.isBlank()) throw MemoryValidationException("A space needs a name.")
        if (observeSpaces().first().any { it.name.equals(name.trim(), ignoreCase = true) }) {
            throw MemoryValidationException("A space with that name already exists.")
        }
        val now = clock.now()
        val space = KnowledgeSpace(ids.spaceId(), name.trim(), description, createdAt = now, updatedAt = now)
        repository.upsertSpace(space)
        return space
    }

    override suspend fun updateSpace(actor: MemoryActor, id: SpaceId, name: String, description: String): KnowledgeSpace = unused()

    override suspend fun deleteSpace(actor: MemoryActor, id: SpaceId): Int {
        if (actor != MemoryActor.Owner) throw MemoryPermissionException("Only the owner can delete spaces.")
        val count = repository.deleteSpace(id)
        for (agent in agents.getAll()) {
            val policy = agent.memory
            if (id in policy.readableSpaces || id in policy.writableSpaces) {
                agents.upsert(agent.copy(memory = policy.copy(readableSpaces = policy.readableSpaces - id, writableSpaces = policy.writableSpaces - id)))
            }
        }
        return count
    }

    override suspend fun graph(actor: MemoryActor, request: GraphRequest): MemoryGraphSnapshot = unused()

    override suspend fun forgetAgent(agentId: AgentId): Int {
        forgottenAgents += agentId
        val owned = repository.list().filter {
            (it.scope as? MemoryScope.Agent)?.agentId == agentId || (it.scope as? MemoryScope.Task)?.agentId == agentId
        }
        owned.forEach { repository.delete(it.id) }
        return owned.size
    }

    override suspend fun maintain(): MaintenanceReport = MaintenanceReport(0, 0)

    private fun name(actor: MemoryActor) = when (actor) {
        MemoryActor.Owner -> "The owner"
        is MemoryActor.Agent -> actor.profile.name
    }

    private fun unused(): Nothing = throw UnsupportedOperationException("not used by the framework")

    companion object {
        const val MAX_CONTENT = 4_000
        const val DAY_MS = 86_400_000L
    }
}
