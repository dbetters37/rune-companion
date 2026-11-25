@file:Phase6Stub("WP10a")

package com.aicontrolcenter.app.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.core.usage.OwnerUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the Usage history section shows (WP10a fills it). */
@Immutable
data class UsageHistoryUiState(val isLoading: Boolean = true)

/**
 * Settings › Usage history (WP10a): keep usage for 90 days, 1 year, 2 years or always (`usageRetentionDays`), and "Clear
 * usage history", which keeps today (S29: today's use seeds the daily limits).
 *
 * @param ownerUsage owner-only: clearing the history.
 */
class UsageHistoryViewModel(
    @Suppress("unused") private val settings: SettingsRepository,
    @Suppress("unused") private val ownerUsage: OwnerUsage,
) : ViewModel() {
    val uiState: StateFlow<UsageHistoryUiState> = MutableStateFlow(UsageHistoryUiState()).asStateFlow()
}
