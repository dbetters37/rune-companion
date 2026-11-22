package com.aicontrolcenter.app.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.NeutralChip
import com.aicontrolcenter.app.ui.components.NoticeDialog
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.SwitchRow
import com.aicontrolcenter.app.ui.components.TintedChip
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.Agent
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Copy
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.agents.label

/** What the detail screen can ask for. */
class AgentDetailActions(
    val onBack: () -> Unit,
    val onEdit: () -> Unit,
    val onDuplicate: () -> Unit,
    val onSetEnabled: (Boolean) -> Unit,
    val onDesignateAsDirector: () -> Unit,
    val onDelete: () -> Unit,
    val onOpenModels: () -> Unit,
    val onMessageShown: () -> Unit,
)

@Composable
fun AgentDetailRoute(
    agentId: AgentId,
    onBack: () -> Unit,
    onEdit: (AgentId) -> Unit,
    onOpenAgent: (AgentId) -> Unit,
    onOpenModels: () -> Unit,
) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel { AgentDetailViewModel(agentId, graph.registryDirectory, graph.agentManager) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    LaunchedEffect(state.duplicateId) {
        state.duplicateId?.let { copy ->
            viewModel.duplicateHandled()
            onOpenAgent(copy)
        }
    }
    AgentDetailScreen(
        state = state,
        actions = AgentDetailActions(
            onBack = onBack,
            onEdit = { onEdit(agentId) },
            onDuplicate = viewModel::duplicate,
            onSetEnabled = viewModel::setEnabled,
            onDesignateAsDirector = viewModel::designateAsDirector,
            onDelete = viewModel::delete,
            onOpenModels = onOpenModels,
            onMessageShown = viewModel::messageShown,
        ),
    )
}

@Composable
fun AgentDetailScreen(state: AgentDetailUiState, actions: AgentDetailActions) {
    val agent = state.agent
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var explainDeleteBlocked by rememberSaveable { mutableStateOf(false) }

    ScreenScaffold(
        title = agent?.profile?.name ?: "Agent",
        onBack = actions.onBack,
        actions = {
            if (agent != null) {
                IconButton(onClick = actions.onEdit) { Icon(AppIcons.Edit, contentDescription = "Edit agent") }
            }
        },
        message = state.message,
        onMessageShown = actions.onMessageShown,
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            agent == null -> EmptyState(
                icon = AppIcons.Agent,
                message = "This agent is no longer in the registry.",
                actionLabel = "Go back",
                onAction = actions.onBack,
                modifier = Modifier.padding(padding),
            )
            else -> Column(
                modifier = Modifier
                    .padding(padding)
                    .readableWidth()
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                AgentHeader(agent, state.isBusy, actions)
                IdentitySection(agent)
                InstructionsSection(agent)
                ResponsibilitiesSection(agent)
                ModelSection(agent, actions.onOpenModels)
                PolicySections(agent)
                TrackRecordSection(agent)
                ManageSection(
                    agent = agent,
                    isBusy = state.isBusy,
                    onDesignate = actions.onDesignateAsDirector,
                    onDelete = { if (agent.deleteBlockedReason != null) explainDeleteBlocked = true else confirmDelete = true },
                )
            }
        }
    }

    if (confirmDelete && agent != null) {
        ConfirmDialog(
            title = "Delete ${agent.profile.name}?",
            message = "The agent and its track record are removed from this device. This cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            icon = AppIcons.Delete,
            onConfirm = {
                confirmDelete = false
                actions.onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
    if (explainDeleteBlocked && agent != null) {
        NoticeDialog(
            title = "The Director cannot be deleted",
            message = agent.deleteBlockedReason ?: "",
            icon = AppIcons.Star,
            onDismiss = { explainDeleteBlocked = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentHeader(agent: AgentDetailUi, isBusy: Boolean, actions: AgentDetailActions) {
    val profile = agent.profile
    AppCard {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            AgentAvatar(
                name = profile.name,
                colorIndex = profile.colorIndex,
                size = 64.dp,
                status = agent.status.tone,
                dimmed = agent.status.tone == StatusTone.DISABLED,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    NeutralChip(profile.kind.label)
                    if (agent.isDesignatedDirector) {
                        TintedChip("Designated Director", MaterialTheme.colorScheme.primary, icon = AppIcons.Star)
                    }
                    StatusChip(agent.status.text, agent.status.tone)
                }
            }
        }
        agent.status.detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.md),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Button(onClick = actions.onEdit, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Icon(AppIcons.Edit, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Edit", modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(
                onClick = actions.onDuplicate,
                enabled = !isBusy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Icon(AppIcons.Copy, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Duplicate", modifier = Modifier.padding(start = 8.dp))
            }
        }
        RowDivider(Modifier.padding(top = Spacing.lg))
        SwitchRow(
            title = "Enabled",
            description = if (profile.enabled) "This agent can be given work." else "Disabled agents are never given work.",
            checked = profile.enabled,
            onCheckedChange = actions.onSetEnabled,
            enabled = !isBusy,
        )
    }
}
