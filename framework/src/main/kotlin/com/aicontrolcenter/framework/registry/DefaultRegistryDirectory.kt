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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.plus

/**
 * The registry as agents and screens read it: agents with their availability, providers, models and apps.
 *
 * Phase 6 (P8): with a [sharing] scope, every [observe] shares one snapshot. Before, each observer built its own
 * flows over the five stores and the settings, and every write (an agent's stats after each run, a provider's status
 * after each call) rebuilt one snapshot per open screen. The shared one runs while someone observes it and for
 * [stopAfterMs] ([STOP_AFTER_MS] in the app) after the last one leaves, then stops and forgets its snapshot, so a later observer never starts
 * from an old one. It runs unconfined, in the thread of the change it follows, so it adds no dispatch of its own.
 *
 * [snapshot] always reads the stores afresh: a caller that has just changed something (a status a check stored)
 * sees its own change, which a shared snapshot that has not caught up yet might not show.
 */
class DefaultRegistryDirectory(
    private val agents: AgentDirectory,
    private val providers: ProviderDirectory,
    private val models: ModelDirectory,
    private val apps: AiAppDirectory,
    private val settings: SettingsView,
    sharing: CoroutineScope? = null,
    stopAfterMs: Long = STOP_AFTER_MS,
) : RegistryDirectory {

    private val shared: SharedFlow<RegistrySnapshot>? = sharing?.let { scope ->
        build().shareIn(scope + Dispatchers.Unconfined, SharingStarted.WhileSubscribed(stopAfterMs, replayExpirationMillis = 0), replay = 1)
    }

    override fun observe(): Flow<RegistrySnapshot> = shared ?: build()

    override suspend fun snapshot(): RegistrySnapshot = build().first()

    private fun build(): Flow<RegistrySnapshot> {
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

    /** How sharing ends. */
    companion object {
        /** A screen that is left and opened again within this time finds the snapshot still running. */
        const val STOP_AFTER_MS: Long = 5_000L
    }
}
