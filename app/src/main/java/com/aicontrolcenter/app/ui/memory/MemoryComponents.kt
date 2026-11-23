package com.aicontrolcenter.app.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.NeutralChip
import com.aicontrolcenter.app.ui.components.TintedChip
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Groups
import com.aicontrolcenter.app.ui.icons.Schedule
import com.aicontrolcenter.app.ui.icons.Tasks
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.memory.MemoryKind
import kotlin.math.roundToInt

/** The glyph of a scope: a lock for private memory, people for a shared space, a checklist for task memory. */
val ScopeKind.icon: ImageVector
    get() = when (this) {
        ScopeKind.PRIVATE -> AppIcons.Lock
        ScopeKind.SHARED -> AppIcons.Groups
        ScopeKind.TASK -> AppIcons.Tasks
    }

/** Whose memory this is, as a pill in the scope's accent. The glyph says the kind, so colour is never the only cue. */
@Composable
fun ScopeChip(scope: ScopeUi, modifier: Modifier = Modifier) {
    TintedChip(
        label = scope.label,
        color = appColors.agentAccent(scope.colorIndex),
        icon = scope.kind.icon,
        modifier = modifier.semantics { contentDescription = scope.description },
    )
}

@Composable
fun KindChip(kind: MemoryKind, modifier: Modifier = Modifier) {
    NeutralChip(kind.label, modifier = modifier)
}

/**
 * Importance as five short bars, read aloud in words. Bars rather than a number because the
 * owner compares importance at a glance across a list; the exact value is on the detail screen.
 */
@Composable
fun ImportanceMeter(importance: Double, modifier: Modifier = Modifier) {
    val filled = (importance.coerceIn(0.0, 1.0) * BARS).roundToInt().coerceIn(if (importance > 0) 1 else 0, BARS)
    val on = MaterialTheme.colorScheme.tertiary
    val off = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = importanceLabel(importance) },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(BARS) { index ->
            Box(
                Modifier
                    .width(4.dp)
                    .height((6 + index * 2).dp)
                    .background(if (index < filled) on else off, RoundedCornerShape(1.dp)),
            )
        }
    }
}

private const val BARS = 5

/** One memory in a list. */
@Immutable
data class MemoryRowUi(
    val id: MemoryId,
    val title: String,
    /** The start of the content, for recognising a memory without opening it. */
    val preview: String,
    val scope: ScopeUi,
    val kind: MemoryKind,
    val importance: Double,
    /** When it last changed, compact: "14:05", "Yesterday 09:12", "3 Nov, 18:40". */
    val date: String,
    val shortTerm: Boolean,
    /** How well it matched a search, 0..1; null while browsing. */
    val relevance: Double? = null,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryRowCard(row: MemoryRowUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(row.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (row.preview.isNotEmpty()) {
            Text(
                row.preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            ScopeChip(row.scope)
            KindChip(row.kind)
            if (row.shortTerm) NeutralChip("Short-term", icon = AppIcons.Schedule)
            ImportanceMeter(row.importance, Modifier.padding(start = 2.dp))
            row.relevance?.let { score ->
                Text(
                    "${(score * 100).roundToInt()}% match",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
