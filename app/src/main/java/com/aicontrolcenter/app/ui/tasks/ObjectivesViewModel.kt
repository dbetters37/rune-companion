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
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.registry.RegistryDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    /** The app may not show its progress notification: the owner refused or switched it off. */
    val notificationsOff: Boolean = false,
    /** Who receives objectives, or null when no Director is designated. */
    val directorName: String? = null,
) {
    val isEmpty: Boolean get() = activeCount + finishedCount == 0
}

/**
 * The owner's objectives, newest first, split into active (planning, running, paused or
 * interrupted) and finished ones. Progress and the agents involved come from each objective's
 * task tree, so the list moves as the team works.
 */
class ObjectivesViewModel(
    tasks: TaskRepository,
    directory: RegistryDirectory,
    orchestrator: Orchestrator,
    notifications: NotificationAccess,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
    initialFilter: ObjectiveFilter = ObjectiveFilter.ACTIVE,
) : ViewModel() {
    private val filter = MutableStateFlow(initialFilter)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val trees: Flow<List<List<TaskRecord>>> = tasks.observeObjectives().flatMapLatest { objectives ->
        if (objectives.isEmpty()) flowOf(emptyList()) else combine(objectives.map { tasks.observeTree(it.id) }) { it.toList() }
    }

    val uiState: StateFlow<ObjectivesUiState> = combine(
        trees,
        directory.observe(),
        orchestrator.running,
        notifications.enabled,
        filter,
    ) { trees, snapshot, running, notificationsOn, filter ->
        val faces = AgentFaces(snapshot)
        val now = clock.now()
        val all = trees.mapNotNull { tree ->
            val objective = tree.firstOrNull { it.id == it.rootId } ?: return@mapNotNull null
            objective to ObjectiveRowUi(
                id = objective.id,
                title = objective.title,
                status = objective.status.toStatusLabel(objective.kind),
                statusDetail = objective.statusDetail ?: objective.error,
                progress = ObjectiveProgress.of(tree),
                agents = participantsOf(tree, faces),
                started = formatCompactTimestamp(objective.startedAt ?: objective.createdAt, now, zone),
                isLive = objective.id in running,
            )
        }.sortedByDescending { (objective, _) -> objective.createdAt }
        val (finished, active) = all.partition { (objective, _) -> objective.status.isFinished }
        ObjectivesUiState(
            isLoading = false,
            filter = filter,
            rows = (if (filter == ObjectiveFilter.ACTIVE) active else finished).map { it.second },
            activeCount = active.size,
            finishedCount = finished.size,
            notificationsOff = !notificationsOn,
            directorName = snapshot.director?.profile?.name,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ObjectivesUiState(filter = initialFilter))

    fun setFilter(value: ObjectiveFilter) = filter.update { value }
}
