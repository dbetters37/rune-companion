package com.aicontrolcenter.app.ui.tasks

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.AvatarStack
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.StackedAvatar
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Egress
import com.aicontrolcenter.app.ui.icons.OnDevice
import com.aicontrolcenter.app.ui.icons.Pause
import com.aicontrolcenter.app.ui.icons.Stop
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors

/** The owner's controls over an objective. */
class ObjectiveControls(
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onCancel: () -> Unit,
    val onDelete: () -> Unit,
    val onRunAgain: () -> Unit,
)

/**
 * The top of the objective screen: status and time, the objective, what the Director made of
 * it, progress, who is involved, what left the phone, and the controls.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ObjectiveHeader(header: ObjectiveHeaderUi, egress: EgressUi, isBusy: Boolean, controls: ObjectiveControls) {
    AppCard(borderColor = if (header.isLive) appColors.working.copy(alpha = 0.5f) else appColors.hairline) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                StatusChip(header.status.text, header.status.tone)
                if (header.isLive) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = appColors.working)
                Text(header.elapsed, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            CollapsibleText(header.text, MaterialTheme.typography.titleMedium, collapsedLines = 4, label = "objective")
            Interpretation(header)
            Progress(header)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                AvatarStack(header.agents.map { StackedAvatar(it.name, it.colorIndex) }, size = 26.dp)
                Text(
                    header.options,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            EgressLine(egress)
            header.error?.let { InfoBanner(text = it, tone = StatusTone.UNAVAILABLE) }
                ?: header.statusDetail?.takeIf { !header.status.tone.isCalm() }?.let { InfoBanner(text = it, tone = header.status.tone) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (header.canPause) ControlButton("Pause", AppIcons.Pause, isBusy, tonal = true, onClick = controls.onPause)
                if (header.canResume) ControlButton("Resume", AppIcons.Play, isBusy, primary = true, onClick = controls.onResume)
                if (header.canRunAgain) ControlButton("Run again", AppIcons.Refresh, isBusy, primary = true, onClick = controls.onRunAgain)
                if (header.canCancel) ControlButton("Cancel", AppIcons.Stop, isBusy, onClick = controls.onCancel)
                if (header.canDelete) ControlButton("Delete", AppIcons.Delete, isBusy, destructive = true, onClick = controls.onDelete)
            }
        }
    }
}

private fun StatusTone.isCalm() = this in CALM_TONES

@Composable
private fun Interpretation(header: ObjectiveHeaderUi) {
    val text = header.interpretation
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, appColors.hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("How the Director understood it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (text != null) {
                CollapsibleText(text, MaterialTheme.typography.bodyMedium, collapsedLines = 3, label = "interpretation")
            } else {
                Text(
                    if (header.isLive) "Reading the objective and planning the work…" else "The Director has not planned this objective.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Progress(header: ObjectiveHeaderUi) {
    val progress = header.progress
    if (progress.isPlanning) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ProgressBar(progress.fraction, finished = progress.completed == progress.planned)
        Text("${progress.completed} of ${progress.planned} tasks completed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Long text collapsed to [collapsedLines]; tapping it shows the rest (and hides it again). */
@Composable
private fun CollapsibleText(text: String, style: TextStyle, collapsedLines: Int, label: String) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by rememberSaveable(text) { mutableStateOf(false) }
    // Only text that does not fit is tappable, so a short objective announces no action.
    val toggle = if (overflows || expanded) {
        Modifier.clickable(onClickLabel = if (expanded) "Show less of the $label" else "Show all of the $label") { expanded = !expanded }
    } else {
        Modifier
    }
    Column(Modifier.animateContentSize().then(toggle)) {
        Text(
            text,
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            Text(if (expanded) "Show less" else "Show all", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Always said, either way: whether anything of this objective left the phone, and to where. */
@Composable
private fun EgressLine(egress: EgressUi) {
    val away = egress.transfers > 0
    val color = if (away) appColors.attention else appColors.onDevice
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Icon(if (away) AppIcons.Egress else AppIcons.OnDevice, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(
            if (away) {
                "${egress.transfers} model call${if (egress.transfers == 1) "" else "s"} left this phone, to ${egress.destinations.joinToString(", ")}. See the Log."
            } else {
                "Nothing has left this phone for this objective."
            },
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

@Composable
private fun ControlButton(
    label: String,
    icon: ImageVector,
    isBusy: Boolean,
    onClick: () -> Unit,
    primary: Boolean = false,
    tonal: Boolean = false,
    destructive: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Text(label, modifier = Modifier.padding(start = Spacing.sm))
    }
    when {
        primary -> Button(onClick = onClick, enabled = !isBusy) { content() }
        tonal -> FilledTonalButton(onClick = onClick, enabled = !isBusy) { content() }
        destructive -> OutlinedButton(
            onClick = onClick,
            enabled = !isBusy,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { content() }
        else -> OutlinedButton(onClick = onClick, enabled = !isBusy) { content() }
    }
}

/** The limits the orchestrator hit, in plain words; shown above the tabs so they are never missed. */
@Composable
internal fun LimitsReachedBanner(reached: List<String>) {
    InfoBanner(
        title = if (reached.size == 1) "A limit was reached" else "${reached.size} limits were reached",
        text = reached.joinToString("\n"),
        tone = StatusTone.ATTENTION,
    )
}
