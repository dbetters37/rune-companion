package com.aicontrolcenter.app.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextAlign
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.icons.Sparkle
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.app.ui.components.ActivityEventRow
import com.aicontrolcenter.app.ui.components.ActivityRowUi
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.ChevronRight
import com.aicontrolcenter.app.ui.icons.Scan
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors

/** The "needs attention" list. Each row leads to the place where the problem is fixed. */
@Composable
internal fun AttentionSection(
    items: List<AttentionItem>,
    onOpen: (AttentionTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader(if (items.isEmpty()) "Needs attention" else "Needs attention · ${items.size}")
        AppCard(contentPadding = PaddingValues(0.dp)) {
            if (items.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Icon(
                        StatusTone.READY.icon,
                        contentDescription = null,
                        tint = appColors.ready,
                        modifier = Modifier.size(22.dp),
                    )
                    // The phone's own items are listed right under this card ("On this phone"), so this line speaks only
                    // for the agents, models and apps (handoff-WP10a.md item 2.5).
                    Text(
                        "Your agents, models and apps need nothing from you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            items.forEachIndexed { index, item ->
                if (index > 0) RowDivider()
                AttentionRow(item, onClick = { onOpen(item.target) })
            }
        }
    }
}

@Composable
private fun AttentionRow(item: AttentionItem, onClick: () -> Unit) {
    AttentionRow(item.tone, item.title, item.detail, onClick, clickLabel = "Fix")
}

/**
 * One attention row: a status icon (named for screen readers, never colour alone), a title and a detail; tappable when
 * [onClick] is set. Shared by the registry's list and the phone's own items ([PhoneAttention]).
 */
@Composable
internal fun AttentionRow(tone: StatusTone, title: String, detail: String, onClick: (() -> Unit)?, clickLabel: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = clickLabel, onClick = onClick) else Modifier.semantics(mergeDescendants = true) {})
            .heightIn(min = 64.dp)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(
            imageVector = tone.icon,
            contentDescription = when (tone) {
                StatusTone.UNAVAILABLE -> "Blocking"
                StatusTone.WAITING -> "Waiting"
                else -> "Attention"
            },
            tint = appColors.status(tone),
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClick != null) Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The two things the owner most often starts from the dashboard. */
@Composable
internal fun QuickActionsSection(
    isScanning: Boolean,
    onNewAgent: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader("Quick actions")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Button(
                onClick = onNewAgent,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("New agent", modifier = Modifier.padding(start = 8.dp), maxLines = 1)
            }
            OutlinedButton(
                onClick = onScan,
                enabled = !isScanning,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
                } else {
                    Icon(AppIcons.Scan, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                }
                Text(
                    text = if (isScanning) "Scanning…" else "Scan for AI apps",
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

/** The newest few events, with a way to the full log. */
@Composable
internal fun RecentActivitySection(
    rows: List<ActivityRowUi>,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader("Latest activity", actionLabel = "See all", onAction = onSeeAll)
        AppCard(contentPadding = PaddingValues(vertical = Spacing.xs)) {
            if (rows.isEmpty()) {
                Text(
                    text = "Nothing has been recorded yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.lg),
                )
            }
            rows.forEachIndexed { index, row ->
                if (index > 0) RowDivider(Modifier.padding(start = 64.dp))
                ActivityEventRow(row)
            }
        }
    }
}

/** The common first step, one tap away: give every agent without a model something to think with. */
@Composable
internal fun QuickStartCard(
    quickStart: QuickStartUi,
    isAssigning: Boolean,
    onConnectProvider: () -> Unit,
    onAssignModel: () -> Unit,
    onTrySimulator: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = quickStart.agentsWithoutModel.size
    AppCard(
        modifier = modifier,
        containerColor = appColors.info.copy(alpha = 0.08f).compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = appColors.info.copy(alpha = 0.35f),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(AppIcons.Sparkle, contentDescription = null, tint = appColors.info, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    if (count == 1) "${quickStart.agentsWithoutModel.single()} has no model" else "$count agents have no model",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (quickStart.hasRealModels) {
                        "They cannot run until each has one. Give them one of your models, or try them with the simulator."
                    } else {
                        "Agents need a model to think with. Connect a model provider, or try everything first with the " +
                            "built-in simulator, which is not a real model."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Button(
                onClick = if (quickStart.hasRealModels) onAssignModel else onConnectProvider,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(
                    if (quickStart.hasRealModels) "Assign a model" else "Connect a model provider",
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }
            if (quickStart.simulatorAvailable) {
                OutlinedButton(
                    onClick = onTrySimulator,
                    enabled = !isAssigning,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) { Text("Try with the simulator", maxLines = 2, textAlign = TextAlign.Center) }
            }
        }
    }
}

/**
 * Agents at work right now: each with the objective's task it is on (or its chat), and the step.
 * A row opens the task when there is one, otherwise the agent.
 */
@Composable
internal fun WorkingNowSection(
    working: List<WorkingAgentUi>,
    onOpenAgent: (AgentId) -> Unit,
    onOpenTask: (TaskId) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader(if (working.isEmpty()) "Working now" else "Working now · ${working.size}")
        AppCard(contentPadding = PaddingValues(0.dp)) {
            if (working.isEmpty()) {
                Text(
                    "No agent is working right now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.lg),
                )
            }
            working.forEachIndexed { index, agent ->
                if (index > 0) RowDivider()
                val taskId = agent.taskId
                val tone = if (agent.waiting) StatusTone.WAITING else StatusTone.WORKING
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = if (taskId != null) "Open ${agent.taskTitle}" else "Open ${agent.name}") {
                            if (taskId != null) onOpenTask(taskId) else onOpenAgent(agent.agentId)
                        }
                        .heightIn(min = 64.dp)
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    AgentAvatar(agent.name, agent.colorIndex, size = 36.dp, status = tone)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(agent.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        agent.taskTitle?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = appColors.status(tone))
                            Text(agent.detail, style = MaterialTheme.typography.bodySmall, color = appColors.status(tone), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
