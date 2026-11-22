package com.aicontrolcenter.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * The app theme: a dark "mission control" scheme with a matching light scheme, chosen by the
 * system setting. Dynamic (wallpaper) colour is deliberately not used, because status and
 * locality colours must mean the same thing on every device.
 */
@Composable
fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppColors provides if (darkTheme) DarkAppColors else LightAppColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

/** Shorthand for the semantic colours of the current theme. */
val appColors: AppColors
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current

/** Layout constants shared by every screen so spacing has one rhythm. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Horizontal padding of screen content. */
    val screen = 16.dp

    /** Smallest size of anything tappable. */
    val touchTarget = 48.dp

    /** Reading width at which content stops stretching on wide screens. */
    val maxContentWidth = 720.dp
}
