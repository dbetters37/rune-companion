package com.aicontrolcenter.app.ui.pickup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.SwitchRow
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Folder
import com.aicontrolcenter.app.ui.icons.Import
import com.aicontrolcenter.app.ui.routines.NotInThisVersion
import com.aicontrolcenter.app.ui.routines.WarningLine
import com.aicontrolcenter.app.ui.tasks.LocalScreenWork
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.PickUpRuleId
import com.aicontrolcenter.core.SpaceId

/** What the pick-up rule editor can do. */
class PickUpRuleEditorActions(
    val onClose: () -> Unit,
    val onSelectFolder: (FolderId) -> Unit = {},
    val onSelectSpace: (SpaceId) -> Unit = {},
    val onMakeReadOnly: () -> Unit = {},
    val onOpenFolders: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onEnabled: (Boolean) -> Unit = {},
    val onDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

/**
 * A new pick-up rule ([ruleId] null; [folderId] when the owner came from a folder) or one to change (WP10c): the folder
 * and the space; the refusals (`FOLDER_NOT_READ_ONLY` with "Make it read-only", `FOLDER_OVERLAPS_WRITABLE`,
 * `FOLDER_UNREADABLE`, `FOLDER_GONE`, the Downloads-entry and other-app sentences) shown as they are.
 */
@Composable
fun PickUpRuleEditorRoute(
    ruleId: PickUpRuleId?,
    folderId: FolderId?,
    onClose: () -> Unit,
    onSaved: (PickUpRuleId) -> Unit,
    onOpenFolders: () -> Unit,
) {
    val ports = LocalAppGraph.current
    val rules = ports.ownerPickUpRules
    if (rules == null) {
        NotInThisVersion(if (ruleId == null) "New pick-up rule" else "Pick-up rule", onClose)
        return
    }
    val work = LocalScreenWork.current
    val viewModel = viewModel(key = "pickup-${ruleId?.value}-${folderId?.value}") {
        PickUpRuleEditorViewModel(ruleId, folderId, rules, ports.ownerFolders, ports.memoryService, work)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) {
        state.saved?.let {
            viewModel.savedHandled()
            onSaved(it)
        }
    }
    LaunchedEffect(state.deleted) { if (state.deleted) onClose() }
    PickUpRuleEditorScreen(
        state,
        PickUpRuleEditorActions(
            onClose = onClose,
            onSelectFolder = viewModel::selectFolder,
            onSelectSpace = viewModel::selectSpace,
            onMakeReadOnly = viewModel::makeReadOnly,
            onOpenFolders = onOpenFolders,
            onSave = viewModel::save,
            onEnabled = viewModel::setEnabled,
            onDelete = viewModel::askDelete,
            onConfirmDelete = viewModel::confirmDelete,
            onCancelDelete = viewModel::cancelDelete,
            onMessageShown = viewModel::messageShown,
        ),
    )
}

@Composable
fun PickUpRuleEditorScreen(state: PickUpRuleEditorUiState, actions: PickUpRuleEditorActions) {
    ScreenScaffold(
        title = if (state.isNew) "New pick-up rule" else "Pick-up rule",
        onBack = actions.onClose,
        message = state.message,
        onMessageShown = actions.onMessageShown,
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.missing -> EmptyState(
                icon = AppIcons.Import,
                title = "This rule is gone",
                message = "It was deleted. The memories it made are kept.",
                modifier = Modifier.padding(padding),
                actionLabel = "Back",
                onAction = actions.onClose,
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
                if (!state.isNew) OnOff(state, actions.onEnabled)
                state.problem?.let { InfoBanner(title = "Not saved", text = it, tone = StatusTone.UNAVAILABLE) }
                FolderSection(state, actions)
                SpaceSection(state, actions.onSelectSpace)
                Text(PickUpWords.WHAT_ARRIVES, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(PickUpWords.WHEN, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.isNew) {
                    Text(
                        "It is switched on when you make it, and picks up only while folder pick-up is on in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = actions.onSave, enabled = state.canSave, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (state.isSaving) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text(if (state.isNew) "Make the rule" else "Save changes")
                    }
                }
                if (!state.isNew) {
                    OutlinedButton(
                        onClick = actions.onDelete,
                        enabled = !state.isSaving,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(AppIcons.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text("Delete rule", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
    if (state.confirmDelete) {
        ConfirmDialog(
            title = "Delete this rule?",
            message = "Nothing more is picked up from this folder by it. The memories it made are kept.",
            confirmLabel = "Delete rule",
            destructive = true,
            icon = AppIcons.Delete,
            onConfirm = actions.onConfirmDelete,
            onDismiss = actions.onCancelDelete,
        )
    }
}

@Composable
private fun OnOff(state: PickUpRuleEditorUiState, onEnabled: (Boolean) -> Unit) {
    AppCard(contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm)) {
        SwitchRow(
            title = "Switched on",
            description = if (state.enabled) {
                "New files in the folder are picked up while folder pick-up is on in Settings."
            } else {
                "Off: nothing is picked up by this rule. Switching it on leaves out the files in the folder now."
            },
            checked = state.enabled,
            onCheckedChange = onEnabled,
            enabled = !state.isSaving,
        )
        state.lastResult?.let {
            Text("Last time: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.sm))
        }
    }
}

@Composable
private fun FolderSection(state: PickUpRuleEditorUiState, actions: PickUpRuleEditorActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader("From this folder")
        if (state.isNew) {
            if (state.folders.isEmpty()) {
                AppCard {
                    Text(
                        "No shared folder yet. Share one in Tools, under Folders, and make it read-only.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OpenFoldersButton(actions.onOpenFolders)
                }
            } else {
                AppCard(contentPadding = PaddingValues(vertical = Spacing.xs)) {
                    Column(Modifier.selectableGroup()) {
                        state.folders.forEachIndexed { index, folder ->
                            if (index > 0) RowDivider(Modifier.padding(horizontal = Spacing.lg))
                            FolderRow(folder, selected = folder.id == state.selectedFolder, onSelect = { actions.onSelectFolder(folder.id) })
                        }
                    }
                }
            }
        } else {
            state.folder?.let { folder ->
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Icon(AppIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(folder.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            if (folder.access.isNotEmpty()) {
                                Text(folder.access, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        // What stands between the chosen folder and a rule, with the way out.
        val problem = state.folder?.problem
        if (problem != null) {
            InfoBanner(title = "This folder cannot be used yet", text = problem, tone = StatusTone.ATTENTION)
            if (state.canMakeReadOnly) {
                Button(onClick = actions.onMakeReadOnly, enabled = !state.isSaving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Make it read-only") }
            } else {
                OpenFoldersButton(actions.onOpenFolders)
            }
        }
        Text(PickUpWords.READ_ONLY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FolderRow(folder: FolderChoiceUi, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = !folder.hasRule, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = !folder.hasRule)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(folder.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(folder.access, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // The full reason shows under the list once the folder is chosen; here a short word, with its icon.
            if (folder.hasRule) {
                Text(PickUpRuleEditorViewModel.HAS_RULE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (folder.problem != null && !selected) {
                WarningLine("Not usable yet: choose it to see why.")
            }
        }
    }
}

@Composable
private fun SpaceSection(state: PickUpRuleEditorUiState, onSelectSpace: (SpaceId) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader("Into this memory space")
        if (state.spaces.isEmpty()) {
            AppCard {
                Text(
                    "No memory space yet. Make one in Memory, under Spaces, then come back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AppCard(contentPadding = PaddingValues(vertical = Spacing.xs)) {
                Column(Modifier.selectableGroup()) {
                    state.spaces.forEachIndexed { index, space ->
                        if (index > 0) RowDivider(Modifier.padding(horizontal = Spacing.lg))
                        val selected = space.id == state.selectedSpace
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelectSpace(space.id) })
                                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(space.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        InfoBanner(text = PickUpWords.NO_MODEL)
    }
}

@Composable
private fun OpenFoldersButton(onOpenFolders: () -> Unit) {
    OutlinedButton(onClick = onOpenFolders, modifier = Modifier.padding(top = Spacing.xs).heightIn(min = 48.dp)) {
        Icon(AppIcons.Folder, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Text("Open shared folders", modifier = Modifier.padding(start = 8.dp))
    }
}
