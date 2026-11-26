package com.aicontrolcenter.app.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.ChevronRight
import com.aicontrolcenter.app.ui.icons.Stop
import com.aicontrolcenter.app.ui.tasks.markdown.MarkdownText
import com.aicontrolcenter.app.ui.theme.OverlineTextStyle
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.TaskKind

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskHeader(
    task: TaskDetailUi,
    isBusy: Boolean,
    onOpenObjective: (TaskId) -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    /** The status chip's glyph when it should not be the tone's own: the kind of request the task waits on. */
    statusIcon: ImageVector? = null,
) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(
                "Objective: ${task.objectiveTitle}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClickLabel = "Open the objective") { onOpenObjective(task.rootId) }.padding(vertical = 4.dp),
            )
            Text(task.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                StatusChip(task.status.text, task.status.tone, icon = statusIcon ?: task.status.tone.icon)
                if (task.attempt > 1) StatusChip("Attempt ${task.attempt}", StatusTone.ATTENTION, icon = AppIcons.Refresh)
                if (task.revisionRound > 0) StatusChip("Revision ${task.revisionRound}", StatusTone.WAITING, icon = AppIcons.Edit)
            }
            task.liveActivity?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = appColors.working)
                    Text(it, style = MaterialTheme.typography.bodySmall, color = appColors.working)
                }
            }
            task.statusDetail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            task.error?.let { InfoBanner(text = it, tone = StatusTone.UNAVAILABLE) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                task.assignee?.let { AgentAvatar(it.name, it.colorIndex, size = 36.dp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(task.assignee?.name ?: "Not assigned", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        buildString {
                            append("Asked by ").append(task.requestedBy)
                            if (task.delegatedBy.isNotEmpty()) append(" · delegated through ").append(task.delegatedBy.joinToString(" → "))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                listOfNotNull(task.timing, task.usage).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Goal 1: what this task's model answers cost so far, with what a token is and that it is an estimate (D3A-4).
            task.cost?.let { cost ->
                Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(cost.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(cost.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (task.canRetry || task.canCancel) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (task.canRetry) {
                        Button(onClick = onRetry, enabled = !isBusy) {
                            Icon(AppIcons.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Text("Run again", modifier = Modifier.padding(start = Spacing.sm))
                        }
                    }
                    if (task.canCancel) {
                        OutlinedButton(onClick = onCancel, enabled = !isBusy) {
                            Icon(AppIcons.Stop, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Text("Cancel task", modifier = Modifier.padding(start = Spacing.sm))
                        }
                    }
                }
            }
            task.retryNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** What was asked, and how to tell it is done. Long briefs are collapsed. */
@Composable
internal fun TaskInstructions(task: TaskDetailUi) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflows by rememberSaveable { mutableStateOf(false) }
    Column {
        SectionHeader(if (task.kind == TaskKind.REVIEW) "What to check" else "Instructions")
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    task.instructions,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 8,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                )
                if (overflows || expanded) {
                    Text(
                        if (expanded) "Show less" else "Show all",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp),
                    )
                }
                if (task.criteria.isNotEmpty()) {
                    RowDivider()
                    Text("DONE WHEN", style = OverlineTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    task.criteria.forEach { criterion ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Icon(AppIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                            Text(criterion, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

/** The tasks this one is connected to: what it checks or was delegated by, what it waits for, what hangs under it. */
@Composable
internal fun TaskLinks(task: TaskDetailUi, onOpenTask: (TaskId) -> Unit) {
    Column {
        SectionHeader("Related tasks")
        AppCard(contentPadding = PaddingValues(0.dp)) {
            val links = buildList {
                task.parent?.let { add((if (task.kind == TaskKind.REVIEW) "Reviews" else "Delegated by") to it) }
                task.dependsOn.forEach { add("Waits for" to it) }
                task.children.forEach { add((if (it.kind == TaskKind.REVIEW) "Reviewed in" else "Delegated") to it) }
            }
            links.forEachIndexed { index, (relation, link) ->
                if (index > 0) RowDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${link.title}") { onOpenTask(link.id) }
                        .heightIn(min = 56.dp).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(relation, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(link.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    StatusChip(link.status.text, link.status.tone)
                    Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun TaskResult(task: TaskDetailUi) {
    Column {
        SectionHeader("Result")
        AppCard {
            val result = task.result
            if (result == null) {
                Text(
                    if (task.status.tone == StatusTone.WORKING || task.status.tone == StatusTone.WAITING) "No result yet." else "This task produced no result.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                MarkdownText(result, textStyle = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Every verdict on this task, oldest first: the reviewer, the decision, the summary and what must change. */
@Composable
internal fun TaskReviews(reviews: List<ReviewUi>) {
    Column {
        SectionHeader("Reviews · ${reviews.size}")
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            reviews.forEach { review ->
                AppCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            review.reviewer?.let { AgentAvatar(it.name, it.colorIndex, size = 24.dp) }
                            Text(review.reviewer?.name ?: "Reviewer", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            Text(review.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusChip(review.status.text, review.status.tone)
                        Text(review.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        review.issues.forEachIndexed { index, issue ->
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium, color = appColors.attention)
                                Text(issue, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}
