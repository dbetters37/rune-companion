package com.aicontrolcenter.app.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.theme.Spacing

/** Fixed, so the Read and Write columns line up whether or not a chip shows its tick. */
private val AccessChipWidth = 88.dp

/** Below this row width the two chips move under the name instead of squeezing it. */
private val SingleLineMinWidth = 340.dp

/**
 * One row of read and write access: [leading] (who or what) and two chips. On a wide row they
 * share one line; on a narrow phone the chips go on a second line, under the name, so a name
 * is never cut to a few letters.
 * The descriptions are what screen readers say for each chip ("Writer may read").
 */
@Composable
fun AccessRow(
    canRead: Boolean,
    canWrite: Boolean,
    readDescription: String,
    writeDescription: String,
    onReadChange: (Boolean) -> Unit,
    onWriteChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = 56.dp,
    leading: @Composable RowScope.() -> Unit,
) {
    val chips: @Composable () -> Unit = {
        AccessChip("Read", canRead, readDescription) { onReadChange(!canRead) }
        AccessChip("Write", canWrite, writeDescription) { onWriteChange(!canWrite) }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= SingleLineMinWidth) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = minHeight).padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                leading()
                chips()
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    leading()
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) { chips() }
            }
        }
    }
}

@Composable
private fun AccessChip(label: String, selected: Boolean, description: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        modifier = Modifier.width(AccessChipWidth).semantics { contentDescription = description },
    )
}
