package com.aicontrolcenter.core.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.MemoryLinkId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where a memory lives, which decides who may see it. Agents never share everything: each has
 * its own private memory, shared knowledge lives in spaces an agent must be given access to,
 * and task memory is working memory for one task.
 */
@Serializable
sealed interface MemoryScope {
    /** One agent's private memory. */
    @Serializable
    @SerialName("agent")
    data class Agent(val agentId: AgentId) : MemoryScope

    /** A shared knowledge space; readable and writable only by agents the owner allowed. */
    @Serializable
    @SerialName("shared")
    data class Shared(val spaceId: SpaceId) : MemoryScope

    /** Short-term working memory of one task, owned by the agent doing it. */
    @Serializable
    @SerialName("task")
    data class Task(val taskId: TaskId, val agentId: AgentId) : MemoryScope
}

/** What kind of thing a memory records. Open to extension; unknown kinds are shown as notes. */
enum class MemoryKind { FACT, PREFERENCE, EVENT, FINDING, DECISION, PROCEDURE, INSIGHT, SUMMARY, NOTE }

/** Short-term memories expire; long-term ones stay until deleted. */
enum class MemoryTier { SHORT_TERM, LONG_TERM }

/** Who or what created a memory. */
enum class ProvenanceOrigin {
    /** Written or edited by the owner. */
    OWNER,

    /** Saved by an agent during its work. */
    AGENT,

    /** Recorded from a tool's result. */
    TOOL,

    /** Created by the framework itself (for example promoting task memory). */
    SYSTEM,

    /** Copied from another memory (sharing) or combined from several (consolidation). */
    DERIVED,

    /** Brought in from outside the app (an import). */
    IMPORT,
}

/** Where a memory came from. Kept for every memory and never edited away. */
@Serializable
data class Provenance(
    val origin: ProvenanceOrigin,
    /** The agent that created it, when an agent did. */
    val agentId: AgentId? = null,
    val taskId: TaskId? = null,
    val conversationId: ConversationId? = null,
    /** The registered model, and the model name that actually answered, when a model was involved. */
    val modelId: ModelId? = null,
    val modelName: String? = null,
    /** Memories this one was derived from (a shared copy's original, a summary's sources). */
    val derivedFrom: List<MemoryId> = emptyList(),
    /** A short human-readable note on the source, e.g. "Tool: Current time" or a URL. */
    val source: String? = null,
    /** When the provenance was recorded, epoch ms. */
    val recordedAt: Long,
)

/** One remembered item. */
@Serializable
data class MemoryRecord(
    val id: MemoryId,
    val scope: MemoryScope,
    val kind: MemoryKind,
    val tier: MemoryTier,
    /** A short label for lists and graph nodes. */
    val title: String,
    /** The memory itself, in plain text. */
    val content: String,
    /** Lower-case tags. */
    val tags: Set<String> = emptySet(),
    /** How much this matters, 0..1. Raises its rank in recall. */
    val importance: Double = 0.5,
    /** How sure the creator was, 0..1. */
    val confidence: Double = 1.0,
    val provenance: Provenance,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessedAt: Long? = null,
    val accessCount: Int = 0,
    /** When a short-term memory stops being recalled and is deleted, epoch ms. Null: no expiry. */
    val expiresAt: Long? = null,
    /** Incremented on every change, starting at 1. */
    val revision: Int = 1,
) {
    fun isExpired(now: Long): Boolean = expiresAt != null && expiresAt <= now
}

/** How two memories relate. */
enum class LinkRelation {
    RELATES_TO,
    SUPPORTS,
    CONTRADICTS,

    /** The source was derived from the target (a summary from its parts). */
    DERIVED_FROM,
    PART_OF,
    REFERENCES,

    /** The source replaces the target, which is kept for history. */
    SUPERSEDES,

    /** The source is a shared copy of the target. */
    SHARED_COPY_OF,
}

/** A typed, weighted edge between two memories. Directed from [from] to [to]. */
@Serializable
data class MemoryLink(
    val id: MemoryLinkId,
    val from: MemoryId,
    val to: MemoryId,
    val relation: LinkRelation,
    /** Strength 0..1. */
    val weight: Double = 1.0,
    val provenance: Provenance,
    val createdAt: Long,
)

/** A shared knowledge space. Agents read or write it only when their memory policy names it. */
@Serializable
data class KnowledgeSpace(
    val id: SpaceId,
    val name: String,
    val description: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)
