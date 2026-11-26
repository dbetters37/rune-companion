package com.aicontrolcenter.app.ui.tasks

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
import com.aicontrolcenter.app.ui.icons.Hub
import com.aicontrolcenter.app.ui.icons.Insights
import com.aicontrolcenter.app.ui.icons.Schedule
import com.aicontrolcenter.app.ui.icons.Speed
import com.aicontrolcenter.app.ui.icons.Storage
import com.aicontrolcenter.app.ui.icons.TouchApp
import com.aicontrolcenter.app.ui.icons.Tune
import com.aicontrolcenter.app.ui.components.NeutralChip
import com.aicontrolcenter.core.RoutineId
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.semantics
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.app.ui.components.calmAnimateContentSize

/** The owner's controls over an objective. */
class ObjectiveControls(
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onCancel: () -> Unit,
    val onDelete: () -> Unit,
    val onRunAgain: () -> Unit,
)

/** Where the header's links lead. */
class HeaderLinks(
    val onOpenTeam: () -> Unit = {},
    val onOpenDailyLimits: () -> Unit = {},
    val onOpenRoutine: (RoutineId) -> Unit = {},
)

/**
 * The top of the objective screen: status and time, the objective, what the Director made of
 * it, why it is not moving, progress, who is involved, the cost so far, what left the phone, and the controls.
 * [elapsed] moves on its own every second while the objective runs (P2).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ObjectiveHeader(
    header: ObjectiveHeaderUi,
    egress: EgressUi,
    isBusy: Boolean,
    controls: ObjectiveControls,
    elapsed: String = "",
    links: HeaderLinks = HeaderLinks(),
) {
    AppCard(borderColor = if (header.isLive) appColors.working.copy(alpha = 0.5f) else appColors.hairline) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                StatusChip(header.status.text, header.status.tone)
                if (header.isLive) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = appColors.working)
                Text(elapsed, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            RoutineLine(header, links.onOpenRoutine)
            CollapsibleText(header.text, MaterialTheme.typography.titleMedium, collapsedLines = 4, label = "objective")
            Interpretation(header)
            header.pause?.let { PauseNotice(it) }
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
            header.cost?.let { CostLine(it) }
            EgressLine(egress)
            if (header.pause == null) {
                header.error?.let { InfoBanner(text = it, tone = StatusTone.UNAVAILABLE) }
                    ?: header.statusDetail?.takeIf { !header.status.tone.isCalm() }?.let { InfoBanner(text = it, tone = header.status.tone) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (header.canPause) ControlButton("Pause", AppIcons.Pause, isBusy, tonal = true, onClick = controls.onPause)
                if (header.canResume) ControlButton("Resume", AppIcons.Play, isBusy, primary = true, onClick = controls.onResume)
                if (header.canRunAgain) ControlButton("Run again", AppIcons.Refresh, isBusy, primary = true, onClick = controls.onRunAgain)
                if (header.pause?.kind == PauseKind.DAILY_LIMIT) ControlButton("Daily limits", AppIcons.Tune, isBusy = false, onClick = links.onOpenDailyLimits)
                ControlButton("Team view", AppIcons.Hub, isBusy = false, onClick = links.onOpenTeam)
                if (header.canCancel) ControlButton("Cancel", AppIcons.Stop, isBusy, onClick = controls.onCancel)
                if (header.canDelete) ControlButton("Delete", AppIcons.Delete, isBusy, destructive = true, onClick = controls.onDelete)
            }
        }
    }
}

/** "Started by routine “Morning news”" (a link to the routine) and "Screens: not allowed" for an objective a routine started. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutineLine(header: ObjectiveHeaderUi, onOpenRoutine: (RoutineId) -> Unit) {
    if (header.routine == null && !header.screensNotAllowed) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs), itemVerticalAlignment = Alignment.CenterVertically) {
        header.routine?.let { line ->
            val id = header.routineId
            if (id != null) {
                TextButton(onClick = { onOpenRoutine(id) }, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
                    Icon(AppIcons.Schedule, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(line, modifier = Modifier.padding(start = Spacing.sm))
                }
            } else {
                Text(line, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (header.screensNotAllowed) NeutralChip("Screens: not allowed", icon = AppIcons.TouchApp)
    }
}

/** Why the objective is not moving: today's limit, the phone almost full, a provider wait. Never colour alone: the title says it. */
@Composable
private fun PauseNotice(pause: PauseNoticeUi) {
    val (tone, icon) = when (pause.kind) {
        PauseKind.DAILY_LIMIT -> StatusTone.ATTENTION to AppIcons.Speed
        PauseKind.STORAGE -> StatusTone.ATTENTION to AppIcons.Storage
        PauseKind.PROVIDER_WAIT -> StatusTone.WAITING to AppIcons.Schedule
    }
    InfoBanner(title = pause.title, text = pause.text, tone = tone, icon = icon)
}

/** What the objective's model calls came to so far, with what a token is and that money is an estimate. */
@Composable
private fun CostLine(cost: CostLineUi) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Icon(AppIcons.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(cost.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            Text(cost.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Column(Modifier.calmAnimateContentSize().then(toggle)) {
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
    val away = egress.away
    val color = if (away) appColors.attention else appColors.onDevice
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Icon(if (away) AppIcons.Egress else AppIcons.OnDevice, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(egressWords(egress), style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** The privacy line in words: from the log when it still holds the calls, from the usage rows when it no longer does (D3A-5). */
internal fun egressWords(egress: EgressUi): String {
    val answers = egress.offPhoneAnswers
    val answerWords = "$answers model answer${if (answers == 1L) "" else "s"}"
    return when {
        egress.transfers > 0 -> buildString {
            append("${egress.transfers} model call${if (egress.transfers == 1) "" else "s"} left this phone, to ${egress.destinations.joinToString(", ")}. See the Log.")
            if (answers > egress.transfers) append(" In all, $answerWords came from models off this phone; the log no longer holds the rest.")
        }
        answers > 0 -> "$answerWords went to models off this phone; the details are no longer in the log."
        else -> "Nothing has left this phone for this objective."
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
