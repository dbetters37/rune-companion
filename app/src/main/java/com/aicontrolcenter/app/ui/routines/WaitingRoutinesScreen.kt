package com.aicontrolcenter.app.ui.routines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.routines.WaitingRoutinesBanner
import com.aicontrolcenter.app.routines.WaitingRoutinesViewModel
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Schedule
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.core.TaskId

/**
 * The "Tap to start" confirmation step (F12; WP10c): the routine runs waiting for the owner's tap. The app comes here by
 * itself when it is brought to the front while a run waits that it has not shown yet (the shell decides from what waits,
 * never from an intent, rule 23). Start is WP17's [WaitingRoutinesBanner], which asks once more before exactly that run
 * starts; nothing starts from this screen otherwise. When nothing waits it says so; after a confirmed start it links to
 * the objective that began.
 */
@Composable
fun WaitingRoutinesRoute(onBack: () -> Unit, onOpenObjective: (TaskId) -> Unit) {
    val graph = LocalAppGraph.current
    val waiting = viewModel { WaitingRoutinesViewModel(graph.waitingRoutines, graph.clock, graph.zone) }
    WaitingRoutinesScreen(waiting, onBack, onOpenObjective)
}

@Composable
fun WaitingRoutinesScreen(waiting: WaitingRoutinesViewModel, onBack: () -> Unit, onOpenObjective: (TaskId) -> Unit) {
    val state by waiting.uiState.collectAsStateWithLifecycle()
    ScreenScaffold(title = "Routines waiting to start", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .readableWidth()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (state.items.isNotEmpty()) {
                Text(
                    WAITING_INTRO,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WaitingRoutinesBanner(waiting, onOpenObjective = onOpenObjective)
            if (state.items.isEmpty() && state.message == null) {
                EmptyState(
                    icon = AppIcons.Schedule,
                    title = "Nothing is waiting to start",
                    message = NOTHING_WAITS,
                )
            }
        }
    }
}

/** Above the waiting runs: what this step is. */
internal const val WAITING_INTRO: String =
    "These routines did not start by themselves: Android held them back, or they came too late. Nothing starts until you tap " +
        "Start and confirm. \"Not now\" leaves this run out; the routine runs again at its next time."

/** When no run waits. */
internal const val NOTHING_WAITS: String =
    "When Android does not let a routine start by itself, it waits here for you to tap Start. Nothing ever starts from this " +
        "screen without you."
