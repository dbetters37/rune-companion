package com.aicontrolcenter.app.ui.memory

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.MemoryBrain
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.memory.MemoryKind

/** What the explorer asks its view model to do. */
class ExplorerActions(
    val onQueryChange: (String) -> Unit,
    val onScopeChange: (ScopeFilter) -> Unit,
    val onToggleKind: (MemoryKind) -> Unit,
    val onIncludeTaskMemoryChange: (Boolean) -> Unit,
    val onClearFilters: () -> Unit,
    val onOpenMemory: (MemoryId) -> Unit,
    val onNewMemory: () -> Unit,
)

/** Search, filters and the list of memories. */
@Composable
internal fun MemoryExplorerTab(state: ExplorerUiState, actions: ExplorerActions) {
    Column(Modifier.fillMaxSize()) {
        ExplorerFiltersBar(state, actions, Modifier.readableWidth())
        when {
            state.totalCount == 0 -> EmptyState(
                icon = AppIcons.MemoryBrain,
                title = "No memories yet",
                message = "Agents save what they learn here as they work, and you can add your own. Each agent has a " +
                    "private memory; shared spaces hold what you let agents share.",
                actionLabel = "New memory",
                onAction = actions.onNewMemory,
            )
            state.searchFailed -> EmptyState(
                icon = AppIcons.Search,
                message = "The search could not run. Try again in a moment, or clear it to browse.",
                actionLabel = "Clear search",
                onAction = { actions.onQueryChange("") },
            )
            state.rows.isEmpty() && !state.isSearching -> EmptyState(
                icon = AppIcons.Search,
                message = if (state.filters.query.isNotBlank()) {
                    "No memory matches \"${state.filters.query.trim()}\" with these filters."
                } else {
                    "No memory matches these filters."
                },
                actionLabel = "Clear filters",
                onAction = actions.onClearFilters,
            )
            else -> LazyColumn(
                modifier = Modifier.readableWidth().fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(state.rows, key = { it.id.value }) { row ->
                    MemoryRowCard(row, onClick = { actions.onOpenMemory(row.id) })
                }
            }
        }
    }
}

@Composable
private fun ExplorerFiltersBar(state: ExplorerUiState, actions: ExplorerActions, modifier: Modifier = Modifier) {
    val filters = state.filters
    // Typed text is kept here so the field never lags behind the keyboard while a search runs.
    var query by remember { mutableStateOf(filters.query) }
    LaunchedEffect(filters.query) { if (filters.query != query) query = filters.query }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                actions.onQueryChange(it)
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen),
            singleLine = true,
            placeholder = { Text("Search memories") },
            leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
            trailingIcon = {
                when {
                    state.isSearching -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    query.isNotEmpty() -> IconButton(onClick = {
                        query = ""
                        actions.onQueryChange("")
                    }) { Icon(AppIcons.Close, contentDescription = "Clear search") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = MaterialTheme.shapes.large,
        )
        ChipRow(label = "In") {
            FilterChip(
                selected = filters.scope == ScopeFilter.All,
                onClick = { actions.onScopeChange(ScopeFilter.All) },
                label = { Text("All memory") },
            )
            (state.agentOptions + state.spaceOptions).forEach { option -> ScopeFilterChip(option, filters.scope == option.filter, actions.onScopeChange) }
        }
        ChipRow(label = "Kind") {
            MemoryKind.entries.forEach { kind ->
                FilterChip(
                    selected = kind in filters.kinds,
                    onClick = { actions.onToggleKind(kind) },
                    label = { Text(kind.label) },
                )
            }
        }
        if (state.totalCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    resultLine(state),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = filters.includeTaskMemory,
                    onClick = { actions.onIncludeTaskMemoryChange(!filters.includeTaskMemory) },
                    label = { Text("Task memory") },
                    leadingIcon = {
                        Icon(ScopeKind.TASK.icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                    },
                )
            }
        }
    }
}

private fun resultLine(state: ExplorerUiState): String {
    val shown = state.rows.size
    return when {
        state.filters.query.isNotBlank() -> "${plural(shown, "match", "matches")}, best first"
        state.filters.isFiltered -> "$shown of ${plural(state.totalCount, "memory", "memories")}, newest first"
        else -> "${plural(state.totalCount, "memory", "memories")}, newest first"
    }
}

/** An agent or space as a filter, marked with its scope glyph and accent. */
@Composable
internal fun ScopeFilterChip(option: ScopeOptionUi, selected: Boolean, onSelect: (ScopeFilter) -> Unit) {
    val accent = appColors.agentAccent(option.colorIndex)
    FilterChip(
        selected = selected,
        onClick = { onSelect(option.filter) },
        label = { Text(option.label) },
        leadingIcon = { Icon(option.kind.icon, contentDescription = null, tint = accent, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
    )
}

/** A labelled, horizontally scrolling row of chips. */
@Composable
internal fun ChipRow(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.screen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = Spacing.xs),
        )
        content()
    }
}
