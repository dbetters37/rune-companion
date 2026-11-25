@file:Phase6Stub("WP10c")

package com.aicontrolcenter.app.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.core.pickup.OwnerPickUpRules
import com.aicontrolcenter.core.routines.OwnerRoutines
import com.aicontrolcenter.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What it shows (WP10c fills it). */
@Immutable
data class RoutinesSettingsUiState(val isLoading: Boolean = true)

/**
 * Settings › Routines and folder pick-up (Q1, Appendix A, Tier B; WP10c): the two master switches (`routinesEnabled`, `folderPickUpEnabled`, both off after installing), how many routines and rules exist, and the links to their lists. @param settings the owner's writer, for the two switches. @param routines null when the build has none (then the routine part stays hidden); [pickUpRules] likewise.
 */
class RoutinesSettingsViewModel(
    @Suppress("unused") private val settings: SettingsRepository,
    @Suppress("unused") private val routines: OwnerRoutines?,
    @Suppress("unused") private val pickUpRules: OwnerPickUpRules?,
) : ViewModel() {
    val uiState: StateFlow<RoutinesSettingsUiState> = MutableStateFlow(RoutinesSettingsUiState()).asStateFlow()
}
