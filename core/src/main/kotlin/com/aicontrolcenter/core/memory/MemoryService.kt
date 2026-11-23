package com.aicontrolcenter.core.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.agents.AgentProfile
import kotlinx.coroutines.flow.Flow

/**
 * Who is acting on memory. The owner may do anything. An agent is limited by its
 * [com.aicontrolcenter.core.agents.MemoryPolicy]: its own private memory (when enabled), the
 * spaces it may read or write, and the task memory of its own tasks.
 */
sealed interface MemoryActor {
    data object Owner : MemoryActor

    /** [profile] must be the agent's current profile: permissions are read from it on every call. */
    data class Agent(val profile: AgentProfile) : MemoryActor
}

/** What an actor may read and write, resolved from its policy. */
data class MemoryAccess(
    val readable: Set<MemoryScope>,
    val writable: Set<MemoryScope>,
    /** The agent whose task memory this actor may use (its own), if any. */
    val taskOwner: AgentId? = null,
    /** The owner sees every scope, including ones created later. */
    val everything: Boolean = false,
) {
    fun canRead(scope: MemoryScope): Boolean =
        everything || scope in readable || (scope is MemoryScope.Task && scope.agentId == taskOwner)

    fun canWrite(scope: MemoryScope): Boolean =
        everything || scope in writable || (scope is MemoryScope.Task && scope.agentId == taskOwner)
}

/** What to remember. Ids, times and revision are assigned by the service. */
data class MemoryDraft(
    val scope: MemoryScope,
    val kind: MemoryKind,
    val title: String,
    val content: String,
    val tier: MemoryTier = MemoryTier.LONG_TERM,
    val tags: Set<String> = emptySet(),
    val importance: Double = 0.5,
    val confidence: Double = 1.0,
    /** Origin, agent, task, conversation, model and source; recordedAt is filled in. */
    val provenance: Provenance,
    /** For short-term memory: how long it lives. Null uses the default for the tier. */
    val ttlMs: Long? = null,
)

/** A change to an existing memory. Null fields stay as they are. */
data class MemoryChange(
    val title: String? = null,
    val content: String? = null,
    val kind: MemoryKind? = null,
    val tags: Set<String>? = null,
    val importance: Double? = null,
    val confidence: Double? = null,
    val tier: MemoryTier? = null,
)

/** A recall request. */
data class RecallQuery(
    val text: String,
    val limit: Int = 8,
    /** Narrows to these scopes; null means every scope the actor may read. Scopes it may not read are ignored. */
    val scopes: Set<MemoryScope>? = null,
    val kinds: Set<MemoryKind> = emptySet(),
    /** Hits scoring below this are left out, 0..1. */
    val minScore: Double = 0.15,
    /** Count the hits as accesses (recall used for real work), or not (browsing). */
    val recordAccess: Boolean = true,
)

/** One recalled memory and why it ranked where it did. Components are each 0..1. */
data class MemoryHit(
    val record: MemoryRecord,
    val score: Double,
    val semantic: Double,
    val keyword: Double,
    val recency: Double,
    val importance: Double,
)

class MemoryPermissionException(message: String) : SecurityException(message)

class MemoryValidationException(message: String) : IllegalArgumentException(message)

/**
 * The memory system. Every operation names its actor and is checked against what that actor
 * may read or write; a refusal is a [MemoryPermissionException] with a sentence an agent or
 * the owner can act on. All changes are recorded in the activity log.
 */
interface MemoryService {
    /** What [actor] may read and write right now. */
    suspend fun access(actor: MemoryActor): MemoryAccess

    /** Memories [actor] may read, newest first, filtered further by [filter]. */
    fun observe(actor: MemoryActor, filter: MemoryFilter = MemoryFilter()): Flow<List<MemoryRecord>>

    suspend fun get(actor: MemoryActor, id: MemoryId): MemoryRecord?

    /** @throws MemoryPermissionException, MemoryValidationException */
    suspend fun remember(actor: MemoryActor, draft: MemoryDraft): MemoryRecord

    /** @throws MemoryPermissionException, MemoryValidationException */
    suspend fun update(actor: MemoryActor, id: MemoryId, change: MemoryChange): MemoryRecord

    /** Deletes a memory and its links. @throws MemoryPermissionException */
    suspend fun forget(actor: MemoryActor, id: MemoryId)

    /** Ranked recall over what [actor] may read: meaning, keywords, recency and importance combined. */
    suspend fun recall(actor: MemoryActor, query: RecallQuery): List<MemoryHit>

    /**
     * Links two memories. The actor must be able to read both and write the scope of [from].
     * @throws MemoryPermissionException, MemoryValidationException
     */
    suspend fun link(actor: MemoryActor, from: MemoryId, to: MemoryId, relation: LinkRelation, weight: Double = 1.0): MemoryLink

    suspend fun unlink(actor: MemoryActor, linkId: com.aicontrolcenter.core.MemoryLinkId)

    /** Links touching memories [actor] may read, where both ends are readable. */
    suspend fun linksOf(actor: MemoryActor, id: MemoryId): List<MemoryLink>

    /**
     * Shares a memory into a knowledge space: a copy is created there, linked to the original
     * with [LinkRelation.SHARED_COPY_OF], with provenance pointing at the original. The original
     * stays where it was. @throws MemoryPermissionException
     */
    suspend fun share(actor: MemoryActor, id: MemoryId, space: SpaceId): MemoryRecord

    /**
     * Copies the important task memories of a finished task into the agent's long-term memory,
     * with provenance, and lets the rest expire. Returns the new long-term memories.
     */
    suspend fun promoteTaskMemory(actor: MemoryActor, taskId: TaskId, agentId: AgentId, minImportance: Double = 0.6): List<MemoryRecord>

    fun observeSpaces(): Flow<List<KnowledgeSpace>>

    /** Owner only. @throws MemoryPermissionException, MemoryValidationException */
    suspend fun createSpace(actor: MemoryActor, name: String, description: String = ""): KnowledgeSpace

    suspend fun updateSpace(actor: MemoryActor, id: SpaceId, name: String, description: String): KnowledgeSpace

    /** Owner only. Deletes the space and its memories, and removes it from every agent's policy. */
    suspend fun deleteSpace(actor: MemoryActor, id: SpaceId): Int

    /** A neutral graph of what [actor] may read, for the graph view and for external graph tools. */
    suspend fun graph(actor: MemoryActor, request: GraphRequest = GraphRequest()): MemoryGraphSnapshot

    /** Deletes expired short-term memories and computes missing embeddings. Safe to call often. */
    suspend fun maintain(): MaintenanceReport
}

data class MaintenanceReport(val expiredDeleted: Int, val embeddingsComputed: Int)
