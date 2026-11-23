package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.MemoryLinkId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.memory.Embedder
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
import com.aicontrolcenter.core.memory.MemoryLink
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.memory.RecallQuery
import com.aicontrolcenter.core.memoryId
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The memory engine: [MemoryService] over any [MemoryRepository].
 *
 * Every call resolves its actor afresh ([AccessResolver]): the owner may do anything; an agent
 * is limited to its own private memory (when its policy enables it), the spaces its policy
 * names for reading or writing, and the memory of its own tasks. Every change is written to the
 * activity log with the memory's title, the actor and the scope, never the content.
 *
 * Decisions worth knowing:
 * - Task memory is always short-term: a task draft asking for long-term is stored short-term
 *   (it lives for the task, then is promoted or expires), and an update cannot make it long-term.
 * - An agent cannot claim the owner wrote something: provenance of an agent's memory always
 *   names that agent, and the origin OWNER becomes AGENT.
 * - Asking for a memory that does not exist is a [com.aicontrolcenter.core.memory.MemoryValidationException];
 *   one that exists but may not be read or changed is a
 *   [com.aicontrolcenter.core.memory.MemoryPermissionException]. [get] returns null for both.
 * - Vectors are computed after the memory is stored, and a failing or forbidden embedder never
 *   loses a memory ([MemoryEmbeddings]).
 * - Read-modify-write steps are serialised by one in-process lock. The app must use a single
 *   instance per repository.
 */
class DefaultMemoryService(
    private val repository: MemoryRepository,
    private val agents: AgentRepository,
    settings: SettingsRepository,
    embedder: Embedder,
    activity: ActivityRecorder,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val config: MemoryConfig = MemoryConfig(),
) : MemoryService {
    private val resolver = AccessResolver(agents, repository)
    private val log = MemoryActivity(activity, agents, repository)
    private val vectors = MemoryEmbeddings(embedder, settings, repository, log, config)
    private val ctx = MemoryContext(repository, agents, ids, clock, config, resolver, log, vectors)
    private val scorer = RecallScorer(config)
    private val reader = MemoryReader(repository)
    private val recaller = MemoryRecall(ctx, reader, scorer)
    private val links = MemoryLinks(ctx)
    private val sharing = MemorySharing(ctx)
    private val spaces = KnowledgeSpaces(ctx)
    private val exporter = MemoryGraphExporter(ctx, reader, MemoryGraphBuilder(config, scorer))

    override suspend fun access(actor: MemoryActor): MemoryAccess = resolver.resolve(actor).access

    /** For an agent, re-evaluates its access whenever its profile or the set of spaces changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(actor: MemoryActor, filter: MemoryFilter): Flow<List<MemoryRecord>> = when (actor) {
        MemoryActor.Owner -> repository.observe(filter)
        is MemoryActor.Agent -> {
            val id = actor.profile.id
            combine(agents.observe(id), repository.observeSpaces()) { profile, spaces ->
                resolver.forProfile(id, profile, spaces.mapTo(HashSet()) { it.id }, actor.profile.name).access
            }.distinctUntilChanged().flatMapLatest { access ->
                if (access.readable.isEmpty() && access.taskOwner == null) {
                    flowOf(emptyList())
                } else {
                    repository.observe(filter).map { records -> records.filter { access.canRead(it.scope) } }
                }
            }
        }
    }

    override suspend fun get(actor: MemoryActor, id: MemoryId): MemoryRecord? {
        val record = repository.get(id) ?: return null
        return record.takeIf { resolver.resolve(actor).access.canRead(it.scope) }
    }

    override suspend fun remember(actor: MemoryActor, draft: MemoryDraft): MemoryRecord {
        val title = MemoryRules.title(draft.title)
        val content = MemoryRules.content(draft.content)
        val tags = MemoryRules.tags(draft.tags)
        val importance = MemoryRules.unit("Importance", draft.importance)
        val confidence = MemoryRules.unit("Confidence", draft.confidence)
        val ttl = MemoryRules.ttl(draft.ttlMs)
        val who = resolver.resolve(actor)
        ctx.requireScopeExists(draft.scope)
        ctx.requireWrite(who, draft.scope)

        val now = clock.now()
        val tier = if (draft.scope is MemoryScope.Task) MemoryTier.SHORT_TERM else draft.tier
        val record = MemoryRecord(
            id = ids.memoryId(), scope = draft.scope, kind = draft.kind, tier = tier, title = title, content = content,
            tags = tags, importance = importance, confidence = confidence,
            provenance = provenanceFor(who, draft.provenance, now),
            createdAt = now, updatedAt = now,
            expiresAt = if (tier == MemoryTier.SHORT_TERM) ctx.expiry(now, ttl ?: config.shortTermTtlMs) else null,
            revision = 1,
        )
        repository.upsert(record)
        log.changed(who, "Memory saved", record)
        vectors.store(listOf(record), who.agentId, replacing = false)
        return record
    }

    /** An update that changes nothing returns the memory as it is, without a new revision or event. */
    override suspend fun update(actor: MemoryActor, id: MemoryId, change: MemoryChange): MemoryRecord {
        val title = change.title?.let(MemoryRules::title)
        val content = change.content?.let(MemoryRules::content)
        val tags = change.tags?.let(MemoryRules::tags)
        val importance = change.importance?.let { MemoryRules.unit("Importance", it) }
        val confidence = change.confidence?.let { MemoryRules.unit("Confidence", it) }
        val who = resolver.resolve(actor)
        val (current, next) = ctx.writeLock.withLock {
            val current = ctx.existing(id)
            ctx.requireRead(who, current.scope)
            ctx.requireWrite(who, current.scope)
            val tier = change.tier ?: current.tier
            if (current.scope is MemoryScope.Task && tier == MemoryTier.LONG_TERM) {
                MemoryRules.fail("Task memory is always short-term. Promote the task's memory to keep it after the task.")
            }
            val now = clock.now()
            val candidate = current.copy(
                title = title ?: current.title, content = content ?: current.content, kind = change.kind ?: current.kind,
                tags = tags ?: current.tags, importance = importance ?: current.importance,
                confidence = confidence ?: current.confidence, tier = tier,
                expiresAt = when {
                    tier == MemoryTier.LONG_TERM -> null
                    current.tier == MemoryTier.SHORT_TERM -> current.expiresAt ?: ctx.expiry(now, config.shortTermTtlMs)
                    else -> ctx.expiry(now, config.shortTermTtlMs)
                },
            )
            if (candidate == current) return current
            val next = candidate.copy(updatedAt = now, revision = current.revision + 1)
            repository.upsert(next)
            current to next
        }
        log.changed(who, "Memory updated", next)
        if (next.title != current.title || next.content != current.content || next.tags != current.tags) {
            vectors.store(listOf(next), who.agentId, replacing = true)
        }
        return next
    }

    /** Forgetting a memory that does not exist does nothing. */
    override suspend fun forget(actor: MemoryActor, id: MemoryId) {
        val who = resolver.resolve(actor)
        val record = repository.get(id) ?: return
        ctx.requireRead(who, record.scope)
        ctx.requireWrite(who, record.scope)
        repository.delete(id)
        log.changed(who, "Memory forgotten", record)
    }

    override suspend fun recall(actor: MemoryActor, query: RecallQuery): List<MemoryHit> =
        recaller.recall(resolver.resolve(actor), query)

    override suspend fun link(actor: MemoryActor, from: MemoryId, to: MemoryId, relation: LinkRelation, weight: Double): MemoryLink =
        links.link(resolver.resolve(actor), from, to, relation, weight)

    /** Unlinking a link that does not exist does nothing. */
    override suspend fun unlink(actor: MemoryActor, linkId: MemoryLinkId) = links.unlink(resolver.resolve(actor), linkId)

    override suspend fun linksOf(actor: MemoryActor, id: MemoryId): List<MemoryLink> = links.linksOf(resolver.resolve(actor), id)

    override suspend fun share(actor: MemoryActor, id: MemoryId, space: SpaceId): MemoryRecord =
        sharing.share(resolver.resolve(actor), id, space)

    override suspend fun promoteTaskMemory(actor: MemoryActor, taskId: TaskId, agentId: AgentId, minImportance: Double): List<MemoryRecord> =
        sharing.promote(resolver.resolve(actor), taskId, agentId, minImportance)

    override fun observeSpaces(): Flow<List<KnowledgeSpace>> = repository.observeSpaces()

    override suspend fun createSpace(actor: MemoryActor, name: String, description: String): KnowledgeSpace =
        spaces.create(resolver.resolve(actor), name, description)

    /** Owner only. @throws com.aicontrolcenter.core.memory.MemoryPermissionException, com.aicontrolcenter.core.memory.MemoryValidationException */
    override suspend fun updateSpace(actor: MemoryActor, id: SpaceId, name: String, description: String): KnowledgeSpace =
        spaces.update(resolver.resolve(actor), id, name, description)

    /** Deleting a space that does not exist returns 0. */
    override suspend fun deleteSpace(actor: MemoryActor, id: SpaceId): Int = spaces.delete(resolver.resolve(actor), id)

    override suspend fun graph(actor: MemoryActor, request: GraphRequest): MemoryGraphSnapshot =
        exporter.graph(resolver.resolve(actor), request)

    /**
     * Deletes the agent's private memory and the memory of all its tasks. Task scopes cannot be
     * listed in advance, so this scans every memory once; it runs only when an agent is deleted.
     */
    override suspend fun forgetAgent(agentId: AgentId): Int {
        var deleted = repository.deleteScope(MemoryScope.Agent(agentId))
        val taskScopes = repository.list().mapNotNullTo(HashSet()) { record ->
            (record.scope as? MemoryScope.Task)?.takeIf { it.agentId == agentId }
        }
        for (scope in taskScopes) deleted += repository.deleteScope(scope)
        val name = agents.get(agentId)?.name ?: "agent ${agentId.value}"
        log.event(
            null, "Agent memory forgotten: $name",
            "Deleted $deleted memor${if (deleted == 1) "y" else "ies"} (private and task memory). Memories it shared into knowledge spaces stay there.",
            buildJsonObject {
                put("agentId", agentId.value)
                put("scope", MemoryScope.Agent(agentId).key)
                put("memoriesDeleted", deleted)
            },
        )
        return deleted
    }

    /** Deletes expired memories, then computes up to [MemoryConfig.maxEmbeddingsPerMaintenance] missing or stale vectors. */
    override suspend fun maintain(): MaintenanceReport {
        val now = clock.now()
        val expired = repository.deleteExpired(now)
        val computed = vectors.refresh(now, config.maxEmbeddingsPerMaintenance)
        if (expired > 0 || computed > 0) {
            log.event(
                null, "Memory maintenance",
                "Deleted $expired expired short-term memor${if (expired == 1) "y" else "ies"}; computed $computed embedding${if (computed == 1) "" else "s"}.",
                buildJsonObject {
                    put("expiredDeleted", expired)
                    put("embeddingsComputed", computed)
                },
            )
        }
        return MaintenanceReport(expired, computed)
    }

    private fun provenanceFor(who: ResolvedActor, given: Provenance, now: Long): Provenance = when {
        who.isOwner -> given.copy(recordedAt = now)
        else -> given.copy(
            origin = if (given.origin == ProvenanceOrigin.OWNER) ProvenanceOrigin.AGENT else given.origin,
            agentId = who.agentId, recordedAt = now,
        )
    }
}
