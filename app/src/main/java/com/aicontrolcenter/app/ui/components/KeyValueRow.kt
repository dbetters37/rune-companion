package com.aicontrolcenter.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.theme.MonoTextStyle

/**
 * A labelled fact. [mono] is for identifiers and other technical values, which are also the
 * only place the monospace face is used.
 */
@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            text = value,
            style = if (mono) MonoTextStyle else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.58f),
        )
    }
}
