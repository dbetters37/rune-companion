package com.aicontrolcenter.app.ui.shell

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.aicontrolcenter.app.ui.activity.ActivityRoute as ActivityScreenRoute
import com.aicontrolcenter.app.ui.agents.AgentDetailRoute as AgentDetailScreenRoute
import com.aicontrolcenter.app.ui.agents.AgentEditorRoute as AgentEditorScreenRoute
import com.aicontrolcenter.app.ui.agents.AgentStatusFilter
import com.aicontrolcenter.app.ui.agents.AgentTemplatesRoute as AgentTemplatesScreenRoute
import com.aicontrolcenter.app.ui.agents.AgentsRoute as AgentsScreenRoute
import com.aicontrolcenter.app.ui.dashboard.AttentionTarget
import com.aicontrolcenter.app.ui.dashboard.DashboardNavigation
import com.aicontrolcenter.app.ui.dashboard.DashboardRoute as DashboardScreenRoute
import com.aicontrolcenter.app.ui.models.AiAppDetailRoute as AiAppDetailScreenRoute
import com.aicontrolcenter.app.ui.chat.ChatRoute as ChatScreenRoute
import com.aicontrolcenter.app.ui.chat.ConversationsRoute as ConversationsScreenRoute
import com.aicontrolcenter.app.ui.models.ConceptsScreen
import com.aicontrolcenter.app.ui.models.ModelEditorRoute as ModelEditorScreenRoute
import com.aicontrolcenter.app.ui.models.ModelsNavigation
import com.aicontrolcenter.app.ui.models.ModelsRoute as ModelsScreenRoute
import com.aicontrolcenter.app.ui.models.PresetGalleryRoute
import com.aicontrolcenter.app.ui.models.ProviderDetailRoute as ProviderDetailScreenRoute
import com.aicontrolcenter.app.ui.models.ProviderFormRoute as ProviderFormScreenRoute
import com.aicontrolcenter.app.ui.models.ModelsTab
import com.aicontrolcenter.app.ui.models.RegisterAppRoute as RegisterAppScreenRoute
import com.aicontrolcenter.app.ui.memory.MemoryDetailRoute as MemoryDetailScreenRoute
import com.aicontrolcenter.app.ui.memory.MemoryEditorRoute as MemoryEditorScreenRoute
import com.aicontrolcenter.app.ui.memory.MemoryNavigation
import com.aicontrolcenter.app.ui.memory.MemorySectionRoute
import com.aicontrolcenter.app.ui.memory.MemoryTab
import com.aicontrolcenter.app.ui.memory.ScopeFilter
import com.aicontrolcenter.app.ui.memory.SpaceDetailRoute as SpaceDetailScreenRoute
import com.aicontrolcenter.app.ui.planned.PlannedSection
import com.aicontrolcenter.app.ui.tasks.NewObjectiveRoute as NewObjectiveScreenRoute
import com.aicontrolcenter.app.ui.tasks.ObjectiveRoute as ObjectiveScreenRoute
import com.aicontrolcenter.app.ui.tasks.ObjectivesRoute
import com.aicontrolcenter.app.ui.tasks.TaskDetailRoute
import com.aicontrolcenter.app.ui.planned.PlannedSectionScreen
import com.aicontrolcenter.app.ui.settings.SettingsRoute as SettingsScreenRoute
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId

/**
 * Switches to a section root. The start destination stays at the bottom of the stack, so Back
 * from any section returns to the Dashboard and then leaves the app.
 *
 * @param restore reopen the section where the owner left it. Pass false when the route carries
 *   arguments that must win over whatever was open before (a filter chosen on the dashboard).
 */
fun NavHostController.navigateToSection(route: Any, restore: Boolean = true) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = restore
    }
}

/**
 * Opens an inner screen. Single-top, so a double tap on a card or button opens the screen once
 * instead of stacking two copies of it.
 */
private fun NavHostController.open(route: Any) = navigate(route) { launchSingleTop = true }

/** The navigation graph: every destination and how screens lead to each other. */
@Composable
fun AppNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    val openAgent: (AgentId) -> Unit = { navController.open(AgentDetailRoute(it.value)) }
    val openApp: (AiAppId) -> Unit = { navController.open(AiAppDetailRoute(it.value)) }
    val openProvider: (ProviderId) -> Unit = { navController.open(ProviderDetailRoute(it.value)) }
    val openModel: (ModelId) -> Unit = { navController.open(ModelEditorRoute(it.value)) }
    val addProvider: () -> Unit = { navController.open(ProviderPresetsRoute) }
    val openConversation: (ConversationId) -> Unit = { navController.open(ChatRoute(it.value)) }
    val back: () -> Unit = { navController.popBackStack() }
    val openMemory: (MemoryId) -> Unit = { navController.open(MemoryDetailRoute(it.value)) }
    val openSpace: (SpaceId) -> Unit = { navController.open(SpaceDetailRoute(it.value)) }
    val openObjective: (TaskId) -> Unit = { navController.open(ObjectiveRoute(it.value)) }
    val openTask: (TaskId) -> Unit = { navController.open(TaskRoute(it.value)) }
    // A filter chosen elsewhere must win over the explorer the owner left, so the section is not restored.
    val openAgentMemories: (AgentId) -> Unit = { navController.navigateToSection(MemoryRoute(agentId = it.value), restore = false) }
    val openSpaceMemories: (SpaceId) -> Unit = { navController.navigateToSection(MemoryRoute(spaceId = it.value), restore = false) }

    NavHost(
        navController = navController,
        startDestination = DashboardRoute,
        modifier = modifier,
        enterTransition = { fadeIn(tween(180)) },
        exitTransition = { fadeOut(tween(120)) },
        popEnterTransition = { fadeIn(tween(180)) },
        popExitTransition = { fadeOut(tween(120)) },
    ) {
        composable<DashboardRoute> {
            DashboardScreenRoute(
                DashboardNavigation(
                    onOpenAgent = openAgent,
                    onOpenAttention = { target ->
                        when (target) {
                            is AttentionTarget.Agent -> openAgent(target.id)
                            is AttentionTarget.AiApp -> openApp(target.id)
                            is AttentionTarget.Provider -> openProvider(target.id)
                            AttentionTarget.Agents -> navController.navigateToSection(AgentsRoute(), restore = false)
                            AttentionTarget.UnavailableAgents ->
                                navController.navigateToSection(AgentsRoute(AgentStatusFilter.UNAVAILABLE), restore = false)
                        }
                    },
                    onNewAgent = { navController.open(AgentTemplatesRoute) },
                    onOpenAgents = { navController.navigateToSection(AgentsRoute()) },
                    onOpenModels = { navController.navigateToSection(ModelsRoute(ModelsTab.MODELS), restore = false) },
                    onOpenProviders = { navController.navigateToSection(ModelsRoute(ModelsTab.PROVIDERS), restore = false) },
                    onOpenApps = { navController.navigateToSection(ModelsRoute(ModelsTab.APPS), restore = false) },
                    onSeeAllActivity = { navController.navigateToSection(ActivityRoute) },
                    onAddProvider = addProvider,
                    onOpenProvider = openProvider,
                    onOpenMemory = { navController.navigateToSection(MemoryRoute(MemoryTab.EXPLORER), restore = false) },
                    onOpenTask = openTask,
                ),
            )
        }
        composable<AgentsRoute> { entry ->
            AgentsScreenRoute(
                initialStatusFilter = entry.toRoute<AgentsRoute>().status,
                onOpenAgent = openAgent,
                onNewAgent = { navController.open(AgentTemplatesRoute) },
                onAddProvider = addProvider,
            )
        }
        composable<AgentTemplatesRoute> {
            AgentTemplatesScreenRoute(
                onBack = back,
                onPickTemplate = { templateId -> navController.open(AgentEditorRoute(templateId = templateId)) },
            )
        }
        composable<AgentEditorRoute> { entry ->
            val route = entry.toRoute<AgentEditorRoute>()
            AgentEditorScreenRoute(
                agentId = route.agentId?.let(::AgentId),
                templateId = route.templateId,
                onClose = back,
                onSaved = { id, isNew ->
                    if (isNew) {
                        // Leave the creation flow behind: Back from the new agent returns to where it started.
                        navController.navigate(AgentDetailRoute(id.value)) {
                            popUpTo<AgentTemplatesRoute> { inclusive = true }
                            launchSingleTop = true
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
            )
        }
        composable<AgentDetailRoute> { entry ->
            AgentDetailScreenRoute(
                agentId = AgentId(entry.toRoute<AgentDetailRoute>().agentId),
                onBack = back,
                onEdit = { navController.open(AgentEditorRoute(agentId = it.value)) },
                onOpenAgent = openAgent,
                onOpenModels = { navController.navigateToSection(ModelsRoute(ModelsTab.MODELS), restore = false) },
                onChat = { navController.open(ConversationsRoute(it.value)) },
                onOpenMemories = openAgentMemories,
                onOpenTask = openTask,
                onOpenObjective = openObjective,
            )
        }
        composable<ConversationsRoute> { entry ->
            ConversationsScreenRoute(
                agentId = AgentId(entry.toRoute<ConversationsRoute>().agentId),
                onBack = back,
                onOpenConversation = openConversation,
            )
        }
        composable<ChatRoute> { entry ->
            ChatScreenRoute(
                conversationId = ConversationId(entry.toRoute<ChatRoute>().conversationId),
                onBack = back,
                onOpenProvider = openProvider,
            )
        }
        composable<ModelsRoute> { entry ->
            ModelsScreenRoute(
                initialTab = entry.toRoute<ModelsRoute>().tab,
                navigation = ModelsNavigation(
                    onOpenApp = openApp,
                    onRegisterManually = { navController.open(RegisterAppRoute) },
                    onOpenConcepts = { navController.open(ConceptsRoute) },
                    onAddProvider = addProvider,
                    onOpenProvider = openProvider,
                    onOpenModel = openModel,
                    onAddDetected = { server ->
                        navController.open(ProviderFormRoute(presetId = server.presetId, baseUrl = server.baseUrl))
                    },
                ),
            )
        }
        composable<ProviderPresetsRoute> {
            PresetGalleryRoute(onBack = back, onPick = { navController.open(ProviderFormRoute(presetId = it)) })
        }
        composable<ProviderFormRoute> { entry ->
            val route = entry.toRoute<ProviderFormRoute>()
            ProviderFormScreenRoute(
                presetId = route.presetId,
                providerId = route.providerId?.let(::ProviderId),
                baseUrl = route.baseUrl,
                onClose = back,
                onFinished = { id, isNew ->
                    if (isNew) {
                        // Leave the adding flow behind: Back from the new provider returns to where it started.
                        navController.popBackStack<ProviderFormRoute>(inclusive = true)
                        navController.popBackStack<ProviderPresetsRoute>(inclusive = true)
                        navController.open(ProviderDetailRoute(id.value))
                    } else {
                        navController.popBackStack()
                    }
                },
            )
        }
        composable<ProviderDetailRoute> { entry ->
            ProviderDetailScreenRoute(
                providerId = ProviderId(entry.toRoute<ProviderDetailRoute>().providerId),
                onBack = back,
                onEdit = { navController.open(ProviderFormRoute(providerId = it.value)) },
                onOpenModel = openModel,
            )
        }
        composable<ModelEditorRoute> { entry ->
            ModelEditorScreenRoute(modelId = ModelId(entry.toRoute<ModelEditorRoute>().modelId), onBack = back)
        }
        composable<AiAppDetailRoute> { entry ->
            AiAppDetailScreenRoute(appId = AiAppId(entry.toRoute<AiAppDetailRoute>().appId), onBack = back)
        }
        composable<RegisterAppRoute> {
            RegisterAppScreenRoute(
                onBack = back,
                onRegistered = { id ->
                    navController.navigate(AiAppDetailRoute(id.value)) {
                        popUpTo<RegisterAppRoute> { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<ConceptsRoute> { ConceptsScreen(onBack = back) }
        composable<TasksRoute> {
            ObjectivesRoute(onOpenObjective = openObjective, onNewObjective = { navController.open(NewObjectiveRoute) })
        }
        composable<NewObjectiveRoute> {
            NewObjectiveScreenRoute(
                onBack = back,
                onStarted = { id ->
                    // Leave the form behind: Back from the new objective returns to the list.
                    navController.navigate(ObjectiveRoute(id.value)) {
                        popUpTo<NewObjectiveRoute> { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<ObjectiveRoute> { entry ->
            ObjectiveScreenRoute(objectiveId = TaskId(entry.toRoute<ObjectiveRoute>().objectiveId), onBack = back, onOpenTask = openTask)
        }
        composable<TaskRoute> { entry ->
            TaskDetailRoute(taskId = TaskId(entry.toRoute<TaskRoute>().taskId), onBack = back, onOpenTask = openTask, onOpenObjective = openObjective)
        }
        composable<MemoryRoute> { entry ->
            val route = entry.toRoute<MemoryRoute>()
            MemorySectionRoute(
                initialTab = route.tab,
                initialScope = when {
                    route.agentId != null -> ScopeFilter.Agent(AgentId(route.agentId))
                    route.spaceId != null -> ScopeFilter.Space(SpaceId(route.spaceId))
                    else -> ScopeFilter.All
                },
                navigation = MemoryNavigation(
                    onOpenMemory = openMemory,
                    onNewMemory = { scope ->
                        navController.open(
                            MemoryEditorRoute(
                                agentId = (scope as? MemoryScope.Agent)?.agentId?.value,
                                spaceId = (scope as? MemoryScope.Shared)?.spaceId?.value,
                            ),
                        )
                    },
                    onOpenSpace = openSpace,
                ),
            )
        }
        composable<MemoryDetailRoute> { entry ->
            MemoryDetailScreenRoute(
                memoryId = MemoryId(entry.toRoute<MemoryDetailRoute>().memoryId),
                onBack = back,
                onEdit = { navController.open(MemoryEditorRoute(memoryId = it.value)) },
                onOpenMemory = openMemory,
                onOpenAgent = openAgent,
                onOpenConversation = openConversation,
            )
        }
        composable<MemoryEditorRoute> { entry ->
            val route = entry.toRoute<MemoryEditorRoute>()
            MemoryEditorScreenRoute(
                memoryId = route.memoryId?.let(::MemoryId),
                initialScope = when {
                    route.agentId != null -> MemoryScope.Agent(AgentId(route.agentId))
                    route.spaceId != null -> MemoryScope.Shared(SpaceId(route.spaceId))
                    else -> null
                },
                onClose = back,
                onSaved = { id, isNew ->
                    if (isNew) {
                        // Leave the editor behind: Back from the new memory returns to where it was started.
                        navController.navigate(MemoryDetailRoute(id.value)) {
                            popUpTo<MemoryEditorRoute> { inclusive = true }
                            launchSingleTop = true
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
            )
        }
        composable<SpaceDetailRoute> { entry ->
            SpaceDetailScreenRoute(
                spaceId = SpaceId(entry.toRoute<SpaceDetailRoute>().spaceId),
                onBack = back,
                onOpenMemories = openSpaceMemories,
            )
        }
        composable<ToolsRoute> { PlannedSectionScreen(PlannedSection.TOOLS) }
        composable<ActivityRoute> { ActivityScreenRoute() }
        composable<SettingsRoute> { SettingsScreenRoute() }
    }
}
