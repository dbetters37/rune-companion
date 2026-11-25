@file:Phase6Stub("WP8")

package com.aicontrolcenter.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.icons.AppIcons

/**
 * Stands in for a wave-3 screen until its package builds it (WP8, part 3a). Every stub screen shows this; the stub
 * gate (`Phase6StubGateTest`) fails while one is left after wave 3, and WP8 removes this file with the last one.
 */
@Composable
internal fun StubScreen(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    ScreenScaffold(title = title, onBack = onBack, modifier = modifier) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            EmptyState(icon = AppIcons.Info, message = "This screen is not ready yet.")
        }
    }
}
