package com.aicontrolcenter.app.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.ExpandLess
import com.aicontrolcenter.app.ui.icons.ExpandMore
import com.aicontrolcenter.app.ui.icons.Lightbulb
import com.aicontrolcenter.app.ui.icons.Tools
import com.aicontrolcenter.app.ui.tasks.markdown.MarkdownText
import com.aicontrolcenter.app.ui.theme.MonoTextStyle
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors

/*
 * How the pieces of a transcript look: the owner's messages, the agent's replies with their
 * reasoning, tool calls and footer, and failure notices. Shared by the chat and by the read-only
 * transcript of a task, so an agent's work looks the same wherever it is shown.
 */

/** A failure or cut-off note in a transcript. */
@Composable
internal fun TranscriptNotice(item: TranscriptItem.Notice) {
    when (item.kind) {
        NoticeKind.CUT_OFF -> InfoBanner(title = "Reply cut off", text = item.text, tone = StatusTone.ATTENTION)
        NoticeKind.FAILURE -> InfoBanner(title = "The turn failed", text = item.text, tone = StatusTone.UNAVAILABLE)
    }
}

@Composable
internal fun OwnerBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 320.dp).padding(start = 48.dp).semantics(mergeDescendants = true) {
                contentDescription = "You: $text"
            },
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/** The agent's side: avatar, reasoning, text, tool calls and the footer, aligned to the start. */
@Composable
internal fun AgentTurn(
    agentName: String,
    colorIndex: Int,
    item: TranscriptItem.Agent,
    showAvatar: Boolean,
    /** Renders the reply's Markdown (task results are written in it); the chat shows replies as written. */
    markdown: Boolean = false,
) {
    AgentColumn(agentName, colorIndex, showAvatar) {
        item.reasoning?.let { ReasoningSummary(it, live = false) }
        if (item.text.isNotBlank()) AgentBubble(item.text, markdown = markdown)
        item.toolCalls.forEach { ToolCallRow(it) }
        item.footer?.let { ReplyFooter(it) }
    }
}

@Composable
internal fun AgentColumn(agentName: String, colorIndex: Int, showAvatar: Boolean, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(end = 24.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (showAvatar) AgentAvatar(agentName, colorIndex, size = 32.dp) else Spacer(Modifier.size(32.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
internal fun AgentBubble(text: String, modifier: Modifier = Modifier, markdown: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, appColors.hairline),
        modifier = modifier,
    ) {
        if (markdown) {
            MarkdownText(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), textStyle = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
internal fun ReasoningSummary(text: String, live: Boolean, answering: Boolean = false) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.animateContentSize()) {
        Row(
            modifier = Modifier
                .clickable(onClickLabel = if (expanded) "Hide reasoning" else "Show reasoning") { expanded = !expanded }
                .heightIn(min = 40.dp)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(AppIcons.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(
                when {
                    live && !answering -> "Thinking…"
                    live -> "Reasoning so far"
                    else -> "Reasoning summary"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        if (expanded) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
internal fun ToolCallRow(call: ToolCallUi) {
    var expanded by rememberSaveable(call.callId) { mutableStateOf(false) }
    val (statusText, tone) = when (call.outcome) {
        ToolOutcomeUi.Running -> "Running" to StatusTone.WORKING
        is ToolOutcomeUi.Succeeded -> "Done" to StatusTone.READY
        is ToolOutcomeUi.Failed -> "Failed" to StatusTone.ATTENTION
        ToolOutcomeUi.NoResult -> "Not run" to StatusTone.DISABLED
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, appColors.hairline),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(
            Modifier
                .clickable(onClickLabel = if (expanded) "Collapse tool call" else "Expand tool call") { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(AppIcons.Tools, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                Text(
                    call.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (call.outcome == ToolOutcomeUi.Running) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = appColors.working)
                } else {
                    Icon(tone.icon, contentDescription = null, tint = appColors.status(tone), modifier = Modifier.size(16.dp))
                }
                Text(statusText, style = MaterialTheme.typography.labelMedium, color = appColors.status(tone))
                Icon(if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            Text(call.argumentsSummary, style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
            val result = when (val outcome = call.outcome) {
                is ToolOutcomeUi.Succeeded -> outcome.content
                is ToolOutcomeUi.Failed -> outcome.content
                ToolOutcomeUi.NoResult -> "The turn ended before this tool ran."
                ToolOutcomeUi.Running -> null
            }
            if (result != null) {
                Text(
                    result,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (call.outcome is ToolOutcomeUi.Failed) appColors.attention else MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) {
                Text("Arguments sent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Text(call.argumentsJson.ifEmpty { "Not recorded" }, style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
internal fun ReplyFooter(footer: ReplyFooterUi) {
    val parts = listOfNotNull(footer.answeredBy?.let { "Answered by $it" }, footer.usage)
    if (parts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(start = 4.dp)) {
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (footer.differs) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(StatusTone.ATTENTION.icon, contentDescription = null, tint = appColors.attention, modifier = Modifier.size(14.dp))
                Text(
                    "Not the assigned model (${footer.assignedModel})",
                    style = MaterialTheme.typography.bodySmall,
                    color = appColors.attention,
                )
            }
        }
    }
}
