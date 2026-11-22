package com.aicontrolcenter.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors

/**
 * An inline note that explains a state rather than reporting an event: why a list is empty,
 * what a switch really does, what is not built yet. [tone] null gives a neutral note.
 */
@Composable
fun InfoBanner(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    tone: StatusTone? = null,
    icon: ImageVector = tone?.icon ?: AppIcons.Info,
) {
    val accent: Color = tone?.let { appColors.status(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    val container = if (tone == null) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        accent.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = container,
        border = BorderStroke(1.dp, if (tone == null) appColors.hairline else accent.copy(alpha = 0.35f)),
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.padding(top = 1.dp).size(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
