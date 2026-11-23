package com.aicontrolcenter.app.ui.tasks

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.ExpandLess
import com.aicontrolcenter.app.ui.icons.ExpandMore
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors

/** The face of a participant: an agent's avatar, or a neutral disc with a glyph for the owner and the system. */
@Composable
internal fun ParticipantAvatar(participant: ParticipantUi, size: Dp = 32.dp) {
    when (participant) {
        is ParticipantUi.Agent -> AgentAvatar(participant.face.name, participant.face.colorIndex, size = size)
        else -> Box(
            Modifier.size(size).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (participant is ParticipantUi.Owner) AppIcons.Person else AppIcons.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

/**
 * One message: who to whom, what kind, when, and the text collapsed to two lines (tap to read
 * it all). Message text is shown as data, exactly as the agent wrote it.
 */
@Composable
internal fun MessageRow(message: MessageUi, modifier: Modifier = Modifier, showTask: Boolean = true) {
    var expanded by rememberSaveable(message.id.value) { mutableStateOf(false) }
    var overflows by rememberSaveable(message.id.value) { mutableStateOf(false) }
    val spoken = "${message.type.label} from ${message.from.name} to ${message.to.name} at ${message.time}: ${message.body}"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (overflows || expanded) {
                    Modifier.clickable(onClickLabel = if (expanded) "Collapse message" else "Expand message") { expanded = !expanded }
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(vertical = Spacing.md)
            .animateContentSize(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        ParticipantAvatar(message.from)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "${message.from.name} → ${message.to.name}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(message.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val tint = message.type.tone?.let { appColors.status(it) } ?: MaterialTheme.colorScheme.secondary
                Icon(message.type.icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                Text(
                    buildString {
                        append(message.type.label)
                        if (showTask) message.taskTitle?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                message.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
            )
            if (overflows || expanded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (expanded) "Show less" else "Show all",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** Narrows the conversation to one kind of message; only kinds that occur are offered. */
@Composable
internal fun MessageFilters(counts: Map<MessageFilter, Int>, selected: MessageFilter, onSelect: (MessageFilter) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        counts.forEach { (filter, count) ->
            FilterChip(selected = filter == selected, onClick = { onSelect(filter) }, label = { Text("${filter.label} · $count") })
        }
    }
}
