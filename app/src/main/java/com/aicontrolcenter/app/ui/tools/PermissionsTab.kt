package com.aicontrolcenter.app.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.NeutralChip
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Key
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.tasks.AgentFaceUi
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.GrantId

/** What the Permissions tab can ask for. */
class PermissionsActions(
    val onSelect: (AgentId?) -> Unit,
    val onRevoke: (GrantId) -> Unit,
    val onRevokeAll: (AgentId) -> Unit,
    val onOpenAgent: (AgentId) -> Unit,
)

/** Every grant still standing, per agent, with Remove and Remove all. */
@Composable
fun PermissionsTab(state: PermissionsUiState, actions: PermissionsActions) {
    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    var confirmAll by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = Modifier.readableWidth().fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.md, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "intro") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "What each agent may do on this phone, beyond what every agent may. Permissions come from your answers to " +
                        "requests, and from the Folders and Screen tabs. Removing one takes effect at once: the agent asks again " +
                        "the next time it needs it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.allowedForEveryone.isNotEmpty()) {
                    Caption("Every agent may, without asking: ${state.allowedForEveryone.joinToString(", ")}.")
                }
                AgentFilter(state.agents, state.selected, actions.onSelect)
            }
        }
        if (state.groups.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = AppIcons.Key,
                    title = "No permissions given",
                    message = "When an agent needs a permission, it asks you and waits. What you allow for longer than one time is listed here.",
                )
            }
        }
        items(state.groups, key = { it.agent.id?.value ?: it.agent.name }) { group ->
            AgentGrantsCard(
                group = group,
                isBusy = state.isBusy,
                onRevoke = actions.onRevoke,
                onRevokeAll = { confirmAll = group.agent.id?.value },
                onOpenAgent = actions.onOpenAgent,
            )
        }
    }
    val confirming = state.groups.firstOrNull { it.agent.id?.value == confirmAll }
    if (confirming != null) {
        ConfirmDialog(
            title = "Remove all of ${confirming.agent.name}'s permissions?",
            message = "${plural(confirming.grants.size, "permission")} end now, including any for a task in progress. " +
                "${confirming.agent.name} asks you again when it needs one. Its tools stay switched on.",
            confirmLabel = "Remove all",
            destructive = true,
            icon = AppIcons.Key,
            onConfirm = {
                confirmAll = null
                confirming.agent.id?.let(actions.onRevokeAll)
            },
            onDismiss = { confirmAll = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentFilter(agents: List<AgentFaceUi>, selected: AgentId?, onSelect: (AgentId?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        ChoiceChip(selected = selected == null, onClick = { onSelect(null) }, label = "All agents")
        agents.forEach { agent ->
            ChoiceChip(
                selected = selected == agent.id,
                onClick = { onSelect(if (selected == agent.id) null else agent.id) },
                label = agent.name,
                leadingIcon = { AgentAvatar(agent.name, agent.colorIndex, size = 18.dp) },
                modifier = Modifier.semantics { contentDescription = "Show ${agent.name}'s permissions" },
            )
        }
    }
}

@Composable
private fun AgentGrantsCard(
    group: AgentGrantsUi,
    isBusy: Boolean,
    onRevoke: (GrantId) -> Unit,
    onRevokeAll: () -> Unit,
    onOpenAgent: (AgentId) -> Unit,
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            AgentAvatar(group.agent.name, group.agent.colorIndex, size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text(group.agent.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Caption(if (group.grants.isEmpty()) "Nothing beyond what every agent may" else plural(group.grants.size, "permission"))
            }
            group.agent.id?.let { id -> TextButton(onClick = { onOpenAgent(id) }) { Text("Open ${group.agent.name}") } }
        }
        group.grants.forEach { grant ->
            RowDivider(Modifier.padding(vertical = Spacing.sm))
            GrantRow(grant, isBusy, onRevoke = { onRevoke(grant.id) }, agentName = group.agent.name)
        }
        if (group.grants.size > 1) {
            RowDivider(Modifier.padding(vertical = Spacing.sm))
            TextButton(
                onClick = onRevokeAll,
                enabled = !isBusy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.align(Alignment.End),
            ) { Text("Remove all") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GrantRow(grant: GrantUi, isBusy: Boolean, onRevoke: () -> Unit, agentName: String) {
    val faded = if (grant.isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(grant.permission, style = MaterialTheme.typography.bodyLarge, color = faded)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                NeutralChip(grant.scope)
                grant.target?.let { NeutralChip(it) }
            }
            Caption(grant.granted)
            grant.inactiveReason?.let { Caption(it) }
        }
        TextButton(
            onClick = onRevoke,
            enabled = !isBusy,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.semantics { contentDescription = "Remove ${grant.permission}${grant.target?.let { " ($it)" } ?: ""} for $agentName" },
        ) { Text("Remove") }
    }
}
