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

@Composable
fun ModelsRoute(
    initialTab: ModelsTab,
    onOpenApp: (AiAppId) -> Unit,
    onRegisterManually: () -> Unit,
    onOpenConcepts: () -> Unit,
) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel { ModelsViewModel(graph.registryDirectory, graph.aiAppRegistry) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    ModelsScreen(
        state = state,
        selectedTab = tab,
        onSelectTab = { tab = it },
        onScan = viewModel::scanDevice,
        onOpenApp = onOpenApp,
        onConfirmApp = viewModel::confirm,
        onRegisterManually = onRegisterManually,
        onOpenConcepts = onOpenConcepts,
        onMessageShown = viewModel::messageShown,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    state: ModelsUiState,
    selectedTab: ModelsTab,
    onSelectTab: (ModelsTab) -> Unit,
    onScan: () -> Unit,
    onOpenApp: (AiAppId) -> Unit,
    onConfirmApp: (AiAppId) -> Unit,
    onRegisterManually: () -> Unit,
    onOpenConcepts: () -> Unit,
    onMessageShown: () -> Unit,
) {
    ScreenScaffold(
        title = "Models",
        actions = {
            IconButton(onClick = onOpenConcepts) {
                Icon(AppIcons.Help, contentDescription = "AI application, model or agent: what is the difference?")
            }
        },
        message = state.message,
        onMessageShown = onMessageShown,
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
                        ModelsTab.MODELS -> state.models.size
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
                    ModelsTab.PROVIDERS -> ProvidersTab(state.providers)
                    ModelsTab.MODELS -> ModelsListTab(state.models)
                    ModelsTab.APPS -> AiAppsTab(
                        apps = state.apps,
                        isScanning = state.isScanning,
                        lastScan = state.lastScan,
                        onScan = onScan,
                        onOpenApp = onOpenApp,
                        onConfirmApp = onConfirmApp,
                        onRegisterManually = onRegisterManually,
                        onOpenConcepts = onOpenConcepts,
                    )
                }
            }
        }
    }
}
