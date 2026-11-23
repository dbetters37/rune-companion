package com.aicontrolcenter.app.ui.tasks

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.ActivityRowUi
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.app.ui.components.formatDuration
import com.aicontrolcenter.app.ui.components.formatTimeOfDay
import com.aicontrolcenter.app.work.ObjectiveProgress
import com.aicontrolcenter.app.ui.approvals.RequestUi
import com.aicontrolcenter.app.ui.approvals.isOpenAt
import com.aicontrolcenter.app.ui.approvals.requestUi
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityInfo
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.Orchestrator
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tasks.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

enum class ObjectiveTab(val label: String) { PLAN("Plan"), CONVERSATION("Conversation"), LOG("Log"), RESULT("Result") }

@Immutable
data class ObjectiveHeaderUi(
    val id: TaskId,
    val title: String,
    /** The objective as the owner wrote it. */
    val text: String,
    val status: StatusLabel,
    val statusDetail: String?,
    val error: String?,
    val director: AgentFaceUi?,
    /** What the Director understood the objective to mean; null before it has planned. */
    val interpretation: String?,
    val started: String,
    /** "Running for 4 min", "Took 12 min", "Paused after 3 min". */
    val elapsed: String,
    val progress: ObjectiveProgress,
    val isLive: Boolean,
    val options: String,
    val agents: List<AgentFaceUi>,
    val canPause: Boolean,
    val canResume: Boolean,
    val canCancel: Boolean,
    val canDelete: Boolean,
    /** Planning failed: the objective itself can be run again. */
    val canRunAgain: Boolean,
)

/** How much of each limit the objective has used, and the limits the orchestrator reported reaching. */
@Immutable
data class LimitsUi(
    val tasksUsed: Int,
    val maxTasks: Int,
    val messagesUsed: Int,
    val maxMessages: Int,
    val timeLimitMinutes: Int,
    /** When the objective's time runs out, while it is running. */
    val endsAt: String?,
    val reached: List<String>,
)

/** What left the phone for this objective, from the activity log. */
@Immutable
data class EgressUi(val transfers: Int = 0, val destinations: List<String> = emptyList())

@Immutable
data class ObjectiveUiState(
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    /** Set after this screen deleted the objective; the route leaves. */
    val deleted: Boolean = false,
    val header: ObjectiveHeaderUi? = null,
    val plan: List<PlanRowUi> = emptyList(),
    val messages: List<MessageUi> = emptyList(),
    val messageFilter: MessageFilter = MessageFilter.ALL,
    val log: List<ActivityRowUi> = emptyList(),
    val egress: EgressUi = EgressUi(),
    /** The final document (Markdown), once the Director has written it. */
    val document: String? = null,
    /** Why there is no document yet, or what it is based on. */
    val documentNote: String = "",
    val limits: LimitsUi? = null,
    /** Questions the objective's agents put to the owner that are still open; their tasks wait for them. */
    val waitingFor: List<RequestUi> = emptyList(),
    val tab: ObjectiveTab = ObjectiveTab.PLAN,
    val isBusy: Boolean = false,
    val message: UserMessage? = null,
) {
    val shownMessages: List<MessageUi> get() = messages.filter { messageFilter.matches(it.type) }

    /** Filters that would show something, with how much; "All" always. */
    val filterCounts: Map<MessageFilter, Int>
        get() = MessageFilter.entries.associateWith { filter -> messages.count { filter.matches(it.type) } }.filter { (filter, count) -> filter == MessageFilter.ALL || count > 0 }
}

/**
 * One objective as it happens: the Director's interpretation and plan, every task with its
 * assignee, status, attempts, revisions and reviews, the conversation between agents, the
 * execution log with what left the phone, and the final document. The owner can pause, resume,
 * cancel or delete it from here.
 *
 * @param ticker makes the elapsed time move while the objective runs; tests pass a single tick.
 */
class ObjectiveViewModel(
    private val rootId: TaskId,
    tasks: TaskRepository,
    messages: MessageRepository,
    directory: RegistryDirectory,
    private val orchestrator: Orchestrator,
    activityLog: ActivityLog,
    settings: SettingsRepository,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
    ticker: Flow<Unit> = everySecond(),
    /** The requests waiting for the owner (`OwnerRequests.pending`); this objective's are shown with a link. */
    requests: Flow<List<OwnerRequest>> = flowOf(emptyList()),
    private val capabilityInfo: (Capability) -> CapabilityInfo? = { null },
) : ViewModel() {

    private data class Local(
        val tab: ObjectiveTab = ObjectiveTab.PLAN,
        val filter: MessageFilter = MessageFilter.ALL,
        val isBusy: Boolean = false,
        val deleted: Boolean = false,
        val message: UserMessage? = null,
    )

    private data class Stored(
        val tree: List<TaskRecord>,
        val messages: List<AgentMessage>,
        val snapshot: RegistrySnapshot,
        val live: Boolean,
        val settings: FrameworkSettings,
    )

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    private val live = orchestrator.running.map { rootId in it }.distinctUntilChanged()

    private val stored = combine(tasks.observeTree(rootId), messages.observe(rootId), directory.observe(), live, settings.settings, ::Stored)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val ticks: Flow<Unit> = live.flatMapLatest { if (it) ticker else flowOf(Unit) }

    val uiState: StateFlow<ObjectiveUiState> = combine(
        stored,
        activityLog.observe(ActivityFilter(rootTaskId = rootId), limit = LOG_LIMIT),
        local,
        ticks,
        requests,
    ) { stored, log, local, _, requests -> build(stored, log, local, requests) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ObjectiveUiState())

    fun setTab(tab: ObjectiveTab) = local.update { it.copy(tab = tab) }

    fun setMessageFilter(filter: MessageFilter) = local.update { it.copy(filter = filter) }

    fun pause() = act { orchestrator.pause(rootId) }

    fun resume() = act { orchestrator.resume(rootId) }

    fun cancel() = act("Cancelled. What the team produced is kept.") { orchestrator.cancel(rootId) }

    fun runAgain() = act { orchestrator.retryTask(rootId) }

    fun delete() = act {
        orchestrator.delete(rootId)
        local.update { it.copy(deleted = true) }
    }

    fun messageShown() = local.update { it.copy(message = null) }

    fun documentCopied() = local.update { it.copy(message = UserMessage("Document copied.", ++messageCounter)) }

    private fun build(stored: Stored, log: List<ActivityEvent>, local: Local, requests: List<OwnerRequest>): ObjectiveUiState {
        val objective = stored.tree.firstOrNull { it.id == rootId }
            ?: return ObjectiveUiState(isLoading = false, notFound = true, deleted = local.deleted, message = local.message)
        val faces = AgentFaces(stored.snapshot)
        val now = clock.now()
        val progress = ObjectiveProgress.of(stored.tree)
        val names = stored.snapshot.agentsById
        return ObjectiveUiState(
            isLoading = false,
            deleted = local.deleted,
            header = header(objective, stored, faces, progress, now),
            plan = planRows(stored.tree, faces),
            messages = messagesUi(stored.messages, stored.tree, faces, now, zone),
            messageFilter = local.filter,
            log = log.map { event ->
                ActivityRowUi(event, formatCompactTimestamp(event.at, now, zone), event.agentId?.let { names[it]?.profile?.name })
            },
            egress = egressOf(log),
            document = objective.result?.takeIf { it.isNotBlank() },
            documentNote = documentNote(objective, progress),
            limits = limits(objective, stored, now),
            waitingFor = requests.filter { it.rootId == rootId && it.isOpenAt(now) }.map { requestUi(it, stored.snapshot, capabilityInfo, now, zone) },
            tab = local.tab,
            isBusy = local.isBusy,
            message = local.message,
        )
    }

    private fun header(objective: TaskRecord, stored: Stored, faces: AgentFaces, progress: ObjectiveProgress, now: Long): ObjectiveHeaderUi {
        val status = objective.status
        val started = objective.startedAt ?: objective.createdAt
        val end = when {
            stored.live -> now
            status.isFinished -> objective.finishedAt ?: objective.updatedAt
            else -> objective.updatedAt
        }
        val duration = formatDuration((end - started).coerceAtLeast(0))
        val since = formatCompactTimestamp(started, now, zone).sentenceCase()
        val elapsed = when {
            stored.live -> "Running for $duration, since $since"
            status == TaskStatus.PAUSED -> "Paused after $duration · started $since"
            status == TaskStatus.INTERRUPTED -> "Stopped after $duration · started $since"
            status.isFinished -> "Took $duration · started $since"
            else -> "Started $since, $duration ago"
        }
        val options = objective.options
        return ObjectiveHeaderUi(
            id = objective.id,
            title = objective.title,
            text = objective.instructions,
            status = status.toStatusLabel(TaskKind.OBJECTIVE),
            statusDetail = objective.statusDetail?.takeIf { it.isNotBlank() },
            error = objective.error?.takeIf { it.isNotBlank() && it != objective.statusDetail },
            director = faces.of(objective.assignee),
            interpretation = interpretationOf(stored.tree, stored.messages),
            started = formatCompactTimestamp(started, now, zone),
            elapsed = elapsed,
            progress = progress,
            isLive = stored.live,
            options = listOfNotNull(
                if (options?.reviewResults != false) "Results reviewed" else "No reviews",
                if (options?.saveKnowledge == true) "Saves what was learned" else null,
                options?.maxTasks?.let { "At most $it tasks" },
            ).joinToString(" · "),
            agents = participantsOf(stored.tree, faces),
            canPause = status == TaskStatus.RUNNING,
            canResume = status == TaskStatus.PAUSED || status == TaskStatus.INTERRUPTED,
            canCancel = !status.isFinished,
            canDelete = status.isFinished,
            canRunAgain = status in RUN_AGAIN && stored.tree.none { it.kind == TaskKind.WORK },
        )
    }

    private fun limits(objective: TaskRecord, stored: Stored, now: Long): LimitsUi {
        val settings = stored.settings
        return LimitsUi(
            tasksUsed = stored.tree.count { it.kind == TaskKind.WORK || it.kind == TaskKind.REVIEW },
            maxTasks = minOf(settings.maxTasksPerObjective, objective.options?.maxTasks ?: Int.MAX_VALUE).coerceAtLeast(1),
            messagesUsed = stored.messages.size,
            maxMessages = settings.maxMessagesPerObjective,
            timeLimitMinutes = settings.objectiveTimeoutMinutes,
            endsAt = objective.deadlineAt?.takeIf { stored.live && it > now }?.let { formatTimeOfDay(it, zone) },
            reached = limitsReached(stored.messages),
        )
    }

    private fun documentNote(objective: TaskRecord, progress: ObjectiveProgress): String = when {
        objective.result != null -> "Written by the Director from the team's results."
        objective.status == TaskStatus.COMPLETED -> "The objective finished without a document."
        objective.status.isFinished -> "No final document: the objective ended (${objective.status.toStatusLabel(TaskKind.OBJECTIVE).text.lowercase()}) before the Director wrote it."
        progress.isPlanning -> "The Director writes the final document once the team has done the work. It is planning now."
        else -> "The Director writes the final document once every task has finished (${progress.completed} of ${progress.planned} done)."
    }

    private fun egressOf(log: List<ActivityEvent>): EgressUi {
        val away = log.mapNotNull { it.egress }.filter { it.locality != Locality.ON_DEVICE }
        return EgressUi(away.size, away.map { it.destinationHost }.distinct())
    }

    /** Runs one owner command at a time; a refusal is shown in the orchestrator's own words. */
    private fun act(success: String? = null, action: suspend () -> Unit) {
        if (local.value.isBusy) return
        local.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val failure = try {
                action()
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: IllegalStateException) {
                refused.message ?: "That is not possible right now."
            } catch (gone: IllegalArgumentException) {
                gone.message ?: "That objective no longer exists."
            } catch (failure: Exception) {
                "Something went wrong: ${failure.message ?: "storage error"}"
            }
            local.update { state ->
                val text = failure ?: success
                state.copy(isBusy = false, message = text?.let { UserMessage(it, ++messageCounter) } ?: state.message)
            }
        }
    }

    companion object {
        const val LOG_LIMIT = 200
        private val RUN_AGAIN = setOf(TaskStatus.FAILED, TaskStatus.TIMED_OUT, TaskStatus.CANCELLED)

        /** A tick now and then every second, for the elapsed time of a running objective. */
        fun everySecond(): Flow<Unit> = flow {
            while (true) {
                emit(Unit)
                delay(1_000)
            }
        }
    }
}
