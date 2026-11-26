package com.aicontrolcenter.app.ui.tasks

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.app.work.NotificationAccess
import com.aicontrolcenter.app.work.ObjectiveProgress
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.Orchestrator
import com.aicontrolcenter.core.tasks.ObjectiveRow
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.registry.RegistryDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.conflate
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.ZoneId

enum class ObjectiveFilter(val label: String) { ACTIVE("Active"), FINISHED("Finished") }

/** One objective in the list. */
@Immutable
data class ObjectiveRowUi(
    val id: TaskId,
    val title: String,
    val status: StatusLabel,
    val statusDetail: String?,
    val progress: ObjectiveProgress,
    val agents: List<AgentFaceUi>,
    val started: String,
    /** Has a live job right now (not paused, not finished). */
    val isLive: Boolean,
)

@Immutable
data class ObjectivesUiState(
    val isLoading: Boolean = true,
    val filter: ObjectiveFilter = ObjectiveFilter.ACTIVE,
    val rows: List<ObjectiveRowUi> = emptyList(),
    val activeCount: Int = 0,
    val finishedCount: Int = 0,
    /** Older objectives exist beyond the ones loaded: "Show older" loads the next [ObjectivesViewModel.PAGE]. */
    val hasOlder: Boolean = false,
    /** The app may not show its progress notification: the owner refused or switched it off. */
    val notificationsOff: Boolean = false,
    /** Who receives objectives, or null when no Director is designated. */
    val directorName: String? = null,
) {
    val isEmpty: Boolean get() = activeCount + finishedCount == 0
}

/**
 * The owner's objectives, newest first, split into active (planning, running, paused or interrupted) and finished
 * ones. One summary query (P1, rule 16): the newest [PAGE] objective rows, each with its plan's progress and the agents
 * involved, and [PAGE] more for every "Show older"; no task tree is read.
 */
class ObjectivesViewModel(
    tasks: TaskRepository,
    directory: RegistryDirectory,
    orchestrator: Orchestrator,
    notifications: NotificationAccess,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
    initialFilter: ObjectiveFilter = ObjectiveFilter.ACTIVE,
    /** Where the state is built (the app's background pool; tests stay on their dispatcher). */
    computation: CoroutineContext = EmptyCoroutineContext,
) : ViewModel() {
    private val filter = MutableStateFlow(initialFilter)

    /** How many rows are shown; one more is read to know whether older ones exist. */
    private val shown = MutableStateFlow(PAGE)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rows: Flow<List<ObjectiveRow>> = shown.flatMapLatest { limit -> tasks.observeObjectiveRows(limit + 1) }

    val uiState: StateFlow<ObjectivesUiState> = combine(
        rows,
        directory.observe(),
        orchestrator.running,
        notifications.enabled,
        combine(filter, shown, ::Pair),
    ) { rows, snapshot, running, notificationsOn, (filter, limit) ->
        val faces = AgentFaces(snapshot)
        val now = clock.now()
        val all = rows.take(limit).map { row ->
            row to ObjectiveRowUi(
                id = row.rootId,
                title = row.title,
                status = row.status.toStatusLabel(TaskKind.OBJECTIVE),
                statusDetail = row.statusDetailShort,
                progress = ObjectiveProgress(row.completed, row.planned),
                agents = row.participants.mapNotNull(faces::of),
                started = formatCompactTimestamp(row.createdAt, now, zone),
                isLive = row.rootId in running,
            )
        }
        val (finished, active) = all.partition { (row, _) -> row.status.isFinished }
        ObjectivesUiState(
            isLoading = false,
            filter = filter,
            rows = (if (filter == ObjectiveFilter.ACTIVE) active else finished).map { it.second },
            activeCount = active.size,
            finishedCount = finished.size,
            hasOlder = rows.size > limit,
            notificationsOff = !notificationsOn,
            directorName = snapshot.director?.profile?.name,
        )
    }.flowOn(computation).conflate().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ObjectivesUiState(filter = initialFilter))

    fun setFilter(value: ObjectiveFilter) = filter.update { value }

    /** "Show older": [PAGE] more objectives. */
    fun showOlder() = shown.update { it + PAGE }

    companion object {
        /** Objectives per page of the list. */
        const val PAGE = 50
    }
}
