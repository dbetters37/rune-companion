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
import com.aicontrolcenter.app.ui.models.ConceptsScreen
import com.aicontrolcenter.app.ui.models.ModelsRoute as ModelsScreenRoute
import com.aicontrolcenter.app.ui.models.ModelsTab
import com.aicontrolcenter.app.ui.models.RegisterAppRoute as RegisterAppScreenRoute
import com.aicontrolcenter.app.ui.planned.PlannedSection
import com.aicontrolcenter.app.ui.planned.PlannedSectionScreen
import com.aicontrolcenter.app.ui.settings.SettingsRoute as SettingsScreenRoute
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.AiAppId

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

/** The navigation graph: every destination and how screens lead to each other. */
@Composable
fun AppNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    val openAgent: (AgentId) -> Unit = { navController.navigate(AgentDetailRoute(it.value)) }
    val openApp: (AiAppId) -> Unit = { navController.navigate(AiAppDetailRoute(it.value)) }
    val back: () -> Unit = { navController.popBackStack() }

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
                            AttentionTarget.Agents -> navController.navigateToSection(AgentsRoute(), restore = false)
                            AttentionTarget.UnavailableAgents ->
                                navController.navigateToSection(AgentsRoute(AgentStatusFilter.UNAVAILABLE), restore = false)
                        }
                    },
                    onNewAgent = { navController.navigate(AgentTemplatesRoute) },
                    onOpenAgents = { navController.navigateToSection(AgentsRoute()) },
                    onOpenModels = { navController.navigateToSection(ModelsRoute(ModelsTab.MODELS), restore = false) },
                    onOpenProviders = { navController.navigateToSection(ModelsRoute(ModelsTab.PROVIDERS), restore = false) },
                    onOpenApps = { navController.navigateToSection(ModelsRoute(ModelsTab.APPS), restore = false) },
                    onSeeAllActivity = { navController.navigateToSection(ActivityRoute) },
                ),
            )
        }
        composable<AgentsRoute> { entry ->
            AgentsScreenRoute(
                initialStatusFilter = entry.toRoute<AgentsRoute>().status,
                onOpenAgent = openAgent,
                onNewAgent = { navController.navigate(AgentTemplatesRoute) },
            )
        }
        composable<AgentTemplatesRoute> {
            AgentTemplatesScreenRoute(
                onBack = back,
                onPickTemplate = { templateId -> navController.navigate(AgentEditorRoute(templateId = templateId)) },
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
                onEdit = { navController.navigate(AgentEditorRoute(agentId = it.value)) },
                onOpenAgent = openAgent,
                onOpenModels = { navController.navigateToSection(ModelsRoute(ModelsTab.MODELS), restore = false) },
            )
        }
        composable<ModelsRoute> { entry ->
            ModelsScreenRoute(
                initialTab = entry.toRoute<ModelsRoute>().tab,
                onOpenApp = openApp,
                onRegisterManually = { navController.navigate(RegisterAppRoute) },
                onOpenConcepts = { navController.navigate(ConceptsRoute) },
            )
        }
        composable<AiAppDetailRoute> { entry ->
            AiAppDetailScreenRoute(appId = AiAppId(entry.toRoute<AiAppDetailRoute>().appId), onBack = back)
        }
        composable<RegisterAppRoute> {
            RegisterAppScreenRoute(
                onBack = back,
                onRegistered = { id ->
                    navController.navigate(AiAppDetailRoute(id.value)) { popUpTo<RegisterAppRoute> { inclusive = true } }
                },
            )
        }
        composable<ConceptsRoute> { ConceptsScreen(onBack = back) }
        composable<TasksRoute> { PlannedSectionScreen(PlannedSection.TASKS) }
        composable<MemoryRoute> { PlannedSectionScreen(PlannedSection.MEMORY) }
        composable<ToolsRoute> { PlannedSectionScreen(PlannedSection.TOOLS) }
        composable<ActivityRoute> { ActivityScreenRoute() }
        composable<SettingsRoute> { SettingsScreenRoute() }
    }
}
