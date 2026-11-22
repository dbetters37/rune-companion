package com.aicontrolcenter.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.StatusDisabled
import com.aicontrolcenter.app.ui.icons.StatusUnavailable
import com.aicontrolcenter.app.ui.icons.StatusWaiting
import com.aicontrolcenter.app.ui.icons.StatusWorking
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors

/** The glyph that always accompanies a tone, so status never depends on colour alone. */
val StatusTone.icon: ImageVector
    get() = when (this) {
        StatusTone.READY -> AppIcons.StatusReady
        StatusTone.WORKING -> AppIcons.StatusWorking
        StatusTone.WAITING -> AppIcons.StatusWaiting
        StatusTone.ATTENTION -> AppIcons.StatusAttention
        StatusTone.UNAVAILABLE -> AppIcons.StatusUnavailable
        StatusTone.DISABLED -> AppIcons.StatusDisabled
    }

/** A status as a pill: tone glyph, label, and a faint wash of the tone's colour. */
@Composable
fun StatusChip(label: String, tone: StatusTone, modifier: Modifier = Modifier, icon: ImageVector = tone.icon) {
    TintedChip(label = label, color = appColors.status(tone), icon = icon, modifier = modifier)
}

/**
 * A small pill in an arbitrary semantic colour (locality, discovery source, …). The label is
 * always present; the colour only reinforces it.
 */
@Composable
fun TintedChip(label: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier = modifier
            .background(color.copy(alpha = if (appColors.isDark) 0.14f else 0.10f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A neutral pill for facts that carry no status, such as a capability or a role. */
@Composable
fun NeutralChip(label: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
