package com.aicontrolcenter.app.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.core.settings.SettingsBounds
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.core.usage.OwnerUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One "keep usage history for" choice. */
@Immutable
data class RetentionChoiceUi(val days: Int, val label: String)

/** What the Usage history section shows. */
@Immutable
data class UsageHistoryUiState(
    val isLoading: Boolean = true,
    /** The saved choice, in days; 0 keeps everything. */
    val retentionDays: Int = 730,
    val choices: List<RetentionChoiceUi> = emptyList(),
    /** True while the history is being cleared. */
    val clearing: Boolean = false,
    /** What the last clear or change did, in words. */
    val message: String? = null,
)

/**
 * Settings › Usage history (WP10a): keep usage for 90 days, 1 year, 2 years or always (`usageRetentionDays`), and "Clear
 * usage history", which keeps today (S29: today's use seeds the daily limits, so clearing never resets them).
 *
 * @param ownerUsage owner-only: clearing the history.
 */
class UsageHistoryViewModel(
    private val settings: SettingsRepository,
    private val ownerUsage: OwnerUsage,
) : ViewModel() {

    private val status = MutableStateFlow(Status())

    private data class Status(val clearing: Boolean = false, val message: String? = null)

    val uiState: StateFlow<UsageHistoryUiState> = combine(settings.settings, status) { current, status ->
        val days = current.usageRetentionDays
        UsageHistoryUiState(
            isLoading = false,
            retentionDays = days,
            choices = (SettingsBounds.USAGE_RETENTION_DAYS + days).distinct().map { RetentionChoiceUi(it, retentionWords(it)) },
            clearing = status.clearing,
            message = status.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsageHistoryUiState())

    /** Keeps usage for [days] (one of [SettingsBounds.USAGE_RETENTION_DAYS]); older records go at the next daily upkeep. */
    fun setRetentionDays(days: Int) {
        if (days !in SettingsBounds.USAGE_RETENTION_DAYS) return
        viewModelScope.launch {
            try {
                settings.update { it.copy(usageRetentionDays = days) }
                status.update { it.copy(message = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                status.update { it.copy(message = "The choice could not be saved. Try again.") }
            }
        }
    }

    /** Deletes the usage history before today. Today's numbers stay: today's limits count them. */
    fun clearHistory() {
        if (status.value.clearing) return
        status.value = Status(clearing = true)
        viewModelScope.launch {
            val message = try {
                val removed = ownerUsage.clearHistory()
                if (removed > 0) CLEARED else NOTHING_TO_CLEAR
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "The history could not be cleared. Try again."
            }
            status.value = Status(clearing = false, message = message)
        }
    }

    companion object {
        const val CLEARED: String = "Usage before today was cleared. Today's numbers stay, because today's limits count them."
        const val NOTHING_TO_CLEAR: String = "There was no usage from before today to clear."

        /** "90 days", "1 year", "2 years", "Always". */
        fun retentionWords(days: Int): String = when {
            days == 0 -> "Always"
            days % 365 == 0 -> (days / 365).let { if (it == 1) "1 year" else "$it years" }
            else -> if (days == 1) "1 day" else "$days days"
        }
    }
}
