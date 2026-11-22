package com.aicontrolcenter.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics

/**
 * The app's text field: outlined, labelled, with an error or helper line underneath.
 *
 * It keeps what is being typed in local state and reports each change upward, adopting the
 * upstream [value] only when that differs from what was typed. A field bound directly to a
 * view model's flow would otherwise receive its own text back a frame late, which makes the
 * cursor jump while typing quickly.
 *
 * @param transformInput applied to typed text before it is shown or reported (for example to
 *   keep digits only).
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    errorText: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    transformInput: (String) -> String = { it },
) {
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != text) text = value }
    val below = errorText ?: supportingText
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            val accepted = transformInput(typed)
            text = accepted
            onValueChange(accepted)
        },
        modifier = modifier.fillMaxWidth().semantics { if (errorText != null) error(errorText) },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = below?.let { { Text(it) } },
        isError = errorText != null,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
    )
}
