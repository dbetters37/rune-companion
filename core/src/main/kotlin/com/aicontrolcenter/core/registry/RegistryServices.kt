package com.aicontrolcenter.core.registry

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.settings.FrameworkSettings
import kotlinx.coroutines.flow.Flow

/** Result of one device scan, after merging into the registry. */
data class DiscoveryReport(
    /** Apps seen for the first time and added to the registry. */
    val added: List<AiApp>,
    /** Registered apps whose details changed (version, capabilities, status). */
    val updated: List<AiApp>,
    /** Registered apps that are no longer installed. They stay listed, marked NOT_INSTALLED. */
    val missing: List<AiApp>,
    val scannedAt: Long,
)

/**
 * The registry of AI applications on this phone: discovery, manual registration, status
 * tracking and per-app authorisation. Registration records that an app exists; it never grants
 * control beyond the app's [controlChannels].
 *
 * Methods that take an id throw [IllegalArgumentException] when no visible app has that id,
 * except [remove], which does nothing in that case.
 */
interface AiAppRegistry {
    /** Registered apps, without the ones the owner removed. */
    fun observeApps(): Flow<List<AiApp>>

    /**
     * Scans the device and merges the result into the registry. The owner's settings on
     * existing entries are kept, removed apps stay removed, and an app whose install identity
     * changed loses its accessibility authorisation.
     */
    suspend fun discover(): DiscoveryReport

    /** Re-checks only the already registered apps. Returns how many entries changed. */
    suspend fun refreshStatuses(): Int

    /** Launchable installed apps that are not in the registry, for the manual-registration picker. */
    suspend fun unregisteredInstalledApps(): List<InstalledAppInfo>

    /**
     * Registers an installed app chosen by the owner, or brings back one that was removed.
     * @throws IllegalArgumentException if the package is not installed or is already registered.
     */
    suspend fun registerManually(packageName: String): AiApp

    suspend fun setEnabled(id: AiAppId, enabled: Boolean)

    /** Records the owner's confirmation that a heuristically detected app is an AI app. */
    suspend fun confirm(id: AiAppId)

    /**
     * Records the owner's explicit decision to allow or forbid accessibility operation of this app.
     * @throws IllegalArgumentException when allowing an app that is not installed, or that is
     * disabled in Android settings.
     */
    suspend fun setAccessibilityAuthorized(id: AiAppId, authorized: Boolean)

    /** Links the app to a registered provider of the same vendor's models, or unlinks it with null. */
    suspend fun linkProvider(id: AiAppId, providerId: ProviderId?)

    suspend fun updateNotes(id: AiAppId, notes: String)

    /**
     * Takes the app out of the registry. A later scan will not bring it back; registering it
     * manually will.
     */
    suspend fun remove(id: AiAppId)
}

/** One agent with everything the UI needs to show about it. */
data class AgentEntry(
    val profile: AgentProfile,
    val availability: AgentAvailability,
    val stats: AgentStats,
    val isDesignatedDirector: Boolean,
)

/** A consistent view of the whole registry at one moment. */
data class RegistrySnapshot(
    val agents: List<AgentEntry> = emptyList(),
    val providers: List<ModelProvider> = emptyList(),
    val models: List<ModelDescriptor> = emptyList(),
    val apps: List<AiApp> = emptyList(),
    val settings: FrameworkSettings = FrameworkSettings(),
) {
    val modelsById: Map<ModelId, ModelDescriptor> by lazy { models.associateBy { it.id } }
    val providersById: Map<ProviderId, ModelProvider> by lazy { providers.associateBy { it.id } }
    val agentsById: Map<AgentId, AgentEntry> by lazy { agents.associateBy { it.profile.id } }

    val readyAgents: Int get() = agents.count { it.availability.isReady }
    val unavailableAgents: Int get() = agents.count { it.availability is AgentAvailability.Unavailable }
    val disabledAgents: Int get() = agents.count { it.availability is AgentAvailability.Disabled }
    val installedApps: Int get() = apps.count { it.status == AiAppStatus.INSTALLED }
    val director: AgentEntry? get() = agents.firstOrNull { it.isDesignatedDirector }

    fun provider(model: ModelDescriptor): ModelProvider? = providersById[model.providerId]
}

/** The directory of every registered AI application, provider, model and agent, kept live. */
interface RegistryDirectory {
    fun observe(): Flow<RegistrySnapshot>

    suspend fun snapshot(): RegistrySnapshot
}
