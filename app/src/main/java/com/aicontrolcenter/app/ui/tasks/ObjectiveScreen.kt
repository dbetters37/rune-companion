package com.aicontrolcenter.app.ui.tasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.ActivityEventRow
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.Activity
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Forum
import com.aicontrolcenter.app.ui.icons.Stop
import com.aicontrolcenter.app.ui.icons.Tasks
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.approvals.WaitingForYou
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId

class ObjectiveActions(
    val onBack: () -> Unit,
    val onOpenTask: (TaskId) -> Unit,
    val onTabChange: (ObjectiveTab) -> Unit,
    val onFilterChange: (MessageFilter) -> Unit,
    val controls: ObjectiveControls,
    val onDocumentCopied: () -> Unit,
    val onMessageShown: () -> Unit,
    /** Opens the Approval screen of a request one of its tasks waits on. */
    val onOpenRequest: (RequestId) -> Unit = {},
)

@Composable
fun ObjectiveRoute(objectiveId: TaskId, onBack: () -> Unit, onOpenTask: (TaskId) -> Unit, onOpenRequest: (RequestId) -> Unit = {}) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel(key = "objective-${objectiveId.value}") {
        ObjectiveViewModel(
            objectiveId, graph.tasks, graph.messages, graph.registryDirectory, graph.orchestrator, graph.activityLog, graph.settings, graph.clock,
            requests = graph.ownerRequests.pending, capabilityInfo = graph.capabilities::info, awaitStartup = graph::awaitStartup,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    ObjectiveScreen(
        state,
        ObjectiveActions(
            onBack = onBack,
            onOpenTask = onOpenTask,
            onTabChange = viewModel::setTab,
            onFilterChange = viewModel::setMessageFilter,
            controls = ObjectiveControls(viewModel::pause, viewModel::resume, viewModel::cancel, viewModel::delete, viewModel::runAgain),
            onDocumentCopied = viewModel::documentCopied,
            onMessageShown = viewModel::messageShown,
            onOpenRequest = onOpenRequest,
        ),
    )
}

/**
 * One objective, live: the header with its controls, then four tabs: the plan, the agents'
 * conversation, the execution log, and the final result.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ObjectiveScreen(state: ObjectiveUiState, actions: ObjectiveActions) {
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val header = state.header
    ScreenScaffold(title = "Objective", onBack = actions.onBack, message = state.message, onMessageShown = actions.onMessageShown) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            header == null -> EmptyState(
                icon = AppIcons.Tasks,
                message = "This objective no longer exists.",
                actionLabel = "Go back",
                onAction = actions.onBack,
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.padding(padding).readableWidth().fillMaxSize(),
                contentPadding = PaddingValues(bottom = Spacing.xl),
            ) {
                item(key = "header") {
                    Box(Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs)) {
                        val controls = actions.controls
                        ObjectiveHeader(
                            header, state.egress, state.isBusy,
                            ObjectiveControls(
                                onPause = controls.onPause,
                                onResume = controls.onResume,
                                onCancel = { confirmCancel = true },
                                onDelete = { confirmDelete = true },
                                onRunAgain = controls.onRunAgain,
                            ),
                        )
                    }
                }
                if (state.waitingFor.isNotEmpty()) {
                    item(key = "waiting") {
                        Box(Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs)) { WaitingForYou(state.waitingFor, actions.onOpenRequest) }
                    }
                }
                state.limits?.reached?.takeIf { it.isNotEmpty() }?.let { reached ->
                    item(key = "limits-reached") { Box(Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs)) { LimitsReachedBanner(reached) } }
                }
                stickyHeader(key = "tabs") { Tabs(state, actions.onTabChange) }
                when (state.tab) {
                    ObjectiveTab.PLAN -> planTab(state, actions)
                    ObjectiveTab.CONVERSATION -> conversationTab(state, actions)
                    ObjectiveTab.LOG -> logTab(state)
                    ObjectiveTab.RESULT -> item(key = "document") {
                        Box(Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.md)) {
                            DocumentCard(header.title, state.document, state.documentNote, actions.onDocumentCopied)
                        }
                    }
                }
            }
        }
    }
    if (confirmCancel && header != null) {
        ConfirmDialog(
            title = "Cancel this objective?",
            message = "Every unfinished task stops now and the objective cannot be resumed. What the team has produced so far is kept, " +
                "and finished tasks can still be read. To stop for a while instead, use Pause.",
            confirmLabel = "Cancel objective",
            dismissLabel = "Keep working",
            destructive = true,
            icon = AppIcons.Stop,
            onConfirm = {
                confirmCancel = false
                actions.controls.onCancel()
            },
            onDismiss = { confirmCancel = false },
        )
    }
    if (confirmDelete && header != null) {
        ConfirmDialog(
            title = "Delete this objective?",
            message = "Its tasks, results, the agents' messages and the task transcripts are removed from this phone. Memories saved " +
                "from it stay in memory. This cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            icon = AppIcons.Delete,
            onConfirm = {
                confirmDelete = false
                actions.controls.onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun Tabs(state: ObjectiveUiState, onTabChange: (ObjectiveTab) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        PrimaryScrollableTabRow(
            selectedTabIndex = state.tab.ordinal,
            edgePadding = Spacing.sm,
            containerColor = MaterialTheme.colorScheme.background,
            divider = { RowDivider() },
        ) {
            ObjectiveTab.entries.forEach { tab ->
                // Only the conversation is counted: the plan's rows include reviews and delegated work, which would not match "n tasks".
                val count = if (tab == ObjectiveTab.CONVERSATION) state.messages.size.takeIf { it > 0 } else null
                Tab(
                    selected = state.tab == tab,
                    onClick = { onTabChange(tab) },
                    text = { Text(if (count != null) "${tab.label} · $count" else tab.label, maxLines = 1) },
                )
            }
        }
    }
}

private fun LazyListScope.planTab(state: ObjectiveUiState, actions: ObjectiveActions) {
    if (state.plan.isEmpty()) {
        item(key = "plan-empty") {
            EmptyState(
                icon = AppIcons.Tasks,
                title = if (state.header?.isLive == true) "Planning" else "No plan",
                message = if (state.header?.isLive == true) {
                    "The Director is reading the objective and splitting it into tasks. They appear here as soon as it has decided."
                } else {
                    "The Director did not plan this objective. " + (state.header?.error ?: state.header?.statusDetail ?: "")
                },
            )
        }
    } else {
        items(state.plan, key = { "plan-${it.id.value}" }) { row ->
            Box(Modifier.padding(start = Spacing.screen, end = Spacing.screen, top = if (row.depth == 0) Spacing.md else Spacing.xs)) {
                PlanRow(row, onClick = { actions.onOpenTask(row.id) })
            }
        }
    }
    state.limits?.let { limits ->
        item(key = "limits") {
            Box(Modifier.padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.lg)) {
                Column {
                    SectionHeader("Limits")
                    LimitsCard(limits)
                }
            }
        }
    }
}

private fun LazyListScope.conversationTab(state: ObjectiveUiState, actions: ObjectiveActions) {
    if (state.messages.isEmpty()) {
        item(key = "messages-empty") {
            EmptyState(icon = AppIcons.Forum, message = "No messages yet. Assignments, findings, reviews and results between agents appear here as they are sent.")
        }
        return
    }
    item(key = "filters") {
        MessageFilters(state.filterCounts, state.messageFilter, actions.onFilterChange, Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs))
    }
    val shown = state.shownMessages
    items(shown, key = { "message-${it.id.value}" }) { message ->
        Column(Modifier.padding(horizontal = Spacing.screen)) {
            MessageRow(message)
            if (message !== shown.last()) RowDivider()
        }
    }
}

private fun LazyListScope.logTab(state: ObjectiveUiState) {
    if (state.log.isEmpty()) {
        item(key = "log-empty") { EmptyState(icon = AppIcons.Activity, message = "Nothing has been logged for this objective yet.") }
        return
    }
    item(key = "log-note") {
        Text(
            "Everything the team did for this objective, newest first: tasks, messages, model calls and tool calls. " +
                "Entries marked as leaving the device say where the data went.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.md),
        )
    }
    items(state.log, key = { "log-${it.id}" }) { row ->
        // As in the Activity section: the row has its own padding, the divider starts after the glyph.
        ActivityEventRow(row)
        RowDivider(Modifier.padding(start = 64.dp))
    }
}
