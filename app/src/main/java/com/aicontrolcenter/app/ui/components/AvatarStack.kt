package com.aicontrolcenter.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One face of an [AvatarStack]. */
data class StackedAvatar(val name: String, val colorIndex: Int)

/**
 * A group of agents as overlapping avatars, the first [max] shown and the rest counted ("+2").
 * Read aloud as the list of names.
 */
@Composable
fun AvatarStack(avatars: List<StackedAvatar>, modifier: Modifier = Modifier, size: Dp = 28.dp, max: Int = 5) {
    if (avatars.isEmpty()) return
    val shown = avatars.take(max)
    val hidden = avatars.size - shown.size
    val overlap = size * 0.3f
    val backdrop = MaterialTheme.colorScheme.surface
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = "Agents: " + avatars.joinToString(", ") { it.name } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        shown.forEachIndexed { index, avatar ->
            Box(
                Modifier
                    .offset(x = -overlap * index)
                    .border(2.dp, backdrop, CircleShape),
            ) {
                AgentAvatar(avatar.name, avatar.colorIndex, size = size, backdrop = backdrop)
            }
        }
        if (hidden > 0) {
            Box(
                modifier = Modifier
                    .offset(x = -overlap * shown.size)
                    .size(size)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                    .border(2.dp, backdrop, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("+$hidden", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
