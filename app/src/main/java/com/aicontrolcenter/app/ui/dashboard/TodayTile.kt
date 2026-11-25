@file:Phase6Stub("WP10a")

package com.aicontrolcenter.app.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aicontrolcenter.app.Phase6Stub

/**
 * The Today tile: answers, tokens and estimated cost today and this month, and today's limits. Opens Activity › Usage and cost (navigation.onOpenUsage) and Settings › Daily limits (navigation.onOpenDailyLimits).
 * A slot of `DashboardScreen` (WP8 places it); WP10a builds it over [extras]. Draws nothing until then.
 */
@Composable
fun TodayTile(
    @Suppress("UNUSED_PARAMETER") extras: DashboardExtrasViewModel,
    @Suppress("UNUSED_PARAMETER") navigation: DashboardNavigation,
    @Suppress("UNUSED_PARAMETER") modifier: Modifier = Modifier,
) {
}
