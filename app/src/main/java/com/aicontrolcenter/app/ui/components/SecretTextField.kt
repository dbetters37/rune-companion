package com.aicontrolcenter.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Key

/**
 * A field for an API key: always masked, with the password keyboard (no suggestions, no
 * learning) and no reveal button. What is typed is kept in plain `remember` state, never in
 * saved instance state, so it is not written into the bundle Android may persist.
 */
@Composable
fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
) {
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != text) text = value }
    val below = errorText ?: supportingText
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            onValueChange(typed)
        },
        modifier = modifier.fillMaxWidth().semantics {
            password()
            if (errorText != null) error(errorText)
        },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = below?.let { { Text(it) } },
        leadingIcon = { Icon(AppIcons.Key, contentDescription = null) },
        isError = errorText != null,
        enabled = enabled,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Next,
        ),
    )
}
