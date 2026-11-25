package com.aicontrolcenter.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors

/**
 * The safe-start banner (rule 10, Tier B): the reason's `ownerText`, "Interrupted work waits for you", and "Back to
 * normal" (`SafeStartControl.leave`). At the very top of the Dashboard while safe start is on; nothing otherwise.
 */
@Composable
fun SafeStartBanner(
    extras: DashboardExtrasViewModel,
    @Suppress("UNUSED_PARAMETER") navigation: DashboardNavigation,
    modifier: Modifier = Modifier,
) {
    val state by extras.uiState.collectAsStateWithLifecycle()
    val safeStart = state.safeStart ?: return
    SafeStartContent(safeStart, onBackToNormal = extras::leaveSafeStart, modifier = modifier)
}

@Composable
internal fun SafeStartContent(safeStart: SafeStartUi, onBackToNormal: () -> Unit, modifier: Modifier = Modifier) {
    val accent = appColors.status(StatusTone.ATTENTION)
    AppCard(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        containerColor = accent.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = accent.copy(alpha = 0.45f),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(StatusTone.ATTENTION.icon, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    "Safe start is on",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                Text(safeStart.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Interrupted work waits for you: nothing starts again by itself until you choose. You can still open " +
                        "each objective and resume it yourself.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(
            onClick = onBackToNormal,
            enabled = !safeStart.leaving,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.md).heightIn(min = 48.dp),
        ) { Text("Back to normal") }
        Text(
            "Back to normal lets interrupted work resume by itself again at the next start, if Settings allows it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}
