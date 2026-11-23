package com.aicontrolcenter.app.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.ObscuredTouchGuard
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.SwitchRow
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Folder
import com.aicontrolcenter.app.ui.icons.Storage
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.FolderId

/** What the Folders tab can ask for. */
class FoldersActions(
    /** Opens Android's folder picker; its answer goes to the view model. */
    val onAddFolder: () -> Unit,
    val onRemove: (FolderId) -> Unit,
    val onSetReadOnly: (FolderId, Boolean) -> Unit,
    val onSetAccess: (FolderId, AgentId, AccessLevel) -> Unit,
)

/**
 * The folders shared with agents, each with its read-only switch and who may use it. Touches are
 * refused while another app's window covers the tab, since its chips give agents access.
 */
@Composable
fun FoldersTab(state: FoldersUiState, actions: FoldersActions) {
    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    ObscuredTouchGuard()
    var confirmRemove by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = Modifier.readableWidth().fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.md, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "intro") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    "Agents reach the phone's storage only through folders you pick here, with Android's folder picker, and only " +
                        "the agents you choose for each. Android keeps this app's access to a folder until you remove it. Nothing " +
                        "else on the phone's storage, and no other app's private files, can be reached.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = actions.onAddFolder, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.padding(end = Spacing.sm))
                    Text("Add a folder")
                }
            }
        }
        if (state.folders.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = AppIcons.Storage,
                    title = "No folders shared",
                    message = "Pick a folder, then choose which agents may read it or change it. Deleting anything in it always asks you first.",
                )
            }
        }
        items(state.folders, key = { it.id.value }) { folder ->
            FolderCard(folder, state.isBusy, actions, onRemove = { confirmRemove = folder.id.value })
        }
    }
    val removing = state.folders.firstOrNull { it.id.value == confirmRemove }
    if (removing != null) {
        ConfirmDialog(
            title = "Remove “${removing.name}”?",
            message = "Agents can no longer reach it: every permission for it is removed first" +
                (if (removing.agentsWithAccess > 0) " (${plural(removing.agentsWithAccess, "agent")} lose access)" else "") +
                ", then this app gives Android's access back. The folder and its files stay on the phone, untouched.",
            confirmLabel = "Remove",
            destructive = true,
            guarded = true,
            icon = AppIcons.Folder,
            onConfirm = {
                confirmRemove = null
                actions.onRemove(removing.id)
            },
            onDismiss = { confirmRemove = null },
        )
    }
}

@Composable
private fun FolderCard(folder: FolderUi, isBusy: Boolean, actions: FoldersActions, onRemove: () -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(AppIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(folder.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Caption(folder.added + " · " + if (folder.agentsWithAccess == 0) "no agent may use it" else "${plural(folder.agentsWithAccess, "agent")} may use it")
            }
            TextButton(
                onClick = onRemove,
                enabled = !isBusy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.semantics { contentDescription = "Remove ${folder.name}" },
            ) { Text("Remove") }
        }
        SwitchRow(
            title = "Read-only",
            description = "No agent can create, change, rename or delete anything in it, whatever is allowed below.",
            checked = folder.readOnly,
            onCheckedChange = { actions.onSetReadOnly(folder.id, it) },
            enabled = !isBusy,
        )
        RowDivider(Modifier.padding(vertical = Spacing.xs))
        SectionHeader("Who may use it")
        folder.agents.forEach { access ->
            AgentAccessRow(
                access = access,
                labels = FoldersViewModel.KIND.labels,
                tools = "folder tools",
                enabled = !isBusy,
                onChange = { level -> access.agent.id?.let { actions.onSetAccess(folder.id, it, level) } },
            )
        }
    }
}
