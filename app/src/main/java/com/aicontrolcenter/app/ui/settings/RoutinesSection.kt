@file:Phase6Stub("WP10c")

package com.aicontrolcenter.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aicontrolcenter.app.Phase6Stub

/** Settings › Routines and folder pick-up (WP10c): the two master switches and links to the lists ([onOpenRoutines], [onOpenPickUpRules]). A slot of `SettingsScreen` (WP8 places it). Draws nothing until WP10c builds it. */
@Composable
fun RoutinesSection(
    @Suppress("UNUSED_PARAMETER") viewModel: RoutinesSettingsViewModel,
    @Suppress("UNUSED_PARAMETER") onOpenRoutines: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenPickUpRules: () -> Unit,
    @Suppress("UNUSED_PARAMETER") modifier: Modifier = Modifier,
) {
}
