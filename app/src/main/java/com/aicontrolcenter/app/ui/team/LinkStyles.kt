package com.aicontrolcenter.app.ui.team

import androidx.compose.ui.graphics.vector.ImageVector
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Delegation
import com.aicontrolcenter.app.ui.icons.Forum
import com.aicontrolcenter.app.ui.icons.Help
import com.aicontrolcenter.app.ui.icons.Insights
import com.aicontrolcenter.app.ui.icons.MemoryBrain
import com.aicontrolcenter.app.ui.icons.Notes
import com.aicontrolcenter.app.ui.icons.Review
import com.aicontrolcenter.app.ui.icons.Tasks

/**
 * How each kind of link is drawn: its glyph (the same ones the Conversation tab uses for those messages, 20 dp at the
 * middle of the line) and its line style. Kinds differ by style and glyph, never by colour alone.
 */
enum class LineStyle {
    SOLID,
    THIN,
    DASHED,
    DOTTED,
    DOUBLE,
    DASH_DOT,
    LONG_DASH,
}

val LinkKind.icon: ImageVector
    get() = when (this) {
        LinkKind.ASSIGNS -> AppIcons.Tasks
        LinkKind.ASKS, LinkKind.ASKS_YOU -> AppIcons.Help
        LinkKind.DELEGATES -> AppIcons.Delegation
        LinkKind.REVIEWS -> AppIcons.Review
        LinkKind.REPORTS -> AppIcons.Insights
        LinkKind.SHARES -> AppIcons.MemoryBrain
        LinkKind.TELLS_YOU -> AppIcons.Info
        LinkKind.NOTES -> AppIcons.Notes
    }

val LinkKind.style: LineStyle
    get() = when (this) {
        LinkKind.ASSIGNS -> LineStyle.SOLID
        LinkKind.ASKS, LinkKind.ASKS_YOU -> LineStyle.DASHED
        LinkKind.DELEGATES -> LineStyle.DOUBLE
        LinkKind.REVIEWS -> LineStyle.DOTTED
        LinkKind.REPORTS, LinkKind.TELLS_YOU -> LineStyle.THIN
        LinkKind.SHARES -> LineStyle.DASH_DOT
        LinkKind.NOTES -> LineStyle.LONG_DASH
    }

/** The legend's words for a line style. */
val LineStyle.legend: String
    get() = when (this) {
        LineStyle.SOLID -> "solid line"
        LineStyle.THIN -> "thin line"
        LineStyle.DASHED -> "dashed line"
        LineStyle.DOTTED -> "dotted line"
        LineStyle.DOUBLE -> "double line"
        LineStyle.DASH_DOT -> "dash and dot line"
        LineStyle.LONG_DASH -> "long dashes"
    }

/** The kinds the legend explains, in the order it lists them. */
val LEGEND_KINDS: List<LinkKind> = listOf(
    LinkKind.ASSIGNS, LinkKind.ASKS, LinkKind.DELEGATES, LinkKind.REVIEWS, LinkKind.REPORTS, LinkKind.SHARES,
    LinkKind.ASKS_YOU, LinkKind.TELLS_YOU, LinkKind.NOTES,
)
