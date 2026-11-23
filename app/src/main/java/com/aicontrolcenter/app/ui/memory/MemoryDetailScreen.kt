package com.aicontrolcenter.app.ui.memory

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.MemoryBrain
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.MemoryLinkId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.memory.LinkRelation

/** What the memory screen can ask for. */
class MemoryDetailActions(
    val onBack: () -> Unit,
    val onEdit: () -> Unit,
    val onOpenMemory: (MemoryId) -> Unit,
    val onOpenAgent: (AgentId) -> Unit,
    val onOpenConversation: (ConversationId) -> Unit,
    val onShare: (SpaceId) -> Unit,
    val onLink: (MemoryId, LinkRelation) -> Unit,
    val onUnlink: (MemoryLinkId) -> Unit,
    val onDelete: () -> Unit,
    val onMessageShown: () -> Unit,
)

@Composable
fun MemoryDetailRoute(
    memoryId: MemoryId,
    onBack: () -> Unit,
    onEdit: (MemoryId) -> Unit,
    onOpenMemory: (MemoryId) -> Unit,
    onOpenAgent: (AgentId) -> Unit,
    onOpenConversation: (ConversationId) -> Unit,
) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel {
        MemoryDetailViewModel(memoryId, graph.memoryService, graph.registryDirectory, graph.ports.conversations)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    MemoryDetailScreen(
        state = state,
        actions = MemoryDetailActions(
            onBack = onBack,
            onEdit = { onEdit(memoryId) },
            onOpenMemory = onOpenMemory,
            onOpenAgent = onOpenAgent,
            onOpenConversation = onOpenConversation,
            onShare = viewModel::share,
            onLink = viewModel::link,
            onUnlink = viewModel::unlink,
            onDelete = viewModel::delete,
            onMessageShown = viewModel::messageShown,
        ),
    )
}

@Composable
fun MemoryDetailScreen(state: MemoryDetailUiState, actions: MemoryDetailActions) {
    val detail = state.memory
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var choosingSpace by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(
        title = "Memory",
        onBack = actions.onBack,
        actions = {
            if (detail != null) IconButton(onClick = actions.onEdit) { Icon(AppIcons.Edit, contentDescription = "Edit memory") }
        },
        message = state.message,
        onMessageShown = actions.onMessageShown,
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            detail == null -> EmptyState(
                icon = AppIcons.MemoryBrain,
                message = "This memory no longer exists. It was deleted, or it was short-term memory that has expired.",
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
                MemoryHeader(detail, state.isBusy, actions, onShare = { choosingSpace = true })
                ContentSection(detail)
                ProvenanceSection(detail.provenance, actions)
                LinksSection(detail, state.linkCandidates, state.isBusy, actions)
                UseSection(detail)
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
                            Text("Delete memory", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete && detail != null) {
        ConfirmDialog(
            title = "Delete this memory?",
            message = "\"${detail.record.title}\" and its links are removed from this phone. Copies already shared into " +
                "spaces stay where they are. This cannot be undone.",
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
    if (choosingSpace && detail != null) {
        ShareDialog(
            targets = state.shareTargets,
            onShare = { space ->
                choosingSpace = false
                actions.onShare(space)
            },
            onDismiss = { choosingSpace = false },
        )
    }
}

/**
 * Picks the space to share into. Sharing makes a copy: the original stays where it is, and the
 * copy is readable by every agent allowed to read that space.
 */
@Composable
private fun ShareDialog(targets: List<SpaceOptionUi>, onShare: (SpaceId) -> Unit, onDismiss: () -> Unit) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Share, contentDescription = null) },
        title = { Text("Share into a space") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    if (targets.isEmpty()) {
                        "There is no other shared space yet. Create one in Memory, Spaces, then share this memory into it."
                    } else {
                        "A copy goes into the space, linked to this memory. Every agent allowed to read the space can then recall it. " +
                            "This memory stays where it is."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                targets.forEach { target ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = chosen == target.id.value, role = Role.RadioButton, onClick = { chosen = target.id.value }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosen == target.id.value, onClick = null)
                        Text(target.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Spacing.md))
                    }
                }
            }
        },
        confirmButton = {
            if (targets.isNotEmpty()) {
                TextButton(onClick = { chosen?.let { onShare(SpaceId(it)) } }, enabled = chosen != null) { Text("Share") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (targets.isEmpty()) "OK" else "Cancel") } },
    )
}

/** A whole row that opens something, with a trailing chevron drawn by the caller. */
internal fun Modifier.opens(label: String, onClick: () -> Unit): Modifier = clickable(onClickLabel = label, onClick = onClick)
