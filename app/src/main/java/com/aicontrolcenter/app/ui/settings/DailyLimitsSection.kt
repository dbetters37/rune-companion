package com.aicontrolcenter.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicontrolcenter.app.ui.activity.LIMITS_STOP_WORK
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.LIMIT_PAUSES_OBJECTIVES
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Tune
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors

/**
 * Settings › Daily limits (WP10a): today's use against each limit in one line each, what happens when one is reached,
 * and [onOpenDailyLimits] to change them on the Daily limits screen. A slot of `SettingsScreen` (WP8 places it).
 */
@Composable
fun DailyLimitsSection(
    viewModel: DailyLimitsViewModel,
    onOpenDailyLimits: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier = modifier) {
        SectionHeader("Daily limits")
        AppCard {
            Text(
                "Agents stop asking models off this phone for the rest of the day when one of these is reached. " +
                    LIMIT_PAUSES_OBJECTIVES,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.reached?.let {
                InfoBanner(it, modifier = Modifier.padding(top = Spacing.md), tone = StatusTone.ATTENTION, title = "Today's limit is reached")
            }
            Column(Modifier.padding(top = Spacing.sm)) {
                state.lines.forEachIndexed { index, line ->
                    if (index > 0) RowDivider()
                    LimitLine(line)
                }
            }
            Text(
                LIMITS_STOP_WORK,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            OutlinedButton(
                onClick = onOpenDailyLimits,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.md).heightIn(min = 48.dp),
            ) {
                Icon(AppIcons.Tune, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Change daily limits", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** "Answers · 37 of 200 today", with a warning icon (not colour alone) when it is reached. Read as one phrase. */
@Composable
internal fun LimitLine(line: LimitLineUi, modifier: Modifier = Modifier) {
    val spoken = line.title + ": " + line.text + if (line.reached) ". Reached." else ""
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = Spacing.sm).clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            line.title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            line.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (line.reached) appColors.status(StatusTone.ATTENTION) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.65f),
        )
        if (line.reached) {
            Icon(StatusTone.ATTENTION.icon, contentDescription = null, tint = appColors.status(StatusTone.ATTENTION), modifier = Modifier.size(20.dp))
        }
    }
}
