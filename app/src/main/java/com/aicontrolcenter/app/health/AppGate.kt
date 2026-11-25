package com.aicontrolcenter.app.health

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.core.health.OwnerRecovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Says whether the app's data opened (Phase 6, R5, rules 9 and 30; WP2 item 9). The main activity touches the app's
 * graph only once the gate is open; while the data cannot be opened it shows the Recovery screen instead of a stuck app,
 * and the graph is never built.
 *
 * The check runs once, off the main thread, when the gate is made: [OwnerRecovery.state] opens the database once
 * (running Room's migrations if an update brought them) and sorts any failure. Until it answers the gate is
 * [State.Checking]; [whenOpen] then queues what the activity wants to do with the graph and runs it, in order, on the
 * main thread, once the data opened. If it never opens, the queued work is dropped: nothing reaches the graph.
 * [recheck] looks again (the Recovery screen's "Try again").
 *
 * @param recovery the database check and Start over; null opens the gate at once (a build without it, or a test that
 *   installed its own graph).
 * @param scope where the check runs; null opens the gate at once.
 * @param main where queued work runs: the main thread.
 * @param prepare runs on [scope], off the main thread, once the data opened and before the gate opens (wave 3, D45):
 *   the app builds its graph there, so the main thread never builds it at a cold start (the class loading and wiring
 *   of every service, which on a slow phone took long enough to be reported as not responding). A failure in it is
 *   ignored: the graph is then built on first use, as before.
 */
class AppGate(
    private val recovery: OwnerRecovery?,
    private val scope: CoroutineScope?,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val prepare: suspend () -> Unit = {},
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

    private val current = MutableStateFlow<State>(if (recovery == null || scope == null) State.Open else State.Checking)

    val state: StateFlow<State> = current.asStateFlow()

    /** Work waiting for the gate to open; touched on the main thread only. */
    private val waiting = ArrayDeque<() -> Unit>()

    init {
        if (current.value == State.Checking) check()
    }

    /**
     * Runs [action] on the calling (main) thread once the gate is open: now if it is, later (in order) while the check
     * runs, never while the data cannot be opened.
     */
    fun whenOpen(action: () -> Unit) {
        when (current.value) {
            State.Open -> action()
            State.Checking -> waiting.addLast(action)
            is State.NeedsRecovery -> Unit
        }
    }

    /**
     * As [whenOpen], for work of [owner] (an activity): queued work runs only while [owner] still exists, so an activity
     * destroyed while the data was opened (rotated, closed) never touches the graph afterwards (wave 3).
     */
    fun whenOpen(owner: LifecycleOwner, action: () -> Unit) {
        whenOpen { if (owner.lifecycle.currentState != Lifecycle.State.DESTROYED) action() }
    }

    /** Looks again whether the data opens (the owner tapped "Try again"). Does nothing unless the data could not be opened. */
    fun recheck() {
        if (current.value !is State.NeedsRecovery) return
        current.value = State.Checking
        check()
    }

    private fun check() {
        val check = recovery ?: return
        val where = scope ?: return
        where.launch {
            val database = try {
                check.state()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DatabaseState.OTHER
            }
            if (!database.needsRecovery) {
                try {
                    prepare()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Built on first use instead, on whatever thread asks.
                }
            }
            withContext(main) { settle(database) }
        }
    }

    private fun settle(database: DatabaseState) {
        if (database.needsRecovery) {
            current.value = State.NeedsRecovery(database)
            waiting.clear()
            return
        }
        current.value = State.Open
        while (waiting.isNotEmpty()) waiting.removeFirst().invoke()
    }

    companion object {
        /** A gate that is open from the start: for a graph a test installs, which never opens the real database. */
        fun open(): AppGate = AppGate(recovery = null, scope = null)
    }
}

/**
 * Shows [content] while [gate] is open and [recovery] when the data cannot be opened. Nothing is shown while the check
 * runs (a fraction of a second at most, behind Android's own start-up screen).
 */
@Composable
fun GateHost(gate: AppGate, recovery: @Composable (DatabaseState) -> Unit, content: @Composable () -> Unit) {
    when (val state = gate.state.collectAsState().value) {
        AppGate.State.Open -> content()
        is AppGate.State.NeedsRecovery -> recovery(state.database)
        AppGate.State.Checking -> Unit
    }
}
