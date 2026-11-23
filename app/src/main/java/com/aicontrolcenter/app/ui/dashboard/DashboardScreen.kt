package com.aicontrolcenter.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.aicontrolcenter.app.ui.agents.summariseNames
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Sparkle
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.AgentId

/** Where the dashboard can send the owner. Supplied by the navigation shell. */
data class DashboardNavigation(
    val onOpenAgent: (AgentId) -> Unit,
    val onOpenAttention: (AttentionTarget) -> Unit,
    val onNewAgent: () -> Unit,
    val onOpenAgents: () -> Unit,
    val onOpenModels: () -> Unit,
    val onOpenProviders: () -> Unit,
    val onOpenApps: () -> Unit,
    val onSeeAllActivity: () -> Unit,
    val onAddProvider: () -> Unit,
    val onOpenProvider: (com.aicontrolcenter.core.ProviderId) -> Unit,
    val onOpenMemory: () -> Unit = {},
)

@Composable
fun DashboardRoute(navigation: DashboardNavigation) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel {
        DashboardViewModel(
            directory = graph.registryDirectory,
            activityLog = graph.activityLog,
            aiAppRegistry = graph.aiAppRegistry,
            clock = graph.clock,
            monitor = graph.activityMonitor,
            agentManager = graph.agentManager,
            memory = graph.memoryService,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardScreen(
        state = state,
        navigation = navigation,
        onSelectNode = viewModel::selectNode,
        onScan = viewModel::scanForApps,
        onTrySimulator = viewModel::trySimulator,
        onMessageShown = viewModel::messageShown,
    )
}

/** Width from which the dashboard splits into two columns instead of one long scroll. */
private val TwoPaneMinWidth = 700.dp

@Composable
fun DashboardScreen(
    state: DashboardUiState,
    navigation: DashboardNavigation,
    onSelectNode: (String?) -> Unit,
    onScan: () -> Unit,
    onTrySimulator: () -> Unit,
    onMessageShown: () -> Unit,
) {
    var confirmSimulator by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(title = "Dashboard", message = state.message, onMessageShown = onMessageShown) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@ScreenScaffold
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val summary: @Composable () -> Unit = {
                EcosystemSummarySection(
                    summary = state.summary,
                    onOpenAgents = navigation.onOpenAgents,
                    onOpenAgent = navigation.onOpenAgent,
                    onOpenModels = navigation.onOpenModels,
                    onOpenProviders = navigation.onOpenProviders,
                    onOpenApps = navigation.onOpenApps,
                    memory = state.memory,
                    onOpenMemory = navigation.onOpenMemory,
                )
            }
            val notice: @Composable () -> Unit = {
                state.quickStart?.let {
                    QuickStartCard(
                        quickStart = it,
                        isAssigning = state.isAssigning,
                        onConnectProvider = navigation.onAddProvider,
                        onAssignModel = { navigation.onOpenAttention(AttentionTarget.Agents) },
                        onTrySimulator = { confirmSimulator = true },
                    )
                }
            }
            val working: @Composable () -> Unit = { WorkingNowSection(state.working, navigation.onOpenAgent) }
            val attention: @Composable () -> Unit = { AttentionSection(state.attention, navigation.onOpenAttention) }
            val network: @Composable () -> Unit = {
                NetworkCard(
                    network = state.network,
                    selectedNode = state.selectedNode,
                    onSelectNode = onSelectNode,
                    onOpenAgent = navigation.onOpenAgent,
                    onNewAgent = navigation.onNewAgent,
                )
            }
            val actions: @Composable () -> Unit = {
                QuickActionsSection(state.isScanning, navigation.onNewAgent, onScan)
            }
            val activity: @Composable () -> Unit = {
                RecentActivitySection(state.recentActivity, navigation.onSeeAllActivity)
            }
            val contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xl)
            if (maxWidth >= TwoPaneMinWidth) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(contentPadding)
                        .widthIn(max = 1200.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        notice()
                        summary()
                        attention()
                        working()
                        actions()
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        network()
                        activity()
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(contentPadding),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    notice()
                    summary()
                    attention()
                    working()
                    network()
                    actions()
                    activity()
                }
            }
        }
    }
    val quickStart = state.quickStart
    if (confirmSimulator && quickStart != null) {
        ConfirmDialog(
            title = "Try with the simulator?",
            message = "${summariseNames(quickStart.agentsWithoutModel)} will use the built-in simulator. It is not a real " +
                "model: it answers offline with scripted replies that say they are simulated, so you can try chat, tools and " +
                "the activity log without connecting anything. Nothing leaves this phone. Assign a real model to each agent later.",
            confirmLabel = "Use the simulator",
            icon = AppIcons.Sparkle,
            cautionary = true,
            onConfirm = {
                confirmSimulator = false
                onTrySimulator()
            },
            onDismiss = { confirmSimulator = false },
        )
    }
}
