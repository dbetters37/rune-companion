package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.MemoryLinkId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.Severity
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
import com.aicontrolcenter.core.memory.ProvenanceAction
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.memory.RecallQuery
import com.aicontrolcenter.core.memory.editedNote
import com.aicontrolcenter.core.memoryId
import com.aicontrolcenter.core.registry.AgentDirectory
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.registry.ReadOnlyRegistry
import com.aicontrolcenter.core.settings.SettingsView
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
 * - Every write and every deletion is serialised by one in-process lock, and the actor's
 *   access is resolved again inside it (see [MemoryContext.writeLock]). The app must use a
 *   single instance per repository.
 * - Provenance says how a memory's text was first written and is never changed, also not on
 *   shared or promoted copies; edits, shares and promotions are appended to
 *   [MemoryRecord.history] with who did them and when.
 */
class DefaultMemoryService(
    private val repository: MemoryRepository,
    /**
     * The owner's agent store. The service keeps only a read-only view of it and a way to take a deleted space away
     * from agents (security review of wave 1, F3, second review): agents reach this service, so it may not hold the
     * store that changes an agent's tools, spaces or model.
     */
    agents: AgentRepository,
    settings: SettingsView,
    embedder: Embedder,
    activity: ActivityRecorder,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val config: MemoryConfig = MemoryConfig(),
    /**
     * Where recall scoring runs: off the caller's thread by default, so a time-out can end the
     * wait. [kotlin.coroutines.EmptyCoroutineContext] keeps it on the caller's dispatcher
     * (tests in virtual time).
     */
    computeContext: CoroutineContext = Dispatchers.Default,
) : MemoryService {
    private val directory: AgentDirectory = ReadOnlyRegistry.agents(agents)
    private val resolver = AccessResolver(directory, repository)
    private val log = MemoryActivity(activity, directory, repository)
    private val vectors = MemoryEmbeddings(embedder, settings, repository, log, config)
    private val ctx = MemoryContext(repository, directory, ReadOnlyRegistry.spaceRevoker(agents), ids, clock, config, resolver, log, vectors)
    private val scorer = RecallScorer(config)
    private val reader = MemoryReader(repository, config)
    private val recaller = MemoryRecall(ctx, reader, scorer, computeContext)
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
            combine(directory.observe(id), repository.observeSpaces()) { profile, spaces ->
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
        // Inside the lock: a space deleted or an agent forgotten meanwhile is noticed here.
        val (who, record) = ctx.writing(actor) { who ->
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
            who to record
        }
        log.changed(who, "Memory saved", record)
        vectors.store(listOf(record), who.agentId, replacing = false)
        return record
    }

    /**
     * An update that changes nothing returns the memory as it is, without a new revision or
     * event. Otherwise the provenance stays as it is (it describes how the text was first
     * written) and an EDITED event naming the editor, the time and the changed fields is
     * appended to the history. A memory forgotten meanwhile is refused, never recreated.
     */
    override suspend fun update(actor: MemoryActor, id: MemoryId, change: MemoryChange): MemoryRecord {
        val title = change.title?.let(MemoryRules::title)
        val content = change.content?.let(MemoryRules::content)
        val tags = change.tags?.let(MemoryRules::tags)
        val importance = change.importance?.let { MemoryRules.unit("Importance", it) }
        val confidence = change.confidence?.let { MemoryRules.unit("Confidence", it) }
        val (who, current, next) = ctx.writing(actor) { who ->
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
            val changed = listOfNotNull(
                "title".takeIf { candidate.title != current.title },
                "content".takeIf { candidate.content != current.content },
                "kind".takeIf { candidate.kind != current.kind },
                "tags".takeIf { candidate.tags != current.tags },
                "importance".takeIf { candidate.importance != current.importance },
                "confidence".takeIf { candidate.confidence != current.confidence },
                "tier".takeIf { candidate.tier != current.tier || candidate.expiresAt != current.expiresAt },
            )
            val next = candidate.copy(
                updatedAt = now, revision = current.revision + 1,
                history = current.history + ctx.event(ProvenanceAction.EDITED, who, now, note = editedNote(changed)),
            )
            repository.upsert(next)
            Triple(who, current, next)
        }
        log.changed(who, "Memory updated", next)
        if (next.title != current.title || next.content != current.content || next.tags != current.tags) {
            vectors.store(listOf(next), who.agentId, replacing = true)
        }
        return next
    }

    /** Forgetting a memory that does not exist does nothing. */
    override suspend fun forget(actor: MemoryActor, id: MemoryId) {
        val (who, record) = ctx.writing(actor) { who ->
            val record = repository.get(id) ?: return
            ctx.requireRead(who, record.scope)
            ctx.requireWrite(who, record.scope)
            repository.delete(id)
            who to record
        }
        log.changed(who, "Memory forgotten", record)
    }

    override suspend fun recall(actor: MemoryActor, query: RecallQuery): List<MemoryHit> =
        recaller.recall(resolver.resolve(actor), query)

    override suspend fun link(actor: MemoryActor, from: MemoryId, to: MemoryId, relation: LinkRelation, weight: Double): MemoryLink =
        links.link(actor, from, to, relation, weight)

    /** Unlinking a link that does not exist does nothing. */
    override suspend fun unlink(actor: MemoryActor, linkId: MemoryLinkId) = links.unlink(actor, linkId)

    override suspend fun linksOf(actor: MemoryActor, id: MemoryId): List<MemoryLink> = links.linksOf(resolver.resolve(actor), id)

    override suspend fun share(actor: MemoryActor, id: MemoryId, space: SpaceId): MemoryRecord = sharing.share(actor, id, space)

    override suspend fun promoteTaskMemory(actor: MemoryActor, taskId: TaskId, agentId: AgentId, minImportance: Double): List<MemoryRecord> =
        sharing.promote(actor, taskId, agentId, minImportance)

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
     * Deletes the agent's private memory and the memory of all its tasks, in one transaction of
     * the repository and without loading any of it. If this never runs (the process died after
     * the agent was deleted), [maintain] removes the memory later.
     */
    override suspend fun forgetAgent(agentId: AgentId): Int {
        val deleted = ctx.writeLock.withLock { repository.deleteAgentMemory(agentId) }
        val name = directory.get(agentId)?.name ?: "agent ${agentId.value}"
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

    /**
     * Deletes expired memories and the private and task memory of agents that no longer exist,
     * then computes up to [MemoryConfig.maxEmbeddingsPerMaintenance] missing or stale vectors.
     * The report's [MaintenanceReport.expiredDeleted] counts expired memories only; memory of
     * removed agents is reported in its own activity entry.
     *
     * Each step runs on its own: a step that fails (storage or the agent registry throwing)
     * counts as zero and is reported in a WARNING entry naming the step and the kind of error,
     * never its message (which may quote memory text); the other steps and the maintenance
     * entry still run. Cancellation is never swallowed.
     */
    override suspend fun maintain(): MaintenanceReport {
        val now = clock.now()
        val failed = LinkedHashMap<String, Throwable>()
        suspend fun <T> step(name: String, fallback: T, block: suspend () -> T): T = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed[name] = e
            fallback
        }
        val expired = step("deleting expired memories", 0) { ctx.writeLock.withLock { repository.deleteExpired(now) } }
        val orphans = step("deleting memory of removed agents", emptyMap()) { ctx.writeLock.withLock { deleteMemoryOfRemovedAgents() } }
        if (orphans.isNotEmpty()) {
            val total = orphans.values.sum()
            log.event(
                null, "Memory of removed agents deleted",
                "Deleted $total memor${if (total == 1) "y" else "ies"} (private and task memory) of ${orphans.size} agent${if (orphans.size == 1) "" else "s"} that no longer exist.",
                buildJsonObject {
                    put("agentIds", JsonArray(orphans.keys.sortedBy { it.value }.map { JsonPrimitive(it.value) }))
                    put("memoriesDeleted", total)
                },
            )
        }
        val computed = step("computing embeddings", 0) { vectors.refresh(now, config.maxEmbeddingsPerMaintenance) }
        if (failed.isNotEmpty()) {
            log.event(
                null, "Memory maintenance incomplete",
                "Failed: " + failed.entries.joinToString("; ") { (name, e) -> "$name (${e::class.simpleName ?: "error"})" } +
                    ". The other steps ran; the next maintenance tries again.",
                buildJsonObject { put("failedSteps", JsonArray(failed.keys.map(::JsonPrimitive))) },
                severity = Severity.WARNING,
            )
        }
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

    /**
     * Private and task memory whose agent is gone, for example because the process died between
     * deleting the agent and [forgetAgent]. Owners are read before agents, so an agent created
     * meanwhile is never mistaken for a removed one. When no agent at all can be read, nothing
     * is deleted: that looks like a storage problem rather than an owner who removed every agent.
     */
    private suspend fun deleteMemoryOfRemovedAgents(): Map<AgentId, Int> {
        val owners = repository.agentsWithMemory()
        if (owners.isEmpty()) return emptyMap()
        val existing = directory.getAll().mapTo(HashSet()) { it.id }
        if (existing.isEmpty()) return emptyMap()
        return (owners - existing).filter { directory.get(it) == null }
            .associateWith { repository.deleteAgentMemory(it) }
            .filterValues { it > 0 }
    }

    private fun provenanceFor(who: ResolvedActor, given: Provenance, now: Long): Provenance = when {
        who.isOwner -> given.copy(recordedAt = now)
        else -> given.copy(
            origin = if (given.origin == ProvenanceOrigin.OWNER) ProvenanceOrigin.AGENT else given.origin,
            agentId = who.agentId, recordedAt = now,
        )
    }
}
