package com.aicontrolcenter.app.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.AppTextField
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Groups
import com.aicontrolcenter.app.ui.icons.MemoryBrain
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.SpaceId

/** What the space screen can ask for. */
class SpaceDetailActions(
    val onBack: () -> Unit,
    val onOpenMemories: () -> Unit,
    val onSetCanRead: (AgentId, Boolean) -> Unit,
    val onSetCanWrite: (AgentId, Boolean) -> Unit,
    val onRename: (name: String, description: String) -> Unit,
    val onRenameHandled: () -> Unit,
    val onDelete: () -> Unit,
    val onMessageShown: () -> Unit,
)

@Composable
fun SpaceDetailRoute(spaceId: SpaceId, onBack: () -> Unit, onOpenMemories: (SpaceId) -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel {
        SpaceDetailViewModel(spaceId, graph.memoryService, graph.registryDirectory, graph.agentManager, graph.clock)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    SpaceDetailScreen(
        state = state,
        actions = SpaceDetailActions(
            onBack = onBack,
            onOpenMemories = { onOpenMemories(spaceId) },
            onSetCanRead = viewModel::setCanRead,
            onSetCanWrite = viewModel::setCanWrite,
            onRename = viewModel::rename,
            onRenameHandled = viewModel::renameHandled,
            onDelete = viewModel::delete,
            onMessageShown = viewModel::messageShown,
        ),
    )
}

@Composable
fun SpaceDetailScreen(state: SpaceDetailUiState, actions: SpaceDetailActions) {
    val detail = state.space
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.renamed) {
        if (state.renamed) {
            editing = false
            actions.onRenameHandled()
        }
    }
    ScreenScaffold(
        title = detail?.space?.name ?: "Shared space",
        onBack = actions.onBack,
        message = state.message,
        onMessageShown = actions.onMessageShown,
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            detail == null -> EmptyState(
                icon = AppIcons.Groups,
                message = "This space no longer exists.",
                actionLabel = "Go back",
                onAction = actions.onBack,
                modifier = Modifier.padding(padding),
            )
            else -> Column(
                modifier = Modifier
                    .padding(padding)
                    .imePadding()
                    .readableWidth()
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (editing) {
                    RenameForm(detail, state, actions, onCancel = {
                        editing = false
                        actions.onRenameHandled()
                    })
                } else {
                    SpaceHeader(detail, actions, onRename = { editing = true })
                }
                AccessSection(detail, actions)
                Column {
                    SectionHeader("Manage")
                    AppCard {
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            enabled = !state.isBusy,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Icon(AppIcons.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Text("Delete space", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete && detail != null) {
        val agents = detail.access.count { it.canRead || it.canWrite }
        ConfirmDialog(
            title = "Delete ${detail.space.name}?",
            message = "The space and ${if (detail.memoryCount == 1) "the 1 memory" else "all ${detail.memoryCount} memories"} in it " +
                "are deleted from this phone" +
                (if (agents > 0) ", and ${plural(agents, "agent")} lose access to it" else "") +
                ". Memories agents keep privately are not touched. This cannot be undone.",
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
}

@Composable
private fun SpaceHeader(detail: SpaceDetailUi, actions: SpaceDetailActions, onRename: () -> Unit) {
    val accent = appColors.agentAccent(detail.colorIndex)
    AppCard {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).background(accent.copy(alpha = 0.16f), MaterialTheme.shapes.large),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Groups, contentDescription = null, tint = accent, modifier = Modifier.size(30.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(detail.space.name, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${plural(detail.memoryCount, "memory", "memories")} · ${detail.readerCount} can read · ${detail.writerCount} can write",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            detail.space.description.ifBlank { "No description. Rename to add one, so you remember what belongs here." },
            style = MaterialTheme.typography.bodyMedium,
            color = if (detail.space.description.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = Spacing.md),
        )
        Row(Modifier.fillMaxWidth().padding(top = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Button(onClick = actions.onOpenMemories, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Icon(AppIcons.MemoryBrain, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Its memories", modifier = Modifier.padding(start = 8.dp), maxLines = 1)
            }
            OutlinedButton(onClick = onRename, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Icon(AppIcons.Edit, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Rename", modifier = Modifier.padding(start = 8.dp), maxLines = 1)
            }
        }
    }
}

@Composable
private fun RenameForm(detail: SpaceDetailUi, state: SpaceDetailUiState, actions: SpaceDetailActions, onCancel: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(detail.space.name) }
    var description by rememberSaveable { mutableStateOf(detail.space.description) }
    AppCard(borderColor = appColors.hairlineStrong) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("Rename space", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            AppTextField(value = name, onValueChange = { name = it }, label = "Space name", errorText = state.renameError)
            AppTextField(value = description, onValueChange = { description = it }, label = "What belongs in it", singleLine = false, maxLines = 4)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(onClick = { actions.onRename(name, description) }, enabled = name.isNotBlank() && !state.isBusy) { Text("Save") }
            }
        }
    }
}

@Composable
private fun AccessSection(detail: SpaceDetailUi, actions: SpaceDetailActions) {
    Column {
        SectionHeader("Who may use it")
        AppCard {
            Text(
                "Read lets an agent recall what is in this space. Write lets it add memories here. Each is allowed separately; " +
                    "an agent you allow nothing never sees this space.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.sm),
            )
            if (detail.access.isEmpty()) {
                Text("There are no agents yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            detail.access.forEachIndexed { index, agent ->
                if (index > 0) RowDivider()
                AgentAccessRow(agent, actions)
            }
        }
    }
}

@Composable
private fun AgentAccessRow(agent: AgentAccessUi, actions: SpaceDetailActions) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        AgentAvatar(agent.name, agent.colorIndex, size = 36.dp, dimmed = !agent.enabled)
        Column(Modifier.weight(1f)) {
            Text(agent.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (agent.enabled) agent.role else "${agent.role}, disabled",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        AccessChip("Read", agent.canRead, "${agent.name} may read") { actions.onSetCanRead(agent.agentId, !agent.canRead) }
        AccessChip("Write", agent.canWrite, "${agent.name} may write") { actions.onSetCanWrite(agent.agentId, !agent.canWrite) }
    }
}

@Composable
private fun AccessChip(label: String, selected: Boolean, description: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        modifier = Modifier.semantics { contentDescription = description },
    )
}
