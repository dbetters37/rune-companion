package com.aicontrolcenter.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors

/**
 * The app's container: a rounded surface outlined by a 1dp hairline instead of a shadow, which
 * keeps dense screens calm on a dark background. Pass [onClick] to make the whole card a
 * single touch target.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = appColors.hairline,
    contentPadding: PaddingValues = PaddingValues(Spacing.lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val border = BorderStroke(1.dp, borderColor)
    if (onClick == null) {
        Surface(modifier = modifier, shape = shape, color = containerColor, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = containerColor,
            border = border,
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}
