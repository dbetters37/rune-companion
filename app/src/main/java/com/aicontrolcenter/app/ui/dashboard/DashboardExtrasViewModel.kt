@file:Phase6Stub("WP10a")

package com.aicontrolcenter.app.ui.dashboard

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.app.health.SafeStartControl
import com.aicontrolcenter.app.usage.TodayUsage
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.health.BackgroundHealth
import com.aicontrolcenter.core.health.StorageWatch
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.core.tasks.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the Dashboard's Phase 6 parts show (WP10a fills it). */
@Immutable
data class DashboardExtrasUiState(val isLoading: Boolean = true)

/**
 * The Dashboard's Phase 6 parts (WP10a): the Today tile (answers, tokens and cost today and this month, cost only when
 * every used model has a price), Getting started (Tier B; `onboardingCompleted` gets its reader, D47), the phone
 * attention items (background check, storage, objectives paused by today's limit) and the safe-start banner (Tier B).
 * One view model for the four slots `DashboardScreen` places; each slot composable reads it.
 *
 * @param today today's use and the limits, read only.
 * @param settings the owner's writer: Getting started's "Done" sets `onboardingCompleted`.
 * @param tasks objective rows only (`observeObjectiveRows`), for "Paused: today's limit is reached".
 * @param safeStart whether safe start is on, and "Back to normal".
 */
class DashboardExtrasViewModel(
    @Suppress("unused") private val today: TodayUsage,
    @Suppress("unused") private val settings: SettingsRepository,
    @Suppress("unused") private val directory: RegistryDirectory,
    @Suppress("unused") private val tasks: TaskRepository,
    @Suppress("unused") private val backgroundHealth: BackgroundHealth,
    @Suppress("unused") private val storage: StorageWatch,
    @Suppress("unused") private val safeStart: SafeStartControl,
    @Suppress("unused") private val clock: Clock,
) : ViewModel() {
    val uiState: StateFlow<DashboardExtrasUiState> = MutableStateFlow(DashboardExtrasUiState()).asStateFlow()
}
