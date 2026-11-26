package com.aicontrolcenter.app.ui.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.MemoryBrain
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors

/** The tag of the list, for tests. */
const val TEAM_LIST_TAG = "team-list"

/**
 * The same picture as a list (works well with TalkBack and large text): "You" first, then the Director, then every agent
 * by what it does (waiting for you first, then working, waiting for a teammate, …, free), then the spaces. Each row says
 * its state in words, its task, and whom it dealt with; tapping it opens the same sheet as the picture.
 */
internal fun LazyListScope.teamListItems(graph: TeamGraph, actions: PictureActions) {
    val members = listOfNotNull(graph.owner, graph.director) + graph.ring.sortedBy { it.state.ordinal }
    item(key = "people-heading") { ListHeading("The team") }
    items(members, key = { "node-${it.id}" }) { node -> MemberRow(node, graph, actions) }
    if (graph.spaces.isNotEmpty()) {
        item(key = "spaces-heading") { ListHeading("Shared spaces") }
        items(graph.spaces, key = { "node-${it.id}" }) { node -> MemberRow(node, graph, actions) }
    }
}

@Composable
private fun ListHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs).semantics { heading() },
    )
}

/** One member: face, name and role, the state in words (with its glyph), what it is about, its task and connections. */
@Composable
private fun MemberRow(node: TeamNode, graph: TeamGraph, actions: PictureActions) {
    val connections = TeamDescriptions.connectionsLine(node.id, graph)
    val custom = buildList {
        node.requestIds.firstOrNull()?.let { id ->
            val question = id in node.questionIds
            add(CustomAccessibilityAction(if (question) "Answer the question" else "Open the request") { actions.onOpenRequest(id, question); true })
        }
        TeamDescriptions.linkRows(node.id, graph).take(6).forEach { row ->
            add(CustomAccessibilityAction("Show messages with ${row.otherName}") { actions.onOpenLink(row.key); true })
        }
    }
    AppCard(
        onClick = { actions.onOpenNode(node.id) },
        modifier = Modifier.padding(vertical = 4.dp).semantics { customActions = custom },
        borderColor = if (node.state == NodeState.WAITING_FOR_YOU) appColors.attention.copy(alpha = 0.55f) else appColors.hairline,
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.Top) {
            ListFace(node)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    listOfNotNull(node.name, node.roleLabel?.takeIf { node.kind != TeamNodeKind.SPACE && it != node.name }).joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                StateLine(node.tone, node.stateText)
                node.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
                node.taskTitle?.let { Text("Task: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (node.kind == TeamNodeKind.SPACE) {
                    Text("Can be read by ${TeamDescriptions.names(node.readers)}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                connections?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun ListFace(node: TeamNode) {
    when (node.kind) {
        TeamNodeKind.OWNER -> Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(AppIcons.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        TeamNodeKind.SPACE -> Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(AppIcons.MemoryBrain, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        else -> AgentAvatar(node.name, node.colorIndex, size = 40.dp, status = node.tone, dimmed = node.dimmed)
    }
}

/** A state in words with its glyph (never a colour alone), wrapping at large text instead of being cut. */
@Composable
internal fun StateLine(tone: com.aicontrolcenter.app.ui.theme.StatusTone, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StateGlyph(tone)
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
