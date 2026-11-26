package com.aicontrolcenter.app.ui.theme

import androidx.compose.runtime.compositionLocalOf

/**
 * Calm (rule 17): true while nothing on screen should travel or pulse (the Team view's dots, the network card's pulses,
 * a button that grows and shrinks). Screens read it and show the end state at once instead of moving to it.
 *
 * The app's root provides it (`AiControlCenterRoot`, wave 3): on when the owner switched Calm on in Settings ›
 * Appearance, or when Android's own animations are off ([calmMotion]). The default, for previews and tests that compose
 * one screen alone, is off.
 */
val LocalCalmMotion = compositionLocalOf { false }

/**
 * Whether motion is calmed: the owner's Calm setting ([calmSetting], `FrameworkSettings.calmMotion`) or Android's
 * animations switched off ([animatorsEnabled] false: `ValueAnimator.areAnimatorsEnabled()`, which developer options and
 * battery saver change).
 */
fun calmMotion(calmSetting: Boolean, animatorsEnabled: Boolean): Boolean = calmSetting || !animatorsEnabled
