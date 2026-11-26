package com.aicontrolcenter.app.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Appearance section shows. */
@Immutable
data class MotionSettingsUiState(
    val isLoading: Boolean = true,
    /** The owner's Calm switch (`calmMotion`). */
    val calm: Boolean = false,
    /** What went wrong with the last change, in words. */
    val message: String? = null,
)

/**
 * Settings › Appearance: Calm (rule 17; WP10b): with Calm on, nothing travels or pulses (the Team view's dots, the network
 * card). The app's root reads the setting (`LocalCalmMotion`, together with Android's own animation switch); this only
 * writes it.
 *
 * @param settings the owner's writer (`calmMotion`).
 */
class MotionSettingsViewModel(
    private val settings: SettingsRepository,
) : ViewModel() {
    private val problem = MutableStateFlow<String?>(null)

    val uiState: StateFlow<MotionSettingsUiState> = combine(settings.settings, problem) { current, message ->
        MotionSettingsUiState(isLoading = false, calm = current.calmMotion, message = message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MotionSettingsUiState())

    fun setCalm(on: Boolean) {
        viewModelScope.launch {
            try {
                settings.update { it.copy(calmMotion = on) }
                problem.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                problem.value = QuestionSettingsViewModel.NOT_SAVED
            }
        }
    }
}
