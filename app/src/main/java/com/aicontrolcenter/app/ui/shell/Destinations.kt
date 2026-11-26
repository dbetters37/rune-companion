package com.aicontrolcenter.app.ui.shell

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import com.aicontrolcenter.app.ui.activity.ActivityTab
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
import com.aicontrolcenter.app.ui.settings.SettingsAnchor
import com.aicontrolcenter.app.ui.tools.ToolsTab
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

/** [tab] opens one of the section's tabs; [agentId] narrows its Permissions tab to one agent. */
@Serializable data class ToolsRoute(val tab: ToolsTab = ToolsTab.TOOLS, val agentId: String? = null)

/**
 * The Activity section (Phase 6: a data class, so a screen can open a tab or a filtered log). [tab] opens the log or
 * "Usage and cost"; [agentId] narrows the log to one agent; [text] fills the log's search (a model's name, say).
 */
@Serializable data class ActivityRoute(val tab: ActivityTab = ActivityTab.LOG, val agentId: String? = null, val text: String? = null)

/** The Settings section; [anchor] opens it scrolled to one of its parts (wave 3: the background check, the routines' switches). */
@Serializable data class SettingsRoute(val anchor: SettingsAnchor? = null)

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

/** Writing a new objective for the Director. */
@Serializable data object NewObjectiveRoute

/** One objective: its plan, the agents' conversation, the log and the final document. */
@Serializable data class ObjectiveRoute(val objectiveId: String)

/** One task of an objective, with its transcript. */
@Serializable data class TaskRoute(val taskId: String)

/** The questions waiting for the owner's answer. */
@Serializable data object ApprovalsRoute

/** One question to the owner, where it is answered. */
@Serializable data class ApprovalRoute(val requestId: String)

/** A folder of the agents' workspace; the empty path is the workspace itself. */
@Serializable data class WorkspaceRoute(val path: String = "")

/** One workspace file, read-only. */
@Serializable data class WorkspaceFileRoute(val path: String)

/** The workspace trash. */
@Serializable data object WorkspaceTrashRoute

// --- Phase 6 (wave 3): the owner's new screens. Which package fills each is in scratchpad/phase6/wave3-ownership.md. ---

/** The Team view (WP9): who works, who waits for whom, who waits for you. [objectiveId] opens it on one objective. */
@Serializable data class TeamRoute(val objectiveId: String? = null)

/** One question an agent asked the owner, where it is answered (WP11a, rule 13): never the Approval screen. */
@Serializable data class QuestionRoute(val requestId: String)

/** Today's limits of answers, tokens and money, and what happens when one is reached (WP10a; Settings). */
@Serializable data object DailyLimitsRoute

/** Saving a backup file (WP10b, Tier B; Settings). No intent opens it (rule 23). */
@Serializable data object BackupSaveRoute

/** Restoring a backup file into a fresh app (WP10b, Tier B; Settings). No intent opens it (rule 23). */
@Serializable data object BackupRestoreRoute

/** The owner's routines (WP10c, Tier B; Tools: a list of the owner's things). */
@Serializable data object RoutinesRoute

/** A new routine, or [routineId] to change one (WP10c). */
@Serializable data class RoutineEditorRoute(val routineId: String? = null)

/**
 * The routines waiting for the owner's tap (WP10c): the "Tap to start" confirmation step. The app opens here by itself
 * when it comes to the front while a run waits that this process has not shown yet (never from an intent, rule 23);
 * Start is WP17's `WaitingRoutinesBanner`, which asks once more before exactly that run starts.
 */
@Serializable data object WaitingRoutinesRoute

/** The owner's folder pick-up rules (WP10c, Tier B; Tools). */
@Serializable data object PickUpRulesRoute

/** A new pick-up rule, for [folderId] when one is picked already, or [ruleId] to change one (WP10c). */
@Serializable data class PickUpRuleEditorRoute(val ruleId: String? = null, val folderId: String? = null)

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
            TOOLS -> ToolsRoute()
            ACTIVITY -> ActivityRoute()
            SETTINGS -> SettingsRoute()
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
    hasRoute<TasksRoute>() || hasRoute<NewObjectiveRoute>() || hasRoute<ObjectiveRoute>() || hasRoute<TaskRoute>() ||
        hasRoute<TeamRoute>() -> Section.TASKS
    hasRoute<MemoryRoute>() || hasRoute<MemoryDetailRoute>() || hasRoute<MemoryEditorRoute>() ||
        hasRoute<SpaceDetailRoute>() -> Section.MEMORY
    // Requests are about what agents may do; routines and pick-up rules are lists of the owner's things.
    hasRoute<ToolsRoute>() || hasRoute<ApprovalsRoute>() || hasRoute<ApprovalRoute>() || hasRoute<QuestionRoute>() ||
        hasRoute<WorkspaceRoute>() || hasRoute<WorkspaceFileRoute>() || hasRoute<WorkspaceTrashRoute>() ||
        hasRoute<RoutinesRoute>() || hasRoute<RoutineEditorRoute>() || hasRoute<WaitingRoutinesRoute>() ||
        hasRoute<PickUpRulesRoute>() || hasRoute<PickUpRuleEditorRoute>() -> Section.TOOLS
    hasRoute<ActivityRoute>() -> Section.ACTIVITY
    // Settings-like switches and the screens behind them.
    hasRoute<SettingsRoute>() || hasRoute<DailyLimitsRoute>() || hasRoute<BackupSaveRoute>() || hasRoute<BackupRestoreRoute>() -> Section.SETTINGS
    else -> Section.DASHBOARD
}
