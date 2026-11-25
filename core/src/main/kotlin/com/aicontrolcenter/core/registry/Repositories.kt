package com.aicontrolcenter.core.registry

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentStats
import kotlinx.coroutines.flow.Flow

/*
 * Persistence ports for the registry. Implementations: Room (the app) and in-memory (tests).
 * All "observe" flows emit the current value immediately and again after every change.
 * Lists are ordered by creation time (first-seen time for apps), oldest first, with ties
 * broken by id. Services that create several rows at once give them increasing timestamps.
 */

/**
 * The agents as agent-facing code reads them (security review of wave 1, F3, second review): no way
 * to add, change or remove an agent, so nothing an agent reaches can widen an agent's tools, spaces
 * or model. [AgentRepository] is the owner's store; agent-facing classes declare this or
 * [AgentStatsLog] and are wired with a separate read-only object ([readOnly]).
 */
interface AgentDirectory {
    fun observeAll(): Flow<List<AgentProfile>>
    fun observe(id: AgentId): Flow<AgentProfile?>
    suspend fun getAll(): List<AgentProfile>
    suspend fun get(id: AgentId): AgentProfile?

    fun observeStats(): Flow<Map<AgentId, AgentStats>>

    /** Returns the stored stats, or an all-zero [AgentStats] for an agent without history. */
    suspend fun getStats(id: AgentId): AgentStats
}

/** The agents read-only, plus the track record the runtime keeps after every task and review: what the runner and the orchestrator hold. */
interface AgentStatsLog : AgentDirectory {
    /**
     * Atomically applies [transform] to the agent's stats; concurrent calls never lose an
     * update. Does nothing for an agent that does not exist, so stats never outlive an agent.
     */
    suspend fun updateStats(id: AgentId, transform: (AgentStats) -> AgentStats)
}

/** The owner's store of agents: owner-only in both architecture tests (it adds, changes and removes agents). */
interface AgentRepository : AgentStatsLog {
    /** Inserts or replaces the profile by id. The agent's stats are not touched. */
    suspend fun upsert(agent: AgentProfile)

    /** Removes the agent and its stats. No-op if absent. */
    suspend fun delete(id: AgentId)
}

/**
 * The providers as agent-facing code reads them (F3, second review): no way to add, repoint or
 * remove one. A status a call learned is stored through `ProviderConnections.storeStatus`, the one
 * agent-facing holder of the full [ProviderRepository].
 */
interface ProviderDirectory {
    fun observeAll(): Flow<List<ModelProvider>>
    suspend fun getAll(): List<ModelProvider>
    suspend fun get(id: ProviderId): ModelProvider?
}

/** The owner's store of providers: owner-only in both architecture tests. */
interface ProviderRepository : ProviderDirectory {
    suspend fun upsert(provider: ModelProvider)

    /** Removes the provider. Its models are removed by [ModelRepository.deleteByProvider]. */
    suspend fun delete(id: ProviderId)
}

/** The models as agent-facing code reads them (F3, second review): no way to add, repoint or remove one. */
interface ModelDirectory {
    fun observeAll(): Flow<List<ModelDescriptor>>
    suspend fun getAll(): List<ModelDescriptor>
    suspend fun get(id: ModelId): ModelDescriptor?
    suspend fun getByProvider(providerId: ProviderId): List<ModelDescriptor>
}

/** The owner's store of models: owner-only in both architecture tests. */
interface ModelRepository : ModelDirectory {
    suspend fun upsert(model: ModelDescriptor)
    suspend fun upsertAll(models: List<ModelDescriptor>)
    suspend fun delete(id: ModelId)
    suspend fun deleteByProvider(providerId: ProviderId)
}

/** The AI apps as agent-facing code reads them (F3, second review). */
interface AiAppDirectory {
    fun observeAll(): Flow<List<AiApp>>
    suspend fun getAll(): List<AiApp>
    suspend fun get(id: AiAppId): AiApp?
    suspend fun getByPackage(packageName: String): AiApp?
}

/** The owner's store of AI apps: owner-only in both architecture tests. */
interface AiAppRepository : AiAppDirectory {
    suspend fun upsert(app: AiApp)
    suspend fun delete(id: AiAppId)
}

/**
 * Takes a knowledge space away from agents' memory settings: the one change to an agent the memory
 * service makes, when the owner deletes a space. It can only narrow what agents may read or write.
 */
fun interface SpaceAccessRevoker {
    /** Removes [spaceId] from the readable and writable spaces of every agent that has it, at [now]; returns how many changed. */
    suspend fun revoke(spaceId: SpaceId, now: Long): Int
}

/**
 * Read-only views of the registry's stores, each a separate object that implements the reading
 * interface alone: nothing holding one can be cast to the store, and the app's architecture walk
 * allows a full store only inside these (security review of wave 1, F3, second review).
 */
object ReadOnlyRegistry {
    private class Agents(private val store: AgentStatsLog) : AgentStatsLog {
        override fun observeAll() = store.observeAll()
        override fun observe(id: AgentId) = store.observe(id)
        override suspend fun getAll() = store.getAll()
        override suspend fun get(id: AgentId) = store.get(id)
        override fun observeStats() = store.observeStats()
        override suspend fun getStats(id: AgentId) = store.getStats(id)
        override suspend fun updateStats(id: AgentId, transform: (AgentStats) -> AgentStats) = store.updateStats(id, transform)
        override fun toString(): String = "ReadOnlyRegistry.Agents"
    }

    private class Providers(private val store: ProviderDirectory) : ProviderDirectory {
        override fun observeAll() = store.observeAll()
        override suspend fun getAll() = store.getAll()
        override suspend fun get(id: ProviderId) = store.get(id)
        override fun toString(): String = "ReadOnlyRegistry.Providers"
    }

    private class Models(private val store: ModelDirectory) : ModelDirectory {
        override fun observeAll() = store.observeAll()
        override suspend fun getAll() = store.getAll()
        override suspend fun get(id: ModelId) = store.get(id)
        override suspend fun getByProvider(providerId: ProviderId) = store.getByProvider(providerId)
        override fun toString(): String = "ReadOnlyRegistry.Models"
    }

    private class Apps(private val store: AiAppDirectory) : AiAppDirectory {
        override fun observeAll() = store.observeAll()
        override suspend fun getAll() = store.getAll()
        override suspend fun get(id: AiAppId) = store.get(id)
        override suspend fun getByPackage(packageName: String) = store.getByPackage(packageName)
        override fun toString(): String = "ReadOnlyRegistry.Apps"
    }

    private class SpaceRevoker(private val store: AgentRepository) : SpaceAccessRevoker {
        override suspend fun revoke(spaceId: SpaceId, now: Long): Int {
            var changed = 0
            for (listed in store.getAll()) {
                if (spaceId !in listed.memory.readableSpaces && spaceId !in listed.memory.writableSpaces) continue
                // Re-read right before writing, so an edit made since the list was read is kept.
                val agent = store.get(listed.id) ?: continue
                val policy = agent.memory
                if (spaceId !in policy.readableSpaces && spaceId !in policy.writableSpaces) continue
                store.upsert(
                    agent.copy(memory = policy.copy(readableSpaces = policy.readableSpaces - spaceId, writableSpaces = policy.writableSpaces - spaceId), updatedAt = now),
                )
                changed++
            }
            return changed
        }

        override fun toString(): String = "ReadOnlyRegistry.SpaceRevoker"
    }

    /** Taking a space away from every agent, and nothing else, over the owner's agent store. */
    fun spaceRevoker(store: AgentRepository): SpaceAccessRevoker = SpaceRevoker(store)

    /** The agents read-only, with their track record writable (stats only). */
    fun agents(store: AgentStatsLog): AgentStatsLog = store as? Agents ?: Agents(store)

    fun providers(store: ProviderDirectory): ProviderDirectory = store as? Providers ?: Providers(store)

    fun models(store: ModelDirectory): ModelDirectory = store as? Models ?: Models(store)

    fun apps(store: AiAppDirectory): AiAppDirectory = store as? Apps ?: Apps(store)

    /** The classes of the views, for the architecture walks' list of objects allowed to hold a full store. */
    val VIEW_CLASSES: List<Class<*>> = listOf(Agents::class.java, Providers::class.java, Models::class.java, Apps::class.java, SpaceRevoker::class.java)
}

/**
 * Reads the device for AI applications. The Android implementation uses only PackageManager
 * queries the app is entitled to; it never inspects another app's data.
 */
interface AiAppScanner {
    /** Installed packages that look like AI applications (catalog matches and intent handlers). */
    suspend fun scanForAiApps(): List<DiscoveredApp>

    /** Every launchable installed app, for manual registration. */
    suspend fun listInstalledApps(): List<InstalledAppInfo>

    /** Fresh facts about one package, or null when it is not installed. */
    suspend fun inspect(packageName: String): DiscoveredApp?
}
