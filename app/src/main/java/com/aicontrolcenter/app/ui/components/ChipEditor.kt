package com.aicontrolcenter.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.icons.AppIcons

/**
 * Edits a short list of text values (skills, responsibilities) as removable chips plus an
 * "add" field. The component owns only the text being typed; the list itself belongs to the
 * caller, which decides whether a value is acceptable (trimming, de-duplication).
 *
 * @param itemName singular name of one entry, used for spoken labels ("Remove skill research").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipEditor(
    values: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    itemName: String,
    modifier: Modifier = Modifier,
    placeholder: String = "Add $itemName",
    emptyText: String? = null,
) {
    var pending by rememberSaveable { mutableStateOf("") }
    val submit = {
        val value = pending.trim()
        if (value.isNotEmpty()) {
            onAdd(value)
            pending = ""
        }
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (values.isEmpty() && emptyText != null) {
            Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (values.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                values.forEach { value ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(value) },
                        label = { Text(value) },
                        trailingIcon = {
                            Icon(
                                AppIcons.Close,
                                contentDescription = "Remove $itemName $value",
                                modifier = Modifier.size(InputChipDefaults.IconSize),
                            )
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = pending,
            onValueChange = { pending = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(placeholder) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                IconButton(onClick = submit, enabled = pending.isNotBlank()) {
                    Icon(AppIcons.Add, contentDescription = placeholder)
                }
            },
        )
    }
}
