package com.aicontrolcenter.app.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.approvals.RequestCard
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.History
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind

/** What the Requests tab can ask for. */
class RequestsActions(
    val onKind: (OwnerRequestKind) -> Unit,
    val onOutcome: (RequestOutcome) -> Unit,
    val onAgent: (AgentId) -> Unit,
    val onClear: () -> Unit,
    val onOpenRequest: (RequestId) -> Unit,
    /** After a refused permission: end the wait before its agent may ask again. */
    val onLetAskAgain: (RequestId) -> Unit = {},
)

/** Every permission and approval request, newest first, with how it ended; filterable once there is something to filter. */
@Composable
fun RequestsTab(state: RequestsUiState, actions: RequestsActions) {
    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(
        modifier = Modifier.readableWidth().fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.md, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    "Every time an agent asked you for a permission or an approval, and what happened. Open one to see exactly what it " +
                        "asked for; one that is still waiting can be answered there. After you refuse a permission, its agent cannot " +
                        "ask for it again for ${OwnerRequest.REFUSAL_COOLDOWN_MS / 60_000} minutes, unless you let it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.total > 0) {
                    FilterRow("Show") {
                        OwnerRequestKind.entries.forEach { kind ->
                            ChoiceChip(
                                selected = state.filter.kind == kind,
                                onClick = { actions.onKind(kind) },
                                label = if (kind == OwnerRequestKind.PERMISSION) "Permissions" else "Approvals",
                            )
                        }
                    }
                    FilterRow("Answer") {
                        RequestOutcome.entries.forEach { outcome ->
                            ChoiceChip(selected = state.filter.outcome == outcome, onClick = { actions.onOutcome(outcome) }, label = outcome.label)
                        }
                    }
                    if (state.agents.size > 1) {
                        FilterRow("Agent") {
                            state.agents.forEach { agent ->
                                ChoiceChip(
                                    selected = state.filter.agent == agent.id,
                                    onClick = { agent.id?.let(actions.onAgent) },
                                    label = agent.name,
                                    leadingIcon = { AgentAvatar(agent.name, agent.colorIndex, size = 18.dp) },
                                    modifier = Modifier.semantics { contentDescription = "Only requests from ${agent.name}" },
                                )
                            }
                        }
                    }
                }
                if (state.total > 0) {
                    Caption(
                        if (state.filter.isActive) "${plural(state.requests.size, "request")} of ${state.total} match" else "${plural(state.total, "request")} · newest first",
                    )
                }
            }
        }
        if (state.requests.isEmpty()) {
            item(key = "empty") {
                if (state.filter.isActive) {
                    EmptyState(icon = AppIcons.History, message = "No request matches these filters.", actionLabel = "Clear filters", onAction = actions.onClear)
                } else {
                    EmptyState(
                        icon = AppIcons.History,
                        title = "No requests yet",
                        message = "When an agent needs a permission, or your approval for something hard to undo, the request and your answer are kept here.",
                    )
                }
            }
        } else if (state.filter.isActive) {
            item(key = "clear") { TextButton(onClick = actions.onClear) { Text("Clear filters") } }
        }
        items(state.requests, key = { it.id.value }) { request ->
            RequestCard(request, onOpen = { actions.onOpenRequest(request.id) }, onLetAskAgain = { actions.onLetAskAgain(request.id) })
        }
    }
}

/** One row of filters with a short caption in front ("Show", "Answer", "Agent"), so the chips read as filters. */
@Composable
private fun FilterRow(caption: String, chips: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Caption(caption, Modifier.width(56.dp).padding(top = 16.dp))
        ChipFlow(Modifier.weight(1f)) { chips() }
    }
}
