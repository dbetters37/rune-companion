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
import com.aicontrolcenter.app.ui.components.InfoBanner
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
)

@Composable
fun DashboardRoute(navigation: DashboardNavigation) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel {
        DashboardViewModel(graph.registryDirectory, graph.activityLog, graph.aiAppRegistry, graph.clock)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardScreen(
        state = state,
        navigation = navigation,
        onSelectNode = viewModel::selectNode,
        onScan = viewModel::scanForApps,
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
    onMessageShown: () -> Unit,
) {
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
                )
            }
            val notice: @Composable () -> Unit = {
                state.setupNotice?.let { InfoBanner(text = it, tone = StatusTone.INFO) }
            }
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
                    network()
                    actions()
                    activity()
                }
            }
        }
    }
}
