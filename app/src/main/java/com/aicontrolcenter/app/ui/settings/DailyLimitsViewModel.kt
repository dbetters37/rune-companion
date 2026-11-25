@file:Phase6Stub("WP10a")

package com.aicontrolcenter.app.ui.settings

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.app.usage.TodayUsage
import com.aicontrolcenter.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the Daily limits section and screen show (WP10a fills it). */
@Immutable
data class DailyLimitsUiState(val isLoading: Boolean = true)

/**
 * Today's limits of model answers, tokens and money (rule 6, Q2: 200 answers, 500,000 tokens, US$1.00; WP10a), with
 * today's use and plain words on what happens when one is reached ("a running objective pauses"; "limits stop work, not
 * cost already spent"). Serves the Settings section and the Daily limits screen.
 *
 * @param settings the owner's writer: the limits are settings (`maxModelCallsPerDay`, `maxTokensPerDay`, `maxSpendPerDayMicros`).
 * @param today today's use, read only, counted as the limiter counts it.
 */
class DailyLimitsViewModel(
    @Suppress("unused") private val settings: SettingsRepository,
    @Suppress("unused") private val today: TodayUsage,
) : ViewModel() {
    /** The three numbers the owner types (rule 27: each field's text lives here). */
    val answers: TextFieldState = TextFieldState()
    val tokens: TextFieldState = TextFieldState()
    val money: TextFieldState = TextFieldState()

    val uiState: StateFlow<DailyLimitsUiState> = MutableStateFlow(DailyLimitsUiState()).asStateFlow()
}
