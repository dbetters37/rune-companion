package com.aicontrolcenter.app.ui.tasks

import com.aicontrolcenter.app.ui.components.OperatorOffTexts
import com.aicontrolcenter.app.ui.settings.PhoneOperatorSwitch
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.onPhoneModels
import com.aicontrolcenter.app.ui.approvals.RequestUi
import com.aicontrolcenter.app.ui.approvals.isOpenAt
import com.aicontrolcenter.app.ui.approvals.requestUi
import com.aicontrolcenter.app.ui.chat.ToolInfo
import com.aicontrolcenter.app.ui.chat.TranscriptItem
import com.aicontrolcenter.app.ui.chat.transcriptOf
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.app.ui.components.formatCount
import com.aicontrolcenter.app.ui.components.formatDuration
import com.aicontrolcenter.app.ui.components.formatTimeOfDay
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.agents.AgentActivityMonitor
import com.aicontrolcenter.core.agents.AgentRuntimeState
import com.aicontrolcenter.core.chat.AgentChat
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.Orchestrator
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tasks.TaskUsage
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.WorkUsage
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityInfo
import com.aicontrolcenter.core.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.conflate
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.tasks.TaskSummary
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.framework.agents.ToolMenu
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Another task, as a link. */
@Immutable
data class TaskLinkUi(val id: TaskId, val title: String, val kind: TaskKind, val status: StatusLabel)

@Immutable
data class ReviewUi(
    val reviewer: AgentFaceUi?,
    val status: StatusLabel,
    val summary: String,
    val issues: List<String>,
    val time: String,
)

@Immutable
data class TaskDetailUi(
    val id: TaskId,
    val rootId: TaskId,
    val objectiveTitle: String,
    val kind: TaskKind,
    val title: String,
    val instructions: String,
    val criteria: List<String>,
    val assignee: AgentFaceUi?,
    val requestedBy: String,
    val status: StatusLabel,
    val statusDetail: String?,
    val error: String?,
    /** What its agent is doing on it right now. */
    val liveActivity: String?,
    val attempt: Int,
    val revisionRound: Int,
    /** For a review, the task it checks; for delegated work, the task that delegated it. */
    val parent: TaskLinkUi?,
    val dependsOn: List<TaskLinkUi>,
    /** Its reviews and the work it delegated. */
    val children: List<TaskLinkUi>,
    /** Agents that delegated down to it, outermost first. */
    val delegatedBy: List<String>,
    val result: String?,
    val reviews: List<ReviewUi>,
    val usage: String?,
    /** Its model answers, tokens and estimated cost so far, from its usage rows (goal 1, D3A-4); null before any call. */
    val cost: CostLineUi? = null,
    val timing: String,
    val canRetry: Boolean,
    val canCancel: Boolean,
    /** Why it cannot be run again, when it has finished without success. */
    val retryNote: String?,
)

@Immutable
data class TaskDetailUiState(
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val task: TaskDetailUi? = null,
    /** The assignee's stored work on this task, read-only. */
    val transcript: List<TranscriptItem> = emptyList(),
    val messages: List<MessageUi> = emptyList(),
    /** Questions this task's agent put to the owner that are still open; the task waits for them. */
    val waitingFor: List<RequestUi> = emptyList(),
    val isBusy: Boolean = false,
    val message: UserMessage? = null,
    /** Phone operator is off now: a phone tool refused for that shows "Turn on" where it was refused. */
    val operatorSwitchedOff: Boolean = false,
    /** "Turn on" can switch it on here (the owner's switch is at hand). */
    val canTurnOnOperator: Boolean = false,
)

/**
 * One task of an objective: what was asked and how to tell it is done, who works on it, its
 * status, result and reviews, the messages about it, and the transcript of the agent's work.
 * Failed tasks can be run again and unfinished ones cancelled; the orchestrator decides whether
 * that is possible and its refusal is shown as is.
 */
class TaskDetailViewModel(
    private val taskId: TaskId,
    private val tasks: TaskRepository,
    private val messages: MessageRepository,
    private val directory: RegistryDirectory,
    private val chat: AgentChat,
    private val tools: ToolRegistry,
    private val orchestrator: Orchestrator,
    private val monitor: AgentActivityMonitor,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** The requests waiting for the owner (`OwnerRequests.pending`); this task's are shown with a link. */
    requests: Flow<List<OwnerRequest>> = flowOf(emptyList()),
    private val capabilityInfo: (Capability) -> CapabilityInfo? = { null },
    /**
     * Waits for the app's start-up work (recovering interrupted objectives) to finish. Run again
     * waits for it, so work restarted on a screen restored after the app was killed never races
     * the recovery.
     */
    private val awaitStartup: suspend () -> Unit = {},
    /** The shared memory spaces, for the names of spaces a memory was shared into. */
    spaces: Flow<List<KnowledgeSpace>> = flowOf(emptyList()),
    /** Where the state is built (the app's background pool; tests stay on their dispatcher). */
    computation: CoroutineContext = EmptyCoroutineContext,
    /** This task's usage rows (`UsageReports.observeWorkUsage(WorkKind.TASK, id)`), for its cost line (D3A-4). */
    workUsage: Flow<List<WorkUsage>> = flowOf(emptyList()),
    /** The owner's prices, read only. */
    prices: Flow<List<ModelPrice>> = flowOf(emptyList()),
    /** The owner's Phone operator switch, for "Turn on" under a refused phone tool; owner UI only. */
    private val operatorSwitch: PhoneOperatorSwitch? = null,
) : ViewModel() {

    private data class Local(val isBusy: Boolean = false, val message: UserMessage? = null)

    /** The objective's task summaries (P1) and this task's own record, read again only when its summary changes. */
    private data class Tree(val summaries: List<TaskSummary>, val task: TaskRecord?)

    private data class Stored(
        val tree: Tree,
        val messages: List<AgentMessage>,
        val snapshot: RegistrySnapshot,
        val working: Map<AgentId, AgentRuntimeState>,
        val spaces: List<KnowledgeSpace>,
    )

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    @OptIn(ExperimentalCoroutinesApi::class)
    private val stored: Flow<Stored?> = flow { emit(tasks.get(taskId)?.rootId) }.flatMapLatest { root ->
        if (root == null) flowOf(null) else combine(treeOf(root), messages.observe(root), directory.observe(), monitor.states, spaces, ::Stored)
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** The summaries of [root]'s tasks, with this task's record read again only when its own summary changes (any field). */
    private fun treeOf(root: TaskId): Flow<Tree> = flow {
        var key: TaskSummary? = null
        var record: TaskRecord? = null
        tasks.observeSummaries(root).collect { all ->
            val summary = all.firstOrNull { it.id == taskId }
            if (summary != key) {
                key = summary
                record = if (summary == null) null else tasks.get(taskId)
            }
            emit(Tree(all, record))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val entries: Flow<List<ConversationEntry>> = stored
        .map { it?.tree?.task?.conversationId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chat.observeEntries(id) }

    /** The task's cost so far: its usage rows at the owner's prices, models on this phone free (D3A-4, D3A-10). */
    private val cost: Flow<CostLineUi?> = combine(workUsage, prices, directory.observe()) { rows, priced, snapshot ->
        costLineOf(rows, priced.associateBy { it.modelId }, snapshot.onPhoneModels())
    }.distinctUntilChanged()

    val uiState: StateFlow<TaskDetailUiState> = combine(stored, entries, local, requests, cost) { stored, entries, local, requests, cost ->
        build(stored, entries, local, requests, cost)
    }
        .flowOn(computation).conflate()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskDetailUiState())

    fun retry() = act("Queued to run again.") {
        awaitStartup()
        orchestrator.retryTask(taskId)
    }

    fun cancel() = act("Cancelled. The Director continues without it.") { orchestrator.cancelTask(taskId) }

    fun messageShown() = local.update { it.copy(message = null) }

    /** "Turn on" under a phone tool refused because Phone operator is off: the owner's switch, as in Settings. */
    fun turnOnOperator() {
        val switch = operatorSwitch ?: return
        viewModelScope.launch {
            val text = try {
                switch.set(true)
                OperatorOffTexts.TURNED_ON
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                NewObjectiveViewModel.OPERATOR_NOT_TURNED_ON
            }
            local.update { it.copy(message = UserMessage(text, ++messageCounter)) }
        }
    }

    private fun build(stored: Stored?, entries: List<ConversationEntry>, local: Local, requests: List<OwnerRequest>, cost: CostLineUi?): TaskDetailUiState {
        val task = stored?.tree?.task?.takeIf { record -> stored.tree.summaries.any { it.id == record.id } }
            ?: return TaskDetailUiState(isLoading = false, notFound = true, message = local.message)
        val faces = AgentFaces(stored.snapshot, stored.spaces.associate { it.id to it.name })
        val now = clock.now()
        val tree = stored.tree.summaries
        val byId = tree.associateBy { it.id }
        fun link(t: TaskSummary) = TaskLinkUi(t.id, t.title, t.kind, taskStatusLabel(t.status, t.kind, t.retryAt, t.statusDetailShort))
        val objective = byId[task.rootId]
        val live = task.assignee?.let { stored.working[it] }?.takeIf { it.currentTaskId == task.id }
        return TaskDetailUiState(
            isLoading = false,
            task = TaskDetailUi(
                id = task.id,
                rootId = task.rootId,
                objectiveTitle = objective?.title.orEmpty(),
                kind = task.kind,
                title = task.title,
                instructions = task.instructions,
                criteria = task.completionCriteria,
                assignee = faces.of(task.assignee),
                requestedBy = faces.requester(task.requestedBy),
                status = taskStatusLabel(task.status, task.kind, task.retryAt, task.statusDetail),
                statusDetail = task.statusDetail?.takeIf { it.isNotBlank() }?.let(::ownerFacing),
                error = task.error?.takeIf { it.isNotBlank() && it != task.statusDetail },
                liveActivity = live?.let { it.detail ?: "Working" },
                attempt = task.attempt + 1,
                revisionRound = task.revisionRound,
                parent = task.parentId?.takeIf { it != task.rootId }?.let(byId::get)?.let(::link),
                dependsOn = task.dependsOn.mapNotNull(byId::get).map(::link),
                children = tree.filter { it.parentId == task.id && it.id != task.id }.map(::link),
                delegatedBy = task.delegationChain.mapNotNull { faces.of(it)?.name },
                result = task.result?.takeIf { it.isNotBlank() },
                reviews = task.reviews.map { verdict ->
                    ReviewUi(faces.of(verdict.reviewer), verdict.decision.toStatusLabel(), verdict.summary, verdict.issues, formatCompactTimestamp(verdict.at, now, zone))
                },
                // With a cost line, the answers and tokens are said there, once.
                usage = usageOf(task.usage, withModelCalls = cost == null),
                cost = cost,
                timing = timingOf(task, now, limitPaused = limitPaused(task.id, task.status, task.kind, tree, requests, now)),
                canRetry = task.status in RETRYABLE && task.kind != TaskKind.REVIEW && task.kind != TaskKind.OBJECTIVE && task.delegationChain.isEmpty(),
                canCancel = !task.status.isFinished && task.kind != TaskKind.OBJECTIVE,
                retryNote = retryNote(task),
            ),
            transcript = transcriptOf(entries, ::toolInfo, assignedModelName = null, turnRunning = task.status == TaskStatus.RUNNING),
            operatorSwitchedOff = !stored.snapshot.settings.operatorEnabled,
            canTurnOnOperator = operatorSwitch != null,
            messages = messagesUiOf(stored.messages.filter { it.taskId == task.id }, tree, faces, now, zone),
            waitingFor = requests.filter { it.taskId == task.id && it.isOpenAt(now) }.map { requestUi(it, stored.snapshot, capabilityInfo, now, zone) },
            isBusy = local.isBusy,
            message = local.message,
        )
    }

    /** A tool as the transcript names it; the agent's "open more tools" call is not a registered tool (WP15). */
    private fun toolInfo(functionName: String): ToolInfo? =
        if (functionName == ToolMenu.FUNCTION_NAME) ToolInfo(ToolMenu.TITLE, RiskLevel.SAFE)
        else tools.byFunctionName(functionName)?.spec?.let { ToolInfo(it.title, it.risk) }

    /** "8 model answers · 2 tool calls · 1,200 tokens in · 300 out" ("answers" as the limits and Usage count them, D3A-9). */
    private fun usageOf(usage: TaskUsage, withModelCalls: Boolean = true): String? {
        if (usage == TaskUsage()) return null
        return listOfNotNull(
            plural(usage.modelCalls, "model answer").takeIf { withModelCalls },
            usage.toolCalls.takeIf { it > 0 }?.let { plural(it, "tool call") },
            "${formatCount(usage.inputTokens)} tokens in · ${formatCount(usage.outputTokens)} out".takeIf { withModelCalls },
            usage.messagesSent.takeIf { it > 0 }?.let { plural(it, "message") + " sent" },
        ).joinToString(" · ").ifEmpty { null }
    }

    /**
     * Whether [task]'s time limit is paused now because a call waits for the owner: the task
     * itself waits for the owner (the broker marks it while its call waits), or it waits for a
     * teammate whose call waits for the owner (team.ask_agent: that request names this task),
     * or it waits for a task it delegated (a work task whose parent it is, in [tree]) whose own
     * limit is paused in one of these ways, any number of delegations down. That is the rule by
     * which the stored deadlines move when such a wait ends (`StoredDeadlines.moveDelegating`:
     * work tasks waiting for an agent, up the chain), so the screen says "paused" exactly where
     * the time-out moment it would otherwise show is about to move.
     * An agent's own ask (`permissions.request`, no tool) never waits and pauses nothing, and
     * a task that is not waiting runs against its limit whatever is open (round 2, B4).
     * [requests] are the ones a live call or ask still waits for (`OwnerRequests.pending`).
     */
    private fun limitPaused(task: TaskId, status: TaskStatus, kind: TaskKind, tree: List<TaskSummary>, requests: List<OwnerRequest>, now: Long, depth: Int = 0): Boolean = when (status) {
        TaskStatus.WAITING_FOR_OWNER -> true
        TaskStatus.WAITING_FOR_AGENT -> requests.any { it.taskId == task && it.toolId != null && it.isOpenAt(now) } ||
            kind == TaskKind.WORK && depth < MAX_DELEGATION_WALK &&
            tree.any { it.parentId == task && it.id != task && it.kind == TaskKind.WORK && limitPaused(it.id, it.status, it.kind, tree, requests, now, depth + 1) }
        else -> false
    }

    private fun timingOf(task: TaskRecord, now: Long, limitPaused: Boolean): String {
        val started = task.startedAt ?: return "Not started yet"
        val parts = arrayListOf("Started ${formatCompactTimestamp(started, now, zone)}")
        val finished = task.finishedAt
        when {
            finished != null -> parts += "took ${formatDuration((finished - started).coerceAtLeast(0))}"
            // Time spent waiting for the owner does not count: the limit moves on when the answer comes.
            limitPaused -> parts += "its time limit is paused while it waits for you"
            task.status == TaskStatus.RUNNING || task.status.isWaiting ->
                task.deadlineAt?.let { parts += "times out at ${formatTimeOfDay(it, zone)}" }
            else -> Unit
        }
        return parts.joinToString(" · ")
    }

    private fun retryNote(task: TaskRecord): String? = when {
        task.status !in RETRYABLE -> null
        task.kind == TaskKind.REVIEW -> "A review is not run again on its own: run the task it reviewed again."
        task.delegationChain.isNotEmpty() -> "Delegated work is run by the task that delegated it: run that task again."
        else -> null
    }

    private fun act(success: String, action: suspend () -> Unit) {
        if (local.value.isBusy) return
        local.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val text = try {
                action()
                success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: IllegalStateException) {
                refused.message ?: "That is not possible right now."
            } catch (gone: IllegalArgumentException) {
                gone.message ?: "That task no longer exists."
            } catch (failure: Exception) {
                "Something went wrong: ${failure.message ?: "storage error"}"
            }
            local.update { it.copy(isBusy = false, message = UserMessage(text, ++messageCounter)) }
        }
    }

    private companion object {
        val RETRYABLE = setOf(TaskStatus.FAILED, TaskStatus.TIMED_OUT, TaskStatus.CANCELLED)

        /** How far down delegated tasks are followed; the framework walks the same distance up (`StoredDeadlines`). */
        const val MAX_DELEGATION_WALK = 16
    }
}

