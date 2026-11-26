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
import com.aicontrolcenter.app.ui.activity.LocalUsageWork
import com.aicontrolcenter.app.routines.WaitingRoutinesBanner
import com.aicontrolcenter.app.routines.WaitingRoutinesViewModel
import androidx.compose.runtime.MutableState
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
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.TaskId

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
    /** Opens a task an agent is working on. */
    val onOpenTask: (TaskId) -> Unit = {},
    // --- Phase 6 (wave 3): where the new Dashboard parts lead ---
    /** Activity › Usage and cost. */
    val onOpenUsage: () -> Unit = {},
    /** Settings › Daily limits. */
    val onOpenDailyLimits: () -> Unit = {},
    /** The Settings section (Working in the background, and the rest). */
    val onOpenSettings: () -> Unit = {},
    /** What a model, a provider and an agent are. */
    val onOpenConcepts: () -> Unit = {},
    /** One objective (one paused by today's limit, say). */
    val onOpenObjective: (TaskId) -> Unit = {},
    /** The Team view, on one objective or on all. */
    val onOpenTeam: (TaskId?) -> Unit = {},
    /** One model's page (the Today tile's "Add its price" for the one model without a price). */
    val onOpenModel: (ModelId) -> Unit = {},
    /**
     * Asks "Try with the simulator?" for the agents that have no model yet (Getting started's simulator step). Null when
     * every agent has a model: the step then opens the agents.
     */
    val onTrySimulator: (() -> Unit)? = null,
)

/**
 * The Dashboard's Phase 6 parts, each owned by its package and placed by [DashboardScreen] (wave 3, WP8): null shows
 * nothing, which is what previews and the existing screenshots use.
 */
class DashboardSlots(
    /** At the very top: safe start is on (WP10a). */
    val safeStart: (@Composable () -> Unit)? = null,
    /** At the top: a routine waits for the owner's tap (WP17's banner). */
    val waitingRoutines: (@Composable () -> Unit)? = null,
    /** With the first-run notice: Getting started (WP10a, Tier B). */
    val gettingStarted: (@Composable () -> Unit)? = null,
    /** Below the summary: today's answers, tokens and cost (WP10a). */
    val today: (@Composable () -> Unit)? = null,
    /** Below the attention list: the phone's own attention items (WP10a). */
    val phoneAttention: (@Composable () -> Unit)? = null,
)

@Composable
fun DashboardRoute(navigation: DashboardNavigation) {
    val graph = LocalAppGraph.current
    // Phase 6 (wave 3): the Dashboard's parts are built on the background pool the app hands the screens (LocalUsageWork,
    // the same pool as LocalScreenWork; tests keep their own dispatcher).
    val work = LocalUsageWork.current
    val viewModel = viewModel {
        DashboardViewModel(
            directory = graph.registryDirectory,
            activityLog = graph.activityLog,
            aiAppRegistry = graph.aiAppRegistry,
            clock = graph.clock,
            // The owner's one zone, as every other screen (wave 3): the activity's times.
            zone = graph.zone(),
            monitor = graph.activityMonitor,
            agentManager = graph.agentManager,
            memory = graph.memoryService,
            orchestrator = graph.orchestrator,
            tasks = graph.tasks,
            messages = graph.messages,
            // P4 (D35): the memory count is the store's COUNT query, never every memory read on every recall write.
            memoryCount = graph.ports.memories::observeCount,
            // P2: the state is built off the main thread, latest first (handoff-WP9.md item 1).
            computation = work,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // One view model for the Today tile, Getting started, the phone's attention items and safe start.
    val extras = viewModel {
        DashboardExtrasViewModel(
            today = graph.todayUsage,
            settings = graph.settings,
            directory = graph.registryDirectory,
            tasks = graph.tasks,
            backgroundHealth = graph.backgroundHealth,
            storage = graph.storageWatch,
            safeStart = graph.safeStart,
            clock = graph.clock,
            reports = graph.usageReports,
            prices = graph.modelPrices,
            zone = graph.zone,
            waits = graph.orchestrator.timedWaits,
            computation = work,
        )
    }
    // Getting started's simulator step asks the same question as the first-run notice, while some agent has no model.
    val confirmSimulator = rememberSaveable { mutableStateOf(false) }
    val extrasNavigation = if (state.quickStart != null) navigation.copy(onTrySimulator = { confirmSimulator.value = true }) else navigation
    // "Tap to start" (WP17, F12): the banner names each waiting run; Start asks once more, then starts exactly that run.
    val waiting = viewModel { WaitingRoutinesViewModel(graph.waitingRoutines, graph.clock, graph.zone) }
    DashboardScreen(
        state = state,
        navigation = navigation,
        onSelectNode = viewModel::selectNode,
        onScan = viewModel::scanForApps,
        onTrySimulator = viewModel::trySimulator,
        onMessageShown = viewModel::messageShown,
        slots = DashboardSlots(
            safeStart = { SafeStartBanner(extras, navigation) },
            // After a confirmed start the message offers the objective, as the "Tap to start" step does (handoff-WP10c.md).
            waitingRoutines = { WaitingRoutinesBanner(waiting, onOpenObjective = navigation.onOpenObjective) },
            gettingStarted = { GettingStartedCard(extras, extrasNavigation) },
            today = { TodayTile(extras, navigation) },
            phoneAttention = { PhoneAttention(extras, navigation) },
        ),
        confirmSimulator = confirmSimulator,
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
    slots: DashboardSlots = DashboardSlots(),
    /** Whether "Try with the simulator?" is open; the route shares it with Getting started. */
    confirmSimulator: MutableState<Boolean> = rememberSaveable { mutableStateOf(false) },
) {
    var askSimulator by confirmSimulator
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
            val top: @Composable () -> Unit = {
                slots.safeStart?.invoke()
                slots.waitingRoutines?.invoke()
            }
            val notice: @Composable () -> Unit = {
                slots.gettingStarted?.invoke()
                state.quickStart?.let {
                    QuickStartCard(
                        quickStart = it,
                        isAssigning = state.isAssigning,
                        onConnectProvider = navigation.onAddProvider,
                        onAssignModel = { navigation.onOpenAttention(AttentionTarget.Agents) },
                        onTrySimulator = { askSimulator = true },
                    )
                }
            }
            val working: @Composable () -> Unit = { WorkingNowSection(state.working, navigation.onOpenAgent, navigation.onOpenTask) }
            val attention: @Composable () -> Unit = {
                AttentionSection(state.attention, navigation.onOpenAttention)
                slots.phoneAttention?.invoke()
            }
            val today: @Composable () -> Unit = { slots.today?.invoke() }
            val network: @Composable () -> Unit = {
                NetworkCard(
                    network = state.network,
                    selectedNode = state.selectedNode,
                    onSelectNode = onSelectNode,
                    onOpenAgent = navigation.onOpenAgent,
                    onNewAgent = navigation.onNewAgent,
                    onOpenTeam = { navigation.onOpenTeam(null) },
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
                        top()
                        notice()
                        summary()
                        today()
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
                    top()
                    notice()
                    summary()
                    today()
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
    if (askSimulator && quickStart != null) {
        ConfirmDialog(
            title = "Try with the simulator?",
            message = "${summariseNames(quickStart.agentsWithoutModel)} will use the built-in simulator. It is not a real " +
                "model: it answers offline with scripted replies that say they are simulated, so you can try chat, tools and " +
                "the activity log without connecting anything. Nothing leaves this phone. Assign a real model to each agent later.",
            confirmLabel = "Use the simulator",
            icon = AppIcons.Sparkle,
            cautionary = true,
            onConfirm = {
                askSimulator = false
                onTrySimulator()
            },
            onDismiss = { askSimulator = false },
        )
    }
}
