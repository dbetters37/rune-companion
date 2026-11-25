package com.aicontrolcenter.framework.registry

import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.agents.resolveAvailability
import com.aicontrolcenter.core.registry.AgentDirectory
import com.aicontrolcenter.core.registry.AgentEntry
import com.aicontrolcenter.core.registry.AiAppDirectory
import com.aicontrolcenter.core.registry.ModelDirectory
import com.aicontrolcenter.core.registry.ProviderDirectory
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.settings.SettingsView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

class DefaultRegistryDirectory(
    private val agents: AgentDirectory,
    private val providers: ProviderDirectory,
    private val models: ModelDirectory,
    private val apps: AiAppDirectory,
    private val settings: SettingsView,
) : RegistryDirectory {

    override fun observe(): Flow<RegistrySnapshot> {
        val registry = combine(providers.observeAll(), models.observeAll(), apps.observeAll(), settings.settings) { p, m, a, s ->
            RegistrySnapshot(providers = p, models = m, apps = a.filterNot { it.hidden }, settings = s)
        }
        return combine(agents.observeAll(), agents.observeStats(), registry) { profiles, stats, base ->
            val modelsById = base.models.associateBy { it.id }
            val providersById = base.providers.associateBy { it.id }
            base.copy(
                agents = profiles.map { profile ->
                    AgentEntry(
                        profile = profile,
                        availability = resolveAvailability(profile, modelsById, providersById, base.settings),
                        stats = stats[profile.id] ?: AgentStats(),
                        isDesignatedDirector = base.settings.directorAgentId == profile.id,
                    )
                },
            )
        }.distinctUntilChanged()
    }

    override suspend fun snapshot(): RegistrySnapshot = observe().first()
}
