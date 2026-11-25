package com.aicontrolcenter.app.ui.tasks

import com.aicontrolcenter.app.Phase6Stub
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Stop
import com.aicontrolcenter.app.ui.icons.Tasks
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.approvals.WaitingForYou
import com.aicontrolcenter.app.ui.approvals.icon
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId

class TaskDetailActions(
    val onBack: () -> Unit,
    val onOpenTask: (TaskId) -> Unit,
    val onOpenObjective: (TaskId) -> Unit,
    val onRetry: () -> Unit,
    val onCancel: () -> Unit,
    val onMessageShown: () -> Unit,
    /** Opens the Approval screen of a request the task waits on. */
    val onOpenRequest: (RequestId) -> Unit = {},
)

/** Wave 3 (WP11a): [onOpenQuestion] opens a question the task waits on (rule 13). */
@Phase6Stub("WP11a")
@Composable
fun TaskDetailRoute(
    taskId: TaskId,
    onBack: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    onOpenObjective: (TaskId) -> Unit,
    onOpenRequest: (RequestId) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onOpenQuestion: (RequestId) -> Unit = {},
) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel(key = "task-${taskId.value}") {
        TaskDetailViewModel(
            taskId, graph.tasks, graph.messages, graph.registryDirectory, graph.agentChat, graph.toolRegistry, graph.orchestrator,
            graph.activityMonitor, graph.clock, requests = graph.ownerRequests.pending, capabilityInfo = graph.capabilities::info,
            awaitStartup = graph::awaitStartup,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TaskDetailScreen(
        state,
        TaskDetailActions(onBack, onOpenTask, onOpenObjective, viewModel::retry, viewModel::cancel, viewModel::messageShown, onOpenRequest),
    )
}

/** One task: what was asked, who does it, how it went, its reviews, messages and the agent's transcript. */
@Composable
fun TaskDetailScreen(state: TaskDetailUiState, actions: TaskDetailActions) {
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    val task = state.task
    ScreenScaffold(title = task?.kind?.label ?: "Task", onBack = actions.onBack, message = state.message, onMessageShown = actions.onMessageShown) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            task == null -> EmptyState(
                icon = AppIcons.Tasks,
                message = "This task no longer exists. Its objective may have been deleted.",
                actionLabel = "Go back",
                onAction = actions.onBack,
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.padding(padding).readableWidth().fillMaxSize(),
                contentPadding = PaddingValues(bottom = Spacing.xl),
            ) {
                item(key = "header") {
                    Pad {
                        TaskHeader(
                            task, state.isBusy, actions.onOpenObjective, actions.onRetry, onCancel = { confirmCancel = true },
                            statusIcon = state.waitingFor.firstOrNull()?.kind?.icon,
                        )
                    }
                }
                if (state.waitingFor.isNotEmpty()) {
                    item(key = "waiting") { Pad { WaitingForYou(state.waitingFor, actions.onOpenRequest) } }
                }
                item(key = "instructions") { Pad { TaskInstructions(task) } }
                if (task.parent != null || task.dependsOn.isNotEmpty() || task.children.isNotEmpty()) {
                    item(key = "links") { Pad { TaskLinks(task, actions.onOpenTask) } }
                }
                item(key = "result") { Pad { TaskResult(task) } }
                if (task.reviews.isNotEmpty()) item(key = "reviews") { Pad { TaskReviews(task.reviews) } }
                if (state.messages.isNotEmpty()) {
                    item(key = "messages-header") { Pad { SectionHeader("Messages about this task · ${state.messages.size}") } }
                    items(state.messages, key = { "message-${it.id.value}" }) { message ->
                        Column(Modifier.padding(horizontal = Spacing.screen)) {
                            MessageRow(message, showTask = false)
                            RowDivider()
                        }
                    }
                }
                transcriptSection(state, task)
            }
        }
    }
    if (confirmCancel && task != null) {
        ConfirmDialog(
            title = "Cancel this task?",
            message = "${task.assignee?.name ?: "The agent"} stops working on “${task.title}”, and the work it delegated stops too. " +
                "The Director continues the objective without it. You can run it again later.",
            confirmLabel = "Cancel task",
            dismissLabel = "Keep working",
            destructive = true,
            icon = AppIcons.Stop,
            onConfirm = {
                confirmCancel = false
                actions.onCancel()
            },
            onDismiss = { confirmCancel = false },
        )
    }
}

private fun LazyListScope.transcriptSection(state: TaskDetailUiState, task: TaskDetailUi) {
    item(key = "transcript-header") {
        Pad {
            Column {
                SectionHeader("Transcript")
                Text(
                    if (state.transcript.isEmpty()) {
                        "Nothing yet: the transcript of ${task.assignee?.name ?: "the agent"}'s work appears here once it starts."
                    } else {
                        "${task.assignee?.name ?: "The agent"}'s work on this task, as stored on this phone. Read-only."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    taskTranscript(state.transcript, task.assignee)
}

@Composable
private fun Pad(content: @Composable () -> Unit) {
    Box(Modifier.padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.md)) { content() }
}
