package com.aicontrolcenter.core.registry

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentStats
import kotlinx.coroutines.flow.Flow

/*
 * Persistence ports for the registry. Implementations: Room (the app) and in-memory (tests).
 * All "observe" flows emit the current value immediately and again after every change.
 * Lists are ordered by creation time, oldest first, unless stated otherwise.
 */

interface AgentRepository {
    fun observeAll(): Flow<List<AgentProfile>>
    fun observe(id: AgentId): Flow<AgentProfile?>
    suspend fun getAll(): List<AgentProfile>
    suspend fun get(id: AgentId): AgentProfile?

    /** Inserts or replaces by id. */
    suspend fun upsert(agent: AgentProfile)

    /** Removes the agent and its stats. No-op if absent. */
    suspend fun delete(id: AgentId)

    fun observeStats(): Flow<Map<AgentId, AgentStats>>

    /** Returns the stored stats, or an all-zero [AgentStats] for an agent without history. */
    suspend fun getStats(id: AgentId): AgentStats

    /** Atomically applies [transform] to the agent's stats. */
    suspend fun updateStats(id: AgentId, transform: (AgentStats) -> AgentStats)
}

interface ProviderRepository {
    fun observeAll(): Flow<List<ModelProvider>>
    suspend fun getAll(): List<ModelProvider>
    suspend fun get(id: ProviderId): ModelProvider?
    suspend fun upsert(provider: ModelProvider)

    /** Removes the provider. Its models are removed by [ModelRepository.deleteByProvider]. */
    suspend fun delete(id: ProviderId)
}

interface ModelRepository {
    fun observeAll(): Flow<List<ModelDescriptor>>
    suspend fun getAll(): List<ModelDescriptor>
    suspend fun get(id: ModelId): ModelDescriptor?
    suspend fun getByProvider(providerId: ProviderId): List<ModelDescriptor>
    suspend fun upsert(model: ModelDescriptor)
    suspend fun upsertAll(models: List<ModelDescriptor>)
    suspend fun delete(id: ModelId)
    suspend fun deleteByProvider(providerId: ProviderId)
}

interface AiAppRepository {
    fun observeAll(): Flow<List<AiApp>>
    suspend fun getAll(): List<AiApp>
    suspend fun get(id: AiAppId): AiApp?
    suspend fun getByPackage(packageName: String): AiApp?
    suspend fun upsert(app: AiApp)
    suspend fun delete(id: AiAppId)
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
