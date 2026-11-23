package com.aicontrolcenter.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors

/**
 * Asks for one short piece of text in place, as a card in the page rather than a dialog: the
 * keyboard pushes the page up instead of covering a floating window, and what the owner was
 * looking at stays in view.
 */
@Composable
fun InlineTextEntry(
    title: String,
    label: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    explanation: String? = null,
    placeholder: String? = null,
    initial: String = "",
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AppCard(modifier = modifier.fillMaxWidth(), borderColor = appColors.hairlineStrong) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            AppTextField(value = text, onValueChange = { text = it }, label = label, placeholder = placeholder)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, androidx.compose.ui.Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmLabel) }
            }
        }
    }
}
