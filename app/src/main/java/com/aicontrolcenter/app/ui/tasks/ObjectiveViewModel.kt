package com.aicontrolcenter.app.ui.tasks

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.activity.TOKENS_IN_PLAIN_WORDS
import com.aicontrolcenter.app.ui.activity.aboutMoney
import com.aicontrolcenter.app.ui.chat.moneyOf
import com.aicontrolcenter.app.ui.components.localityOf
import com.aicontrolcenter.app.ui.components.onPhoneModels
import com.aicontrolcenter.app.ui.approvals.RequestUi
import com.aicontrolcenter.app.ui.approvals.isOpenAt
import com.aicontrolcenter.app.ui.approvals.requestUi
import com.aicontrolcenter.app.ui.components.ActivityRowUi
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.app.ui.components.formatDuration
import com.aicontrolcenter.app.ui.components.formatTimeOfDay
import com.aicontrolcenter.app.ui.components.putText
import com.aicontrolcenter.app.ui.components.textChanges
import com.aicontrolcenter.app.work.ObjectiveProgress
import com.aicontrolcenter.app.ui.tasks.markdown.Markdown
import com.aicontrolcenter.app.ui.tasks.markdown.MarkdownBlock
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.Orchestrator
import com.aicontrolcenter.core.tasks.OwnerConversation
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tasks.TaskSummary
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityInfo
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.ModelPrices
import com.aicontrolcenter.core.usage.UsageReports
import com.aicontrolcenter.core.usage.WorkKind
import com.aicontrolcenter.core.usage.WorkUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

enum class ObjectiveTab(val label: String) { PLAN("Plan"), CONVERSATION("Conversation"), LOG("Log"), RESULT("Result") }

/** Why an objective is not moving, in plain words, and what the owner can do about it. */
@Immutable
data class PauseNoticeUi(
    val kind: PauseKind,
    val title: String,
    val text: String,
)

enum class PauseKind {
    /** Today's limit was reached (Q2): Resume after midnight, or raise the limit. */
    DAILY_LIMIT,

    /** The phone is almost full (R8): free some room, then Resume. */
    STORAGE,

    /** The Director or a step waits for a provider that is not answering or asked the app to slow down (R4). */
    PROVIDER_WAIT,
}

/** What the objective's model calls cost so far: tokens, and money once prices are entered (rule 29). */
@Immutable
data class CostLineUi(val text: String, val note: String)

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
    /** Why it is not moving (paused by today's limit, the phone almost full, a provider wait); null otherwise. */
    val pause: PauseNoticeUi? = null,
    /** What its model calls cost so far; null before any call. */
    val cost: CostLineUi? = null,
    /** "Started by routine “Morning news”" for an objective a routine started. */
    val routine: String? = null,
    val routineId: RoutineId? = null,
    /** The routine's objective may not use other apps' screens (F12): "Screens: not allowed". */
    val screensNotAllowed: Boolean = false,
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

/**
 * What left the phone for this objective: the model calls the activity log shows leaving ([transfers], to
 * [destinations]), and the answers its usage rows record from models off this phone ([offPhoneAnswers]). The usage rows
 * are kept longer than the log and are not cut to the newest events, so "Nothing has left this phone" is said only when
 * neither shows anything (D3A-5).
 */
@Immutable
data class EgressUi(val transfers: Int = 0, val destinations: List<String> = emptyList(), val offPhoneAnswers: Long = 0) {
    /** Something of this objective left the phone, by either record. */
    val away: Boolean get() = transfers > 0 || offPhoneAnswers > 0
}

/** Who a note can go to: the Director (no task), or the agent working on one unfinished task. */
@Immutable
data class NoteRecipientUi(val taskId: TaskId?, val label: String)

/** The "Write to the team" composer (C2, Tier B). */
@Immutable
data class NotesUi(
    /** The objective is unfinished: notes can be written. */
    val canWrite: Boolean = false,
    /** The composer is open; closed, it is one button, so the conversation stays in view. */
    val isOpen: Boolean = false,
    val recipients: List<NoteRecipientUi> = emptyList(),
    /** Who the note goes to now; null is the Director. */
    val to: TaskId? = null,
    val notesWritten: Int = 0,
    val isSending: Boolean = false,
    /** Why the last note was not added, in the app's words. */
    val refusal: String? = null,
)

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
    /** [document] parsed, off the main thread (P7). */
    val documentBlocks: List<MarkdownBlock> = emptyList(),
    /** Why there is no document yet, or what it is based on. */
    val documentNote: String = "",
    val limits: LimitsUi? = null,
    /** Requests and questions the objective's agents put to the owner that are still open; their tasks wait for them. */
    val waitingFor: List<RequestUi> = emptyList(),
    val tab: ObjectiveTab = ObjectiveTab.PLAN,
    val notes: NotesUi = NotesUi(),
    val isBusy: Boolean = false,
    val message: UserMessage? = null,
) {
    val shownMessages: List<MessageUi> get() = messages.filter { messageFilter.matches(it.type) }

    /** Filters that would show something, with how much; "All" always. */
    val filterCounts: Map<MessageFilter, Int>
        get() = MessageFilter.entries.associateWith { filter -> messages.count { filter.matches(it.type) } }.filter { (filter, count) -> filter == MessageFilter.ALL || count > 0 }
}

/**
 * One objective as it happens: the Director's interpretation and plan, every task with its assignee, status, attempts,
 * revisions and reviews, the conversation between agents, the execution log with what left the phone, the cost so far
 * and the final document. The owner can pause, resume, cancel or delete it from here, and write to the team.
 *
 * What it reads (P1, P2, rule 16): the objective's task summaries (no task's instructions, result or reviews), the
 * objective's own record and the final-result task's record only when their summaries change, its messages, its log
 * and its cost rows. The state is built on [computation] and only when data changes: the 1-second clock of a running
 * objective moves [elapsed] alone, never the plan, the messages or the log.
 *
 * @param ticker makes the elapsed time move while the objective runs; tests pass a single tick.
 */
class ObjectiveViewModel(
    private val rootId: TaskId,
    private val tasks: TaskRepository,
    messages: MessageRepository,
    directory: RegistryDirectory,
    private val orchestrator: Orchestrator,
    activityLog: ActivityLog,
    settings: SettingsView,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
    ticker: Flow<Unit> = everySecond(),
    /** The requests waiting for the owner (`OwnerRequests.pending`); this objective's are shown with a link. */
    requests: Flow<List<OwnerRequest>> = flowOf(emptyList()),
    private val capabilityInfo: (Capability) -> CapabilityInfo? = { null },
    /**
     * Waits for the app's start-up work (recovering interrupted objectives) to finish. Resume and
     * Run again wait for it, so work restarted on a screen restored after the app was killed never
     * races the recovery.
     */
    private val awaitStartup: suspend () -> Unit = {},
    /** The owner's notes to the team (C2, Tier B); refuses everything while notes are off. */
    private val notes: OwnerConversation = OwnerConversation.Unavailable,
    /** The cost rows of the objective (read only). */
    usage: UsageReports = UsageReports.None,
    /** The owner's prices, as read. */
    prices: ModelPrices = ModelPrices.None,
    /** The owner's routines, for the title of the one that started this objective (read only). */
    routines: Flow<List<Routine>> = flowOf(emptyList()),
    /** The shared memory spaces, for the names of spaces a memory was shared into. */
    spaces: Flow<List<KnowledgeSpace>> = flowOf(emptyList()),
    /** Where the state is built (the app's background pool; tests stay on their dispatcher). */
    computation: CoroutineContext = EmptyCoroutineContext,
) : ViewModel() {

    private data class Local(
        val tab: ObjectiveTab = ObjectiveTab.PLAN,
        val filter: MessageFilter = MessageFilter.ALL,
        val isBusy: Boolean = false,
        val deleted: Boolean = false,
        val message: UserMessage? = null,
        val noteTo: TaskId? = null,
        val noteOpen: Boolean = false,
        val noteSending: Boolean = false,
        val noteRefusal: String? = null,
    )

    /** The objective as its summaries, its own record and the final result's instructions give it. */
    private data class Tree(val summaries: List<TaskSummary>, val objective: TaskRecord?, val interpretationSource: String?)

    private data class Stored(
        val tree: Tree,
        val messages: List<AgentMessage>,
        val snapshot: RegistrySnapshot,
        val live: Boolean,
        val settings: FrameworkSettings,
    )

    private data class Extras(val cost: List<WorkUsage>, val prices: List<ModelPrice>, val routines: List<Routine>, val spaces: List<KnowledgeSpace>)

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    /** How many times the state was built: a test hook (P2: the clock alone never rebuilds it). */
    internal val builds = AtomicInteger()

    /** The note being written (rule 27: typed straight into it). */
    val note: TextFieldState = TextFieldState()

    private val live = orchestrator.running.map { rootId in it }.distinctUntilChanged()

    /**
     * The objective's summaries (P1), with its own record read again only when its summary changes (status, times,
     * detail) and the final result's instructions read once that task exists. One read of the summaries for the whole
     * screen, shared by the state and the elapsed line.
     */
    private val tree: Flow<Tree> = flow {
        var objectiveKey: TaskSummary? = null
        var record: TaskRecord? = null
        var synthesisId: TaskId? = null
        var source: String? = null
        tasks.observeSummaries(rootId).collect { all ->
            val key = all.firstOrNull { it.id == rootId }
            if (key != objectiveKey) {
                objectiveKey = key
                record = if (key == null) null else tasks.get(rootId)
            }
            val synthesis = all.firstOrNull { it.kind == TaskKind.SYNTHESIS }?.id
            if (synthesis != synthesisId) {
                synthesisId = synthesis
                source = synthesis?.let { tasks.get(it)?.instructions }
            }
            emit(Tree(all, record, source))
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val stored = combine(tree, messages.observe(rootId), directory.observe(), live, settings.settings, ::Stored)

    private val extras = combine(usage.observeWorkUsage(WorkKind.OBJECTIVE, rootId.value), prices.observeAll(), routines, spaces, ::Extras)

    private val inputs = combine(stored, activityLog.observe(ActivityFilter(rootTaskId = rootId), limit = LOG_LIMIT), extras, ::Triple)

    val uiState: StateFlow<ObjectiveUiState> = combine(inputs, local, requests) { (stored, log, extras), local, requests ->
        build(stored, log, extras, local, requests)
    }.flowOn(computation).conflate().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ObjectiveUiState())

    /** The times the elapsed line is made of; they change only when the objective's record does. */
    private data class Times(val status: TaskStatus, val started: Long, val finished: Long?, val updated: Long)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val ticks: Flow<Unit> = live.flatMapLatest { if (it) ticker else flowOf(Unit) }

    /**
     * "Running for 4 min, since 10:42", "Took 12 min · started …", "Paused after 3 min · started …": the only part of the
     * screen that moves every second while the objective runs (P2), in a flow of its own.
     */
    val elapsed: StateFlow<String> = combine(
        tree.map { it.objective?.let { record -> Times(record.status, record.startedAt ?: record.createdAt, record.finishedAt, record.updatedAt) } }.distinctUntilChanged(),
        live,
        ticks,
    ) { times, live, _ -> times?.let { elapsedText(it, live, clock.now()) }.orEmpty() }
        .flowOn(computation).conflate().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    init {
        viewModelScope.launch { note.textChanges().collect { local.update { it.copy(noteRefusal = null) } } }
    }

    fun setTab(tab: ObjectiveTab) = local.update { it.copy(tab = tab) }

    fun setMessageFilter(filter: MessageFilter) = local.update { it.copy(filter = filter) }

    /** "Write to the team": opens the composer, or folds it away again with [open] false (the note typed so far is kept). */
    fun openNotes(open: Boolean = true) = local.update { it.copy(noteOpen = open) }

    /** Who the next note goes to: null for the Director, or the agent on [taskId]. */
    fun setNoteRecipient(taskId: TaskId?) = local.update { it.copy(noteTo = taskId) }

    /** "Send" in the "Write to the team" composer: the note as typed now, refused in the app's own words. */
    fun sendNote() {
        if (local.value.noteSending) return
        val text = note.text.toString().trim()
        OwnerConversation.textProblem(text)?.let { problem ->
            local.update { it.copy(noteRefusal = problem) }
            return
        }
        val to = local.value.noteTo?.takeIf { id -> uiState.value.notes.recipients.any { it.taskId == id } }
        local.update { it.copy(noteSending = true, noteRefusal = null) }
        viewModelScope.launch {
            val result = try {
                notes.addNote(rootId, to, text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                OwnerResult.Refused("Your note could not be saved: ${failure.message ?: "storage error"}.")
            }
            when (result) {
                is OwnerResult.Done -> {
                    note.putText("")
                    local.update { it.copy(noteSending = false, noteOpen = false, noteRefusal = null, message = UserMessage(NOTE_SENT, ++messageCounter)) }
                }
                is OwnerResult.Refused -> local.update { it.copy(noteSending = false, noteRefusal = result.reason) }
            }
        }
    }

    fun pause() = act { orchestrator.pause(rootId) }

    fun resume() = act {
        awaitStartup()
        orchestrator.resume(rootId)
    }

    fun cancel() = act("Cancelled. What the team produced is kept.") { orchestrator.cancel(rootId) }

    fun runAgain() = act {
        awaitStartup()
        orchestrator.retryTask(rootId)
    }

    fun delete() = act {
        orchestrator.delete(rootId)
        local.update { it.copy(deleted = true) }
    }

    fun messageShown() = local.update { it.copy(message = null) }

    fun documentCopied() = local.update { it.copy(message = UserMessage("Document copied.", ++messageCounter)) }

    private fun build(stored: Stored, log: List<ActivityEvent>, extras: Extras, local: Local, requests: List<OwnerRequest>): ObjectiveUiState {
        builds.incrementAndGet()
        val summaries = stored.tree.summaries
        val objective = stored.tree.objective?.takeIf { record -> summaries.any { it.id == record.id } }
            ?: return ObjectiveUiState(isLoading = false, notFound = true, deleted = local.deleted, message = local.message)
        val faces = AgentFaces(stored.snapshot, extras.spaces.associate { it.id to it.name })
        val now = clock.now()
        val progress = progressOf(summaries)
        val names = stored.snapshot.agentsById
        val pricesById = extras.prices.associateBy { it.modelId }
        return ObjectiveUiState(
            isLoading = false,
            deleted = local.deleted,
            header = header(objective, stored, faces, progress, extras, pricesById),
            plan = planRowsOf(summaries, faces),
            messages = messagesUiOf(stored.messages, summaries, faces, now, zone),
            messageFilter = local.filter,
            log = log.map { event ->
                ActivityRowUi(event, formatCompactTimestamp(event.at, now, zone), event.agentId?.let { names[it]?.profile?.name })
            },
            egress = egressOf(log, extras.cost, stored.snapshot),
            document = objective.result?.takeIf { it.isNotBlank() },
            documentBlocks = blocksOf(objective.result?.takeIf { it.isNotBlank() }),
            documentNote = documentNote(objective, progress),
            limits = limits(objective, stored, now),
            waitingFor = requests.filter { it.rootId == rootId && it.isOpenAt(now) }.map { requestUi(it, stored.snapshot, capabilityInfo, now, zone) },
            tab = local.tab,
            notes = notesOf(objective, summaries, stored.messages, faces, local),
            isBusy = local.isBusy,
            message = local.message,
        )
    }

    /** The last document parsed and its blocks: parsed again only when the document changes. */
    private var parsed: Pair<String, List<MarkdownBlock>>? = null

    private fun blocksOf(document: String?): List<MarkdownBlock> {
        if (document == null) return emptyList()
        parsed?.let { (text, blocks) -> if (text == document) return blocks }
        return Markdown.parse(document).also { parsed = document to it }
    }

    private fun header(
        objective: TaskRecord,
        stored: Stored,
        faces: AgentFaces,
        progress: ObjectiveProgress,
        extras: Extras,
        prices: Map<com.aicontrolcenter.core.ModelId, ModelPrice>,
    ): ObjectiveHeaderUi {
        val status = objective.status
        val started = objective.startedAt ?: objective.createdAt
        val options = objective.options
        val routine = options?.routineId?.let { id -> id to extras.routines.firstOrNull { it.id == id }?.title }
        return ObjectiveHeaderUi(
            id = objective.id,
            title = objective.title,
            text = objective.instructions,
            status = taskStatusLabel(status, TaskKind.OBJECTIVE, objective.retryAt, objective.statusDetail),
            statusDetail = objective.statusDetail?.takeIf { it.isNotBlank() }?.let(::ownerFacing),
            error = objective.error?.takeIf { it.isNotBlank() && it != objective.statusDetail },
            director = faces.of(objective.assignee),
            interpretation = interpretationOf(stored.tree.interpretationSource, stored.messages),
            started = formatCompactTimestamp(started, clock.now(), zone),
            progress = progress,
            isLive = stored.live,
            options = listOfNotNull(
                if (options?.reviewResults != false) "Results reviewed" else "No reviews",
                if (options?.saveKnowledge == true) "Saves what was learned" else null,
                options?.maxTasks?.let { "At most $it tasks" },
            ).joinToString(" · "),
            agents = participantsOfSummaries(stored.tree.summaries, faces),
            canPause = status == TaskStatus.RUNNING,
            canResume = status == TaskStatus.PAUSED || status == TaskStatus.INTERRUPTED,
            canCancel = !status.isFinished,
            canDelete = status.isFinished,
            canRunAgain = status in RUN_AGAIN && stored.tree.summaries.none { it.kind == TaskKind.WORK },
            pause = pauseOf(objective),
            cost = costLineOf(extras.cost, prices, stored.snapshot.onPhoneModels()),
            routine = routine?.let { (_, title) -> title?.let { "Started by routine “$it”" } ?: "Started by a routine" },
            routineId = routine?.first,
            screensNotAllowed = options != null && !options.screenActionsAllowed,
        )
    }

    private fun limits(objective: TaskRecord, stored: Stored, now: Long): LimitsUi {
        val settings = stored.settings
        return LimitsUi(
            tasksUsed = stored.tree.summaries.count { it.kind == TaskKind.WORK || it.kind == TaskKind.REVIEW },
            maxTasks = minOf(settings.maxTasksPerObjective, objective.options?.maxTasks ?: Int.MAX_VALUE).coerceAtLeast(1),
            messagesUsed = stored.messages.size,
            maxMessages = settings.maxMessagesPerObjective,
            timeLimitMinutes = settings.objectiveTimeoutMinutes,
            endsAt = objective.deadlineAt?.takeIf { stored.live && it > now }?.let { formatTimeOfDay(it, zone) },
            reached = limitsReached(stored.messages),
        )
    }

    /** The composer: open while the objective is unfinished; the Director and each unfinished task of the plan can be written to. */
    private fun notesOf(objective: TaskRecord, summaries: List<TaskSummary>, messages: List<AgentMessage>, faces: AgentFaces, local: Local): NotesUi {
        val open = !objective.status.isFinished
        val directorName = faces.of(objective.assignee)?.name?.takeIf { !it.equals("Director", ignoreCase = true) }
        val director = NoteRecipientUi(null, "The Director" + (directorName?.let { ", $it" } ?: ""))
        val working = summaries.filter { it.kind == TaskKind.WORK && !it.status.isFinished && it.assigneeId != null }
            .sortedWith(compareBy({ it.createdAt }, { it.id.value }))
            .map { task -> NoteRecipientUi(task.id, "${faces.of(task.assigneeId)?.name ?: "An agent"} · ${task.title}") }
        val recipients = listOf(director) + working
        return NotesUi(
            canWrite = open,
            isOpen = open && local.noteOpen,
            recipients = recipients,
            to = local.noteTo?.takeIf { id -> working.any { it.taskId == id } },
            notesWritten = messages.count { it.type == com.aicontrolcenter.core.tasks.MessageType.GUIDANCE },
            isSending = local.noteSending,
            refusal = local.noteRefusal,
        )
    }

    private fun documentNote(objective: TaskRecord, progress: ObjectiveProgress): String = when {
        objective.result != null -> "Written by the Director from the team's results."
        objective.status == TaskStatus.COMPLETED -> "The objective finished without a document."
        objective.status.isFinished -> "No final document: the objective ended (${objective.status.toStatusLabel(TaskKind.OBJECTIVE).text.lowercase()}) before the Director wrote it."
        progress.isPlanning -> "The Director writes the final document once the team has done the work. It is planning now."
        else -> "The Director writes the final document once every task has finished (${progress.completed} of ${progress.planned} done)."
    }

    private fun egressOf(log: List<ActivityEvent>, usage: List<WorkUsage>, snapshot: RegistrySnapshot): EgressUi {
        val away = log.mapNotNull { it.egress }.filter { it.locality != Locality.ON_DEVICE }
        // A model no longer registered counts as off the phone: a privacy line must never say less than happened.
        // Tokens are the proof a request was sent (a request refused before sending is counted as a call too).
        val offPhone = usage.filter { snapshot.localityOf(it.modelId) != Locality.ON_DEVICE && it.inputTokens + it.outputTokens > 0 }
        return EgressUi(away.size, away.map { it.destinationHost }.distinct(), offPhone.sumOf { it.calls })
    }

    private fun elapsedText(times: Times, live: Boolean, now: Long): String {
        val end = when {
            live -> now
            times.status.isFinished -> times.finished ?: times.updated
            else -> times.updated
        }
        val duration = formatDuration((end - times.started).coerceAtLeast(0))
        val since = formatCompactTimestamp(times.started, now, zone).sentenceCase()
        return when {
            live -> "Running for $duration, since $since"
            times.status == TaskStatus.PAUSED -> "Paused after $duration · started $since"
            times.status == TaskStatus.INTERRUPTED -> "Stopped after $duration · started $since"
            times.status.isFinished -> "Took $duration · started $since"
            else -> "Started $since, $duration ago"
        }
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

        /** What the composer says once a note is stored. */
        const val NOTE_SENT = "Note added. The team reads it from their next step."

        /** A tick now and then every second, for the elapsed time of a running objective. */
        fun everySecond(): Flow<Unit> = flow {
            while (true) {
                emit(Unit)
                delay(1_000)
            }
        }
    }
}

/** How far the plan has got, from its summaries ([TaskSummary.isPlanStep]). */
fun progressOf(summaries: List<TaskSummary>): ObjectiveProgress {
    val steps = summaries.filter { it.isPlanStep }
    return ObjectiveProgress(steps.count { it.status == TaskStatus.COMPLETED }, steps.size)
}

/**
 * Why [objective] is not moving, when the owner should know: paused by today's limit (Q2), paused because the phone is
 * almost full (R8), or the Director waiting for a provider while it plans (R4). Null otherwise.
 */
fun pauseOf(objective: TaskRecord): PauseNoticeUi? {
    val detail = objective.statusDetail
    return when {
        objective.status == TaskStatus.PAUSED && detail == com.aicontrolcenter.core.usage.DailyLimits.PAUSED_TEXT -> PauseNoticeUi(
            PauseKind.DAILY_LIMIT,
            "Paused: today's limit is reached",
            "The app stops asking models for the rest of today, so nothing more is spent. Resume after midnight, or raise today's " +
                "limit in Daily limits and resume now. What the team did so far is kept.",
        )
        objective.status == TaskStatus.PAUSED && detail == com.aicontrolcenter.app.work.WorkResult.STORAGE_PAUSED_DETAIL -> PauseNoticeUi(
            PauseKind.STORAGE,
            "Paused: the phone is almost full",
            "The app paused this objective so nothing is lost when the phone runs out of room. Free some room, for example by " +
                "removing photos or apps you do not need, then resume it.",
        )
        objective.status == TaskStatus.RUNNING && objective.retryAt != null -> WaitTexts.waitingFor(detail)?.let { what ->
            PauseNoticeUi(PauseKind.PROVIDER_WAIT, "The Director is waiting", "$what The objective goes on by itself.")
        }
        else -> null
    }
}

/**
 * The cost so far of an objective or a task from its usage rows: "So far: about US$0.03 · 12,400 tokens · 3 model
 * answers" once a used model has a price, else the tokens alone, with what a token is and that money is an estimate
 * (rule 29). Money is in US$, the app's one currency, as the chat's line (D3A-8): a price in another currency is no
 * price; models in [onPhone] cost nothing, even with a price entered (D3A-10). Null before any model call.
 */
fun costLineOf(
    rows: List<WorkUsage>,
    prices: Map<com.aicontrolcenter.core.ModelId, ModelPrice>,
    onPhone: Set<com.aicontrolcenter.core.ModelId> = emptySet(),
): CostLineUi? {
    if (rows.isEmpty()) return null
    val tokens = rows.sumOf { it.inputTokens + it.outputTokens }
    val calls = rows.sumOf { it.calls }
    val counted = prices.filterValues { it.counts }
    val charged = rows.filter { it.modelId !in onPhone }
    val money = moneyOf(charged, counted)
    val words = "%,d".format(java.util.Locale.ENGLISH, tokens) + " tokens · " + "%,d".format(java.util.Locale.ENGLISH, calls) + if (calls == 1L) " model answer" else " model answers"
    return when {
        money != null -> CostLineUi(
            "So far: ${aboutMoney(money)} · $words" + if (charged.any { it.modelId !in counted }) " (some models have no price)" else "",
            com.aicontrolcenter.core.usage.CostEstimate.DISCLAIMER + " $TOKENS_IN_PLAIN_WORDS",
        )
        charged.isEmpty() -> CostLineUi("So far: $words · free on this phone", "$TOKENS_IN_PLAIN_WORDS Models on this phone cost nothing.")
        else -> CostLineUi("So far: $words", "$TOKENS_IN_PLAIN_WORDS Enter your model's price in Models to see an estimated cost.")
    }
}

/**
 * The conversation timeline from whole messages, with task titles from the summaries. Message text is the agents'
 * (data, shown as it is).
 */
fun messagesUiOf(messages: List<AgentMessage>, summaries: List<TaskSummary>, faces: AgentFaces, now: Long, zone: ZoneId): List<MessageUi> {
    val titles = summaries.associate { it.id to it.title }
    return messages.map { message ->
        val aboutTask = message.taskId?.takeIf { it != message.rootId }
        MessageUi(
            id = message.id,
            type = message.type,
            from = faces.of(message.from),
            to = faces.of(message.to),
            time = formatCompactTimestamp(message.at, now, zone),
            body = message.body,
            taskTitle = aboutTask?.let(titles::get),
            taskId = aboutTask,
        )
    }
}
