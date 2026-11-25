package com.aicontrolcenter.app.ui.approvals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Approval
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.core.RequestId

/**
 * The "Waiting for you" list: approvals, permission requests and agents' questions, oldest first. A question opens its
 * own screen through [onOpenQuestion] (rule 13), never the Approval screen; everything else opens through [onOpenRequest].
 */
@Composable
fun ApprovalsRoute(onBack: (() -> Unit)?, onOpenRequest: (RequestId) -> Unit, onOpenQuestion: (RequestId) -> Unit = onOpenRequest) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel {
        ApprovalsViewModel(graph.ownerRequests, graph.registryDirectory, graph.capabilities::info, graph.clock, notificationsEnabled = graph.notifications.enabled)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ApprovalsScreen(state, onBack, onOpenRequest, onOpenQuestion)
}

/** The screen's title, also the shell's rail item. */
const val WAITING_FOR_YOU = "Waiting for you"

/**
 * "Waiting for you · 2 requests and 1 question, oldest first": what waits, in words (requests are approvals and
 * permission requests; questions are agents' questions).
 */
fun waitingSummary(requests: Int, questions: Int): String {
    val parts = listOfNotNull(
        requests.takeIf { it > 0 }?.let { plural(it, "request") },
        questions.takeIf { it > 0 }?.let { plural(it, "question") },
    )
    return "$WAITING_FOR_YOU · " + parts.joinToString(" and ") + ", oldest first"
}

/**
 * Everything waiting for the owner, oldest first. A request opens its Approval screen; a question opens its own screen
 * ([onOpenQuestion]). [onBack] is null where the screen is a destination of its own (the rail's "Waiting for you").
 */
@Composable
fun ApprovalsScreen(state: ApprovalsUiState, onBack: (() -> Unit)?, onOpenRequest: (RequestId) -> Unit, onOpenQuestion: (RequestId) -> Unit = onOpenRequest) {
    ScreenScaffold(title = WAITING_FOR_YOU, onBack = onBack) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.requests.isEmpty() -> Column(Modifier.padding(padding)) {
                if (state.notificationsOff) ApprovalNotificationsOffBanner(Modifier.readableWidth().padding(horizontal = Spacing.screen))
                EmptyState(
                    icon = AppIcons.Approval,
                    title = "Nothing is waiting for you",
                    message = "When an agent needs a permission, your approval for something hard to undo, or an answer to a question, " +
                        "it waits here, in the chat or task that asked, and in a notification while notifications are allowed.",
                )
            }
            else -> LazyColumn(
                modifier = Modifier.padding(padding).readableWidth().fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (state.notificationsOff) item(key = "notifications") { ApprovalNotificationsOffBanner() }
                val questions = state.requests.count { it.isQuestion }
                item(key = "header") {
                    SectionHeader(waitingSummary(state.requests.size - questions, questions))
                    Text(
                        buildString {
                            append("Each agent waits until you answer. ")
                            if (questions < state.requests.size) append("A request is refused when its time runs out; open it to see exactly what it would do. ")
                            if (questions > 0) append("A question is only text for the agent: unanswered in time, the agent decides itself. ")
                            append("Answered ones are kept in Tools › History.")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(state.requests, key = { it.id.value }) { request ->
                    RequestCard(request, onOpen = { if (request.isQuestion) onOpenQuestion(request.id) else onOpenRequest(request.id) })
                }
            }
        }
    }
}
