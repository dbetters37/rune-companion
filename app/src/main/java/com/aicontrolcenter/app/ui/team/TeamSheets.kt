package com.aicontrolcenter.app.ui.team

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.approvals.RequestCard
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.ChevronRight
import com.aicontrolcenter.app.ui.tasks.MessageRow
import com.aicontrolcenter.app.ui.tasks.icon
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.OwnerRequestKind

/** Where a sheet's buttons lead. */
class SheetActions(
    val onClose: () -> Unit,
    val onOpenLink: (String) -> Unit,
    val onOpenAgent: (AgentId) -> Unit,
    val onOpenTask: (TaskId) -> Unit,
    val onOpenObjective: (TaskId) -> Unit,
    val onOpenRequest: (RequestId, Boolean) -> Unit,
    val onOpenSpace: (SpaceId) -> Unit,
)

/** The open sheet: a member, a link, or "You". Nothing in a sheet answers a request: it opens the request's own screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamSheet(sheet: TeamSheetUi, actions: SheetActions) {
    ModalBottomSheet(
        onDismissRequest = actions.onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            when (sheet) {
                is TeamSheetUi.Member -> MemberSheet(sheet, actions)
                is TeamSheetUi.Link -> LinkSheet(sheet)
                is TeamSheetUi.You -> YouSheet(sheet, actions)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemberSheet(sheet: TeamSheetUi.Member, actions: SheetActions) {
    val node = sheet.node
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (node.isAgent) AgentAvatar(node.name, node.colorIndex, size = 48.dp, status = node.tone, dimmed = node.dimmed)
        Column(Modifier.weight(1f)) {
            Text(node.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
            node.roleLabel?.takeIf { it != node.name }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    StateLine(node.tone, node.stateText)
    node.detail?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
    node.taskTitle?.let { Text("Task: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
    node.modelLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (node.kind == TeamNodeKind.SPACE) {
        Text("Can be read by ${TeamDescriptions.names(node.readers)}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text("Can be written by ${TeamDescriptions.names(node.writers)}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        if (sheet.sharedBy.isNotEmpty()) {
            Text("Shared into it here: ${TeamDescriptions.names(sheet.sharedBy)}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            "Who may read or write a space is set on each agent's page; this view never changes it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    sheet.requests.forEach { request ->
        RequestCard(request, onOpen = { actions.onOpenRequest(request.id, request.kind == OwnerRequestKind.QUESTION) }, showAgent = false)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        node.agentId?.takeIf { node.kind != TeamNodeKind.FORMER_AGENT }?.let { id -> SheetButton("Open agent") { actions.onOpenAgent(id) } }
        node.taskId?.takeIf { node.taskTitle != null }?.let { id -> SheetButton("Open task") { actions.onOpenTask(id) } }
        node.rootId?.let { id -> SheetButton("Open objective") { actions.onOpenObjective(id) } }
        node.spaceId?.let { id -> SheetButton("Open space") { actions.onOpenSpace(id) } }
    }
    if (sheet.links.isNotEmpty()) {
        SheetHeading("Who it dealt with")
        sheet.links.forEach { row -> LinkRow(row, actions.onOpenLink) }
    }
    if (sheet.messages.isNotEmpty()) {
        SheetHeading("Latest messages")
        sheet.messages.forEach { MessageHeaderRow(it) }
    }
}

@Composable
private fun LinkSheet(sheet: TeamSheetUi.Link) {
    Text(sheet.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
    Text(
        "Drawn as a ${sheet.link.kind.style.legend} with this sign:",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Icon(sheet.link.kind.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    val messages = sheet.messages
    if (messages == null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Reading the messages…", style = MaterialTheme.typography.bodyMedium)
        }
        sheet.previews.forEach { MessageHeaderRow(it) }
    } else {
        Text(
            "Every message on this line, in order, as the team wrote it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        messages.forEach { MessageRow(it, showTask = true) }
    }
}

@Composable
private fun YouSheet(sheet: TeamSheetUi.You, actions: SheetActions) {
    Text("You", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    SheetHeading("Waiting for you")
    if (sheet.requests.isEmpty()) {
        Text(TeamWords.NOTHING_WAITS + ".", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text(
            "Oldest first. Each opens on its own screen, where you answer it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        sheet.requests.forEach { request ->
            RequestCard(request, onOpen = { actions.onOpenRequest(request.id, request.kind == OwnerRequestKind.QUESTION) })
        }
    }
    if (sheet.news.isNotEmpty()) {
        SheetHeading("What the team and the app told you")
        sheet.news.forEach { MessageHeaderRow(it) }
    }
    if (sheet.links.isNotEmpty()) {
        SheetHeading("Who you dealt with")
        sheet.links.forEach { row -> LinkRow(row, actions.onOpenLink) }
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = Spacing.sm).semantics { heading() },
    )
}

@Composable
private fun SheetButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
}

/** One link of a member: its kind's glyph, what happened along it, and the way to its messages. */
@Composable
internal fun LinkRow(row: LinkRowUi, onOpen: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = "Show the messages", role = Role.Button) { onOpen(row.key) }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(row.kind.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(row.words, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(TeamWords.times(row.count, "message", "messages"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    RowDivider()
}

/** One message's header: its kind, who to whom, when, and its first words as the team wrote them. */
@Composable
private fun MessageHeaderRow(row: HeaderRowUi) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Icon(row.type.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${row.label} · ${row.from} to ${row.to} · ${row.time}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(row.preview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
