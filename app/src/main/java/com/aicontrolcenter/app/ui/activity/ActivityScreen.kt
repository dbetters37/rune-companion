package com.aicontrolcenter.app.ui.activity

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.ActivityEventRow
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.components.label
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.Activity
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Egress
import com.aicontrolcenter.app.ui.icons.Filter
import com.aicontrolcenter.app.ui.theme.OverlineTextStyle
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity

@Composable
fun ActivityRoute() {
    val graph = LocalAppGraph.current
    val viewModel = viewModel { ActivityViewModel(graph.activityLog, graph.registryDirectory, graph.clock) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ActivityScreen(
        state = state,
        onToggleType = viewModel::toggleType,
        onMinSeverityChange = viewModel::setMinSeverity,
        onEgressOnlyChange = viewModel::setEgressOnly,
        onClearFilters = viewModel::clearFilters,
        onSelect = viewModel::select,
        onCloseDetail = viewModel::closeDetail,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ActivityScreen(
    state: ActivityUiState,
    onToggleType: (ActivityType) -> Unit,
    onMinSeverityChange: (Severity) -> Unit,
    onEgressOnlyChange: (Boolean) -> Unit,
    onClearFilters: () -> Unit,
    onSelect: (ActivityEvent) -> Unit,
    onCloseDetail: () -> Unit,
) {
    ScreenScaffold(
        title = "Activity",
        actions = {
            if (state.filter.isActive) {
                TextButton(onClick = onClearFilters) { Text("Clear filters") }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ActivityFilters(state.filter, onToggleType, onMinSeverityChange, onEgressOnlyChange, Modifier.readableWidth())
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.groups.isEmpty() && state.filter.isActive -> EmptyState(
                    icon = AppIcons.Filter,
                    message = "No recorded event matches these filters.",
                    actionLabel = "Clear filters",
                    onAction = onClearFilters,
                )
                state.groups.isEmpty() -> EmptyState(
                    icon = AppIcons.Activity,
                    title = "Nothing recorded yet",
                    message = "Every change to the registry, every action and every transfer of data is written here as it happens.",
                )
                else -> LazyColumn(
                    modifier = Modifier.readableWidth().fillMaxSize(),
                    contentPadding = PaddingValues(bottom = Spacing.xl),
                ) {
                    state.groups.forEach { group ->
                        stickyHeader(key = "day:${group.heading}") { DayHeading(group.heading, group.rows.size) }
                        items(group.rows, key = { it.id }) { row ->
                            ActivityEventRow(row, onClick = { onSelect(row.event) })
                            RowDivider(Modifier.padding(start = 64.dp))
                        }
                    }
                    if (state.truncated) {
                        item {
                            Text(
                                "Showing the newest ${state.eventCount} matching events. Narrow the filters to see older ones.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(Spacing.lg),
                            )
                        }
                    }
                }
            }
        }
    }

    state.selected?.let { detail ->
        ModalBottomSheet(
            onDismissRequest = onCloseDetail,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            EventDetailContent(detail)
        }
    }
}

@Composable
private fun DayHeading(heading: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = Spacing.screen, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            heading.uppercase(),
            style = OverlineTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics {
                heading()
                contentDescription = heading
            },
        )
        Text(
            if (count == 1) "1 event" else "$count events",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActivityFilters(
    filter: ActivityFilterUi,
    onToggleType: (ActivityType) -> Unit,
    onMinSeverityChange: (Severity) -> Unit,
    onEgressOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // An unselected chip must not look switched on: its icon is as quiet as its label.
    val filterColors = FilterChipDefaults.filterChipColors(iconColor = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(modifier = modifier) {
        ChipRow {
            FilterChip(
                selected = filter.egressOnly,
                onClick = { onEgressOnlyChange(!filter.egressOnly) },
                label = { Text("Left the device") },
                leadingIcon = { Icon(AppIcons.Egress, contentDescription = null, modifier = Modifier.size(18.dp)) },
                colors = filterColors,
            )
            FilterChip(
                selected = filter.minSeverity == Severity.WARNING,
                onClick = { onMinSeverityChange(if (filter.minSeverity == Severity.WARNING) Severity.INFO else Severity.WARNING) },
                label = { Text("Warnings and errors") },
            )
            FilterChip(
                selected = filter.minSeverity == Severity.ERROR,
                onClick = { onMinSeverityChange(if (filter.minSeverity == Severity.ERROR) Severity.INFO else Severity.ERROR) },
                label = { Text("Errors only") },
            )
        }
        ChipRow {
            ActivityType.entries.forEach { type ->
                FilterChip(
                    selected = type in filter.types,
                    onClick = { onToggleType(type) },
                    label = { Text(type.label) },
                    leadingIcon = { Icon(type.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    colors = filterColors,
                )
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}
