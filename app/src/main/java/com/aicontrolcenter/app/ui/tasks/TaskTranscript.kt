package com.aicontrolcenter.app.ui.tasks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.chat.AgentTurn
import com.aicontrolcenter.app.ui.chat.TranscriptItem
import com.aicontrolcenter.app.ui.chat.TranscriptNotice
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Description
import com.aicontrolcenter.app.ui.icons.ExpandLess
import com.aicontrolcenter.app.ui.icons.ExpandMore
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.framework.orchestration.OrchestrationProtocol
import com.aicontrolcenter.app.ui.components.calmAnimateContentSize

/**
 * A task's transcript, read-only, drawn with the chat's own renderers. The "user" side of a task
 * conversation is not the owner: it is the brief the app wrote for the agent, so it is shown as
 * a brief rather than as something the owner said.
 */
internal fun LazyListScope.taskTranscript(items: List<TranscriptItem>, agent: AgentFaceUi?) {
    itemsIndexed(items, key = { _, item -> "transcript-${item.key}" }) { index, item ->
        Column(Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs)) {
            when (item) {
                is TranscriptItem.Owner -> BriefCard(item.text)
                is TranscriptItem.Agent -> AgentTurn(
                    agentName = agent?.name ?: "Agent",
                    colorIndex = agent?.colorIndex ?: 0,
                    item = item,
                    showAvatar = items.getOrNull(index - 1) !is TranscriptItem.Agent,
                    markdown = true,
                )
                is TranscriptItem.Notice -> TranscriptNotice(item)
                // A task's requests are shown at the top of the task screen, not in its transcript.
                is TranscriptItem.Request -> Unit
            }
        }
    }
}

/** The brief, without the protocol marker (plumbing for the agent), collapsed to a few lines. */
@Composable
private fun BriefCard(text: String) {
    var expanded by rememberSaveable(text.hashCode()) { mutableStateOf(false) }
    val shown = text.lineSequence().filterNot { it.trim() in OrchestrationProtocol.ALL }.joinToString("\n").trim()
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, appColors.hairline),
        modifier = Modifier.fillMaxWidth().calmAnimateContentSize(),
    ) {
        Column(
            Modifier
                .clickable(onClickLabel = if (expanded) "Collapse brief" else "Show the whole brief") { expanded = !expanded }
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(AppIcons.Description, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                Text("Brief written by the app for this task", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Icon(if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            Text(
                shown,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
