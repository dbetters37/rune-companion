package com.aicontrolcenter.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.SwitchRow
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Schedule
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone

/**
 * Settings › Routines and folder pick-up (WP10c): the two master switches, each saying in plain words what it does and that
 * work then runs while nobody watches (switching on asks once more), how many routines and rules exist, and the links to
 * the lists ([onOpenRoutines], [onOpenPickUpRules]). A slot of `SettingsScreen` (WP8 places it); a part whose port is null
 * stays hidden.
 */
@Composable
fun RoutinesSection(
    viewModel: RoutinesSettingsViewModel,
    onOpenRoutines: () -> Unit,
    onOpenPickUpRules: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RoutinesSection(
        state = state,
        onRequest = viewModel::request,
        onConfirm = viewModel::confirm,
        onCancel = viewModel::cancel,
        onOpenRoutines = onOpenRoutines,
        onOpenPickUpRules = onOpenPickUpRules,
        modifier = modifier,
    )
}

@Composable
fun RoutinesSection(
    state: RoutinesSettingsUiState,
    onRequest: (MasterSwitch, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onOpenRoutines: () -> Unit,
    onOpenPickUpRules: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.isLoading || (!state.routinesAvailable && !state.pickUpAvailable)) return
    Column(modifier = modifier) {
        SectionHeader(ROUTINES_SECTION_TITLE)
        AppCard {
            Text(
                "Both are off until you switch them on. While one is on, the app does that work by itself, also while nobody " +
                    "is looking at the phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.problem?.let { InfoBanner(it, modifier = Modifier.padding(top = Spacing.sm), tone = StatusTone.UNAVAILABLE) }
            if (state.routinesAvailable) {
                SwitchRow(
                    title = "Routines",
                    description = RoutinesSettingsViewModel.ROUTINES_TEXT,
                    checked = state.routinesOn,
                    onCheckedChange = { onRequest(MasterSwitch.ROUTINES, it) },
                    enabled = !state.isSaving,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
                Text(state.routinesLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onOpenRoutines, modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm).heightIn(min = 48.dp)) {
                    Text("Open routines")
                }
            }
            if (state.routinesAvailable && state.pickUpAvailable) RowDivider(Modifier.padding(vertical = Spacing.md))
            if (state.pickUpAvailable) {
                SwitchRow(
                    title = "Folder pick-up",
                    description = RoutinesSettingsViewModel.PICK_UP_TEXT,
                    checked = state.pickUpOn,
                    onCheckedChange = { onRequest(MasterSwitch.PICK_UP, it) },
                    enabled = !state.isSaving,
                )
                Text(state.pickUpLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onOpenPickUpRules, modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm).heightIn(min = 48.dp)) {
                    Text("Open folder pick-up")
                }
            }
        }
    }
    when (state.confirming) {
        MasterSwitch.ROUTINES -> ConfirmDialog(
            title = "Switch routines on?",
            message = RoutinesSettingsViewModel.CONFIRM_ROUTINES,
            confirmLabel = "Switch on",
            icon = AppIcons.Schedule,
            cautionary = true,
            guarded = true,
            onConfirm = onConfirm,
            onDismiss = onCancel,
        )
        MasterSwitch.PICK_UP -> ConfirmDialog(
            title = "Switch folder pick-up on?",
            message = RoutinesSettingsViewModel.CONFIRM_PICK_UP,
            confirmLabel = "Switch on",
            icon = AppIcons.Schedule,
            cautionary = true,
            guarded = true,
            onConfirm = onConfirm,
            onDismiss = onCancel,
        )
        null -> Unit
    }
}

/** The section's heading. */
const val ROUTINES_SECTION_TITLE: String = "Routines and folder pick-up"
