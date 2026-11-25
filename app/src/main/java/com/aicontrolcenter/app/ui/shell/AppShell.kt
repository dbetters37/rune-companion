package com.aicontrolcenter.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.approvals.OperatorNotificationsAsk
import com.aicontrolcenter.app.ui.theme.AppTheme

/** Width from which the app uses a navigation rail instead of a bottom bar. */
internal val WideLayoutMinWidth = 600.dp

/**
 * The whole app for one [graph]: theme, composition root and navigation shell.
 *
 * @param darkTheme follows the system unless a caller (a preview, a screenshot test) pins it.
 */
@Composable
fun AiControlCenterRoot(graph: AppGraph, darkTheme: Boolean = isSystemInDarkTheme()) {
    AppTheme(darkTheme) {
        CompositionLocalProvider(LocalAppGraph provides graph) { AppShell() }
    }
}

/**
 * The frame around every screen. Phones get a bottom bar with four sections and "More", which
 * opens a sheet listing all eight; wide screens get a rail with all eight. The bottom bar is
 * shown on section roots only, so inner screens (an editor, a detail page) have the full height.
 * While requests or agents' questions wait for the owner, phones show how many above the bottom bar, and the rail
 * shows the count on its "Waiting for you" item at the top.
 */
@Composable
fun AppShell(modifier: Modifier = Modifier) {
    val graph = LocalAppGraph.current
    val navController = rememberNavController()
    val shellViewModel = viewModel { ShellViewModel(graph.registryDirectory, graph.ownerRequests.pending, graph.waitingRoutines.observeWaiting()) }
    val shellState by shellViewModel.uiState.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val currentSection = destination?.section() ?: Section.DASHBOARD
    val isTopLevel = destination?.isTopLevel() ?: true
    var showMore by rememberSaveable { mutableStateOf(false) }
    val openSection: (Section) -> Unit = { section -> navController.navigateToSection(section.route) }
    val approvalsOpen = destination?.let { it.hasRoute<ApprovalsRoute>() || it.hasRoute<ApprovalRoute>() } == true
    val openApprovals: () -> Unit = { navController.navigate(ApprovalsRoute) { launchSingleTop = true } }

    // A request opened from its notification goes straight to its Approval screen.
    val approvalToOpen by graph.approvalToOpen.collectAsStateWithLifecycle()
    LaunchedEffect(approvalToOpen) {
        approvalToOpen?.let { id ->
            graph.approvalOpened()
            navController.navigate(ApprovalRoute(id.value)) { launchSingleTop = true }
        }
    }

    // "Tap to start" (WP17, F12): a routine run that waits for the owner's tap opens its confirmation step once, when the
    // app is on screen; never from an intent (rule 23). The Dashboard shows the same banner, so there it only counts as shown.
    val routineToShow = shellState.routineToShow
    LaunchedEffect(routineToShow) {
        routineToShow?.let { run ->
            shellViewModel.routineShown(run)
            val here = navController.currentDestination
            val alreadyShown = here?.hasRoute<DashboardRoute>() == true || here?.hasRoute<WaitingRoutinesRoute>() == true
            if (!alreadyShown) navController.navigate(WaitingRoutinesRoute) { launchSingleTop = true }
        }
    }

    // With the phone operator on, the owner is asked once for notifications, after an explanation, so a waiting request is seen.
    OperatorNotificationsAsk()

    // An objective asked for from outside the app (its notification) opens once the shell exists.
    val objectiveToOpen by graph.objectiveToOpen.collectAsStateWithLifecycle()
    LaunchedEffect(objectiveToOpen) {
        objectiveToOpen?.let { id ->
            graph.objectiveOpened()
            navController.navigate(ObjectiveRoute(id.value)) { launchSingleTop = true }
        }
    }

    // Movable so the navigation host keeps its state when a resize swaps bar for rail.
    val screens = remember(navController) {
        movableContentOf { hostModifier: Modifier -> AppNavHost(navController, hostModifier) }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (maxWidth >= WideLayoutMinWidth) {
            Row(Modifier.fillMaxSize()) {
                AppNavigationRail(currentSection, shellState, openSection, approvalsOpen, openApprovals)
                Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.systemBars.only(WindowInsetsSides.Start))) {
                    screens(Modifier.fillMaxSize())
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier.weight(1f).then(
                        // The bar already sits above the system navigation area.
                        if (isTopLevel) {
                            Modifier.consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                        } else {
                            Modifier
                        },
                    ),
                ) {
                    screens(Modifier.fillMaxSize())
                }
                // Agents wait while a request is open, so it is shown on every section root until answered.
                if (isTopLevel && shellState.pendingRequests > 0) {
                    ApprovalsStrip(shellState.pendingRequests, openApprovals, questions = shellState.pendingQuestions)
                }
                if (isTopLevel) {
                    AppBottomBar(
                        current = currentSection,
                        state = shellState,
                        onOpenSection = openSection,
                        onOpenMore = { showMore = true },
                    )
                }
            }
        }
    }

    if (showMore) {
        MoreSheet(
            current = currentSection,
            state = shellState,
            onOpenSection = { section ->
                showMore = false
                openSection(section)
            },
            onDismiss = { showMore = false },
        )
    }
}
