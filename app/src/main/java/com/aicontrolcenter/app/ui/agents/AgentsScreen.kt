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
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.registry.Locality
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.compositeOver
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.SIMULATOR_LABEL
import com.aicontrolcenter.app.ui.components.label
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.label

@Composable
fun AgentsRoute(
    initialStatusFilter: AgentStatusFilter,
    onOpenAgent: (AgentId) -> Unit,
    onNewAgent: () -> Unit,
    onAddProvider: () -> Unit,
) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel { AgentsViewModel(graph.registryDirectory, initialStatusFilter, graph.agentManager) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AgentsScreen(
        state = state,
        onAssignModel = viewModel::assignToAgentsWithoutModel,
        onAddProvider = onAddProvider,
        onMessageShown = viewModel::messageShown,
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
    onAssignModel: (ModelId) -> Unit,
    onAddProvider: () -> Unit,
    onMessageShown: () -> Unit,
    onQueryChange: (String) -> Unit,
    onRoleFilterChange: (AgentKind?) -> Unit,
    onStatusFilterChange: (AgentStatusFilter) -> Unit,
    onClearFilters: () -> Unit,
    onOpenAgent: (AgentId) -> Unit,
    onNewAgent: () -> Unit,
) {
    ScreenScaffold(
        title = "Agents",
        message = state.message,
        onMessageShown = onMessageShown,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewAgent,
                // Material clears the semantics of an extended FAB's text, so the icon carries the label.
                icon = { Icon(AppIcons.Add, contentDescription = "New agent") },
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
                    if (state.withoutModel.isNotEmpty()) {
                        item(key = "without-model") {
                            WithoutModelCard(state, onAssignModel, onAddProvider)
                        }
                    }
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
            placeholder = { Text("Search agents") },
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

/** Offers the common first step: one model for every agent that has none. */
@Composable
private fun WithoutModelCard(state: AgentsUiState, onAssignModel: (ModelId) -> Unit, onAddProvider: () -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val count = state.withoutModel.size
    AppCard(containerColor = appColors.info.copy(alpha = 0.08f).compositeOver(MaterialTheme.colorScheme.surface)) {
        Text(
            if (count == 1) "${state.withoutModel.single()} has no model" else "$count agents have no model",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            (if (count == 1) "It cannot run until it has one." else summariseNames(state.withoutModel) + " cannot run until each has one."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (state.assignableModels.isEmpty()) {
            OutlinedButton(onClick = onAddProvider, modifier = Modifier.padding(top = Spacing.md).heightIn(min = 48.dp)) {
                Text("Connect a model provider")
            }
        } else {
            OutlinedButton(
                onClick = { picking = true },
                enabled = !state.isAssigning,
                modifier = Modifier.padding(top = Spacing.md).heightIn(min = 48.dp),
            ) { Text("Assign a model to agents without one") }
        }
    }
    if (picking) {
        ModelPickerDialog(
            title = "Model for agents without one",
            models = state.assignableModels,
            selectedId = null,
            onPick = {
                picking = false
                chosen = it.value
            },
            onDismiss = { picking = false },
        )
    }
    val option = state.assignableModels.firstOrNull { it.id.value == chosen }
    if (option != null) {
        ConfirmDialog(
            title = if (option.isSimulator) "Use the simulator?" else "Assign ${option.name}?",
            message = buildString {
                if (option.isSimulator) {
                    append("${summariseNames(state.withoutModel)} will use the built-in simulator. It is not a real model: it answers ")
                    append("offline with scripted replies labelled as simulated, for trying agents out. Nothing leaves this phone. ")
                } else {
                    append("${summariseNames(state.withoutModel)} will use ${option.name} from ${option.providerName}. ")
                    when (option.locality) {
                        Locality.CLOUD -> append("What you send these agents will leave this device, to a service on the internet. ")
                        Locality.LOCAL_NETWORK -> append("What you send these agents will leave this device, to a machine on your network. ")
                        else -> append("What you send them stays on this device. ")
                    }
                }
                append("Agents that already have a model keep it.")
            },
            confirmLabel = if (option.isSimulator) "Use the simulator" else "Assign",
            onConfirm = {
                chosen = null
                onAssignModel(option.id)
            },
            onDismiss = { chosen = null },
        )
    }
}
