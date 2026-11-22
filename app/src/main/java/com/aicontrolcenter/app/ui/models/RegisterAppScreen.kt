package com.aicontrolcenter.app.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Apps
import com.aicontrolcenter.app.ui.theme.MonoTextStyle
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.registry.InstalledAppInfo

@Composable
fun RegisterAppRoute(onBack: () -> Unit, onRegistered: (AiAppId) -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel { RegisterAppViewModel(graph.aiAppRegistry) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.registeredId) {
        state.registeredId?.let { id ->
            viewModel.registeredHandled()
            onRegistered(id)
        }
    }
    RegisterAppScreen(
        state = state,
        onBack = onBack,
        onQueryChange = viewModel::setQuery,
        onIncludeSystemAppsChange = viewModel::setIncludeSystemApps,
        onRegister = viewModel::register,
        onRetry = viewModel::load,
        onMessageShown = viewModel::messageShown,
    )
}

/** Picker over the installed apps that are not registered yet. */
@Composable
fun RegisterAppScreen(
    state: RegisterAppUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onIncludeSystemAppsChange: (Boolean) -> Unit,
    onRegister: (packageName: String) -> Unit,
    onRetry: () -> Unit,
    onMessageShown: () -> Unit,
) {
    ScreenScaffold(
        title = "Register an app",
        onBack = onBack,
        message = state.message,
        onMessageShown = onMessageShown,
    ) { padding ->
        Column(Modifier.padding(padding).readableWidth().fillMaxSize()) {
            Text(
                "Pick an installed app to list it as an AI application. Registering records that it exists; " +
                    "it gives this framework no control over it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.screen),
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.md),
                singleLine = true,
                placeholder = { Text("Search installed apps") },
                leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) { Icon(AppIcons.Close, contentDescription = "Clear search") }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = MaterialTheme.shapes.large,
            )
            FilterChip(
                selected = state.includeSystemApps,
                onClick = { onIncludeSystemAppsChange(!state.includeSystemApps) },
                label = { Text("Include system apps") },
                modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs),
            )
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.loadError != null -> EmptyState(
                    icon = AppIcons.Apps,
                    message = state.loadError,
                    actionLabel = "Try again",
                    onAction = onRetry,
                )
                state.totalCount == 0 -> EmptyState(
                    icon = AppIcons.Apps,
                    message = "Every installed app that can be opened is already registered.",
                )
                state.apps.isEmpty() -> EmptyState(
                    icon = AppIcons.Search,
                    message = "No installed app matches. Try another search, or include system apps.",
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xl)) {
                    items(state.apps, key = { it.packageName }) { app ->
                        InstalledAppRow(app, enabled = !state.isRegistering, onClick = { onRegister(app.packageName) })
                        RowDivider(Modifier.padding(start = 76.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledAppRow(app: InstalledAppInfo, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClickLabel = "Register", onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = Spacing.screen, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        AppMonogram(app.label)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                app.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.packageName,
                style = MonoTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val facts = listOfNotNull(app.versionName?.let { "version $it" }, "System app".takeIf { app.isSystemApp })
            if (facts.isNotEmpty()) {
                Text(
                    facts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(AppIcons.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}
