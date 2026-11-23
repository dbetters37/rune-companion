package com.aicontrolcenter.app.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.NeutralChip
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SIMULATOR_LABEL
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.SwitchRow
import com.aicontrolcenter.app.ui.components.TintedChip
import com.aicontrolcenter.app.ui.components.explanation
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.components.label
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Key
import com.aicontrolcenter.app.ui.icons.Provider
import com.aicontrolcenter.app.ui.icons.Sparkle
import com.aicontrolcenter.app.ui.theme.MonoTextStyle
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId

class ProviderDetailActions(
    val onBack: () -> Unit,
    val onEdit: () -> Unit,
    val onCheckNow: () -> Unit,
    val onRefreshModels: () -> Unit,
    val onSetEnabled: (Boolean) -> Unit,
    val onRemove: () -> Unit,
    val onAddModel: (String) -> Unit,
    val onOpenModel: (ModelId) -> Unit,
    val onSetModelEnabled: (ModelId, Boolean) -> Unit,
    val onMessageShown: () -> Unit,
)

@Composable
fun ProviderDetailRoute(
    providerId: ProviderId,
    onBack: () -> Unit,
    onEdit: (ProviderId) -> Unit,
    onOpenModel: (ModelId) -> Unit,
) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel { ProviderDetailViewModel(providerId, graph.registryDirectory, graph.providerManager, graph.clock) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.removed) { if (state.removed) onBack() }
    ProviderDetailScreen(
        state = state,
        actions = ProviderDetailActions(
            onBack = onBack,
            onEdit = { onEdit(providerId) },
            onCheckNow = viewModel::checkNow,
            onRefreshModels = viewModel::refreshModels,
            onSetEnabled = viewModel::setEnabled,
            onRemove = viewModel::remove,
            onAddModel = viewModel::addModel,
            onOpenModel = onOpenModel,
            onSetModelEnabled = viewModel::setModelEnabled,
            onMessageShown = viewModel::messageShown,
        ),
    )
}

@Composable
fun ProviderDetailScreen(state: ProviderDetailUiState, actions: ProviderDetailActions) {
    val provider = state.provider
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var addingModel by rememberSaveable { mutableStateOf(false) }

    ScreenScaffold(
        title = provider?.name ?: "Provider",
        onBack = actions.onBack,
        actions = {
            if (provider != null && !provider.row.isSimulator) {
                IconButton(onClick = actions.onEdit) { Icon(AppIcons.Edit, contentDescription = "Edit provider") }
            }
        },
        message = state.message,
        onMessageShown = actions.onMessageShown,
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            provider == null -> EmptyState(
                icon = AppIcons.Provider,
                message = "This provider is no longer registered.",
                actionLabel = "Go back",
                onAction = actions.onBack,
                modifier = Modifier.padding(padding),
            )
            else -> Column(
                modifier = Modifier
                    .padding(padding)
                    .readableWidth()
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                ProviderHeader(provider, actions)
                if (provider.row.isSimulator) {
                    InfoBanner(
                        title = SIMULATOR_LABEL,
                        text = "It answers offline with labelled, scripted replies, so you can try agents, tools and chat " +
                            "before connecting a real model. It is built in and cannot be removed; disable it to keep agents off it.",
                        tone = StatusTone.ATTENTION,
                        icon = AppIcons.Sparkle,
                    )
                }
                if (provider.blockedByLocalOnly) {
                    InfoBanner(
                        title = "Blocked by local-only mode",
                        text = "${provider.name} would send data off this device, so it is not contacted and its models cannot " +
                            "be used until local-only mode is turned off in Settings.",
                        tone = StatusTone.ATTENTION,
                    )
                }
                ConnectionActions(state, actions)
                ModelsSection(provider, actions, addingModel = addingModel, onAddingModelChange = { addingModel = it })
                if (!provider.row.isSimulator) {
                    ManageProvider(onEdit = actions.onEdit, onRemove = { confirmRemove = true })
                }
            }
        }
    }

    if (confirmRemove && provider != null) {
        ConfirmDialog(
            title = "Remove ${provider.name}?",
            message = removalMessage(provider),
            confirmLabel = "Remove",
            destructive = true,
            icon = AppIcons.Delete,
            onConfirm = {
                confirmRemove = false
                actions.onRemove()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

/** Says exactly who loses what, by name, before anything is removed. */
internal fun removalMessage(provider: ProviderDetailUi): String = buildString {
    append("Its ${plural(provider.models.size, "model")}")
    append(if (provider.row.keyStored == true) " and its stored API key are removed from this phone. " else " are removed from this phone. ")
    val dependents = provider.dependents
    if (dependents.leftWithoutModel.isNotEmpty()) {
        append(namesSentence(dependents.leftWithoutModel)).append(" would be left without a model and could not run until you assign another one. ")
    }
    if (dependents.fallingBack.isNotEmpty()) {
        append(namesSentence(dependents.fallingBack)).append(" would switch to a fallback model. ")
    }
    if (dependents.isEmpty) append("No agent uses its models. ")
    append("This cannot be undone.")
}.trim()

private fun namesSentence(names: List<String>): String = when (names.size) {
    1 -> names.single()
    2 -> "${names[0]} and ${names[1]}"
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderHeader(provider: ProviderDetailUi, actions: ProviderDetailActions) {
    val row = provider.row
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(
                if (row.isSimulator) AppIcons.Sparkle else AppIcons.Provider,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(row.kindLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        row.address?.let {
            Text(it, style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.sm))
        }
        FlowRow(
            modifier = Modifier.padding(top = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusChip(row.status.text, row.status.tone)
            TintedChip(row.locality.label, appColors.locality(row.locality), icon = row.locality.icon)
            row.keyNote?.let { note ->
                if (row.keyMissing) TintedChip(note, appColors.attention, icon = AppIcons.Key) else NeutralChip(note, icon = AppIcons.Key)
            }
        }
        Text(
            row.locality.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        row.statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = appColors.status(row.status.tone), modifier = Modifier.padding(top = Spacing.sm))
        }
        Text(row.lastChecked, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
        RowDivider(Modifier.padding(top = Spacing.md))
        SwitchRow(
            title = "Enabled",
            description = if (row.enabled) "Agents can use its models." else "Disabled: no agent can use its models, and it is not checked.",
            checked = row.enabled,
            onCheckedChange = actions.onSetEnabled,
        )
    }
}

@Composable
private fun ConnectionActions(state: ProviderDetailUiState, actions: ProviderDetailActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Button(
                onClick = actions.onCheckNow,
                enabled = !state.isChecking,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                if (state.isChecking) {
                    CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(AppIcons.StatusReady, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                }
                Text(if (state.isChecking) "Checking…" else "Check now", modifier = Modifier.padding(start = 8.dp), maxLines = 1)
            }
            OutlinedButton(
                onClick = actions.onRefreshModels,
                enabled = !state.isRefreshing,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                if (state.isRefreshing) {
                    CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
                } else {
                    Icon(AppIcons.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                }
                Text(if (state.isRefreshing) "Refreshing…" else "Refresh models", modifier = Modifier.padding(start = 8.dp), maxLines = 1)
            }
        }
        state.lastCheck?.let { InfoBanner(text = it) }
        state.lastSync?.let { SyncReportCard(it) }
    }
}

@Composable
private fun SyncReportCard(report: SyncReportUi) {
    AppCard {
        Text("Model list refreshed", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(report.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        listOf("Added" to report.added, "Updated" to report.updated, "No longer listed" to report.missing)
            .filter { it.second.isNotEmpty() }
            .forEach { (title, names) ->
                Text(
                    "$title: ${names.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
        if (report.missing.isNotEmpty()) {
            Text(
                "Models no longer listed stay registered, marked unavailable, so agents that use them say why they cannot run.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

@Composable
private fun ModelsSection(
    provider: ProviderDetailUi,
    actions: ProviderDetailActions,
    addingModel: Boolean,
    onAddingModelChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader(
            "Models · ${provider.models.size}",
            actionLabel = if (provider.row.isSimulator || addingModel) null else "Add model",
            onAction = { onAddingModelChange(true) },
        )
        if (addingModel) {
            AddModelCard(
                providerName = provider.name,
                onAdd = {
                    actions.onAddModel(it)
                    onAddingModelChange(false)
                },
                onDismiss = { onAddingModelChange(false) },
            )
        }
        if (provider.models.isEmpty()) {
            InfoBanner(text = "No models yet. Refresh the list, or add a model by the name the provider expects.")
        }
        provider.models.forEach { model ->
            ModelCard(model, onClick = { actions.onOpenModel(model.id) }, onSetEnabled = { actions.onSetModelEnabled(model.id, it) })
        }
    }
}

@Composable
private fun ManageProvider(onEdit: () -> Unit, onRemove: () -> Unit) {
    Column {
        SectionHeader("Manage")
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(AppIcons.Edit, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text("Edit name, address, key or headers", modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = onRemove,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(AppIcons.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text("Remove provider", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
