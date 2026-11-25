@file:Phase6Stub("WP10b")

package com.aicontrolcenter.app.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What it shows (WP10b fills it). */
@Immutable
data class MotionSettingsUiState(val isLoading: Boolean = true)

/**
 * Settings › Appearance: Calm (rule 17; WP10b): with Calm on, nothing travels or pulses (the Team view's dots, the network card). @param settings the owner's writer (`calmMotion`).
 */
class MotionSettingsViewModel(
    @Suppress("unused") private val settings: SettingsRepository,
) : ViewModel() {
    val uiState: StateFlow<MotionSettingsUiState> = MutableStateFlow(MotionSettingsUiState()).asStateFlow()
}
