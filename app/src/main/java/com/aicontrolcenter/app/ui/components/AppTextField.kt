package com.aicontrolcenter.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.byValue
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.flow.Flow

/**
 * The app's text field: outlined, labelled, with an error or helper line underneath.
 *
 * The text lives in [state], which the field reads and the keyboard writes directly, so every
 * key lands in order however fast it is typed. A screen's view model owns the state of a form
 * it saves; what it derives from the text (validation, a draft, a filter) follows the field
 * through [textChanges] and never feeds back into it.
 *
 * @param inputTransformation applied to what is typed before it is shown (for example
 *   [digitsOnly]); text put in from code is taken as it is.
 */
@Composable
fun AppTextField(
    state: TextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    errorText: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    inputTransformation: InputTransformation? = null,
    onKeyboardAction: KeyboardActionHandler? = null,
    enabled: Boolean = true,
) {
    val below = errorText ?: supportingText
    OutlinedTextField(
        state = state,
        modifier = modifier.fillMaxWidth().semantics { if (errorText != null) error(errorText) },
        enabled = enabled,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = below?.let { { Text(it) } },
        isError = errorText != null,
        inputTransformation = inputTransformation,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onKeyboardAction,
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(minLines, maxLines),
    )
}

/** Keeps the digits of what is typed or pasted, at most [maxDigits] of them: for token counts and other whole numbers. */
fun digitsOnly(maxDigits: Int): InputTransformation =
    InputTransformation.byValue { _, proposed -> proposed.filter(Char::isDigit).take(maxDigits) }

/** Keeps at most [max] characters of what is typed or pasted, cutting off the rest. */
fun atMostChars(max: Int): InputTransformation =
    InputTransformation.byValue { _, proposed -> if (proposed.length <= max) proposed else proposed.subSequence(0, max) }

/**
 * The field's text now and after every change, for what a view model derives from it. The field
 * itself never waits for this flow.
 */
fun TextFieldState.textChanges(): Flow<String> = snapshotFlow { text.toString() }

/**
 * Puts [value] in the field from code: a record that has just loaded, a draft restored, or
 * emptying the field after it was sent. Never called while the owner types into it. Leaves the
 * field (and its cursor) alone when it already holds [value].
 */
fun TextFieldState.putText(value: String) {
    if (text.toString() != value) setTextAndPlaceCursorAtEnd(value)
}
