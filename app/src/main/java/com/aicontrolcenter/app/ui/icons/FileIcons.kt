/*
 * Path data of Folder and Image comes from Material Icons (Rounded), Copyright The Android Open
 * Source Project, licensed under the Apache License, Version 2.0. Import is drawn on the same grid.
 */
@file:Suppress("ktlint", "MagicNumber")

package com.aicontrolcenter.app.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector

/** Material Icons Rounded "Folder". */
val AppIcons.Folder: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    appIcon("Folder") {
        p {
            moveTo(10.59f, 4.59f); curveTo(10.21f, 4.21f, 9.7f, 4f, 9.17f, 4f); horizontalLineTo(4f)
            curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f); lineTo(2f, 18f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(16f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineTo(8f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); horizontalLineToRelative(-8f)
            lineToRelative(-1.41f, -1.41f); close()
        }
    }
}

/** Material Icons Rounded "Image". */
val AppIcons.Image: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    appIcon("Image") {
        p {
            moveTo(21f, 19f); verticalLineTo(5f); curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); horizontalLineTo(5f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(14f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(14f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); close()
            moveTo(8.9f, 13.98f); lineToRelative(2.1f, 2.53f); lineToRelative(3.1f, -3.99f)
            curveToRelative(0.2f, -0.26f, 0.6f, -0.26f, 0.8f, 0.01f); lineToRelative(3.51f, 4.68f)
            curveToRelative(0.25f, 0.33f, 0.01f, 0.8f, -0.4f, 0.8f); horizontalLineTo(6.02f)
            curveToRelative(-0.42f, 0f, -0.65f, -0.48f, -0.39f, -0.81f); lineTo(8.12f, 14f)
            curveToRelative(0.19f, -0.26f, 0.57f, -0.27f, 0.78f, -0.02f); close()
        }
    }
}

/** An arrow up onto a tray: bringing a file in. */
val AppIcons.Import: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    appIcon("Import") {
        p {
            moveTo(5f, 18f); horizontalLineToRelative(14f); verticalLineToRelative(2f); horizontalLineTo(5f); close()
            moveTo(12f, 4f); lineToRelative(5f, 5.5f); horizontalLineToRelative(-3f); verticalLineToRelative(6.5f)
            horizontalLineToRelative(-4f); verticalLineToRelative(-6.5f); horizontalLineTo(7f); close()
        }
    }
}
