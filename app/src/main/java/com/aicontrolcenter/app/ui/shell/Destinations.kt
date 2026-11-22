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

@Serializable data object MemoryRoute

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
            MEMORY -> MemoryRoute
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
        hasRoute<AgentDetailRoute>() -> Section.AGENTS
    hasRoute<ModelsRoute>() || hasRoute<AiAppDetailRoute>() || hasRoute<RegisterAppRoute>() ||
        hasRoute<ConceptsRoute>() -> Section.MODELS
    hasRoute<TasksRoute>() -> Section.TASKS
    hasRoute<MemoryRoute>() -> Section.MEMORY
    hasRoute<ToolsRoute>() -> Section.TOOLS
    hasRoute<ActivityRoute>() -> Section.ACTIVITY
    hasRoute<SettingsRoute>() -> Section.SETTINGS
    else -> Section.DASHBOARD
}
