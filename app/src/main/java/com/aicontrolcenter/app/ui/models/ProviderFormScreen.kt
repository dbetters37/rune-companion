package com.aicontrolcenter.app.ui.models

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.AppTextField
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SecretTextField
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.SwitchRow
import com.aicontrolcenter.app.ui.components.TintedChip
import com.aicontrolcenter.app.ui.components.explanation
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.components.label
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Lightbulb
import com.aicontrolcenter.app.ui.icons.Provider
import com.aicontrolcenter.app.ui.icons.StatusDisabled
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.llm.ProviderField
import com.aicontrolcenter.core.registry.ProviderKind

/** What the provider form can ask for. */
class ProviderFormActions(
    val onNameChange: (String) -> Unit,
    val onBaseUrlChange: (String) -> Unit,
    val onApiKeyChange: (String) -> Unit,
    val onHeadersChange: (String) -> Unit,
    val onRemoveStoredKeyChange: (Boolean) -> Unit,
    val onSave: () -> Unit,
    val onClose: () -> Unit,
    /** After the set-up steps: go on to the provider's page. */
    val onFinished: (ProviderId) -> Unit,
    val onMessageShown: () -> Unit,
)

@Composable
fun ProviderFormRoute(
    presetId: String?,
    providerId: ProviderId?,
    baseUrl: String?,
    onClose: () -> Unit,
    onFinished: (ProviderId, isNew: Boolean) -> Unit,
) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel {
        ProviderFormViewModel(graph.providerManager, graph.registryDirectory, presetId, providerId, baseUrl)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProviderFormScreen(
        state = state,
        actions = ProviderFormActions(
            onNameChange = viewModel::setName,
            onBaseUrlChange = viewModel::setBaseUrl,
            onApiKeyChange = viewModel::setApiKey,
            onHeadersChange = viewModel::setHeadersText,
            onRemoveStoredKeyChange = viewModel::setRemoveStoredKey,
            onSave = viewModel::save,
            onClose = onClose,
            onFinished = { onFinished(it, providerId == null) },
            onMessageShown = viewModel::messageShown,
        ),
    )
}

@Composable
fun ProviderFormScreen(state: ProviderFormUiState, actions: ProviderFormActions) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val setup = state.setup
    val requestClose = {
        when {
            setup != null -> actions.onFinished(setup.providerId)
            state.isDirty -> confirmDiscard = true
            else -> actions.onClose()
        }
    }
    BackHandler(enabled = state.isDirty || setup != null) { requestClose() }

    ScreenScaffold(
        title = when {
            setup != null -> "Connecting ${setup.providerName}"
            state.isEditing -> "Edit provider"
            else -> state.presetTitle ?: "Add a provider"
        },
        onBack = requestClose,
        actions = {
            if (!state.isLoading && !state.notFound && setup == null) {
                TextButton(onClick = actions.onSave, enabled = !state.isSaving) { Text("Save") }
            }
        },
        message = state.message,
        onMessageShown = actions.onMessageShown,
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.notFound -> EmptyState(
                icon = AppIcons.Provider,
                message = "This provider no longer exists, so there is nothing to edit.",
                actionLabel = "Go back",
                onAction = actions.onClose,
                modifier = Modifier.padding(padding),
            )
            else -> Column(
                modifier = Modifier
                    .padding(padding)
                    .imePadding()
                    .readableWidth()
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (setup != null) {
                    SetupProgress(setup, onOpen = { actions.onFinished(setup.providerId) })
                } else {
                    ProviderFields(state, actions)
                }
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            message = if (state.isEditing) "Your edits to this provider have not been saved." else "This provider has not been added yet.",
            confirmLabel = "Discard",
            dismissLabel = "Keep editing",
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                actions.onClose()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
private fun ProviderFields(state: ProviderFormUiState, actions: ProviderFormActions) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                if (state.isEditing) state.kindLabel else listOfNotNull(state.presetTitle, state.kindLabel).distinct().joinToString(" · "),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            state.setupHint?.takeIf { it.isNotBlank() }?.let { hint ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Icon(
                        AppIcons.Lightbulb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 1.dp).size(18.dp),
                    )
                    Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    Column {
        SectionHeader("Connection")
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AppTextField(
                    value = state.name,
                    onValueChange = actions.onNameChange,
                    label = "Name",
                    errorText = state.issue(ProviderField.NAME),
                )
                if (state.showBaseUrl) {
                    AppTextField(
                        value = state.baseUrl,
                        onValueChange = actions.onBaseUrlChange,
                        label = "Base URL",
                        placeholder = "https://host/v1",
                        supportingText = "Leave empty to use the vendor's own address.".takeIf { state.kind.addressOptional },
                        errorText = state.issue(ProviderField.BASE_URL),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    TintedChip(state.locality.label, appColors.locality(state.locality), icon = state.locality.icon)
                    Text(
                        "Where prompts go with this address. " + state.locality.explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    Column {
        SectionHeader("API key")
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SecretTextField(
                    value = state.apiKey,
                    onValueChange = actions.onApiKeyChange,
                    label = if (state.isEditing && state.keyStored) "New API key" else "API key",
                    placeholder = if (state.isEditing && state.keyStored) "Leave empty to keep the stored key" else null,
                    supportingText = keyHelp(state),
                    errorText = state.issue(ProviderField.API_KEY),
                    enabled = !state.removeStoredKey,
                )
                if (state.isEditing && state.keyStored) {
                    RowDivider()
                    SwitchRow(
                        title = "Remove stored key",
                        description = "Deletes the key from this phone when you save.",
                        checked = state.removeStoredKey,
                        onCheckedChange = actions.onRemoveStoredKeyChange,
                    )
                }
            }
        }
    }

    Column {
        SectionHeader("Extra headers (optional)")
        AppCard {
            AppTextField(
                value = state.headersText,
                onValueChange = actions.onHeadersChange,
                label = "Extra headers",
                placeholder = "X-Title: AI Control Center",
                supportingText = "One per line, as Name: value. Stored in plain form, so never put a key here.",
                errorText = state.issue(ProviderField.HEADERS),
                singleLine = false,
                minLines = 2,
            )
        }
    }

    Button(
        onClick = actions.onSave,
        enabled = !state.isSaving,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        if (state.isSaving) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text(if (state.isEditing) "Save and check" else "Add and connect")
        }
    }
    Text(
        "After saving, the app checks the connection and fetches the model list.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Vendor kinds know their own endpoint; an address is only entered to go through a proxy. */
private val ProviderKind.addressOptional: Boolean
    get() = this == ProviderKind.ANTHROPIC || this == ProviderKind.GEMINI

private fun keyHelp(state: ProviderFormUiState): String = when {
    state.removeStoredKey -> "The stored key will be deleted when you save."
    state.isEditing && state.keyStored -> "A key is stored. It is never shown again; type a new one only to replace it."
    state.needsApiKey -> "Required by this service. Stored encrypted on this phone and never shown again."
    else -> "Only if the server asks for one. Stored encrypted on this phone and never shown again."
}

@Composable
private fun SetupProgress(setup: SetupProgressUi, onOpen: () -> Unit) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            setup.steps.forEachIndexed { index, step ->
                if (index > 0) RowDivider()
                SetupStepRow(step)
            }
        }
    }
    if (setup.finished) {
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Open provider") }
        if (!setup.succeeded) {
            Text(
                "The provider is saved. Its page has Check now and Refresh models for when the problem is fixed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SetupStepRow(step: SetupStepUi) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (step.state) {
                SetupStepState.RUNNING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                SetupStepState.DONE -> Icon(AppIcons.StatusReady, contentDescription = "Done", tint = appColors.ready)
                SetupStepState.FAILED -> Icon(AppIcons.StatusAttention, contentDescription = "Failed", tint = appColors.status(StatusTone.ATTENTION))
                SetupStepState.SKIPPED, SetupStepState.WAITING ->
                    Icon(AppIcons.StatusDisabled, contentDescription = if (step.state == SetupStepState.SKIPPED) "Skipped" else "Waiting", tint = appColors.disabled)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(step.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            step.detail?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
