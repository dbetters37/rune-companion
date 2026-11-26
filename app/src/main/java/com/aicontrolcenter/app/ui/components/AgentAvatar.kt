package com.aicontrolcenter.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors

/**
 * An agent's face in the UI: its monogram on its accent colour, with an optional status badge
 * at the lower right. The badge is the tone's glyph, not just a coloured dot. The avatar is
 * decorative for accessibility; the row or node that contains it carries the description.
 */
@Composable
fun AgentAvatar(
    name: String,
    colorIndex: Int,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    status: StatusTone? = null,
    dimmed: Boolean = false,
    backdrop: Color = MaterialTheme.colorScheme.surface,
) {
    val accent = appColors.agentAccent(colorIndex).let { if (dimmed) it.copy(alpha = 0.45f) else it }
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(accent.copy(alpha = accent.alpha * 0.18f).compositeOver(backdrop), CircleShape)
                .border(1.5.dp, accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = monogramOf(name),
                color = accent,
                fontWeight = FontWeight.SemiBold,
                // From the circle's size in dp, not scaled again by the font size: at font scale 2.0 the letter must still fit (D3A-13).
                fontSize = with(LocalDensity.current) { (size * 0.36f).toSp() },
                // The line as tall as the letter, or the inherited line height (scaled by the font size) pushes it down.
                lineHeight = with(LocalDensity.current) { (size * 0.36f).toSp() },
                maxLines = 1,
            )
        }
        if (status != null) {
            val badge = size * 0.4f
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(badge)
                    .background(backdrop, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = status.icon,
                    contentDescription = null,
                    tint = appColors.status(status),
                    modifier = Modifier.size(badge - 2.dp),
                )
            }
        }
    }
}

/** Up to two initials: "Phone Operator" gives "PO", "Researcher" gives "R". */
fun monogramOf(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}
