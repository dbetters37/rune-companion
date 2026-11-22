package com.aicontrolcenter.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Remove
import com.aicontrolcenter.app.ui.theme.appColors

/**
 * Minus / value / plus for a bounded whole number. The buttons disable at the ends of [range]
 * and the reported value is always inside it, whatever [value] was passed in.
 *
 * @param name what is being adjusted, for the buttons' spoken labels ("Decrease task time-out").
 */
@Composable
fun NumericStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    name: String,
    modifier: Modifier = Modifier,
    step: Int = 1,
    enabled: Boolean = true,
    valueText: String = value.toString(),
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, appColors.hairlineStrong),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onValueChange((value - step).coerceIn(range.first, range.last)) },
                enabled = enabled && value > range.first,
            ) {
                Icon(AppIcons.Remove, contentDescription = "Decrease $name")
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 44.dp).semantics {
                    contentDescription = name
                    stateDescription = valueText
                },
            )
            IconButton(
                onClick = { onValueChange((value + step).coerceIn(range.first, range.last)) },
                enabled = enabled && value < range.last,
            ) {
                Icon(AppIcons.Add, contentDescription = "Increase $name")
            }
        }
    }
}
