package com.aicontrolcenter.app.ui.approvals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.icons.Approval
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.ChevronRight
import com.aicontrolcenter.app.ui.icons.Key
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.tools.RiskLevel

/**
 * [text] as the owner must see it: exactly, never cut short, with every marker that stands for
 * a hidden or misleading character drawn in the attention colour on a tinted ground, so a "⏎"
 * or "⟨U+202E⟩" can never pass for part of a file name or a message.
 */
@Composable
fun PreviewTextView(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val markColor = appColors.attention
    val shown = remember(text, markColor) { previewAnnotated(text, markColor) }
    // Always left to right: a value that starts with right-to-left letters cannot flip or reorder the whole line.
    Text(shown, style = style.copy(textDirection = TextDirection.Ltr), color = color, modifier = modifier)
}

/** [text] with its [previewMarks] highlighted in [markColor]. */
fun previewAnnotated(text: String, markColor: Color): AnnotatedString {
    val marks = previewMarks(text)
    if (marks.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var at = 0
        for (range in marks) {
            append(text.substring(at, range.first))
            withStyle(SpanStyle(color = markColor, background = markColor.copy(alpha = 0.16f), fontWeight = FontWeight.SemiBold)) {
                append(text.substring(range.first, range.last + 1))
            }
            at = range.last + 1
        }
        append(text.substring(at))
    }
}

/** The icon of a request's kind. */
val OwnerRequestKind.icon get() = if (this == OwnerRequestKind.APPROVAL) AppIcons.Approval else AppIcons.Key

/** The glyph of a risk: a tick for reading, a pencil for changes, a warning for what is hard to undo. */
val RiskLevel.icon: ImageVector
    get() = when (this) {
        RiskLevel.SAFE -> AppIcons.StatusReady
        RiskLevel.MODIFYING -> AppIcons.Edit
        RiskLevel.CONSEQUENTIAL -> AppIcons.StatusAttention
    }

/** A risk as a chip, in the one set of risk words ([toRiskLabel]). */
@Composable
fun RiskChip(risk: RiskLevel, modifier: Modifier = Modifier) {
    val label = risk.toRiskLabel()
    StatusChip(label.text, label.tone, modifier, icon = risk.icon)
}

/** Marks a request whose text holds a hidden character ([RequestUi.hiddenWarning]). */
@Composable
fun HiddenCharactersChip(modifier: Modifier = Modifier) {
    StatusChip("Hidden characters", StatusTone.UNAVAILABLE, modifier, icon = AppIcons.StatusAttention)
}

/**
 * One request in a list, a chat or a task: who asks, the exact summary, its risk, where it
 * stands, and a button to the Approval screen, where the answer is given. Nothing here answers it.
 * A closed request is labelled by its kind in a quiet tone, so it never reads as still needing the owner.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RequestCard(request: RequestUi, onOpen: () -> Unit, modifier: Modifier = Modifier, showAgent: Boolean = true) {
    AppCard(
        modifier = modifier,
        onClick = onOpen,
        borderColor = if (request.isOpen) appColors.attention.copy(alpha = 0.55f) else appColors.hairline,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (request.isOpen) {
                    StatusChip(request.title, request.status.tone, icon = request.kind.icon)
                } else {
                    StatusChip(request.title, StatusTone.DISABLED, icon = request.kind.icon)
                    StatusChip(request.status.text, request.status.tone)
                }
                RiskChip(request.riskLevel)
                if (request.hiddenWarning != null) HiddenCharactersChip()
                Text(request.timing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showAgent) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    AgentAvatar(request.agent.name, request.agent.colorIndex, size = 28.dp)
                    Text(
                        request.agent.name + (request.toolTitle?.let { " · $it" } ?: " · asks for “${request.permission}”"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            PreviewTextView(request.summary, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurface)
            request.askedAgain?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = appColors.attention) }
            Row(Modifier, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    request.dataGoesTo,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (request.dataLeavesPhone) appColors.cloud else appColors.onDevice,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpen) {
                    Text(if (request.isOpen) "Review" else "Details")
                    Icon(AppIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** The requests a task or objective is waiting on, each with the way to its Approval screen. */
@Composable
fun WaitingForYou(requests: List<RequestUi>, onOpen: (RequestId) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader("Waiting for you · ${requests.size}")
        requests.forEach { request -> RequestCard(request, onOpen = { onOpen(request.id) }) }
    }
}
