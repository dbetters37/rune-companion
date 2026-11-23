package com.aicontrolcenter.app.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Apps
import com.aicontrolcenter.app.ui.icons.DataObject
import com.aicontrolcenter.app.ui.icons.Folder
import com.aicontrolcenter.app.ui.icons.Groups
import com.aicontrolcenter.app.ui.icons.Image
import com.aicontrolcenter.app.ui.icons.MemoryBrain
import com.aicontrolcenter.app.ui.icons.Public
import com.aicontrolcenter.app.ui.icons.Storage
import com.aicontrolcenter.app.ui.icons.TouchApp
import com.aicontrolcenter.app.ui.tasks.AgentFaceUi
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors

/** The icon of a tool family. */
val ToolFamily.icon: ImageVector
    get() = when (this) {
        ToolFamily.INFORMATION -> AppIcons.Info
        ToolFamily.TEAM -> AppIcons.Groups
        ToolFamily.MEMORY -> AppIcons.MemoryBrain
        ToolFamily.WORKSPACE -> AppIcons.Folder
        ToolFamily.FOLDERS -> AppIcons.Storage
        ToolFamily.MEDIA -> AppIcons.Image
        ToolFamily.APPS -> AppIcons.Apps
        ToolFamily.WEB -> AppIcons.Public
        ToolFamily.SCREEN -> AppIcons.TouchApp
        ToolFamily.SCRIPTS -> AppIcons.DataObject
    }

/** An agent as a chip that opens it, with its avatar. */
@Composable
fun AgentLinkChip(agent: AgentFaceUi, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onOpen,
        label = { Text(agent.name, maxLines = 1) },
        leadingIcon = { AgentAvatar(agent.name, agent.colorIndex, size = 20.dp) },
        modifier = modifier.semantics { contentDescription = "Open ${agent.name}" },
        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = appColors.hairline),
    )
}

/**
 * A filter or a single choice as a chip that reads clearly in sunlight: the selected one is a
 * solid primary fill with a tick, the others have a visible outline, and screen readers hear
 * "Selected" or "Not selected". [leadingIcon] shows while it is not selected.
 */
@Composable
fun ChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        enabled = enabled,
        leadingIcon = if (selected) {
            { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            leadingIcon
        },
        colors = FilterChipDefaults.filterChipColors(
            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        ),
        modifier = modifier.semantics { stateDescription = if (selected) "Selected" else "Not selected" },
    )
}

/**
 * One agent's access to a folder or app: its name, why the choice may not matter yet, and the
 * three levels as a single choice. Each chip names the agent for screen readers ("Read, for
 * Operator"), since every row offers the same words. [tools] names the tools the access is for
 * ("folder tools"), for the line shown when the agent has none of them switched on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentAccessRow(access: AgentAccessUi, labels: List<String>, tools: String, enabled: Boolean, onChange: (AccessLevel) -> Unit) {
    Column(Modifier.padding(vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            AgentAvatar(access.agent.name, access.agent.colorIndex, size = 28.dp)
            Column(Modifier.weight(1f)) {
                Text(access.agent.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                if (!access.hasTools) {
                    Text(
                        "${access.agent.name}'s $tools are off, so this has no effect yet. Switch them on in its editor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                access.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = appColors.attention) }
            }
        }
        FlowRow(
            modifier = Modifier.semantics { selectableGroup() },
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            AccessLevel.entries.forEach { level ->
                val label = labels[level.ordinal]
                ChoiceChip(
                    selected = access.level == level,
                    onClick = { if (access.level != level) onChange(level) },
                    label = label,
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = "$label, for ${access.agent.name}" },
                )
            }
        }
    }
}

/** A short line of secondary text. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** A row of filter chips that wraps. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) { content() }
}

/** Icon size inside chips and buttons. */
internal val SmallIcon = Modifier.size(18.dp)
