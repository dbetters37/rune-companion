@file:Phase6Stub("WP10a")

package com.aicontrolcenter.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.shell.StubScreen

/** The Daily limits screen (WP10a): the three limits as fields, today's use, and what happens when one is reached. */
@Composable
fun DailyLimitsRoute(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    @Suppress("UNUSED_VARIABLE")
    val viewModel = viewModel { DailyLimitsViewModel(graph.settings, graph.todayUsage) }
    StubScreen("Daily limits", onBack)
}
