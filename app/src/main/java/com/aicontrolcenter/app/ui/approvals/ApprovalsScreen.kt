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

@Composable
fun ApprovalsRoute(onBack: (() -> Unit)?, onOpenRequest: (RequestId) -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel {
        ApprovalsViewModel(graph.ownerRequests, graph.registryDirectory, graph.capabilities::info, graph.clock, notificationsEnabled = graph.notifications.enabled)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ApprovalsScreen(state, onBack, onOpenRequest)
}

/**
 * Every question waiting for the owner, oldest first. Each opens its Approval screen, where it is
 * answered. [onBack] is null where the screen is a destination of its own (the rail's Approvals).
 */
@Composable
fun ApprovalsScreen(state: ApprovalsUiState, onBack: (() -> Unit)?, onOpenRequest: (RequestId) -> Unit) {
    ScreenScaffold(title = "Approvals", onBack = onBack) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.requests.isEmpty() -> Column(Modifier.padding(padding)) {
                if (state.notificationsOff) ApprovalNotificationsOffBanner(Modifier.readableWidth().padding(horizontal = Spacing.screen))
                EmptyState(
                    icon = AppIcons.Approval,
                    title = "Nothing is waiting for you",
                    message = "When an agent needs a permission, or your approval for something hard to undo, the request waits here, " +
                        "in the chat or task that asked, and in a notification while notifications are allowed.",
                )
            }
            else -> LazyColumn(
                modifier = Modifier.padding(padding).readableWidth().fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (state.notificationsOff) item(key = "notifications") { ApprovalNotificationsOffBanner() }
                item(key = "header") {
                    SectionHeader("Waiting for you · " + plural(state.requests.size, "request") + ", oldest first")
                    Text(
                        "Each agent waits until you answer, or is refused when the time runs out. Open a request to see exactly what it would do. " +
                            "Answered requests are kept in Tools › History.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(state.requests, key = { it.id.value }) { request ->
                    RequestCard(request, onOpen = { onOpenRequest(request.id) })
                }
            }
        }
    }
}
