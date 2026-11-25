package com.aicontrolcenter.app.health

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.core.health.OwnerRecovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Says whether the app's data opened (Phase 6, R5, rules 9 and 30; WP2 owns this file in wave 2). The main activity
 * touches the app's graph only once the gate is open; while the data cannot be opened it shows the Recovery screen
 * instead of a stuck app, and the graph is never built.
 *
 * Wave 1: the gate is open at once, as the app was before Phase 6.
 *
 * @param recovery the database check and Start over; null opens the gate at once.
 * @param scope where the check runs (WP2: once, off the main thread).
 */
@Phase6Stub("WP2")
class AppGate(
    @Suppress("UNUSED_PARAMETER") recovery: OwnerRecovery?,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope?,
) {
    /** Where the gate is. */
    sealed interface State {
        /** The database is being opened. */
        data object Checking : State

        /** The data opened: the app can be used. */
        data object Open : State

        /** The data cannot be opened, for [database]'s reason: the Recovery screen shows. */
        data class NeedsRecovery(val database: DatabaseState) : State
    }

    private val current = MutableStateFlow<State>(State.Open)

    val state: StateFlow<State> = current.asStateFlow()

    /**
     * Runs [action] on the calling (main) thread once the gate is open: now if it is, never if the data cannot be
     * opened. Wave 1: the gate is always open, so [action] runs at once.
     */
    fun whenOpen(action: () -> Unit) {
        if (state.value == State.Open) action()
    }

    companion object {
        /** A gate that is open from the start: for a graph a test installs, which never opens the real database. */
        fun open(): AppGate = AppGate(recovery = null, scope = null)
    }
}

/**
 * Shows [content] while [gate] is open and [recovery] when the data cannot be opened. Nothing is shown while the check
 * runs (WP2 may show a calm "Opening your data" line).
 */
@Composable
fun GateHost(gate: AppGate, recovery: @Composable (DatabaseState) -> Unit, content: @Composable () -> Unit) {
    when (val state = gate.state.collectAsState().value) {
        AppGate.State.Open -> content()
        is AppGate.State.NeedsRecovery -> recovery(state.database)
        AppGate.State.Checking -> Unit
    }
}
