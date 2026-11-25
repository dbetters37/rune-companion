@file:Phase6Stub("WP10a")

package com.aicontrolcenter.app.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aicontrolcenter.app.Phase6Stub

/**
 * The safe-start banner (rule 10, Tier B): the reason's ownerText, "Interrupted work waits for you", and "Back to normal" (SafeStartControl.leave).
 * A slot of `DashboardScreen` (WP8 places it); WP10a builds it over [extras]. Draws nothing until then.
 */
@Composable
fun SafeStartBanner(
    @Suppress("UNUSED_PARAMETER") extras: DashboardExtrasViewModel,
    @Suppress("UNUSED_PARAMETER") navigation: DashboardNavigation,
    @Suppress("UNUSED_PARAMETER") modifier: Modifier = Modifier,
) {
}
