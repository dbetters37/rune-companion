package com.aicontrolcenter.app.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.KeyValueRow
import com.aicontrolcenter.app.ui.components.NeutralChip
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Delegation
import com.aicontrolcenter.app.ui.icons.Description
import com.aicontrolcenter.app.ui.icons.Review
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.tasks.TaskKind

/**
 * One step of the plan. Planned work carries its number; reviews and delegated work sit under
 * the task they belong to, indented. Badges say what is not obvious: which steps it waits for,
 * a second attempt, a revision round, and the latest review verdict.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlanRow(row: PlanRowUi, onClick: () -> Unit) {
    val nested = row.depth > 0
    AppCard(
        onClick = onClick,
        modifier = Modifier.padding(start = (row.depth * 20).dp),
        containerColor = if (nested) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            StepMarker(row)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    row.title,
                    style = if (nested) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    row.assignee?.let {
                        AgentAvatar(it.name, it.colorIndex, size = 20.dp)
                        Text(it.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                    StatusChip(row.status.text, row.status.tone)
                }
                val badges = row.dependsOn.isNotEmpty() || row.attempt > 1 || row.revisionRound > 0 || row.verdict != null || (row.needsReview && row.kind == TaskKind.WORK)
                if (badges) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (row.dependsOn.isNotEmpty()) NeutralChip("After " + row.dependsOn.joinToString(", "))
                        if (row.attempt > 1) StatusChip("Attempt ${row.attempt}", StatusTone.ATTENTION, icon = AppIcons.Refresh)
                        if (row.revisionRound > 0) StatusChip("Revision ${row.revisionRound}", StatusTone.WAITING, icon = AppIcons.Edit)
                        val verdict = row.verdict
                        if (verdict != null) {
                            StatusChip(if (verdict.rounds > 1) "${verdict.status.text} · ${verdict.rounds} reviews" else verdict.status.text, verdict.status.tone, icon = AppIcons.Review)
                        } else if (row.needsReview && row.kind == TaskKind.WORK) {
                            NeutralChip("To be reviewed", icon = AppIcons.Review)
                        }
                    }
                }
                row.statusDetail?.takeIf { row.status.tone != StatusTone.READY }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun StepMarker(row: PlanRowUi) {
    val tone = row.status.tone
    val ring = appColors.status(tone)
    Box(
        Modifier.size(28.dp).background(ring.copy(alpha = if (appColors.isDark) 0.16f else 0.12f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val number = row.number
        when {
            number != null -> Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = ring)
            else -> Icon(
                when (row.kind) {
                    TaskKind.REVIEW -> AppIcons.Review
                    TaskKind.SYNTHESIS -> AppIcons.Description
                    else -> AppIcons.Delegation
                },
                contentDescription = null,
                tint = ring,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** How much of each limit is used. Limits keep a runaway team from looping or spending without end. */
@Composable
internal fun LimitsCard(limits: LimitsUi) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            KeyValueRow("Tasks", "${limits.tasksUsed} of ${limits.maxTasks}")
            KeyValueRow("Messages", "${limits.messagesUsed} of ${limits.maxMessages}")
            KeyValueRow("Time limit", "${limits.timeLimitMinutes} min" + (limits.endsAt?.let { " · ends $it" } ?: ""))
            Text(
                "The Director stops planning, messaging or working when a limit is reached and writes the result from what exists. Change the limits in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}
