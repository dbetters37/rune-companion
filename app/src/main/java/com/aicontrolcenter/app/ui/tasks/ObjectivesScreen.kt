package com.aicontrolcenter.app.ui.tasks

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.AvatarStack
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.StackedAvatar
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Tasks
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.TaskId

@Composable
fun ObjectivesRoute(onOpenObjective: (TaskId) -> Unit, onNewObjective: () -> Unit) {
    val graph = LocalAppGraph.current
    val work = LocalScreenWork.current
    val viewModel = viewModel {
        ObjectivesViewModel(graph.tasks, graph.registryDirectory, graph.orchestrator, graph.notifications, graph.clock, graph.zone(), computation = work)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ObjectivesScreen(state, viewModel::setFilter, onOpenObjective, onNewObjective, viewModel::showOlder)
}

/** The Tasks section: the owner's objectives, active or finished, and the way to give a new one. */
@Composable
fun ObjectivesScreen(
    state: ObjectivesUiState,
    onFilterChange: (ObjectiveFilter) -> Unit,
    onOpenObjective: (TaskId) -> Unit,
    onNewObjective: () -> Unit,
    onShowOlder: () -> Unit = {},
) {
    ScreenScaffold(
        title = "Tasks",
        floatingActionButton = {
            // The empty state has its own button; one is enough.
            if (!state.isLoading && !state.isEmpty) ExtendedFloatingActionButton(
                onClick = onNewObjective,
                icon = { Icon(AppIcons.Add, contentDescription = "New objective") },
                text = { Text("New objective") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.isEmpty -> EmptyState(
                icon = AppIcons.Tasks,
                title = "No objectives yet",
                message = "Give the Director an objective, such as researching a subject and writing up what it found. It splits " +
                    "the work into tasks, hands them to the team, has important results reviewed and writes the final document. " +
                    "You can follow, pause or stop it at any point.",
                actionLabel = "New objective",
                onAction = onNewObjective,
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.padding(padding).readableWidth().fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (state.notificationsOff) item(key = "notifications") { NotificationsOffBanner() }
                item(key = "filters") { Filters(state, onFilterChange) }
                if (state.rows.isEmpty()) {
                    item(key = "none") {
                        Text(
                            if (state.filter == ObjectiveFilter.ACTIVE) "Nothing is in progress. Finished objectives are under Finished." else "No objective has finished yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Spacing.lg),
                        )
                    }
                }
                items(state.rows, key = { it.id.value }) { row -> ObjectiveCard(row, onClick = { onOpenObjective(row.id) }) }
                if (state.hasOlder) {
                    item(key = "older") {
                        OutlinedButton(onClick = onShowOlder, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget)) {
                            Text("Show older objectives")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Filters(state: ObjectivesUiState, onFilterChange: (ObjectiveFilter) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        ObjectiveFilter.entries.forEach { filter ->
            val count = if (filter == ObjectiveFilter.ACTIVE) state.activeCount else state.finishedCount
            FilterChip(selected = state.filter == filter, onClick = { onFilterChange(filter) }, label = { Text("${filter.label} · $count") })
        }
    }
}

/** Said plainly, with the way to change it: the work goes on, only its notification is missing. */
@Composable
private fun NotificationsOffBanner() {
    val context = LocalContext.current
    Column {
        InfoBanner(
            title = "Notifications are off",
            text = "Objectives keep running, but their progress and the Pause button are not shown outside the app. " +
                "Open this screen to follow them, or allow notifications for this app.",
            tone = StatusTone.ATTENTION,
        )
        TextButton(onClick = {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }) { Text("Notification settings") }
    }
}

@Composable
internal fun ObjectiveCard(row: ObjectiveRowUi, onClick: () -> Unit) {
    AppCard(onClick = onClick, borderColor = if (row.isLive) appColors.working.copy(alpha = 0.5f) else appColors.hairline) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                StatusChip(row.status.text, row.status.tone)
                Text("Started ${row.started.sentenceCase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            ProgressLine(row)
            // Why it is not moving, when that needs the owner (paused, interrupted, failed).
            if (!row.progress.isPlanning && row.status.tone !in CALM_TONES) {
                row.statusDetail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = appColors.status(row.status.tone), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (row.agents.isNotEmpty()) {
                AvatarStack(row.agents.map { StackedAvatar(it.name, it.colorIndex) }, size = 26.dp)
            }
        }
    }
}

@Composable
private fun ProgressLine(row: ObjectiveRowUi) {
    val progress = row.progress
    if (progress.isPlanning) {
        Text(
            if (row.isLive) "The Director is planning the work." else row.statusDetail ?: "Not planned.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        ProgressBar(progress.fraction, finished = row.status.tone == StatusTone.READY, modifier = Modifier.weight(1f), muted = row.status.tone !in CALM_TONES)
        Text(
            "${progress.completed} of ${progress.planned} tasks",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A plain progress bar: green once everything is done, grey when nothing moves. Also used by the objective screen. */
@Composable
internal fun ProgressBar(fraction: Float, finished: Boolean, modifier: Modifier = Modifier, muted: Boolean = false) {
    LinearProgressIndicator(
        progress = { fraction },
        modifier = modifier.fillMaxWidth().clearAndSetSemantics {},
        color = when {
            finished -> appColors.ready
            // Not moving (paused, interrupted, stopped): the bar should not look like work in progress.
            muted -> MaterialTheme.colorScheme.outline
            else -> MaterialTheme.colorScheme.primary
        },
        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        drawStopIndicator = {},
    )
}
