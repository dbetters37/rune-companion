package com.aicontrolcenter.app.ui.activity

import androidx.compose.ui.graphics.vector.ImageVector
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.appIcon

/** Money (Material "Payments"): the estimated-cost tiles, so cost does not wear the clock icon of time (D3A-16). */
val AppIcons.Payments: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    appIcon("Payments") {
        p {
            moveTo(19f, 14f); verticalLineTo(6f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); horizontalLineTo(3f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(8f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(14f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); close()
            moveTo(10f, 13f)
            curveToRelative(-1.66f, 0f, -3f, -1.34f, -3f, -3f)
            reflectiveCurveToRelative(1.34f, -3f, 3f, -3f)
            reflectiveCurveToRelative(3f, 1.34f, 3f, 3f)
            reflectiveCurveToRelative(-1.34f, 3f, -3f, 3f); close()
            moveTo(23f, 7f); verticalLineToRelative(11f)
            curveToRelative(0f, 1.1f, -0.9f, 2f, -2f, 2f); horizontalLineTo(4f)
            verticalLineToRelative(-2f); horizontalLineToRelative(17f); verticalLineTo(7f); horizontalLineToRelative(2f); close()
        }
    }
}
