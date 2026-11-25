@file:Phase6Stub("WP10a")

package com.aicontrolcenter.app.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aicontrolcenter.app.Phase6Stub

/**
 * The phone's attention items: notifications off, battery optimisation on, background restricted, rare standby bucket (Settings › Working in the background: navigation.onOpenSettings), storage low or critical, objectives paused by today's limit (navigation.onOpenObjective, navigation.onOpenDailyLimits).
 * A slot of `DashboardScreen` (WP8 places it); WP10a builds it over [extras]. Draws nothing until then.
 */
@Composable
fun PhoneAttention(
    @Suppress("UNUSED_PARAMETER") extras: DashboardExtrasViewModel,
    @Suppress("UNUSED_PARAMETER") navigation: DashboardNavigation,
    @Suppress("UNUSED_PARAMETER") modifier: Modifier = Modifier,
) {
}
