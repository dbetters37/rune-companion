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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.ActivityEventRow
import com.aicontrolcenter.app.ui.components.AppTextField
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.atMostChars
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.components.label
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.Activity
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Egress
import com.aicontrolcenter.app.ui.icons.Filter
import com.aicontrolcenter.app.ui.theme.OverlineTextStyle
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import kotlinx.coroutines.launch

/**
 * The Activity section (wave 3, WP10a): the [ActivityTab.LOG] tab (newest first, "Show older", search) and the
 * [ActivityTab.USAGE] tab ("Usage and cost", [UsageViewModel]). [initialAgent] and [initialText] open the log filtered (a
 * usage row's link from another screen); [navigation] leads on. A usage row's own link switches to the log in place.
 */
@Composable
fun ActivityRoute(
    initialTab: ActivityTab = ActivityTab.LOG,
    initialAgent: AgentId? = null,
    initialText: String? = null,
    navigation: ActivityNavigation = ActivityNavigation(),
) {
    val graph = LocalAppGraph.current
    val work = LocalUsageWork.current
    val log = viewModel {
        ActivityViewModel(
            activityLog = graph.activityLog,
            directory = graph.registryDirectory,
            clock = graph.clock,
            zone = graph.zone,
            initialAgent = initialAgent,
            initialText = initialText,
            computation = work,
        )
    }
    val usage = viewModel {
        UsageViewModel(
            reports = graph.usageReports,
            prices = graph.modelPrices,
            directory = graph.registryDirectory,
            recentRequests = graph.ownerRequests::observeRecent,
            clock = graph.clock,
            zone = graph.zone,
            computation = work,
        )
    }
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val state by log.uiState.collectAsStateWithLifecycle()
    ActivityScreen(
        selectedTab = tab,
        onSelectTab = { tab = it },
        state = state,
        search = log.search,
        actions = ActivityLogActions(
            onToggleType = log::toggleType,
            onMinSeverityChange = log::setMinSeverity,
            onEgressOnlyChange = log::setEgressOnly,
            onClearAgent = log::clearAgent,
            onClearFilters = log::clearFilters,
            onShowOlder = log::showOlder,
            onShowNewest = log::showNewest,
            onSelect = log::select,
            onCloseDetail = log::closeDetail,
        ),
        usage = { modifier ->
            UsageTab(
                viewModel = usage,
                navigation = navigation,
                onOpenLog = { agentId, text ->
                    log.showOnly(agentId, text)
                    tab = ActivityTab.LOG
                },
                modifier = modifier,
            )
        },
    )
}

/** What the log can ask for. */
class ActivityLogActions(
    val onToggleType: (ActivityType) -> Unit = {},
    val onMinSeverityChange: (Severity) -> Unit = {},
    val onEgressOnlyChange: (Boolean) -> Unit = {},
    val onClearAgent: () -> Unit = {},
    val onClearFilters: () -> Unit = {},
    val onShowOlder: () -> Unit = {},
    val onShowNewest: () -> Unit = {},
    val onSelect: (ActivityEvent) -> Unit = {},
    val onCloseDetail: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    selectedTab: ActivityTab,
    onSelectTab: (ActivityTab) -> Unit,
    state: ActivityUiState,
    search: TextFieldState,
    actions: ActivityLogActions,
    usage: @Composable (Modifier) -> Unit,
) {
    ScreenScaffold(
        title = "Activity",
        actions = {
            if (selectedTab == ActivityTab.LOG && state.filter.isActive) {
                TextButton(onClick = actions.onClearFilters) { Text("Clear filters") }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.background,
                divider = { HorizontalDivider(color = appColors.hairline) },
            ) {
                ActivityTab.entries.forEach { tab ->
                    Tab(
                        selected = tab == selectedTab,
                        onClick = { onSelectTab(tab) },
                        text = { Text(tab.title, maxLines = 1) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (selectedTab) {
                ActivityTab.LOG -> LogTab(state, search, actions)
                ActivityTab.USAGE -> usage(Modifier.fillMaxSize())
            }
        }
    }

    state.selected?.let { detail ->
        ModalBottomSheet(
            onDismissRequest = actions.onCloseDetail,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            EventDetailContent(detail)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogTab(state: ActivityUiState, search: TextFieldState, actions: ActivityLogActions) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // A new filter or search starts at the top of its list.
    LaunchedEffect(state.filter) { listState.scrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.readableWidth()) {
            AppTextField(
                state = search,
                label = "Search the log",
                placeholder = "For example: web, failed, Researcher",
                inputTransformation = atMostChars(SEARCH_MAX_CHARS),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs),
            )
            ActivityFilters(state, actions)
        }
        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.groups.isEmpty() && state.filter.isActive -> EmptyState(
                icon = AppIcons.Filter,
                message = if (state.filter.text.isNotBlank()) {
                    "No recorded event mentions \"${state.filter.text}\" with these filters."
                } else {
                    "No recorded event matches these filters."
                },
                actionLabel = "Clear filters",
                onAction = actions.onClearFilters,
            )
            state.groups.isEmpty() -> EmptyState(
                icon = AppIcons.Activity,
                title = "Nothing recorded yet",
                message = "Every change to the registry, every action and every transfer of data is written here as it happens.",
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.readableWidth().fillMaxSize(),
                contentPadding = PaddingValues(bottom = Spacing.xl),
            ) {
                if (state.newer > 0) {
                    item(key = "newer") {
                        NewerEvents(state.newer, state.newerCapped) {
                            actions.onShowNewest()
                            scope.launch { listState.scrollToItem(0) }
                        }
                    }
                }
                state.groups.forEach { group ->
                    stickyHeader(key = "day:${group.heading}") { DayHeading(group.heading, group.rows.size) }
                    items(group.rows, key = { it.id }) { row ->
                        ActivityEventRow(row, onClick = { actions.onSelect(row.event) })
                        RowDivider(Modifier.padding(start = 64.dp))
                    }
                }
                item(key = "older") {
                    OlderEvents(state, actions.onShowOlder)
                }
            }
        }
    }
}

/** "Show older" (D41), or the end of the log. */
@Composable
private fun OlderEvents(state: ActivityUiState, onShowOlder: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            state.loadingOlder -> CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = "Reading older events" })
            state.canShowOlder -> OutlinedButton(onClick = onShowOlder, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Show older events")
            }
            else -> Text(
                if (state.eventCount == 1) "This is the only event." else "That is every one of the ${state.eventCount} events.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** While older pages are shown the list holds still; this says what arrived since. */
@Composable
private fun NewerEvents(count: Int, capped: Boolean, onShowNewest: () -> Unit) {
    val text = when {
        capped -> "$count or more new events since you looked further back."
        count == 1 -> "1 new event since you looked further back."
        else -> "$count new events since you looked further back."
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen, vertical = Spacing.xs)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        TextButton(onClick = onShowNewest, modifier = Modifier.heightIn(min = 48.dp)) { Text("Show the newest") }
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
private fun ActivityFilters(state: ActivityUiState, actions: ActivityLogActions, modifier: Modifier = Modifier) {
    val filter = state.filter
    // An unselected chip must not look switched on: its icon is as quiet as its label.
    val filterColors = FilterChipDefaults.filterChipColors(iconColor = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(modifier = modifier) {
        ChipRow {
            state.agentName?.let { name ->
                InputChip(
                    selected = true,
                    onClick = actions.onClearAgent,
                    label = { Text("Only $name") },
                    trailingIcon = { Icon(AppIcons.Close, contentDescription = "Show every agent", modifier = Modifier.size(18.dp)) },
                )
            }
            FilterChip(
                selected = filter.egressOnly,
                onClick = { actions.onEgressOnlyChange(!filter.egressOnly) },
                label = { Text("Left the device") },
                leadingIcon = { Icon(AppIcons.Egress, contentDescription = null, modifier = Modifier.size(18.dp)) },
                colors = filterColors,
            )
            FilterChip(
                selected = filter.minSeverity == Severity.WARNING,
                onClick = { actions.onMinSeverityChange(if (filter.minSeverity == Severity.WARNING) Severity.INFO else Severity.WARNING) },
                label = { Text("Warnings and errors") },
            )
            FilterChip(
                selected = filter.minSeverity == Severity.ERROR,
                onClick = { actions.onMinSeverityChange(if (filter.minSeverity == Severity.ERROR) Severity.INFO else Severity.ERROR) },
                label = { Text("Errors only") },
            )
        }
        ChipRow {
            ActivityType.entries.forEach { type ->
                FilterChip(
                    selected = type in filter.types,
                    onClick = { actions.onToggleType(type) },
                    label = { Text(type.label) },
                    leadingIcon = { Icon(type.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    colors = filterColors,
                )
            }
        }
    }
}

@Composable
internal fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** The longest search the log takes. */
private const val SEARCH_MAX_CHARS = 200
