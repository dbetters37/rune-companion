package com.aicontrolcenter.app.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Schedule
import com.aicontrolcenter.app.ui.theme.LocalCalmMotion
import com.aicontrolcenter.app.ui.theme.Spacing

/**
 * The step a save, an opening or a restore is at (Tier B; WP10b): its name, a bar (filled as far as the work went, or
 * moving while the key is made, which reports no measure), what the owner should do ("keep the app open"), and Cancel
 * while the work may be stopped. Under Calm (or with Android's animations off) nothing moves: the key step shows a clock
 * and words instead of a moving bar.
 */
@Composable
internal fun BackupStepCard(
    step: BackupStepUi,
    keepOpen: String,
    cancellable: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val calm = LocalCalmMotion.current
    AppCard(modifier = modifier) {
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(step.step, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            val fraction = step.fraction
            when {
                fraction != null -> LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = step.spoken },
                )
                calm -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Icon(AppIcons.Schedule, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(STILL_WORKING, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = step.spoken })
            }
            if (fraction == null) {
                Text(KEY_STEP_TAKES_TIME, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(keepOpen, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        if (cancellable) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.md).heightIn(min = 48.dp),
            ) { Text("Cancel") }
        }
    }
}

/** A list of short sentences, each read on its own. */
@Composable
internal fun NoteLines(lines: List<String>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        lines.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** While the key is made (no measure: seconds on a phone, minutes on a slow one). */
const val KEY_STEP_TAKES_TIME: String =
    "This takes a few seconds on most phones and can take a few minutes on slower ones. The rest of the app keeps working."

/** Under Calm, in place of a moving bar. */
const val STILL_WORKING: String = "Working on it…"
