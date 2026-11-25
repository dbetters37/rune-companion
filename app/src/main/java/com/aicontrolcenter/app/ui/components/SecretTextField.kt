package com.aicontrolcenter.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Key

/**
 * A field for an API key or a passphrase: Android's secure text field, always masked (no
 * character is shown, not even the last one typed), with the password keyboard, so the keyboard
 * neither suggests nor learns it, and no copying out of it. There is no reveal button.
 *
 * [state] belongs to the view model, never to saved instance state, so the text is not written
 * into the bundle Android may persist; the view model empties it (and its undo history) once
 * the secret has gone where it belongs.
 */
@Composable
fun SecretTextField(
    state: TextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
) {
    val below = errorText ?: supportingText
    OutlinedSecureTextField(
        state = state,
        modifier = modifier.fillMaxWidth().semantics { if (errorText != null) error(errorText) },
        enabled = enabled,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = { Icon(AppIcons.Key, contentDescription = null) },
        supportingText = below?.let { { Text(it) } },
        isError = errorText != null,
        textObfuscationMode = TextObfuscationMode.Hidden,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Next,
        ),
    )
}
