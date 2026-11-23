package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.MemoryPolicy
import com.aicontrolcenter.core.memory.Embedder
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryDraft
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.InMemoryAgentRepository
import com.aicontrolcenter.testkit.InMemoryMemoryRepository
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import com.aicontrolcenter.testkit.SequentialIdGenerator

/** The memory engine wired over testkit's in-memory ports, with helpers for agents, spaces and memories. */
class MemoryWorld(
    val embedder: Embedder = HashingEmbedder(),
    config: MemoryConfig = MemoryConfig(),
    localOnly: Boolean = false,
    /** Wraps the repository the service uses, e.g. to observe or delay its calls; [repository] stays the plain one. */
    wrap: (InMemoryMemoryRepository) -> MemoryRepository = { it },
) {
    val clock = FakeClock()
    val ids = SequentialIdGenerator()
    val repository = InMemoryMemoryRepository()
    val store: MemoryRepository = wrap(repository)
    val agents = InMemoryAgentRepository()
    val settings = InMemorySettingsRepository(FrameworkSettings(localOnlyMode = localOnly))
    val activityLog = InMemoryActivityLog()
    val service = DefaultMemoryService(
        store, agents, settings, embedder, ActivityRecorder(activityLog, ids, clock), ids, clock, config,
    )
    val owner = MemoryActor.Owner

    val events: List<ActivityEvent> get() = activityLog.all

    suspend fun agent(
        id: String,
        name: String = id.removePrefix("agt_").replaceFirstChar { it.uppercase() },
        private: Boolean = true,
        read: Set<SpaceId> = emptySet(),
        write: Set<SpaceId> = emptySet(),
        enabled: Boolean = true,
    ): MemoryActor.Agent {
        val profile = AgentProfile(
            id = AgentId(id), name = name, kind = AgentKind.SPECIALIST,
            memory = MemoryPolicy(privateMemoryEnabled = private, readableSpaces = read, writableSpaces = write),
            enabled = enabled, createdAt = 1, updatedAt = 1,
        )
        agents.upsert(profile)
        return MemoryActor.Agent(profile)
    }

    /** Changes an agent's stored policy; the actor object the caller holds is left stale on purpose. */
    suspend fun changePolicy(id: AgentId, transform: (MemoryPolicy) -> MemoryPolicy) {
        val profile = agents.get(id)!!
        agents.upsert(profile.copy(memory = transform(profile.memory)))
    }

    suspend fun space(name: String): KnowledgeSpace = service.createSpace(owner, name, "About $name")

    fun draft(
        scope: MemoryScope,
        title: String,
        content: String = "Details about $title.",
        kind: MemoryKind = MemoryKind.FACT,
        tier: MemoryTier = MemoryTier.LONG_TERM,
        tags: Set<String> = emptySet(),
        importance: Double = 0.5,
        ttlMs: Long? = null,
    ) = MemoryDraft(
        scope = scope, kind = kind, title = title, content = content, tier = tier, tags = tags, importance = importance,
        provenance = Provenance(ProvenanceOrigin.AGENT, recordedAt = 0), ttlMs = ttlMs,
    )

    suspend fun remember(
        actor: MemoryActor,
        scope: MemoryScope,
        title: String,
        content: String = "Details about $title.",
        importance: Double = 0.5,
        kind: MemoryKind = MemoryKind.FACT,
        tags: Set<String> = emptySet(),
    ): MemoryRecord = service.remember(actor, draft(scope, title, content, kind = kind, importance = importance, tags = tags))

    companion object {
        fun private(agent: MemoryActor.Agent) = MemoryScope.Agent(agent.profile.id)
        fun task(agent: MemoryActor.Agent, task: String) = MemoryScope.Task(TaskId(task), agent.profile.id)
        fun shared(space: KnowledgeSpace) = MemoryScope.Shared(space.id)
    }
}

/** An embedder that records every text it is sent, optionally fails, and claims any locality. */
class RecordingEmbedder(
    override val locality: Locality = Locality.CLOUD,
    override val id: String = "test-remote-384",
) : Embedder {
    private val delegate = HashingEmbedder()
    override val dimensions: Int = delegate.dimensions
    val calls = mutableListOf<List<String>>()
    var failing = false

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        calls += texts
        // The message quotes the input, as real client libraries sometimes do: it must never reach the log.
        if (failing) throw IllegalStateException("could not embed: ${texts.joinToString()}")
        return delegate.embed(texts)
    }
}
