package com.aicontrolcenter.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.Agent
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.ChevronRight
import com.aicontrolcenter.app.ui.icons.Forum
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId

@Composable
fun ConversationsRoute(agentId: AgentId, onBack: () -> Unit, onOpenConversation: (ConversationId) -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel { ConversationsViewModel(agentId, graph.registryDirectory, graph.agentChat, graph.clock) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.openConversation) {
        state.openConversation?.let {
            viewModel.openHandled()
            onOpenConversation(it)
        }
    }
    ConversationsScreen(
        state = state,
        onBack = onBack,
        onNewConversation = viewModel::newConversation,
        onOpenConversation = onOpenConversation,
        onMessageShown = viewModel::messageShown,
    )
}

@Composable
fun ConversationsScreen(
    state: ConversationsUiState,
    onBack: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenConversation: (ConversationId) -> Unit,
    onMessageShown: () -> Unit,
) {
    ScreenScaffold(
        title = if (state.agentName.isBlank()) "Conversations" else "Chat with ${state.agentName}",
        onBack = onBack,
        floatingActionButton = {
            if (!state.isLoading && !state.notFound && state.conversations.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onNewConversation,
                    icon = { Icon(AppIcons.Add, contentDescription = "New conversation") },
                    text = { Text("New conversation") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
        message = state.message,
        onMessageShown = onMessageShown,
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.notFound -> EmptyState(
                icon = AppIcons.Agent,
                message = "This agent is no longer in the registry.",
                actionLabel = "Go back",
                onAction = onBack,
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.padding(padding).readableWidth().fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item(key = "agent") { AgentSummary(state) }
                if (state.conversations.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            icon = AppIcons.Forum,
                            title = "No conversations yet",
                            message = "Start one to talk to ${state.agentName} directly. Conversations are kept on this phone.",
                            actionLabel = "New conversation",
                            onAction = onNewConversation,
                        )
                    }
                } else {
                    item(key = "header") { SectionHeader("Conversations · ${state.conversations.size}") }
                    items(state.conversations, key = { it.id.value }) { row ->
                        ConversationCard(row, onClick = { onOpenConversation(row.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentSummary(state: ConversationsUiState) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            val working = state.conversations.firstNotNullOfOrNull { it.livePhase }
            AgentAvatar(state.agentName, state.agentColorIndex, size = 44.dp, status = if (working != null) StatusTone.WORKING else state.status?.tone)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.agentName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                if (working != null) {
                    StatusChip("Working: $working", StatusTone.WORKING)
                } else {
                    state.status?.let { StatusChip(it.text, it.tone) }
                }
            }
        }
        state.statusDetail?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.sm))
        }
        state.destination?.let {
            Box(Modifier.padding(top = Spacing.md)) { DestinationLine(it) }
        }
    }
}

@Composable
private fun ConversationCard(row: ConversationRowUi, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(AppIcons.Forum, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Last activity ${row.lastActivity}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                row.livePhase?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = appColors.working)
                        Text(it, style = MaterialTheme.typography.labelMedium, color = appColors.status(StatusTone.WORKING))
                    }
                }
            }
            Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
