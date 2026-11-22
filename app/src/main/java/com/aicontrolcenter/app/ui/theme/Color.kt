package com.aicontrolcenter.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Raw palette. Nothing outside this package refers to these values: screens use the Material
 * colour roles and the semantic colours in AppColors, so a palette change stays in one file.
 */

internal val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF35E0C8),
    onPrimary = Color(0xFF00201B),
    primaryContainer = Color(0xFF0E3B36),
    onPrimaryContainer = Color(0xFFA6F4E8),
    inversePrimary = Color(0xFF006B5E),
    secondary = Color(0xFF8AB4F8),
    onSecondary = Color(0xFF062E6F),
    secondaryContainer = Color(0xFF1A2F4D),
    onSecondaryContainer = Color(0xFFD3E3FD),
    tertiary = Color(0xFFFFD166),
    onTertiary = Color(0xFF3D2E00),
    tertiaryContainer = Color(0xFF3F3210),
    onTertiaryContainer = Color(0xFFFFE6A6),
    background = Color(0xFF0A0F14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF101820),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF16212B),
    onSurfaceVariant = Color(0xFFA3B3C0),
    surfaceTint = Color(0xFF101820),
    inverseSurface = Color(0xFFE6EDF3),
    inverseOnSurface = Color(0xFF101820),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF4A1A1C),
    onErrorContainer = Color(0xFFFFD9D6),
    outline = Color(0xFF4B6071),
    outlineVariant = Color(0xFF2B3D4C),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF1D2B37),
    surfaceDim = Color(0xFF0A0F14),
    surfaceContainerLowest = Color(0xFF070B0F),
    surfaceContainerLow = Color(0xFF101820),
    surfaceContainer = Color(0xFF16212B),
    surfaceContainerHigh = Color(0xFF1D2B37),
    surfaceContainerHighest = Color(0xFF253645),
)

internal val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF006B5E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC2F2EA),
    onPrimaryContainer = Color(0xFF00201B),
    inversePrimary = Color(0xFF35E0C8),
    secondary = Color(0xFF2B5CB8),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE7FB),
    onSecondaryContainer = Color(0xFF0A2A5E),
    tertiary = Color(0xFF7A5800),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFEBB8),
    onTertiaryContainer = Color(0xFF2A1E00),
    background = Color(0xFFF2F5F7),
    onBackground = Color(0xFF0F1B24),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F1B24),
    surfaceVariant = Color(0xFFE9EFF3),
    onSurfaceVariant = Color(0xFF465765),
    surfaceTint = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF16212B),
    inverseOnSurface = Color(0xFFE6EDF3),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF758695),
    outlineVariant = Color(0xFFC6D2DB),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDDE5EA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFB),
    surfaceContainer = Color(0xFFF0F4F6),
    surfaceContainerHigh = Color(0xFFE7EDF1),
    surfaceContainerHighest = Color(0xFFDEE6EB),
)

internal val DarkAppColors = AppColors(
    isDark = true,
    ready = Color(0xFF4ADE80),
    working = Color(0xFF35E0C8),
    waiting = Color(0xFF8AB4F8),
    attention = Color(0xFFFFD166),
    unavailable = Color(0xFFFF6B6B),
    disabled = Color(0xFF7A8B99),
    info = Color(0xFF8AB4F8),
    onDevice = Color(0xFF4ADE80),
    localNetwork = Color(0xFF8AB4F8),
    cloud = Color(0xFFFFD166),
    hairline = Color(0xFF1F2D39),
    hairlineStrong = Color(0xFF2B3D4C),
    agentAccents = listOf(
        Color(0xFF35E0C8), // teal
        Color(0xFF8AB4F8), // blue
        Color(0xFFFFD166), // amber
        Color(0xFFB9A3FF), // violet
        Color(0xFFFF8E7A), // coral
        Color(0xFFA6E36E), // lime
        Color(0xFFF59BD0), // pink
        Color(0xFF5CC8FF), // sky
    ),
)

internal val LightAppColors = AppColors(
    isDark = false,
    ready = Color(0xFF137A3A),
    working = Color(0xFF006B5E),
    waiting = Color(0xFF2B5CB8),
    attention = Color(0xFF8A5D00),
    unavailable = Color(0xFFBA1A1A),
    disabled = Color(0xFF5B6B78),
    info = Color(0xFF2B5CB8),
    onDevice = Color(0xFF137A3A),
    localNetwork = Color(0xFF2B5CB8),
    cloud = Color(0xFF8A5D00),
    hairline = Color(0xFFDCE4EA),
    hairlineStrong = Color(0xFFC6D2DB),
    agentAccents = listOf(
        Color(0xFF00796B),
        Color(0xFF2B5CB8),
        Color(0xFF8A5D00),
        Color(0xFF6A4FC4),
        Color(0xFFC0432B),
        Color(0xFF3F7D1E),
        Color(0xFFAD3A80),
        Color(0xFF0B78B0),
    ),
)
