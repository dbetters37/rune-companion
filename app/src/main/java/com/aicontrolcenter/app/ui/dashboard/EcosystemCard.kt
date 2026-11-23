package com.aicontrolcenter.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.StatTile
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Apps
import com.aicontrolcenter.app.ui.icons.ChevronRight
import com.aicontrolcenter.app.ui.icons.ModelChip
import com.aicontrolcenter.app.ui.icons.Provider
import com.aicontrolcenter.app.ui.theme.OverlineTextStyle
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.AgentId

/**
 * The top of the dashboard: how many agents can run, who the Director is and whether it can
 * run, and the three registry counts. Agents, models and AI applications are always counted
 * and labelled separately.
 */
@Composable
internal fun EcosystemSummarySection(
    summary: EcosystemSummary,
    onOpenAgents: () -> Unit,
    onOpenAgent: (AgentId) -> Unit,
    onOpenModels: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        AppCard(contentPadding = PaddingValues(0.dp)) {
            AgentAvailabilityBlock(summary, onOpenAgents)
            // An empty registry has nobody to direct; the missing Director is only worth a row once agents exist.
            if (summary.agentCount > 0) {
                RowDivider()
                DirectorRow(summary.director, onOpenAgent, onOpenAgents)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            StatTile(
                value = summary.modelCount.toString(),
                label = "Models",
                icon = AppIcons.ModelChip,
                accent = MaterialTheme.colorScheme.secondary,
                onClick = onOpenModels,
                modifier = Modifier.weight(1f),
                caption = "${summary.modelsInUse} in use",
            )
            StatTile(
                value = summary.providerCount.toString(),
                label = "Providers",
                icon = AppIcons.Provider,
                accent = MaterialTheme.colorScheme.secondary,
                onClick = onOpenProviders,
                modifier = Modifier.weight(1f),
                caption = if (summary.providerProblems > 0) {
                    if (summary.providerProblems == 1) "1 problem" else "${summary.providerProblems} problems"
                } else {
                    "${summary.providersOnline} online"
                },
                captionColor = if (summary.providerProblems > 0) appColors.attention else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatTile(
                value = summary.installedApps.toString(),
                label = "AI apps",
                icon = AppIcons.Apps,
                accent = MaterialTheme.colorScheme.tertiary,
                onClick = onOpenApps,
                modifier = Modifier.weight(1f),
                caption = "installed",
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentAvailabilityBlock(summary: EcosystemSummary, onOpenAgents: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open agents", onClick = onOpenAgents)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text("AGENTS", style = OverlineTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = if (summary.agentCount == 0) {
                "No agents yet"
            } else {
                "${summary.readyAgents} of ${plural(summary.agentCount, "agent")} ready to run"
            },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        AvailabilityBar(summary)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            AvailabilityCount(summary.readyAgents, "Ready", StatusTone.READY)
            if (summary.agentsWithoutModel > 0) {
                AvailabilityCount(summary.agentsWithoutModel, "No model yet", StatusTone.INFO)
            }
            // "Unavailable" is always shown unless the only reason is a missing model.
            if (summary.unavailableAgents > 0 || summary.agentsWithoutModel == 0) {
                AvailabilityCount(summary.unavailableAgents, "Unavailable", StatusTone.UNAVAILABLE)
            }
            AvailabilityCount(summary.disabledAgents, "Disabled", StatusTone.DISABLED)
        }
    }
}

/** Proportions of ready, model-less, unavailable and disabled agents. Decorative: the counts below say the same in words. */
@Composable
private fun AvailabilityBar(summary: EcosystemSummary) {
    val colors = appColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(
            summary.readyAgents to colors.ready,
            summary.agentsWithoutModel to colors.info,
            summary.unavailableAgents to colors.unavailable,
            summary.disabledAgents to colors.disabled,
        ).filter { it.first > 0 }.forEach { (count, color) ->
            Box(Modifier.weight(count.toFloat()).fillMaxHeight().background(color))
        }
    }
}

@Composable
private fun AvailabilityCount(count: Int, label: String, tone: StatusTone) {
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$count $label" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(tone.icon, contentDescription = null, tint = appColors.status(tone), modifier = Modifier.size(16.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun DirectorRow(director: DirectorSummary?, onOpenAgent: (AgentId) -> Unit, onOpenAgents: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = if (director == null) "Open agents" else "Open the Director",
                onClick = { if (director == null) onOpenAgents() else onOpenAgent(director.agentId) },
            )
            .heightIn(min = 72.dp)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (director == null) {
            Icon(
                StatusTone.ATTENTION.icon,
                contentDescription = null,
                tint = appColors.attention,
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "No Director designated",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Objectives will have nowhere to go until an agent is designated.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AgentAvatar(director.name, director.colorIndex, size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = director.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Designated Director",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatusChip(label = director.runLabel, tone = director.status.tone)
            }
        }
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
