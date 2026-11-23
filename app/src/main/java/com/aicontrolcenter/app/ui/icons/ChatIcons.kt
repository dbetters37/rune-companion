package com.aicontrolcenter.app.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector

/* Icons of the chat screen, built from the Material "Rounded" path data like the rest of the set. */

/** Stops a turn in progress. */
val AppIcons.Stop: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    appIcon("Stop") {
        p {
            moveTo(8f, 6f); horizontalLineToRelative(8f); curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f); verticalLineToRelative(8f)
            curveToRelative(0f, 1.1f, -0.9f, 2f, -2f, 2f); horizontalLineTo(8f); curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f)
            verticalLineTo(8f); curveToRelative(0f, -1.1f, 0.9f, -2f, 2f, -2f); close()
        }
    }
}
