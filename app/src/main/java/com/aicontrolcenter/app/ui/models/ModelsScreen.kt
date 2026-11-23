package com.aicontrolcenter.app.ui.models

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Help
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.llm.DetectedServer

/** Where the Models section leads. Supplied by the navigation shell. */
class ModelsNavigation(
    val onOpenApp: (AiAppId) -> Unit,
    val onRegisterManually: () -> Unit,
    val onOpenConcepts: () -> Unit,
    val onAddProvider: () -> Unit,
    val onOpenProvider: (ProviderId) -> Unit,
    val onOpenModel: (ModelId) -> Unit,
    /** Opens the provider form pre-filled for a server found on this phone. */
    val onAddDetected: (DetectedServer) -> Unit,
)

@Composable
fun ModelsRoute(initialTab: ModelsTab, navigation: ModelsNavigation) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel {
        ModelsViewModel(graph.registryDirectory, graph.aiAppRegistry, graph.providerManager, graph.clock)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    ModelsScreen(
        state = state,
        selectedTab = tab,
        onSelectTab = { tab = it },
        actions = ModelsActions(
            onScan = viewModel::scanDevice,
            onConfirmApp = viewModel::confirm,
            onSetModelEnabled = viewModel::setModelEnabled,
            onAddModel = viewModel::addModel,
            onFindServers = viewModel::findLocalServers,
            onDismissDetection = viewModel::dismissDetection,
            onMessageShown = viewModel::messageShown,
        ),
        navigation = navigation,
    )
}

/** What the Models screen asks its view model to do. */
class ModelsActions(
    val onScan: () -> Unit,
    val onConfirmApp: (AiAppId) -> Unit,
    val onSetModelEnabled: (ModelId, Boolean) -> Unit,
    val onAddModel: (ProviderId, String) -> Unit,
    val onFindServers: () -> Unit,
    val onDismissDetection: () -> Unit,
    val onMessageShown: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    state: ModelsUiState,
    selectedTab: ModelsTab,
    onSelectTab: (ModelsTab) -> Unit,
    actions: ModelsActions,
    navigation: ModelsNavigation,
) {
    ScreenScaffold(
        title = "Models",
        actions = {
            IconButton(onClick = navigation.onOpenConcepts) {
                Icon(AppIcons.Help, contentDescription = "AI application, model or agent: what is the difference?")
            }
        },
        message = state.message,
        onMessageShown = actions.onMessageShown,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.background,
                divider = { HorizontalDivider(color = appColors.hairline) },
            ) {
                ModelsTab.entries.forEach { tab ->
                    val count = when (tab) {
                        ModelsTab.PROVIDERS -> state.providers.size
                        ModelsTab.MODELS -> state.modelCount
                        ModelsTab.APPS -> state.apps.size
                    }
                    Tab(
                        selected = tab == selectedTab,
                        onClick = { onSelectTab(tab) },
                        text = { Text(if (state.isLoading) tab.title else "${tab.title} · $count", maxLines = 1) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                when (selectedTab) {
                    ModelsTab.PROVIDERS -> ProvidersTab(
                        providers = state.providers,
                        isSearching = state.detection == DetectionUi.Searching,
                        onAddProvider = navigation.onAddProvider,
                        onFindServers = actions.onFindServers,
                        onOpenProvider = navigation.onOpenProvider,
                    )
                    ModelsTab.MODELS -> ModelsListTab(
                        groups = state.groups,
                        onOpenModel = navigation.onOpenModel,
                        onSetEnabled = actions.onSetModelEnabled,
                        onAddModel = actions.onAddModel,
                        onAddProvider = navigation.onAddProvider,
                    )
                    ModelsTab.APPS -> AiAppsTab(
                        apps = state.apps,
                        isScanning = state.isScanning,
                        lastScan = state.lastScan,
                        onScan = actions.onScan,
                        onOpenApp = navigation.onOpenApp,
                        onConfirmApp = actions.onConfirmApp,
                        onRegisterManually = navigation.onRegisterManually,
                        onOpenConcepts = navigation.onOpenConcepts,
                    )
                }
            }
        }
    }
    state.detection?.let { detection ->
        LocalServersDialog(
            detection = detection,
            onAdd = { server ->
                actions.onDismissDetection()
                navigation.onAddDetected(server)
            },
            onDismiss = actions.onDismissDetection,
        )
    }
}
