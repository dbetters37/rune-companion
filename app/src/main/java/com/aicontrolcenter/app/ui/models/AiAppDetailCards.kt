package com.aicontrolcenter.app.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Egress
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors

/** Shown for a name-based guess until the owner says it really is an AI application. */
@Composable
internal fun UnverifiedCard(label: String, enabled: Boolean, onConfirm: () -> Unit) {
    AppCard(borderColor = appColors.attention.copy(alpha = 0.45f)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(
                AppIcons.StatusAttention,
                contentDescription = null,
                tint = appColors.attention,
                modifier = Modifier.padding(top = 2.dp).size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text("Unverified", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "$label is listed only because its name suggests an AI app. Nobody has checked that it is one. " +
                        "Confirm it if it is, or remove it if it is not.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedButton(
            onClick = onConfirm,
            enabled = enabled,
            modifier = Modifier.padding(top = Spacing.md).fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Text("Confirm it is an AI app", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/**
 * Where the owner writes the text to hand over. It sits in the page rather than in a dialog so
 * the warning stays readable above the keyboard while typing.
 */
@Composable
internal fun HandOffComposer(appLabel: String, enabled: Boolean, onCancel: () -> Unit, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text("Send text to $appLabel", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        InfoBanner(
            tone = StatusTone.ATTENTION,
            icon = AppIcons.Egress,
            text = "The text is handed to $appLabel's own screen and then leaves this app's control: what " +
                "$appLabel does with it, and where it sends it, is up to that app. " +
                "The reply stays in $appLabel. AI Control Center cannot read it.",
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Text to hand over") },
            minLines = 3,
            maxLines = 8,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
            Button(
                onClick = { onSend(text) },
                enabled = enabled && text.isNotBlank(),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Icon(AppIcons.Send, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Send", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
