@file:Phase6Stub("WP10a")

package com.aicontrolcenter.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aicontrolcenter.app.Phase6Stub

/**
 * Settings › Daily limits (WP10a): today's use against each limit in one line each, and [onOpenDailyLimits] to change
 * them on the Daily limits screen. A slot of `SettingsScreen` (WP8 places it). Draws nothing until WP10a builds it.
 */
@Composable
fun DailyLimitsSection(
    @Suppress("UNUSED_PARAMETER") viewModel: DailyLimitsViewModel,
    @Suppress("UNUSED_PARAMETER") onOpenDailyLimits: () -> Unit,
    @Suppress("UNUSED_PARAMETER") modifier: Modifier = Modifier,
) {
}
