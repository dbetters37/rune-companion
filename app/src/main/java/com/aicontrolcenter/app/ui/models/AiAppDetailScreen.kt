package com.aicontrolcenter.app.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.Accessibility
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Apps
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.registry.AiApp

/** What the app detail screen can ask for. */
class AiAppDetailActions(
    val onBack: () -> Unit,
    val onOpenApp: () -> Unit,
    val onHandOffText: (String) -> Unit,
    val onSetEnabled: (Boolean) -> Unit,
    val onSetAccessibilityAuthorized: (Boolean) -> Unit,
    val onSaveNotes: (String) -> Unit,
    val onRemove: () -> Unit,
    val onMessageShown: () -> Unit,
)

@Composable
fun AiAppDetailRoute(appId: AiAppId, onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel {
        AiAppDetailViewModel(appId, graph.aiAppRegistry, graph.aiAppLauncher, graph.activityRecorder)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.removed) { if (state.removed) onBack() }
    AiAppDetailScreen(
        state = state,
        actions = AiAppDetailActions(
            onBack = onBack,
            onOpenApp = viewModel::openApp,
            onHandOffText = viewModel::handOffText,
            onSetEnabled = viewModel::setEnabled,
            onSetAccessibilityAuthorized = viewModel::setAccessibilityAuthorized,
            onSaveNotes = viewModel::saveNotes,
            onRemove = viewModel::remove,
            onMessageShown = viewModel::messageShown,
        ),
    )
}

private enum class AppDialog { SEND_TEXT, ACCESSIBILITY_WARNING, REMOVE }

@Composable
fun AiAppDetailScreen(state: AiAppDetailUiState, actions: AiAppDetailActions) {
    val app = state.app
    var dialog by rememberSaveable { mutableStateOf<AppDialog?>(null) }

    ScreenScaffold(
        title = app?.label ?: "AI app",
        onBack = actions.onBack,
        message = state.message,
        onMessageShown = actions.onMessageShown,
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            app == null -> EmptyState(
                icon = AppIcons.Apps,
                message = "This app is no longer in the registry.",
                actionLabel = "Go back",
                onAction = actions.onBack,
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
                AppIdentityCard(app)
                PermittedSection(
                    state = state,
                    onOpenApp = actions.onOpenApp,
                    onSendText = { dialog = AppDialog.SEND_TEXT },
                )
                CapabilitiesSection(app)
                RegistrySettingsSection(
                    app = app,
                    isBusy = state.isBusy,
                    onSetEnabled = actions.onSetEnabled,
                    onToggleAccessibility = { wanted ->
                        if (wanted) dialog = AppDialog.ACCESSIBILITY_WARNING else actions.onSetAccessibilityAuthorized(false)
                    },
                )
                NotesSection(app.notes, state.isBusy, actions.onSaveNotes)
                Column {
                    SectionHeader("Registry")
                    AppCard {
                        OutlinedButton(
                            onClick = { dialog = AppDialog.REMOVE },
                            enabled = !state.isBusy,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Icon(AppIcons.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Text("Remove from registry", modifier = Modifier.padding(start = 8.dp))
                        }
                        Text(
                            "Removing only forgets the app here. It does not uninstall it or change it in any way.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.sm),
                        )
                    }
                }
            }
        }
    }

    if (app != null) {
        when (dialog) {
            AppDialog.SEND_TEXT -> SendTextDialog(
                app = app,
                onSend = { text ->
                    dialog = null
                    actions.onHandOffText(text)
                },
                onDismiss = { dialog = null },
            )
            AppDialog.ACCESSIBILITY_WARNING -> ConfirmDialog(
                title = "Allow operating ${app.label}'s screen?",
                message = "This records your permission for agents to operate ${app.label} through Android's " +
                    "accessibility service: reading what the app shows, including your conversations in it, and " +
                    "tapping and typing in it as if they were you.\n\n" +
                    "Nothing can use this permission yet. It also needs the operator service of a later version of " +
                    "this app, which you would have to switch on separately in Android settings.\n\n" +
                    "You can revoke it here at any time, and it is withdrawn automatically if the app is uninstalled.",
                confirmLabel = "Authorise",
                icon = AppIcons.Accessibility,
                onConfirm = {
                    dialog = null
                    actions.onSetAccessibilityAuthorized(true)
                },
                onDismiss = { dialog = null },
            )
            AppDialog.REMOVE -> ConfirmDialog(
                title = "Remove ${app.label} from the registry?",
                message = "Its notes and settings here are forgotten. The app itself stays installed and untouched. " +
                    "A later scan may find it again.",
                confirmLabel = "Remove",
                destructive = true,
                onConfirm = {
                    dialog = null
                    actions.onRemove()
                },
                onDismiss = { dialog = null },
            )
            null -> Unit
        }
    }
}

@Composable
private fun SendTextDialog(app: AiApp, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send text to ${app.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    "The text is handed to ${app.label}'s own screen. Whatever ${app.label} answers stays in that app: " +
                        "AI Control Center cannot read the reply.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Text to hand over") },
                    minLines = 3,
                    maxLines = 6,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSend(text) }, enabled = text.isNotBlank()) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NotesSection(savedNotes: String, isBusy: Boolean, onSave: (String) -> Unit) {
    var notes by rememberSaveable(savedNotes) { mutableStateOf(savedNotes) }
    Column {
        SectionHeader("Notes")
        AppCard {
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Your notes about this app") },
                minLines = 2,
                maxLines = 6,
            )
            TextButton(
                onClick = { onSave(notes) },
                enabled = !isBusy && notes.trim() != savedNotes,
                modifier = Modifier.align(Alignment.End).padding(top = Spacing.xs),
            ) { Text("Save notes") }
        }
    }
}
