package com.aicontrolcenter.app.ui.agents

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.Agent
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.label

@Composable
fun AgentsRoute(
    initialStatusFilter: AgentStatusFilter,
    onOpenAgent: (AgentId) -> Unit,
    onNewAgent: () -> Unit,
) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel { AgentsViewModel(graph.registryDirectory, initialStatusFilter) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AgentsScreen(
        state = state,
        onQueryChange = viewModel::setQuery,
        onRoleFilterChange = viewModel::setRoleFilter,
        onStatusFilterChange = viewModel::setStatusFilter,
        onClearFilters = viewModel::clearFilters,
        onOpenAgent = onOpenAgent,
        onNewAgent = onNewAgent,
    )
}

@Composable
fun AgentsScreen(
    state: AgentsUiState,
    onQueryChange: (String) -> Unit,
    onRoleFilterChange: (AgentKind?) -> Unit,
    onStatusFilterChange: (AgentStatusFilter) -> Unit,
    onClearFilters: () -> Unit,
    onOpenAgent: (AgentId) -> Unit,
    onNewAgent: () -> Unit,
) {
    ScreenScaffold(
        title = "Agents",
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewAgent,
                icon = { Icon(AppIcons.Add, contentDescription = null) },
                text = { Text("New agent") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@ScreenScaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            AgentFilters(state, onQueryChange, onRoleFilterChange, onStatusFilterChange, Modifier.readableWidth())
            when {
                state.totalCount == 0 -> EmptyState(
                    icon = AppIcons.Agent,
                    title = "No agents yet",
                    message = "An agent is a configured actor that uses a model to do a job. Create one from a template.",
                    actionLabel = "New agent",
                    onAction = onNewAgent,
                )
                state.rows.isEmpty() -> EmptyState(
                    icon = AppIcons.Search,
                    message = "No agent matches the search and filters.",
                    actionLabel = "Clear filters",
                    onAction = onClearFilters,
                )
                else -> LazyColumn(
                    modifier = Modifier.readableWidth().fillMaxSize(),
                    contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.sm, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    items(state.rows, key = { it.id.value }) { row ->
                        AgentRowCard(row, onClick = { onOpenAgent(row.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentFilters(
    state: AgentsUiState,
    onQueryChange: (String) -> Unit,
    onRoleFilterChange: (AgentKind?) -> Unit,
    onStatusFilterChange: (AgentStatusFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen),
            singleLine = true,
            placeholder = { Text("Search agents by name") },
            leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) { Icon(AppIcons.Close, contentDescription = "Clear search") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = MaterialTheme.shapes.large,
        )
        FilterRow(label = "Role") {
            FilterChip(
                selected = state.roleFilter == null,
                onClick = { onRoleFilterChange(null) },
                label = { Text("Any role") },
            )
            AgentKind.entries.forEach { kind ->
                FilterChip(
                    selected = state.roleFilter == kind,
                    onClick = { onRoleFilterChange(if (state.roleFilter == kind) null else kind) },
                    label = { Text(kind.label) },
                )
            }
        }
        FilterRow(label = "Status") {
            AgentStatusFilter.entries.forEach { status ->
                FilterChip(
                    selected = state.statusFilter == status,
                    onClick = {
                        onStatusFilterChange(
                            if (state.statusFilter == status) AgentStatusFilter.ALL else status,
                        )
                    },
                    label = { Text(status.label) },
                )
            }
        }
    }
}

/** A labelled, horizontally scrolling row of filter chips. */
@Composable
private fun FilterRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.screen),
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
