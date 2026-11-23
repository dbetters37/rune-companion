package com.aicontrolcenter.app.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AppTextField
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.OnDevice
import com.aicontrolcenter.app.ui.theme.MonoTextStyle
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.llm.DetectedServer

/** Registers a model by the exact name its provider expects. */
@Composable
internal fun AddModelDialog(providerName: String, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a model to $providerName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "For a model the provider does not list, or not yet. Enter the name exactly as the provider expects it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Model name",
                    placeholder = "for example llama3.2:3b",
                    supportingText = "Its capabilities are estimated from the name; you can correct them afterwards.",
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name) }, enabled = name.isNotBlank()) { Text("Add model") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The outcome of probing this phone for model servers, with a way to add each one found. */
@Composable
internal fun LocalServersDialog(detection: DetectionUi, onAdd: (DetectedServer) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.OnDevice, contentDescription = null, tint = appColors.onDevice) },
        title = { Text("Servers on this phone") },
        text = {
            when (detection) {
                DetectionUi.Searching -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Asking the usual addresses on this phone…", style = MaterialTheme.typography.bodyMedium)
                }
                is DetectionUi.Done -> if (detection.servers.isEmpty()) {
                    Text(
                        "No new model server answered on this phone. Start one (for example \"ollama serve\" in Termux, " +
                            "or llama-server) and search again. Servers that are already registered are not listed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Column {
                        Text(
                            "Nothing was sent off this phone to find these.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = Spacing.sm),
                        )
                        detection.servers.forEachIndexed { index, server ->
                            if (index > 0) RowDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(server.title, style = MaterialTheme.typography.titleSmall)
                                    Text(server.baseUrl, style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        "Serves ${plural(server.modelCount, "model")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                FilledTonalButton(onClick = { onAdd(server) }) { Text("Add") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (detection == DetectionUi.Searching) "Cancel" else "Close") } },
    )
}
