@file:Phase6Stub("WP10c")

package com.aicontrolcenter.app.ui.routines

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.routines.WaitingRoutinesBanner
import com.aicontrolcenter.app.routines.WaitingRoutinesViewModel
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.core.TaskId

/**
 * The "Tap to start" confirmation step (F12; WP10c): the routine runs waiting for the owner's tap. The app comes here by
 * itself when it is brought to the front while a run waits that it has not shown yet (the shell decides from what waits,
 * never from an intent, rule 23). Start is WP17's [WaitingRoutinesBanner], which asks once more before exactly that run
 * starts; nothing starts from this screen otherwise. WP10c adds the empty state and the link to the objective.
 */
@Composable
fun WaitingRoutinesRoute(onBack: () -> Unit, @Suppress("UNUSED_PARAMETER") onOpenObjective: (TaskId) -> Unit) {
    val graph = LocalAppGraph.current
    val waiting = viewModel { WaitingRoutinesViewModel(graph.waitingRoutines, graph.clock, graph.zone) }
    ScreenScaffold(title = "Routines waiting to start", onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Spacing.screen)) {
            WaitingRoutinesBanner(waiting)
        }
    }
}
