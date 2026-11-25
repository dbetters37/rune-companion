@file:Phase6Stub("WP10c")

package com.aicontrolcenter.app.ui.routines

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.routines.OwnerRoutines
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.framework.routines.WaitingRoutines
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId

/** What the Routines list shows (WP10c fills it). */
@Immutable
data class RoutinesUiState(val isLoading: Boolean = true)

/** What the routine editor shows (WP10c fills it). */
@Immutable
data class RoutineEditorUiState(val isLoading: Boolean = true)

/**
 * The Routines list (WP10c). Owner-only: it holds the owner's routines port.
 *
 * @param waiting how many runs wait for a tap (the screen links to the confirmation step).
 * @param settings read only: the master switch.
 */
class RoutinesViewModel(
    @Suppress("unused") private val routines: OwnerRoutines,
    @Suppress("unused") private val waiting: WaitingRoutines,
    @Suppress("unused") private val settings: SettingsView,
    @Suppress("unused") private val clock: Clock,
    @Suppress("unused") private val zone: () -> ZoneId,
) : ViewModel() {
    val uiState: StateFlow<RoutinesUiState> = MutableStateFlow(RoutinesUiState()).asStateFlow()
}

/**
 * A new routine, or one to change (WP10c): title (at most 80 characters), the objective (at most 4,000), the schedule
 * (daily or every few hours, with weekdays), the approval time-out (1..240 minutes or "as usual") and "May use other
 * apps' screens" (off by default); the run history with each outcome's words (scratchpad/phase6/handoff-wp17.md §6).
 */
class RoutineEditorViewModel(
    @Suppress("unused") private val routineId: RoutineId?,
    @Suppress("unused") private val routines: OwnerRoutines,
    @Suppress("unused") private val clock: Clock,
    @Suppress("unused") private val zone: () -> ZoneId,
) : ViewModel() {
    /** The routine's title and objective (rule 27: the fields' text lives here). */
    val title: TextFieldState = TextFieldState()
    val objective: TextFieldState = TextFieldState()

    val uiState: StateFlow<RoutineEditorUiState> = MutableStateFlow(RoutineEditorUiState()).asStateFlow()
}
