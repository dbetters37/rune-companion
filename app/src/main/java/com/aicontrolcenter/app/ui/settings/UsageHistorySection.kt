package com.aicontrolcenter.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.RowText
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.theme.Spacing

/**
 * Settings › Usage history (WP10a): how long the usage numbers are kept, and clearing them, which never clears today
 * (today's limits count today's numbers). A slot of `SettingsScreen`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsageHistorySection(
    viewModel: UsageHistoryViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier) {
        SectionHeader("Usage history")
        AppCard {
            RowText(
                title = "Keep usage numbers for",
                description = "How many answers and tokens each model and agent used, hour by hour, for Activity › Usage and " +
                    "cost. Numbers only: no messages. They never leave this phone and are not in the backup.",
            )
            FlowRow(
                modifier = Modifier.padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                state.choices.forEach { choice ->
                    FilterChip(
                        selected = state.retentionDays == choice.days,
                        onClick = { viewModel.setRetentionDays(choice.days) },
                        label = { Text(choice.label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            RowDivider(Modifier.padding(vertical = Spacing.sm))
            OutlinedButton(
                onClick = { confirmClear = true },
                enabled = !state.clearing,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(AppIcons.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text(if (state.clearing) "Clearing…" else "Clear usage history", modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                "Today's numbers always stay: today's limits count them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            state.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = Spacing.sm).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Clear the usage history?",
            message = "The answers, tokens and costs recorded before today are deleted from this phone. Today's numbers " +
                "stay, so today's limits are not reset. This cannot be undone.",
            confirmLabel = "Clear history",
            destructive = true,
            icon = AppIcons.Delete,
            onConfirm = {
                confirmClear = false
                viewModel.clearHistory()
            },
            onDismiss = { confirmClear = false },
        )
    }
}
