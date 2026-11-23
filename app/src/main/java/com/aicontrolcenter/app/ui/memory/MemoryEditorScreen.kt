package com.aicontrolcenter.app.ui.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.AppTextField
import com.aicontrolcenter.app.ui.components.ChipEditor
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.SwitchRow
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.MemoryBrain
import com.aicontrolcenter.app.ui.theme.OverlineTextStyle
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryScope

/** What the memory editor can ask for. */
class MemoryEditorActions(
    val onScope: (MemoryScope) -> Unit,
    val onTitle: (String) -> Unit,
    val onContent: (String) -> Unit,
    val onKind: (MemoryKind) -> Unit,
    val onImportance: (Double) -> Unit,
    val onAddTag: (String) -> Unit,
    val onRemoveTag: (String) -> Unit,
    val onShortTerm: (Boolean) -> Unit,
    val onSave: () -> Unit,
    val onClose: () -> Unit,
    val onMessageShown: () -> Unit,
)

@Composable
fun MemoryEditorRoute(
    memoryId: MemoryId?,
    initialScope: MemoryScope?,
    onClose: () -> Unit,
    onSaved: (MemoryId, isNew: Boolean) -> Unit,
) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel {
        MemoryEditorViewModel(
            memory = graph.memoryService,
            directory = graph.registryDirectory,
            clock = graph.clock,
            editingId = memoryId,
            initialScope = initialScope,
            shortTermTtlMs = graph.memoryConfig.shortTermTtlMs,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedId) {
        state.savedId?.let { id ->
            viewModel.savedHandled()
            onSaved(id, state.isNew)
        }
    }
    MemoryEditorScreen(
        state,
        MemoryEditorActions(
            onScope = viewModel::setScope,
            onTitle = viewModel::setTitle,
            onContent = viewModel::setContent,
            onKind = viewModel::setKind,
            onImportance = viewModel::setImportance,
            onAddTag = viewModel::addTag,
            onRemoveTag = viewModel::removeTag,
            onShortTerm = viewModel::setShortTerm,
            onSave = viewModel::save,
            onClose = onClose,
            onMessageShown = viewModel::messageShown,
        ),
    )
}

@Composable
fun MemoryEditorScreen(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val requestClose = { if (state.isDirty) confirmDiscard = true else actions.onClose() }
    BackHandler(enabled = state.isDirty) { confirmDiscard = true }
    ScreenScaffold(
        title = if (state.isNew) "New memory" else "Edit memory",
        onBack = requestClose,
        actions = {
            if (!state.isLoading && !state.notFound) TextButton(onClick = actions.onSave, enabled = !state.isSaving) { Text("Save") }
        },
        message = state.message,
        onMessageShown = actions.onMessageShown,
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.notFound -> EmptyState(
                icon = AppIcons.MemoryBrain,
                message = "This memory no longer exists, so there is nothing to edit.",
                actionLabel = "Go back",
                onAction = actions.onClose,
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
                Column {
                    SectionHeader("The memory")
                    AppCard {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            AppTextField(
                                value = state.form.title,
                                onValueChange = actions.onTitle,
                                label = "Title",
                                placeholder = "Prefers summaries in bullet points",
                                supportingText = "A short label for lists and the graph.",
                                errorText = state.titleError,
                            )
                            AppTextField(
                                value = state.form.content,
                                onValueChange = actions.onContent,
                                label = "What to remember",
                                singleLine = false,
                                minLines = 4,
                                maxLines = 14,
                                errorText = state.contentError,
                            )
                        }
                    }
                }
                // What to remember comes first; where it goes is often pre-set from the explorer.
                ScopeSection(state, actions)
                DetailsSection(state, actions)
                Button(onClick = actions.onSave, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (state.isNew) "Save memory" else "Save changes")
                }
            }
        }
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            message = if (state.isNew) "This memory has not been saved. Leaving now discards what you wrote." else "Your edits to this memory have not been saved.",
            confirmLabel = "Discard",
            dismissLabel = "Keep editing",
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                actions.onClose()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
private fun ScopeSection(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    Column {
        SectionHeader("Where it goes")
        AppCard {
            val fixed = state.fixedScope
            if (fixed != null) {
                ScopeChip(fixed)
                Text(
                    "A memory stays where it was saved. To let other agents use it too, share it into a space from its page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
                return@AppCard
            }
            val agents = state.scopeChoices.filter { it.kind == ScopeKind.PRIVATE }
            val spaces = state.scopeChoices.filter { it.kind == ScopeKind.SHARED }
            ScopeGroup("An agent's private memory", agents, state.form.scope, actions.onScope)
            RowDivider(Modifier.padding(vertical = Spacing.sm))
            if (spaces.isEmpty()) {
                Text("A SHARED SPACE", style = OverlineTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "There are no shared spaces yet. Create one in the Spaces tab to share knowledge between agents.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            } else {
                ScopeGroup("A shared space", spaces, state.form.scope, actions.onScope)
            }
            state.scopeError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.sm))
            }
        }
    }
}

@Composable
private fun ScopeGroup(title: String, choices: List<ScopeChoiceUi>, selected: MemoryScope?, onSelect: (MemoryScope) -> Unit) {
    Text(title.uppercase(), style = OverlineTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { contentDescription = title })
    choices.forEach { choice ->
        val isSelected = choice.scope == selected
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(choice.scope) })
                .padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            RadioButton(selected = isSelected, onClick = null)
            Icon(choice.kind.icon, contentDescription = null, tint = appColors.agentAccent(choice.colorIndex), modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Text(choice.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    choice.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (choice.warn) appColors.attention else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsSection(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    val form = state.form
    Column {
        SectionHeader("Details")
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("Kind", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    MemoryKind.entries.forEach { kind ->
                        FilterChip(selected = form.kind == kind, onClick = { actions.onKind(kind) }, label = { Text(kind.label) })
                    }
                }
                RowDivider(Modifier.padding(vertical = Spacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("Importance", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    ImportanceMeter(form.importance)
                    Text(importanceLabel(form.importance), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value = form.importance.toFloat(),
                    onValueChange = { actions.onImportance(it.toDouble()) },
                    valueRange = 0f..1f,
                    steps = 9,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Importance" },
                )
                Text(
                    "More important memories rank higher when agents recall.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RowDivider(Modifier.padding(vertical = Spacing.xs))
                Text("Tags", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                ChipEditor(values = form.tags, onAdd = actions.onAddTag, onRemove = actions.onRemoveTag, itemName = "tag", emptyText = "No tags.")
                RowDivider(Modifier.padding(vertical = Spacing.xs))
                SwitchRow(
                    title = "Short-term",
                    description = when {
                        state.isTaskMemory -> "Task memory is always short-term: it lives while the task needs it."
                        form.shortTerm -> "Deleted automatically ${state.shortTermDays} days after it became short-term."
                        else -> "Kept until you delete it. Switch on for something that only matters for the next few days."
                    },
                    checked = form.shortTerm,
                    onCheckedChange = actions.onShortTerm,
                    enabled = !state.isTaskMemory,
                )
            }
        }
    }
}
