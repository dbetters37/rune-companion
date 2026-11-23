package com.aicontrolcenter.app.ui.memory

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.memory.graph.GraphActions
import com.aicontrolcenter.app.ui.memory.graph.GraphExportFiles
import com.aicontrolcenter.app.ui.memory.graph.GraphUiState
import com.aicontrolcenter.app.ui.memory.graph.MemoryGraphTab
import com.aicontrolcenter.app.ui.memory.graph.MemoryGraphViewModel
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.memory.MemoryScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** The Memory section's three views. */
@Serializable
enum class MemoryTab(val title: String) {
    EXPLORER("Explorer"),
    SPACES("Spaces"),
    GRAPH("Graph"),
}

/** Where the Memory section leads. Supplied by the navigation shell. */
class MemoryNavigation(
    val onOpenMemory: (MemoryId) -> Unit,
    /** Opens the editor for a new memory, pre-set to [MemoryScope] when the explorer is narrowed to one. */
    val onNewMemory: (MemoryScope?) -> Unit,
    val onOpenSpace: (SpaceId) -> Unit,
)

/**
 * The Memory section: the explorer (search and browse), shared spaces, and the graph. Each
 * tab's view model is created the first time the tab is shown and then kept with the section,
 * so switching tabs keeps searches, filters and the graph's selection.
 */
@Composable
fun MemorySectionRoute(initialTab: MemoryTab, initialScope: ScopeFilter, navigation: MemoryNavigation) {
    val graph = LocalAppGraph.current
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var creatingSpace by rememberSaveable { mutableStateOf(false) }
    val explorer = viewModel { MemoryExplorerViewModel(graph.memoryService, graph.registryDirectory, graph.clock, initialScope) }
    val explorerState by explorer.uiState.collectAsStateWithLifecycle()

    var spacesState: SpacesUiState? = null
    var spacesMessageShown: () -> Unit = {}
    var graphState: GraphUiState? = null
    var graphMessageShown: () -> Unit = {}

    val newMemory = {
        navigation.onNewMemory(
            when (val scope = explorerState.filters.scope) {
                is ScopeFilter.Agent -> MemoryScope.Agent(scope.id)
                is ScopeFilter.Space -> MemoryScope.Shared(scope.id)
                ScopeFilter.All -> null
            },
        )
    }

    // The tab bodies are built first so the scaffold can show the open tab's message and button.
    val body: @Composable () -> Unit = when (tab) {
        MemoryTab.EXPLORER -> ({
            MemoryExplorerTab(
                explorerState,
                ExplorerActions(
                    onQueryChange = explorer::setQuery,
                    onScopeChange = explorer::setScope,
                    onToggleKind = explorer::toggleKind,
                    onIncludeTaskMemoryChange = explorer::setIncludeTaskMemory,
                    onClearFilters = explorer::clearFilters,
                    onOpenMemory = navigation.onOpenMemory,
                    onNewMemory = newMemory,
                ),
            )
        })
        MemoryTab.SPACES -> {
            val spaces = viewModel { SpacesViewModel(graph.memoryService, graph.registryDirectory) }
            val state by spaces.uiState.collectAsStateWithLifecycle()
            spacesState = state
            spacesMessageShown = spaces::messageShown
            LaunchedEffect(state.createdSpaceId) {
                state.createdSpaceId?.let { id ->
                    spaces.createdHandled()
                    creatingSpace = false
                    navigation.onOpenSpace(id)
                }
            }
            ({
                SpacesTab(
                    state,
                    isCreating = creatingSpace,
                    actions = SpacesActions(
                        onStartCreate = { creatingSpace = true },
                        onCreate = spaces::create,
                        onCancelCreate = {
                            creatingSpace = false
                            spaces.clearCreateError()
                        },
                        onClearCreateError = spaces::clearCreateError,
                        onOpenSpace = navigation.onOpenSpace,
                    ),
                )
            })
        }
        MemoryTab.GRAPH -> {
            val context = LocalContext.current
            val viewModel = viewModel {
                MemoryGraphViewModel(graph.memoryService, graph.registryDirectory, graph.settings, graph.activityRecorder, graph.clock)
            }
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            graphState = state
            graphMessageShown = viewModel::messageShown
            LaunchedEffect(state.pendingExport) {
                state.pendingExport?.let { export ->
                    // Writing the file is disk work; the share sheet itself must be opened from the main thread.
                    val failure = withContext(Dispatchers.IO) {
                        runCatching { GraphExportFiles.write(context.cacheDir, export.fileName, export.json) }
                    }.fold(
                        onSuccess = { file ->
                            try {
                                context.startActivity(GraphExportFiles.shareIntent(context, file))
                                null
                            } catch (_: android.content.ActivityNotFoundException) {
                                "No app on this phone can receive the file."
                            } catch (_: IllegalArgumentException) {
                                "The export file could not be shared."
                            }
                        },
                        onFailure = { "The export file could not be written. Free some storage and try again." },
                    )
                    viewModel.exportHandled(failure)
                }
            }
            ({
                MemoryGraphTab(
                    state,
                    GraphActions(
                        onToggleScope = { filter ->
                            when (filter) {
                                is ScopeFilter.Agent -> viewModel.toggleAgent(filter.id)
                                is ScopeFilter.Space -> viewModel.toggleSpace(filter.id)
                                ScopeFilter.All -> viewModel.showEverything()
                            }
                        },
                        onShowEverything = viewModel::showEverything,
                        onIncludeTaskMemoryChange = viewModel::setIncludeTaskMemory,
                        onSelect = viewModel::selectNode,
                        onOpenMemory = navigation.onOpenMemory,
                        onExport = viewModel::exportGraph,
                    ),
                )
            })
        }
    }

    val message: UserMessage? = when (tab) {
        MemoryTab.EXPLORER -> null
        MemoryTab.SPACES -> spacesState?.message
        MemoryTab.GRAPH -> graphState?.message
    }
    MemoryScreen(
        selectedTab = tab,
        onSelectTab = { tab = it },
        memoryCount = explorerState.totalCount.takeIf { !explorerState.isLoading },
        spaceCount = explorerState.spaceOptions.size.takeIf { !explorerState.isLoading },
        message = message,
        onMessageShown = if (tab == MemoryTab.SPACES) spacesMessageShown else graphMessageShown,
        floatingAction = when (tab) {
            MemoryTab.EXPLORER -> FloatingAction("New memory", newMemory)
            MemoryTab.SPACES -> FloatingAction("New space") { creatingSpace = true }.takeIf { !creatingSpace }
            MemoryTab.GRAPH -> null
        },
        body = body,
    )
}

/** The one primary action of the open tab. */
class FloatingAction(val label: String, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    selectedTab: MemoryTab,
    onSelectTab: (MemoryTab) -> Unit,
    memoryCount: Int?,
    spaceCount: Int?,
    message: UserMessage?,
    onMessageShown: () -> Unit,
    floatingAction: FloatingAction?,
    body: @Composable () -> Unit,
) {
    ScreenScaffold(
        title = "Memory",
        message = message,
        onMessageShown = onMessageShown,
        floatingActionButton = {
            if (floatingAction != null) {
                ExtendedFloatingActionButton(
                    onClick = floatingAction.onClick,
                    // Material clears the semantics of an extended FAB's text, so the icon carries the label.
                    icon = { Icon(AppIcons.Add, contentDescription = floatingAction.label) },
                    text = { Text(floatingAction.label) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.background,
                divider = { HorizontalDivider(color = appColors.hairline) },
            ) {
                MemoryTab.entries.forEach { tab ->
                    val count = when (tab) {
                        MemoryTab.EXPLORER -> memoryCount
                        MemoryTab.SPACES -> spaceCount
                        MemoryTab.GRAPH -> null
                    }
                    Tab(
                        selected = tab == selectedTab,
                        onClick = { onSelectTab(tab) },
                        text = { Text(if (count == null) tab.title else "${tab.title} · $count", maxLines = 1) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            body()
        }
    }
}
