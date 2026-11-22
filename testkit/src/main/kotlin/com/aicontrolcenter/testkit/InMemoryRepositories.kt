package com.aicontrolcenter.testkit

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.registry.AiAppRepository
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ModelRepository
import com.aicontrolcenter.core.registry.ProviderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/*
 * In-memory reference implementations of the persistence ports. They define the expected
 * behaviour that the Room implementations must match (see the contract suites).
 */

class InMemoryAgentRepository : AgentRepository {
    private val agents = MutableStateFlow<Map<AgentId, AgentProfile>>(LinkedHashMap())
    private val stats = MutableStateFlow<Map<AgentId, AgentStats>>(emptyMap())

    private fun Map<AgentId, AgentProfile>.ordered() = values.sortedWith(compareBy({ it.createdAt }, { it.id.value }))

    override fun observeAll(): Flow<List<AgentProfile>> = agents.map { it.ordered() }.distinctUntilChanged()
    override fun observe(id: AgentId): Flow<AgentProfile?> = agents.map { it[id] }.distinctUntilChanged()
    override suspend fun getAll(): List<AgentProfile> = agents.value.ordered()
    override suspend fun get(id: AgentId): AgentProfile? = agents.value[id]
    override suspend fun upsert(agent: AgentProfile) = agents.update { it + (agent.id to agent) }

    override suspend fun delete(id: AgentId) {
        agents.update { it - id }
        stats.update { it - id }
    }

    override fun observeStats(): Flow<Map<AgentId, AgentStats>> = stats
    override suspend fun getStats(id: AgentId): AgentStats = stats.value[id] ?: AgentStats()

    override suspend fun updateStats(id: AgentId, transform: (AgentStats) -> AgentStats) {
        if (id !in agents.value) return
        stats.update { it + (id to transform(it[id] ?: AgentStats())) }
    }
}

class InMemoryProviderRepository : ProviderRepository {
    private val state = MutableStateFlow<Map<ProviderId, ModelProvider>>(LinkedHashMap())

    private fun Map<ProviderId, ModelProvider>.ordered() = values.sortedWith(compareBy({ it.createdAt }, { it.id.value }))

    override fun observeAll(): Flow<List<ModelProvider>> = state.map { it.ordered() }.distinctUntilChanged()
    override suspend fun getAll(): List<ModelProvider> = state.value.ordered()
    override suspend fun get(id: ProviderId): ModelProvider? = state.value[id]
    override suspend fun upsert(provider: ModelProvider) = state.update { it + (provider.id to provider) }
    override suspend fun delete(id: ProviderId) = state.update { it - id }
}

class InMemoryModelRepository : ModelRepository {
    private val state = MutableStateFlow<Map<ModelId, ModelDescriptor>>(LinkedHashMap())

    private fun Map<ModelId, ModelDescriptor>.ordered() = values.sortedWith(compareBy({ it.createdAt }, { it.id.value }))

    override fun observeAll(): Flow<List<ModelDescriptor>> = state.map { it.ordered() }.distinctUntilChanged()
    override suspend fun getAll(): List<ModelDescriptor> = state.value.ordered()
    override suspend fun get(id: ModelId): ModelDescriptor? = state.value[id]
    override suspend fun getByProvider(providerId: ProviderId): List<ModelDescriptor> =
        state.value.ordered().filter { it.providerId == providerId }

    override suspend fun upsert(model: ModelDescriptor) = state.update { it + (model.id to model) }
    override suspend fun upsertAll(models: List<ModelDescriptor>) = state.update { it + models.associateBy { m -> m.id } }
    override suspend fun delete(id: ModelId) = state.update { it - id }
    override suspend fun deleteByProvider(providerId: ProviderId) =
        state.update { all -> all.filterValues { it.providerId != providerId } }
}

class InMemoryAiAppRepository : AiAppRepository {
    private val state = MutableStateFlow<Map<AiAppId, AiApp>>(LinkedHashMap())

    private fun Map<AiAppId, AiApp>.ordered() = values.sortedWith(compareBy({ it.firstSeenAt }, { it.id.value }))

    override fun observeAll(): Flow<List<AiApp>> = state.map { it.ordered() }.distinctUntilChanged()
    override suspend fun getAll(): List<AiApp> = state.value.ordered()
    override suspend fun get(id: AiAppId): AiApp? = state.value[id]
    override suspend fun getByPackage(packageName: String): AiApp? =
        state.value.values.firstOrNull { it.packageName == packageName }

    override suspend fun upsert(app: AiApp) = state.update { it + (app.id to app) }
    override suspend fun delete(id: AiAppId) = state.update { it - id }
}

class InMemoryActivityLog : ActivityLog {
    private val events = MutableStateFlow<List<ActivityEvent>>(emptyList())

    /** Every event in insertion order, for assertions. */
    val all: List<ActivityEvent> get() = events.value

    override suspend fun record(event: ActivityEvent) =
        events.update { all -> if (all.any { it.id == event.id }) all else all + event }

    private fun select(all: List<ActivityEvent>, filter: ActivityFilter, limit: Int): List<ActivityEvent> =
        all.asReversed().asSequence().filter(filter::matches)
            .sortedWith(compareByDescending<ActivityEvent> { it.at })
            .take(limit).toList()

    override fun observe(filter: ActivityFilter, limit: Int): Flow<List<ActivityEvent>> =
        events.map { select(it, filter, limit) }.distinctUntilChanged()

    override suspend fun recent(filter: ActivityFilter, limit: Int): List<ActivityEvent> =
        select(events.value, filter, limit)

    override suspend fun prune(olderThan: Long): Int {
        var removed = 0
        events.update { all ->
            val kept = all.filter { it.at >= olderThan }
            removed = all.size - kept.size
            kept
        }
        return removed
    }

    override suspend fun clear() = events.update { emptyList() }
}
