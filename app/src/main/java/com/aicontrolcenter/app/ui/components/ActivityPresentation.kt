package com.aicontrolcenter.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.icons.Approval
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Egress
import com.aicontrolcenter.app.ui.icons.ErrorFilled
import com.aicontrolcenter.app.ui.icons.Extension
import com.aicontrolcenter.app.ui.icons.Forum
import com.aicontrolcenter.app.ui.icons.Layers
import com.aicontrolcenter.app.ui.icons.MemoryBrain
import com.aicontrolcenter.app.ui.icons.ModelChip
import com.aicontrolcenter.app.ui.icons.Shield
import com.aicontrolcenter.app.ui.icons.Tasks
import com.aicontrolcenter.app.ui.icons.TouchApp
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity

val ActivityType.label: String
    get() = when (this) {
        ActivityType.SYSTEM -> "System"
        ActivityType.USER_ACTION -> "Your action"
        ActivityType.REGISTRY -> "Registry"
        ActivityType.MESSAGE -> "Message"
        ActivityType.MODEL_CALL -> "Model call"
        ActivityType.TOOL_CALL -> "Tool call"
        ActivityType.PERMISSION -> "Permission"
        ActivityType.APPROVAL -> "Approval"
        ActivityType.TASK -> "Task"
        ActivityType.MEMORY -> "Memory"
        ActivityType.DATA_EGRESS -> "Data transfer"
        ActivityType.ERROR -> "Error"
    }

val ActivityType.icon: ImageVector
    get() = when (this) {
        ActivityType.SYSTEM -> AppIcons.Settings
        ActivityType.USER_ACTION -> AppIcons.TouchApp
        ActivityType.REGISTRY -> AppIcons.Layers
        ActivityType.MESSAGE -> AppIcons.Forum
        ActivityType.MODEL_CALL -> AppIcons.ModelChip
        ActivityType.TOOL_CALL -> AppIcons.Extension
        ActivityType.PERMISSION -> AppIcons.Shield
        ActivityType.APPROVAL -> AppIcons.Approval
        ActivityType.TASK -> AppIcons.Tasks
        ActivityType.MEMORY -> AppIcons.MemoryBrain
        ActivityType.DATA_EGRESS -> AppIcons.Egress
        ActivityType.ERROR -> AppIcons.ErrorFilled
    }

val Severity.label: String
    get() = when (this) {
        Severity.INFO -> "Info"
        Severity.WARNING -> "Warning"
        Severity.ERROR -> "Error"
    }

/** Informational events carry no tone; only warnings and errors are highlighted. */
val Severity.tone: StatusTone?
    get() = when (this) {
        Severity.INFO -> null
        Severity.WARNING -> StatusTone.ATTENTION
        Severity.ERROR -> StatusTone.UNAVAILABLE
    }

/** One activity event prepared for a list: everything already formatted by the view model. */
@Immutable
data class ActivityRowUi(
    val event: ActivityEvent,
    val time: String,
    /** Name of the agent that caused the event, when there is one. */
    val agentName: String? = null,
) {
    val id: String get() = event.id.value
}

/**
 * One event in a timeline: type glyph, title, detail, who caused it and when. Warnings, errors
 * and data that left the device are marked with a labelled chip, never with colour alone.
 */
@Composable
fun ActivityEventRow(row: ActivityRowUi, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val event = row.event
    val tone = event.severity.tone
    val glyphColor = tone?.let { appColors.status(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Show details", onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(event.type.icon, contentDescription = event.type.label, tint = glyphColor, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(row.time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val secondary = listOfNotNull(row.agentName, event.detail.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (secondary.isNotEmpty()) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tone != null || event.egress != null) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (tone != null) StatusChip(event.severity.label, tone)
                    event.egress?.let {
                        TintedChip("Left device · ${it.locality.label}", appColors.locality(it.locality), icon = AppIcons.Egress)
                    }
                }
            }
        }
    }
}
