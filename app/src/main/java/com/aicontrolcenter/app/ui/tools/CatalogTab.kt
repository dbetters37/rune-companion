package com.aicontrolcenter.app.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.approvals.RiskChip
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.TintedChip
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Cloud
import com.aicontrolcenter.app.ui.icons.ExpandLess
import com.aicontrolcenter.app.ui.icons.ExpandMore
import com.aicontrolcenter.app.ui.icons.Folder
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.AgentId

/**
 * Every tool by family. A family opens to list its tools; each says what it does, its risk,
 * whether it sends data off the phone, the permission it needs, and the agents that have it
 * switched on (each a link to the agent). The Workspace family is also the way into the
 * workspace's files.
 */
@Composable
fun CatalogTab(state: ToolsCatalogUiState, onOpenAgent: (AgentId) -> Unit, onOpenWorkspace: () -> Unit) {
    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    LazyColumn(
        modifier = Modifier.readableWidth().fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.md, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "intro") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    "${plural(state.toolCount, "tool")} agents can be given. Switching a tool on for an agent, in its editor, does not give " +
                        "it the permission the tool needs: the agent asks you the first time, and buying, sending, deleting or running a " +
                        "command asks you every time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.families, key = { it.family.name }) { family ->
            FamilyCard(
                family = family,
                isExpanded = family.family.name in expanded,
                onToggle = { open -> expanded = if (open) expanded + family.family.name else expanded - family.family.name },
                onOpenAgent = onOpenAgent,
                onOpenWorkspace = onOpenWorkspace.takeIf { family.family == ToolFamily.WORKSPACE },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FamilyCard(
    family: CatalogFamilyUi,
    isExpanded: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenAgent: (AgentId) -> Unit,
    onOpenWorkspace: (() -> Unit)?,
) {
    AppCard(contentPadding = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (isExpanded) "Hide tools" else "Show tools") { onToggle(!isExpanded) }
                .semantics { stateDescription = if (isExpanded) "Expanded" else "Collapsed" }
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(family.family.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(family.family.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Caption(
                    plural(family.tools.size, "tool") + " · " +
                        if (family.agentCount == 0) "not switched on for any agent" else "switched on for ${plural(family.agentCount, "agent")}",
                )
            }
            Icon(if (isExpanded) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Caption(family.family.description)
            family.blockedBecause?.let { InfoBanner(it, tone = StatusTone.DISABLED) }
            onOpenWorkspace?.let {
                OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(AppIcons.Folder, contentDescription = null, modifier = SmallIcon)
                    Text("Open the workspace", modifier = Modifier.padding(start = Spacing.sm))
                }
            }
            if (isExpanded) {
                family.tools.forEach { tool ->
                    RowDivider(Modifier.padding(vertical = Spacing.xs))
                    ToolRow(tool, onOpenAgent)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolRow(tool: CatalogToolUi, onOpenAgent: (AgentId) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(tool.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(tool.plainWords, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            RiskChip(tool.riskLevel)
            if (tool.sendsDataOffPhone) TintedChip("Sends data off this phone", appColors.cloud, icon = AppIcons.Cloud)
        }
        Caption("Permission: ${tool.permission} · ${tool.permissionNote}")
        if (tool.agents.isEmpty()) {
            Caption("Not switched on for any agent.")
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                tool.agents.forEach { agent -> AgentLinkChip(agent, onOpen = { agent.id?.let(onOpenAgent) }) }
            }
        }
    }
}
