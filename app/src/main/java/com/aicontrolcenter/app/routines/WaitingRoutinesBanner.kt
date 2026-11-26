package com.aicontrolcenter.app.routines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.RoutineRunId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.framework.routines.WaitingRoutine
import com.aicontrolcenter.framework.routines.WaitingRoutines
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** One run waiting for the owner's tap, as the banner shows it. */
@Immutable
data class WaitingRoutineUi(
    val run: WaitingRoutine,
    /** "Routine \"Morning news\" is waiting to start". */
    val headline: String,
    /** When it was due, in plain words. */
    val due: String,
)

@Immutable
data class WaitingRoutinesUiState(
    val items: List<WaitingRoutineUi> = emptyList(),
    /** The run the owner tapped Start on, asked about once more. */
    val confirming: WaitingRoutineUi? = null,
    /** A start is under way: the buttons wait. */
    val busy: Boolean = false,
    val message: UserMessage? = null,
    /** The objective the last confirmed start began (wave 3, WP10c: the confirmation step links to it). */
    val started: TaskId? = null,
)

/**
 * The "Routine '<title>' is waiting to start" banner's state (Phase 6, plan Appendix A; security review of wave 1, F12;
 * WP17). A run waits for a tap when Android did not let the app start it by itself, ran it too late, or the owner opens
 * the app after its time. Nothing starts until the owner taps Start **and** confirms; then exactly that run starts, with
 * every check of a routine run made again ([WaitingRoutines.startWaiting]). "Not now" records it as missed.
 *
 * It looks for due times that passed without a run when it is created ([WaitingRoutines.catchUp]): the owner is looking
 * at the app, so that is when they become waiting runs. Owner-only: it holds the owner's [WaitingRoutines].
 */
class WaitingRoutinesViewModel(
    private val waiting: WaitingRoutines,
    private val clock: Clock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {
    private data class Local(
        val confirming: RoutineRunId? = null,
        val busy: Boolean = false,
        val message: UserMessage? = null,
        val started: TaskId? = null,
    )

    private val local = MutableStateFlow(Local())
    private var messages = 0L

    val uiState: StateFlow<WaitingRoutinesUiState> = combine(waiting.observeWaiting(), local) { runs, local ->
        val now = clock.now()
        val items = runs.map { run ->
            WaitingRoutineUi(run, headline(run.title), "It was due at ${formatCompactTimestamp(run.dueAt, now, zone())}. Nothing starts until you tap Start.")
        }
        WaitingRoutinesUiState(items, items.firstOrNull { it.run.runId == local.confirming }, local.busy, local.message, local.started)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WaitingRoutinesUiState())

    init {
        viewModelScope.launch {
            try {
                waiting.catchUp()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Looked at again the next time the banner is shown; nothing starts either way.
            }
        }
    }

    /** The owner tapped Start: ask once more. */
    fun askToStart(run: WaitingRoutine) = local.update { it.copy(confirming = run.runId) }

    fun cancelStart() = local.update { it.copy(confirming = null) }

    /** The owner confirmed: start exactly the run they confirmed, once. */
    fun confirmStart() {
        val runId = local.value.confirming ?: return
        val item = uiState.value.items.firstOrNull { it.run.runId == runId }?.run
        local.update { it.copy(confirming = null, busy = true) }
        if (item == null) {
            local.update { it.copy(busy = false) }
            return
        }
        viewModelScope.launch {
            var started: TaskId? = null
            val text = try {
                when (val result = waiting.startWaiting(item.routineId, item.runId)) {
                    is OwnerResult.Done -> {
                        started = result.value.objectiveId
                        "Started: the team is working on \"${item.title}\"."
                    }
                    is OwnerResult.Refused -> result.reason
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                "The routine could not be started. Try again."
            }
            local.update { it.copy(busy = false, message = UserMessage(text, ++messages), started = started) }
        }
    }

    /** The owner chose not to start it now: it is recorded as missed. */
    fun notNow(run: WaitingRoutine) {
        viewModelScope.launch {
            waiting.dismissWaiting(run.routineId, run.runId)
        }
    }

    fun messageShown() = local.update { it.copy(message = null, started = null) }

    /** Texts. */
    companion object {
        fun headline(title: String): String = "Routine \"${title.trim()}\" is waiting to start"

        /** What the owner is asked before the run starts. */
        fun confirmation(run: WaitingRoutine): String = buildString {
            append("The team will work on: \"").append(run.objectiveText.trim().take(MAX_OBJECTIVE_SHOWN))
            if (run.objectiveText.trim().length > MAX_OBJECTIVE_SHOWN) append("…")
            append("\"\n\nIt uses your models and counts towards today's limits. ")
            append(if (run.allowScreenActions) "It may use other apps' screens." else "It may not use other apps' screens.")
            append(" Every permission and every action that is hard to undo is still asked for.")
        }

        private const val MAX_OBJECTIVE_SHOWN = 400
    }
}

/**
 * The banner (WP17's; wave 3 places it at the top of the Dashboard and the Routines screen): one card per waiting run,
 * with Start (asks once more, then starts exactly that run) and "Not now". Shows nothing when nothing waits. The
 * confirmation refuses touches while another app's window covers it ([ConfirmDialog] guarded), and screen control
 * never operates this app.
 */
@Composable
fun WaitingRoutinesBanner(viewModel: WaitingRoutinesViewModel, modifier: Modifier = Modifier, onOpenObjective: ((TaskId) -> Unit)? = null) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    WaitingRoutinesBanner(
        state = state,
        onStart = viewModel::askToStart,
        onNotNow = viewModel::notNow,
        onConfirm = viewModel::confirmStart,
        onCancel = viewModel::cancelStart,
        onMessageShown = viewModel::messageShown,
        modifier = modifier,
        onOpenObjective = onOpenObjective?.let { open -> { id: TaskId -> viewModel.messageShown(); open(id) } },
    )
}

@Composable
fun WaitingRoutinesBanner(
    state: WaitingRoutinesUiState,
    onStart: (WaitingRoutine) -> Unit,
    onNotNow: (WaitingRoutine) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the objective a confirmed start began; null: no link (the Dashboard's banner). */
    onOpenObjective: ((TaskId) -> Unit)? = null,
) {
    if (state.items.isEmpty() && state.message == null) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (item in state.items) {
            AppCard(borderColor = appColors.attention) {
                Text(item.headline, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(item.due, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onStart(item.run) }, enabled = !state.busy) { Text("Start") }
                    TextButton(onClick = { onNotNow(item.run) }, enabled = !state.busy) { Text("Not now") }
                }
            }
        }
        state.message?.let { message ->
            AppCard {
                Text(message.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val started = state.started
                    if (started != null && onOpenObjective != null) {
                        Button(onClick = { onOpenObjective(started) }) { Text("Open the objective") }
                    }
                    TextButton(onClick = onMessageShown) { Text("OK") }
                }
            }
        }
    }
    state.confirming?.let { item ->
        ConfirmDialog(
            title = "Start \"${item.run.title.trim()}\" now?",
            message = WaitingRoutinesViewModel.confirmation(item.run),
            confirmLabel = "Start now",
            onConfirm = onConfirm,
            onDismiss = onCancel,
            cautionary = true,
            guarded = true,
        )
    }
}
