@file:Phase6Stub("WP10b")

package com.aicontrolcenter.app.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.app.di.wiring.BackgroundScreens
import com.aicontrolcenter.core.health.BackgroundHealth
import com.aicontrolcenter.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What it shows (WP10b fills it). */
@Immutable
data class BackgroundSectionUiState(val isLoading: Boolean = true)

/**
 * Settings › Working in the background (A3; WP10b): whether Android lets the app work in the background (notifications, battery optimisation, background restriction, standby bucket), each with its button to Android's own screen (`refresh()` on return; `BackgroundSettingsIntents.NO_SUCH_SCREEN` when a screen is missing), and the switches "Keep the phone awake while agents work" and "Tell me when work ends". @param settings the owner's writer, for the two switches.
 */
class BackgroundSectionViewModel(
    @Suppress("unused") private val health: BackgroundHealth,
    @Suppress("unused") private val screens: BackgroundScreens,
    @Suppress("unused") private val settings: SettingsRepository,
) : ViewModel() {
    val uiState: StateFlow<BackgroundSectionUiState> = MutableStateFlow(BackgroundSectionUiState()).asStateFlow()
}
