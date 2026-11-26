package com.aicontrolcenter.app.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.SettingsBounds
import com.aicontrolcenter.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One "time to answer" choice. */
@Immutable
data class TimeoutChoiceUi(val minutes: Int, val label: String)

/** What the Questions from agents section shows. */
@Immutable
data class QuestionSettingsUiState(
    val isLoading: Boolean = true,
    /** "Agents may ask me questions" (`agentQuestionsEnabled`). */
    val questionsOn: Boolean = true,
    /** How long a question waits for the owner, in minutes (`ownerQuestionTimeoutMinutes`). */
    val timeoutMinutes: Int = DEFAULT_TIMEOUT_MINUTES,
    val choices: List<TimeoutChoiceUi> = emptyList(),
    /** What went wrong with the last change, in words. */
    val message: String? = null,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MINUTES: Int = 20
    }
}

/**
 * Settings › Questions from agents (Q3, Tier B; WP10b): "Agents may ask me questions" (`agentQuestionsEnabled`, on by
 * default) and the question time-out (`ownerQuestionTimeoutMinutes`, `SettingsBounds.QUESTION_TIMEOUT_MINUTES` 5..240,
 * default 20). Switching questions off stops new questions only: one already waiting can still be answered.
 *
 * @param settings the owner's writer.
 */
class QuestionSettingsViewModel(
    private val settings: SettingsRepository,
) : ViewModel() {
    private val problem = MutableStateFlow<String?>(null)

    val uiState: StateFlow<QuestionSettingsUiState> = combine(settings.settings, problem) { current, message ->
        val minutes = current.ownerQuestionTimeoutMinutes
        QuestionSettingsUiState(
            isLoading = false,
            questionsOn = current.agentQuestionsEnabled,
            timeoutMinutes = minutes,
            choices = choicesWith(minutes).map { TimeoutChoiceUi(it, minutesWords(it, usual = it == QuestionSettingsUiState.DEFAULT_TIMEOUT_MINUTES)) },
            message = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QuestionSettingsUiState())

    fun setQuestionsOn(on: Boolean) = change { it.copy(agentQuestionsEnabled = on) }

    /** Keeps [minutes] if it is inside [SettingsBounds.QUESTION_TIMEOUT_MINUTES]; anything else is ignored. */
    fun setTimeoutMinutes(minutes: Int) {
        if (minutes !in SettingsBounds.QUESTION_TIMEOUT_MINUTES) return
        change { it.copy(ownerQuestionTimeoutMinutes = minutes) }
    }

    private fun change(edit: (FrameworkSettings) -> FrameworkSettings) {
        viewModelScope.launch {
            try {
                settings.update(edit)
                problem.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                problem.value = NOT_SAVED
            }
        }
    }

    companion object {
        /** The times offered; a stored value between them is shown too. */
        val CHOICES: List<Int> = listOf(5, 10, 20, 30, 60, 120, 240)

        const val NOT_SAVED: String = "The change could not be saved. Try again."

        /** The offered times, with the stored one added in its place when it is not one of them. */
        fun choicesWith(current: Int): List<Int> =
            (CHOICES + current.coerceIn(SettingsBounds.QUESTION_TIMEOUT_MINUTES)).distinct().sorted()

        /** "5 minutes", "20 minutes (usual)", "1 hour", "4 hours". */
        fun minutesWords(minutes: Int, usual: Boolean = false): String {
            val words = when {
                minutes % 60 == 0 -> (minutes / 60).let { if (it == 1) "1 hour" else "$it hours" }
                minutes == 1 -> "1 minute"
                else -> "$minutes minutes"
            }
            return if (usual) "$words (usual)" else words
        }
    }
}
