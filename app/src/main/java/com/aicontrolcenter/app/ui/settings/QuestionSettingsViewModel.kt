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
data class QuestionSettingsUiState(val isLoading: Boolean = true)

/**
 * Settings › Questions from agents (Q3, Tier B; WP10b): "Agents may ask me questions" (`agentQuestionsEnabled`, on by default) and the question time-out (`ownerQuestionTimeoutMinutes`, `SettingsBounds.QUESTION_TIMEOUT_MINUTES` 5..240, default 20). @param settings the owner's writer.
 */
class QuestionSettingsViewModel(
    @Suppress("unused") private val settings: SettingsRepository,
) : ViewModel() {
    val uiState: StateFlow<QuestionSettingsUiState> = MutableStateFlow(QuestionSettingsUiState()).asStateFlow()
}
