package com.aicontrolcenter.app.ui.shell

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import com.aicontrolcenter.app.ui.agents.AgentStatusFilter
import com.aicontrolcenter.app.ui.icons.Activity
import com.aicontrolcenter.app.ui.icons.Agent
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Dashboard
import com.aicontrolcenter.app.ui.icons.MemoryBrain
import com.aicontrolcenter.app.ui.icons.ModelChip
import com.aicontrolcenter.app.ui.icons.Tasks
import com.aicontrolcenter.app.ui.icons.Tools
import com.aicontrolcenter.app.ui.memory.MemoryTab
import com.aicontrolcenter.app.ui.models.ModelsTab
import kotlinx.serialization.Serializable

/*
 * Type-safe navigation routes. The eight section routes are the top level; everything else is
 * a screen reached from inside a section. Ids travel as plain strings and are wrapped in their
 * typed ids at the destination.
 */

@Serializable data object DashboardRoute

@Serializable data class AgentsRoute(val status: AgentStatusFilter = AgentStatusFilter.ALL)

@Serializable data class ModelsRoute(val tab: ModelsTab = ModelsTab.PROVIDERS)

@Serializable data object TasksRoute

/** [agentId] or [spaceId] opens the explorer narrowed to that agent's private memory or that space. */
@Serializable data class MemoryRoute(val tab: MemoryTab = MemoryTab.EXPLORER, val agentId: String? = null, val spaceId: String? = null)

@Serializable data object ToolsRoute

@Serializable data object ActivityRoute

@Serializable data object SettingsRoute

@Serializable data object AgentTemplatesRoute

/** Both null creates a blank agent; [templateId] starts from a template; [agentId] edits an existing agent. */
@Serializable data class AgentEditorRoute(val agentId: String? = null, val templateId: String? = null)

@Serializable data class AgentDetailRoute(val agentId: String)

@Serializable data class AiAppDetailRoute(val appId: String)

@Serializable data object RegisterAppRoute

@Serializable data object ConceptsRoute

/** The gallery of provider presets: the first step of adding a provider. */
@Serializable data object ProviderPresetsRoute

/**
 * Adds a provider from [presetId], or edits [providerId]. [baseUrl] pre-fills the address of
 * a server found on this phone.
 */
@Serializable data class ProviderFormRoute(val presetId: String? = null, val providerId: String? = null, val baseUrl: String? = null)

@Serializable data class ProviderDetailRoute(val providerId: String)

@Serializable data class ModelEditorRoute(val modelId: String)

/** An agent's conversations. */
@Serializable data class ConversationsRoute(val agentId: String)

@Serializable data class ChatRoute(val conversationId: String)

@Serializable data class MemoryDetailRoute(val memoryId: String)

/** [memoryId] edits a memory; otherwise a new one, pre-set to [agentId]'s private memory or [spaceId]. */
@Serializable data class MemoryEditorRoute(val memoryId: String? = null, val agentId: String? = null, val spaceId: String? = null)

@Serializable data class SpaceDetailRoute(val spaceId: String)

/** The eight primary sections of the control center, in their fixed order. */
enum class Section(val title: String, val description: String) {
    DASHBOARD("Dashboard", "Overview of your whole AI ecosystem"),
    AGENTS("Agents", "View, create, edit and manage agents"),
    MODELS("Models", "Providers, models and installed AI apps"),
    TASKS("Tasks", "Objectives and the work done on them"),
    MEMORY("Memory", "What agents remember and share"),
    TOOLS("Tools", "What agents are allowed to do"),
    ACTIVITY("Activity", "Communication, actions and history"),
    SETTINGS("Settings", "Framework limits, privacy and security"),
    ;

    val icon: ImageVector
        get() = when (this) {
            DASHBOARD -> AppIcons.Dashboard
            AGENTS -> AppIcons.Agent
            MODELS -> AppIcons.ModelChip
            TASKS -> AppIcons.Tasks
            MEMORY -> AppIcons.MemoryBrain
            TOOLS -> AppIcons.Tools
            ACTIVITY -> AppIcons.Activity
            SETTINGS -> AppIcons.Settings
        }

    /** The route that opens this section in its default state. */
    val route: Any
        get() = when (this) {
            DASHBOARD -> DashboardRoute
            AGENTS -> AgentsRoute()
            MODELS -> ModelsRoute()
            TASKS -> TasksRoute
            MEMORY -> MemoryRoute()
            TOOLS -> ToolsRoute
            ACTIVITY -> ActivityRoute
            SETTINGS -> SettingsRoute
        }

    companion object {
        /** Sections with their own item in the phone's bottom bar; the rest are reached through "More". */
        val bottomBar: List<Section> = listOf(DASHBOARD, AGENTS, TASKS, ACTIVITY)
    }
}

/** True for the eight section roots, where the phone shows its bottom bar. */
fun NavDestination.isTopLevel(): Boolean =
    hasRoute<DashboardRoute>() || hasRoute<AgentsRoute>() || hasRoute<ModelsRoute>() || hasRoute<TasksRoute>() ||
        hasRoute<MemoryRoute>() || hasRoute<ToolsRoute>() || hasRoute<ActivityRoute>() || hasRoute<SettingsRoute>()

/** The section a destination belongs to, so navigation stays highlighted on inner screens. */
fun NavDestination.section(): Section = when {
    hasRoute<DashboardRoute>() -> Section.DASHBOARD
    hasRoute<AgentsRoute>() || hasRoute<AgentTemplatesRoute>() || hasRoute<AgentEditorRoute>() ||
        hasRoute<AgentDetailRoute>() || hasRoute<ConversationsRoute>() || hasRoute<ChatRoute>() -> Section.AGENTS
    hasRoute<ModelsRoute>() || hasRoute<AiAppDetailRoute>() || hasRoute<RegisterAppRoute>() ||
        hasRoute<ConceptsRoute>() || hasRoute<ProviderPresetsRoute>() || hasRoute<ProviderFormRoute>() ||
        hasRoute<ProviderDetailRoute>() || hasRoute<ModelEditorRoute>() -> Section.MODELS
    hasRoute<TasksRoute>() -> Section.TASKS
    hasRoute<MemoryRoute>() || hasRoute<MemoryDetailRoute>() || hasRoute<MemoryEditorRoute>() ||
        hasRoute<SpaceDetailRoute>() -> Section.MEMORY
    hasRoute<ToolsRoute>() -> Section.TOOLS
    hasRoute<ActivityRoute>() -> Section.ACTIVITY
    hasRoute<SettingsRoute>() -> Section.SETTINGS
    else -> Section.DASHBOARD
}
