package com.aicontrolcenter.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalDensity
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
            // Not the limiter's "raise it in Settings › Daily limits": the owner is there already (D3A-16).
            state.reachedHere?.let {
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

/**
 * "Answers · 37 of 200 today", with a warning icon (not colour alone) when it is reached. Read as one phrase. With large
 * fonts or a narrow window the title sits above its value, so "Answers" never breaks inside the word (D3A-6), as the
 * Usage tiles stack.
 */
@Composable
internal fun LimitLine(line: LimitLineUi, modifier: Modifier = Modifier) {
    val spoken = line.title + ": " + line.text + if (line.reached) ". Reached." else ""
    BoxWithConstraints(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }) {
        val stacked = maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.5f
        val color = if (line.reached) appColors.status(StatusTone.ATTENTION) else MaterialTheme.colorScheme.onSurfaceVariant
        @Composable
        fun ReachedIcon() {
            if (line.reached) {
                Icon(StatusTone.ATTENTION.icon, contentDescription = null, tint = appColors.status(StatusTone.ATTENTION), modifier = Modifier.size(20.dp))
            }
        }
        if (stacked) {
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(line.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    ReachedIcon()
                }
                Text(line.text, style = MaterialTheme.typography.bodyMedium, color = color)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(line.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(0.35f))
                Text(line.text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(0.65f))
                ReachedIcon()
            }
        }
    }
}
