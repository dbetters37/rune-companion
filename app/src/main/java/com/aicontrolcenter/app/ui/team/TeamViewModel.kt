package com.aicontrolcenter.app.ui.team

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.core.tools.CapabilityInfo
import com.aicontrolcenter.app.ui.approvals.RequestUi
import com.aicontrolcenter.app.ui.approvals.requestUi
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.app.ui.tasks.AgentFaces
import com.aicontrolcenter.app.ui.tasks.MessageUi
import com.aicontrolcenter.app.ui.tasks.label
import com.aicontrolcenter.app.ui.tasks.messagesUiOf
import com.aicontrolcenter.app.ui.tasks.taskStatusLabel
import com.aicontrolcenter.app.ui.tasks.WaitTexts
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.agents.AgentActivityMonitor
import com.aicontrolcenter.core.agents.AgentRuntimeState
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageHeader
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ObjectiveRow
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tasks.TaskSummary
import com.aicontrolcenter.core.tools.Capability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Which objectives the picture shows: every current one (running, paused or stopped), or one. */
sealed interface TeamFilter {
    data object AllCurrent : TeamFilter

    data class One(val id: TaskId) : TeamFilter
}

/** One objective in the row of choices above the picture. */
@Immutable
data class ObjectiveChipUi(val id: TaskId, val title: String, val status: StatusLabel, val finished: Boolean)

/** Why an objective does not move, in the owner's words: "Paused: today's limit is reached" and what to do. */
@Immutable
data class ObjectiveNoticeUi(val objectiveId: TaskId, val objectiveTitle: String, val title: String, val text: String, val tone: StatusTone)

/** The one objective shown: its status, the routine that started it, and why it does not move. */
@Immutable
data class ObjectiveCardUi(
    val id: TaskId,
    val title: String,
    val status: StatusLabel,
    /** "Started by routine “Morning news”"; null when the owner started it. */
    val routine: String?,
    val notice: ObjectiveNoticeUi?,
)

/** One message as a sheet lists it: header only (kind, who, when, the first words). */
@Immutable
data class HeaderRowUi(
    val id: MessageId,
    val type: MessageType,
    val label: String,
    val from: String,
    val to: String,
    val time: String,
    /** The first words of the message as stored (the agents' text: data, shown as it is). */
    val preview: String,
)

/** One of a member's links, as its sheet lists it: "asked Analyst 2 times". */
@Immutable
data class LinkRowUi(val key: String, val otherName: String, val words: String, val kind: LinkKind, val count: Int)

/** What the open sheet shows. */
@Immutable
sealed interface TeamSheetUi {
    /** An agent, the Director, a former member, or a space. */
    data class Member(
        val node: TeamNode,
        val links: List<LinkRowUi>,
        val messages: List<HeaderRowUi>,
        val requests: List<RequestUi>,
        /** A space: who shared into it in what is shown. */
        val sharedBy: List<String> = emptyList(),
    ) : TeamSheetUi

    /** Every message on one link, in order; the whole texts once read ([messages] null while they load). */
    data class Link(
        val link: TeamLink,
        val title: String,
        val previews: List<HeaderRowUi>,
        val messages: List<MessageUi>?,
    ) : TeamSheetUi

    /** "You": what waits for you (oldest first) and what the team and the app told you. */
    data class You(val requests: List<RequestUi>, val news: List<HeaderRowUi>, val links: List<LinkRowUi>) : TeamSheetUi
}

/** Everything the Team view shows. */
@Immutable
data class TeamUiState(
    val isLoading: Boolean = true,
    val filter: TeamFilter = TeamFilter.AllCurrent,
    /** Current objectives first (newest first), then the latest finished ones. */
    val objectives: List<ObjectiveChipUi> = emptyList(),
    val currentCount: Int = 0,
    /** With one objective chosen. */
    val card: ObjectiveCardUi? = null,
    /** With all current ones: those that are paused or stopped, and why. */
    val notices: List<ObjectiveNoticeUi> = emptyList(),
    val graph: TeamGraph = TeamGraph(),
    /** "2 working, 1 waiting for you, 1 waiting for a teammate" as it is now (the spoken one is [TeamViewModel.liveSummary]). */
    val summary: String = "",
    /** Nothing runs: what the screen can still do. */
    val emptyNote: String? = null,
    val sheet: TeamSheetUi? = null,
    val message: UserMessage? = null,
)

/**
 * The Team view's state (goal 9; WP9), built off the main thread ([computation]) from summaries only (rule 16:
 * `observeObjectiveRows`, `observeSummaries`, `observeHeaders`; the objective's own record is read once per change of its
 * summary, for its routine and its status line; message bodies only when a link's sheet opens). The spoken summary
 * ([liveSummary]) changes at most every [summaryEveryMs].
 *
 * @param pendingRequests who waits for the owner: PERMISSION, APPROVAL and QUESTION (`OwnerRequests.pending`).
 * @param memory the spaces along the bottom (`observeSpaces`), read as the owner reads them.
 * @param routines the owner's routines, read only, for "Started by routine “…”".
 */
class TeamViewModel(
    objectiveId: TaskId?,
    private val tasks: TaskRepository,
    private val messages: MessageRepository,
    directory: RegistryDirectory,
    pendingRequests: Flow<List<OwnerRequest>>,
    monitor: AgentActivityMonitor,
    memory: MemoryService,
    private val clock: Clock,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    routines: Flow<List<Routine>> = flowOf(emptyList()),
    private val capabilityInfo: (Capability) -> CapabilityInfo? = { null },
    private val computation: CoroutineContext = EmptyCoroutineContext,
    summaryEveryMs: Long = SUMMARY_EVERY_MS,
) : ViewModel() {

    /** What the owner opened. */
    private sealed interface Selection {
        data class Member(val id: String) : Selection
        data class Link(val key: String) : Selection
        data object You : Selection
    }

    private data class Local(
        val filter: TeamFilter,
        val selection: Selection? = null,
        /** Whole messages of the open link, once read. */
        val bodies: Map<String, List<AgentMessage>> = emptyMap(),
        val message: UserMessage? = null,
    )

    /** One shown objective: its summaries, its own record, and its message headers. */
    private data class Root(val id: TaskId, val summaries: List<TaskSummary>, val record: TaskRecord?, val headers: List<MessageHeader>)

    private data class Inputs(
        val rows: List<ObjectiveRow>,
        val roots: List<Root>,
        val snapshot: RegistrySnapshot,
        val requests: List<OwnerRequest>,
        val monitor: Map<com.aicontrolcenter.core.AgentId, AgentRuntimeState>,
        val spaces: List<KnowledgeSpace>,
        val routines: List<Routine>,
    )

    /** The picture and everything around it, before a sheet is chosen. */
    private data class Built(val inputs: Inputs, val filter: TeamFilter, val state: TeamUiState)

    private val local = MutableStateFlow(Local(objectiveId?.let(TeamFilter::One) ?: TeamFilter.AllCurrent))
    private var messageCounter = 0L

    /** How many times the picture was built: a test hook (opening a sheet never rebuilds it). */
    internal val builds = AtomicInteger()

    private val rows = tasks.observeObjectiveRows(OBJECTIVE_ROWS)

    private val filter = local.map { it.filter }.distinctUntilChanged()

    private val shown: Flow<List<TaskId>> = combine(rows, filter) { rows, filter ->
        when (filter) {
            TeamFilter.AllCurrent -> rows.filter { !it.status.isFinished }.take(MAX_CURRENT).map { it.rootId }
            is TeamFilter.One -> listOf(filter.id)
        }
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val roots: Flow<List<Root>> = shown.flatMapLatest { ids ->
        if (ids.isEmpty()) flowOf(emptyList()) else combine(ids.map(::root)) { it.toList() }
    }

    /** One objective's summaries with its record (read again only when its own summary changes), and its headers. */
    private fun root(id: TaskId): Flow<Root> {
        val tree = flow {
            var key: TaskSummary? = null
            var record: TaskRecord? = null
            tasks.observeSummaries(id).collect { all ->
                val own = all.firstOrNull { it.id == id }
                if (own != key) {
                    key = own
                    record = if (own == null) null else tasks.get(id)
                }
                emit(all to record)
            }
        }
        return combine(tree, messages.observeHeaders(id, HEADER_LIMIT)) { (summaries, record), headers -> Root(id, summaries, record, headers) }
    }

    private val inputs: Flow<Inputs> = combine(
        combine(rows, roots, directory.observe(), ::Triple),
        pendingRequests,
        monitor.states,
        memory.observeSpaces(),
        routines,
    ) { (rows, roots, snapshot), requests, monitor, spaces, routines -> Inputs(rows, roots, snapshot, requests, monitor, spaces, routines) }

    private val built: Flow<Built> = combine(inputs, filter) { inputs, filter -> Built(inputs, filter, build(inputs, filter)) }
        .flowOn(computation)
        .conflate()

    val uiState: StateFlow<TeamUiState> = combine(built, local) { built, local -> withSheet(built, local) }
        .flowOn(computation)
        .conflate()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TeamUiState())

    /**
     * The summary TalkBack reads (one polite live region): it changes at most every [summaryEveryMs] and only when the
     * numbers change. Single messages are never announced.
     */
    val liveSummary: StateFlow<String> = built.map { it.state.summary }
        .distinctUntilChanged()
        .atMostEvery(summaryEveryMs)
        .distinctUntilChanged()
        .flowOn(computation)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun show(filter: TeamFilter) = local.update { it.copy(filter = filter, selection = null) }

    /** Opens the sheet of a member of the picture ("you" opens what waits for you). */
    fun openNode(id: String) = local.update { it.copy(selection = if (id == YOU_ID) Selection.You else Selection.Member(id)) }

    /** Opens the sheet of a link and reads its messages' whole texts (only now: the picture reads headers). */
    fun openLink(key: String) {
        local.update { it.copy(selection = Selection.Link(key)) }
        val link = uiState.value.graph.link(key) ?: return
        if (key in local.value.bodies) return
        viewModelScope.launch {
            val bodies = try {
                withContext(computation) {
                    val ids = link.messageIds.toSet()
                    val roots = uiState.value.graph.let { _ -> currentRoots() }
                    roots.flatMap { root -> messages.list(root) }.filter { it.id in ids }.sortedWith(compareBy({ it.at }, { it.id.value }))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                local.update { it.copy(message = UserMessage("The messages could not be read. Try again.", ++messageCounter)) }
                return@launch
            }
            local.update { it.copy(bodies = it.bodies + (key to bodies)) }
        }
    }

    fun closeSheet() = local.update { it.copy(selection = null) }

    fun messageShown() = local.update { it.copy(message = null) }

    private var lastRoots: List<TaskId> = emptyList()

    private fun currentRoots(): List<TaskId> = lastRoots

    // --- Building ---

    private fun build(inputs: Inputs, filter: TeamFilter): TeamUiState {
        builds.incrementAndGet()
        val now = clock.now()
        val summaries = inputs.roots.flatMap { it.summaries }
        val headers = inputs.roots.flatMap { it.headers }
        lastRoots = inputs.roots.map { it.id }
        val single = (filter as? TeamFilter.One)?.id
        val graph = TeamGraphBuilder.build(inputs.snapshot, summaries, headers, inputs.requests, inputs.monitor, now, inputs.spaces, single)
        val current = inputs.rows.filter { !it.status.isFinished }
        val finished = inputs.rows.filter { it.status.isFinished }.take(MAX_FINISHED)
        val chips = (current + finished).map { row ->
            ObjectiveChipUi(row.rootId, row.title, taskStatusLabel(row.status, TaskKind.OBJECTIVE, null, row.statusDetailShort), row.status.isFinished)
        }.toMutableList()
        // An older objective opened from its own page is offered too.
        if (single != null && chips.none { it.id == single }) {
            inputs.roots.firstOrNull { it.id == single }?.summaries?.firstOrNull { it.id == single }?.let { own ->
                chips += ObjectiveChipUi(single, own.title, taskStatusLabel(own.status, TaskKind.OBJECTIVE, own.retryAt, own.statusDetailShort), own.status.isFinished)
            }
        }
        val notices = inputs.roots.mapNotNull { root -> noticeOf(root) }
        val card = single?.let { id ->
            val root = inputs.roots.firstOrNull { it.id == id }
            val own = root?.summaries?.firstOrNull { it.id == id }
            own?.let {
                ObjectiveCardUi(
                    id = id,
                    title = it.title,
                    status = taskStatusLabel(it.status, TaskKind.OBJECTIVE, it.retryAt, it.statusDetailShort),
                    routine = root.record?.options?.routineId?.let { routineId -> TeamWords.routine(inputs.routines.firstOrNull { r -> r.id == routineId }?.title) },
                    notice = noticeOf(root),
                )
            }
        }
        return TeamUiState(
            isLoading = false,
            filter = filter,
            objectives = chips,
            currentCount = current.size,
            card = card,
            notices = if (single == null) notices else emptyList(),
            graph = graph,
            summary = TeamWords.summary(
                graph.count(NodeState.WORKING), graph.count(NodeState.WAITING_FOR_YOU), graph.count(NodeState.WAITING_FOR_TEAMMATE),
                graph.count(NodeState.PAUSED), graph.count(NodeState.STOPPED),
            ),
            emptyNote = TeamWords.NO_OBJECTIVE.takeIf { single == null && current.isEmpty() },
        )
    }

    /** Why [root] does not move, when it does not: paused (with its reason), stopped, or waiting for its provider. */
    private fun noticeOf(root: Root): ObjectiveNoticeUi? {
        val own = root.summaries.firstOrNull { it.id == root.id } ?: return null
        val detail = root.record?.statusDetail ?: own.statusDetailShort
        return when {
            own.status == TaskStatus.PAUSED ->
                ObjectiveNoticeUi(root.id, own.title, TeamWords.pauseReason(detail), TeamWords.pauseAdvice(detail), StatusTone.DISABLED)
            own.status == TaskStatus.INTERRUPTED ->
                ObjectiveNoticeUi(root.id, own.title, "Stopped", TeamWords.stoppedReason(detail), StatusTone.ATTENTION)
            own.status == TaskStatus.RUNNING && own.retryAt != null && detail?.startsWith(WaitTexts.WAITING) == true ->
                ObjectiveNoticeUi(root.id, own.title, "The Director is waiting", "${TeamWords.tryAgainReason(detail)} The objective goes on by itself.", StatusTone.WAITING)
            else -> null
        }
    }

    private fun withSheet(built: Built, local: Local): TeamUiState {
        val state = built.state.copy(message = local.message)
        val selection = local.selection ?: return state
        val inputs = built.inputs
        val graph = state.graph
        val now = clock.now()
        val zone = zone()
        val headers = inputs.roots.flatMap { it.headers }.associateBy { it.id }
        val names = graph.nodes.associate { it.id to it.name }
        fun row(header: MessageHeader) = HeaderRowUi(
            id = header.id,
            type = header.type,
            label = header.type.label,
            from = nameOf(header.from, header.type, false, names),
            to = nameOf(header.to, header.type, true, names),
            time = formatCompactTimestamp(header.at, now, zone),
            preview = header.preview,
        )
        fun requestsOf(ids: List<com.aicontrolcenter.core.RequestId>): List<RequestUi> =
            ids.mapNotNull { id -> inputs.requests.firstOrNull { it.id == id } }.map { requestUi(it, inputs.snapshot, capabilityInfo, now, zone) }
        val sheet: TeamSheetUi? = when (selection) {
            is Selection.Member -> graph.node(selection.id)?.let { node ->
                val mine = headers.values.filter { header ->
                    TeamGraphBuilder.nodeIdOf(header.from, header.type, false) == node.id || TeamGraphBuilder.nodeIdOf(header.to, header.type, true) == node.id
                }.sortedWith(compareBy({ it.at }, { it.id.value }))
                TeamSheetUi.Member(
                    node = node,
                    links = TeamDescriptions.linkRows(node.id, graph),
                    messages = mine.takeLast(MEMBER_MESSAGES).asReversed().map(::row),
                    requests = requestsOf(node.requestIds),
                    sharedBy = if (node.kind == TeamNodeKind.SPACE) {
                        graph.linksOf(node.id).filter { it.kind == LinkKind.SHARES }.map { names[it.other(node.id)].orEmpty() }.filter { it.isNotEmpty() }
                    } else {
                        emptyList()
                    },
                )
            }
            is Selection.Link -> graph.link(selection.key)?.let { link ->
                val previews = link.messageIds.mapNotNull(headers::get).map(::row)
                val bodies = local.bodies[link.key]?.let { stored ->
                    val faces = AgentFaces(inputs.snapshot, inputs.spaces.associate { it.id to it.name })
                    messagesUiOf(stored, inputs.roots.flatMap { it.summaries }, faces, now, zone)
                }
                TeamSheetUi.Link(link, TeamDescriptions.linkTitle(link, graph), previews, bodies)
            }
            Selection.You -> graph.owner?.let { you ->
                val news = headers.values.filter { it.to == Participant.Owner && it.type != MessageType.ANSWER }
                    .sortedWith(compareBy({ it.at }, { it.id.value })).takeLast(NEWS).asReversed().map(::row)
                TeamSheetUi.You(requestsOf(you.requestIds), news, TeamDescriptions.linkRows(YOU_ID, graph))
            }
        }
        return state.copy(sheet = sheet)
    }

    private fun nameOf(participant: Participant, type: MessageType, recipient: Boolean, names: Map<String, String>): String = when (participant) {
        Participant.Owner -> TeamWords.YOU
        Participant.System -> if (recipient && type == MessageType.MEMORY_SHARE) TeamWords.SHARED_MEMORY else "The app"
        else -> TeamGraphBuilder.nodeIdOf(participant, type, recipient)?.let(names::get) ?: when (participant) {
            is Participant.Agent -> TeamWords.FORMER_MEMBER
            else -> "A shared space"
        }
    }

    companion object {
        /** Objectives looked at for the choices (current ones and the latest finished). */
        const val OBJECTIVE_ROWS = 30

        /** Current objectives shown together under "All current". */
        const val MAX_CURRENT = 5
        const val MAX_FINISHED = 5

        /** Message headers read per shown objective (the settings allow at most 1,000 messages an objective). */
        const val HEADER_LIMIT = 1_000
        const val MEMBER_MESSAGES = 10
        const val NEWS = 10
        const val SUMMARY_EVERY_MS = 10_000L
    }
}

/**
 * At most one value every [periodMs]: the first at once, then the newest one after each quiet period (values in between
 * are dropped). For the spoken summary, so TalkBack is never flooded while the team works.
 */
internal fun <T> Flow<T>.atMostEvery(periodMs: Long): Flow<T> = flow {
    coroutineScope {
        val upstream = this@atMostEvery.buffer(Channel.CONFLATED).produceIn(this)
        var next = upstream.receiveCatching()
        while (next.isSuccess) {
            @Suppress("UNCHECKED_CAST")
            emit(next.getOrNull() as T)
            delay(periodMs)
            val waiting = upstream.tryReceive()
            next = if (waiting.isSuccess) waiting else upstream.receiveCatching()
        }
    }
}
