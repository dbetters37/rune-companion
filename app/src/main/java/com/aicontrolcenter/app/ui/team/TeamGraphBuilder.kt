package com.aicontrolcenter.app.ui.team

import com.aicontrolcenter.app.ui.approvals.isOpenAt
import com.aicontrolcenter.app.ui.approvals.visible
import com.aicontrolcenter.app.ui.components.destinationPhrase
import com.aicontrolcenter.app.ui.components.toStatusLabel
import com.aicontrolcenter.app.ui.tasks.WaitTexts
import com.aicontrolcenter.app.ui.tasks.ownerFacing
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.agents.AgentActivity
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.AgentRuntimeState
import com.aicontrolcenter.core.agents.label
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.registry.AgentEntry
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.core.tasks.MessageHeader
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.SummaryText
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tasks.TaskSummary
import com.aicontrolcenter.app.ui.theme.StatusTone

/**
 * Builds the Team view's [TeamGraph] from what is stored, as a pure function (rule 16: summaries and message headers
 * only, never whole task rows or message bodies):
 *
 * - **who**: with one [objective], its participants (assignees of its tasks, senders and recipients of its messages,
 *   agents asking the owner, and the Director); otherwise the whole team. "You" is always there; deleted agents that
 *   took part are "A former team member"; at most [MAX_AGENTS] agents on the ring (the rest are counted), at most
 *   [MAX_SPACES] spaces along the bottom.
 * - **what each one does** ([NodeState], first that applies): waiting for you (a request or question of theirs waits),
 *   working, waiting for a teammate, waiting to try again (a provider wait, R4), cannot run (their availability), paused
 *   (with its plain reason), stopped (interrupted, or waiting work after a pause: "Was waiting: …", never shown as
 *   waiting with a time), waiting for earlier steps, up next, could not finish, done, free.
 * - **who talked to whom**: one [TeamLink] per pair, by message kind; memory shares go to their space
 *   (`Participant.Space`), older ones to "Shared memory"; notes from "You" are [LinkKind.NOTES]; at most [MAX_LINKS],
 *   newest first.
 */
object TeamGraphBuilder {
    /** Agents placed on the ring; more open the list (the plan's cap). */
    const val MAX_AGENTS = 30
    const val MAX_SPACES = 6
    const val MAX_LINKS = 120

    /** Messages a link remembers for its sheet. */
    const val LINK_MESSAGES = 50

    /** The newest messages offered as dots; the screen sends one only for a message it has not seen. */
    const val PULSE_CANDIDATES = 20

    /** A link that carried a message this recently is drawn thicker. */
    const val RECENT_MS = 60_000L

    /** The longest quoted question in a state line, in code points. */
    private const val QUESTION_CHARS = 160

    /** Messages that answer another one: they show the way back without counting as a new exchange. */
    private val REPLIES = setOf(MessageType.ASSISTANCE_RESPONSE, MessageType.ANSWER, MessageType.REVIEW_REQUEST, MessageType.CANCEL)

    fun build(
        snapshot: RegistrySnapshot,
        summaries: List<TaskSummary>,
        headers: List<MessageHeader>,
        requests: List<OwnerRequest>,
        monitor: Map<AgentId, AgentRuntimeState>,
        now: Long,
        spaces: List<KnowledgeSpace> = emptyList(),
        /** One objective: only its participants are placed. Null: the whole team, over the objectives in [summaries]. */
        objective: TaskId? = null,
    ): TeamGraph {
        val open = requests.filter { it.isOpenAt(now) && (objective == null || it.rootId == objective) }.sortedBy { it.createdAt }
        val tasksById = summaries.associateBy { it.id }
        val sorted = headers.sortedWith(compareBy<MessageHeader>({ it.at }, { it.id.value }))
        val director = snapshot.director

        // --- Who is in the picture ---
        val involved = LinkedHashSet<AgentId>()
        summaries.forEach { task -> task.assigneeId?.let(involved::add) }
        sorted.forEach { header -> header.agents().forEach(involved::add) }
        open.forEach { involved += it.agentId }
        val ringIds = ArrayList<AgentId>()
        val registryIds = snapshot.agents.map { it.profile.id }
        if (objective == null) {
            registryIds.filterTo(ringIds) { it != director?.profile?.id }
        } else {
            registryIds.filterTo(ringIds) { it in involved && it != director?.profile?.id }
        }
        // People who took part and have been deleted since.
        involved.filterTo(ringIds) { it !in snapshot.agentsById && it != director?.profile?.id }
        val notInObjective = if (objective == null) 0 else registryIds.count { it !in involved && it != director?.profile?.id }
        val kept = if (ringIds.size <= MAX_AGENTS) {
            ringIds
        } else {
            // The busy ones first, so nobody who waits for the owner is left out; then back into the team's order.
            val busy = ringIds.filter { it in involved || it in monitor }.take(MAX_AGENTS).toSet()
            val chosen = busy + ringIds.filter { it !in busy }.take(MAX_AGENTS - busy.size)
            ringIds.filter { it in chosen }
        }

        val faces = Faces(snapshot, spaces)
        val nodes = ArrayList<TeamNode>()
        nodes += ownerNode(open)
        director?.let { nodes += agentNode(it.profile.id, it, TeamNodeKind.DIRECTOR, summaries, tasksById, open, monitor, objective, faces) }
        kept.forEach { id ->
            val entry = snapshot.agentsById[id]
            nodes += agentNode(id, entry, if (entry == null) TeamNodeKind.FORMER_AGENT else TeamNodeKind.AGENT, summaries, tasksById, open, monitor, objective, faces)
        }

        // --- Spaces along the bottom: those shared into here first (newest share first), then the others ---
        val shared = LinkedHashMap<String, SpaceId?>()
        sorted.asReversed().forEach { header ->
            if (header.type != MessageType.MEMORY_SHARE) return@forEach
            when (val to = header.to) {
                is Participant.Space -> shared.putIfAbsent(spaceNodeId(to.spaceId), to.spaceId)
                Participant.System -> shared.putIfAbsent(SHARED_MEMORY_ID, null)
                else -> Unit
            }
        }
        val placedAgents = nodes.filter { it.isAgent }.mapNotNull { it.agentId }.toSet()
        val others = spaces.sortedBy { it.name.lowercase() }.filter { space ->
            spaceNodeId(space.id) !in shared && (objective == null || placedAgents.any { faces.mayWrite(it, space.id) || faces.mayRead(it, space.id) })
        }.map { spaceNodeId(it.id) to it.id }
        val allSpaces = shared.entries.map { it.key to it.value } + others
        allSpaces.take(MAX_SPACES).forEach { (nodeId, spaceId) -> nodes += spaceNode(nodeId, spaceId, sorted, faces) }

        // --- Links ---
        val placed = nodes.associateBy { it.id }
        val tallies = LinkedHashMap<String, LinkTally>()
        val linkOf = HashMap<MessageId, Pair<String, LinkKind>>()
        for (header in sorted) {
            val from = nodeIdOf(header.from, header.type, isRecipient = false) ?: continue
            val to = nodeIdOf(header.to, header.type, isRecipient = true) ?: continue
            if (from == to || from !in placed || to !in placed) continue
            val kind = kindOf(header.type, from, to)
            val key = linkKeyOf(from, to)
            tallies.getOrPut(key) { LinkTally(key, minOf(from, to), maxOf(from, to)) }.add(header, from, kind)
            linkOf[header.id] = key to kind
        }
        val newestFirst = tallies.values.sortedByDescending { it.lastAt }
        val links = newestFirst.take(MAX_LINKS).map { it.toLink(now) }
        val keptKeys = links.mapTo(HashSet()) { it.key }
        val pulses = sorted.filter { linkOf[it.id]?.first in keptKeys }.takeLast(PULSE_CANDIDATES).map { header ->
            val (key, kind) = linkOf.getValue(header.id)
            TeamPulse(header.id.value, nodeIdOf(header.from, header.type, false)!!, nodeIdOf(header.to, header.type, true)!!, key, kind)
        }
        return TeamGraph(
            nodes = nodes,
            links = links,
            pulses = pulses,
            agentsNotInObjective = notInObjective,
            agentsLeftOut = ringIds.size - kept.size,
            spacesLeftOut = (allSpaces.size - MAX_SPACES).coerceAtLeast(0),
            linksLeftOut = newestFirst.size - links.size,
        )
    }

    /** Which kind of link a message belongs to, by its type and who is at each end. */
    fun kindOf(type: MessageType, from: String, to: String): LinkKind {
        val withYou = from == YOU_ID || to == YOU_ID
        return when {
            withYou && (type == MessageType.QUESTION || type == MessageType.ANSWER) -> LinkKind.ASKS_YOU
            type == MessageType.GUIDANCE -> LinkKind.NOTES
            from == YOU_ID -> LinkKind.ASSIGNS
            to == YOU_ID -> LinkKind.TELLS_YOU
            to.startsWith("space:") || type == MessageType.MEMORY_SHARE -> LinkKind.SHARES
            else -> when (type) {
                MessageType.ASSIGNMENT, MessageType.CANCEL -> LinkKind.ASSIGNS
                MessageType.ASSISTANCE_REQUEST, MessageType.ASSISTANCE_RESPONSE, MessageType.QUESTION, MessageType.ANSWER -> LinkKind.ASKS
                MessageType.DELEGATION -> LinkKind.DELEGATES
                MessageType.REVIEW_REQUEST, MessageType.REVIEW_RESULT -> LinkKind.REVIEWS
                else -> LinkKind.REPORTS
            }
        }
    }

    /** The node a message's end is drawn at; null for the app itself ("System"), which is not in the picture. */
    fun nodeIdOf(participant: Participant, type: MessageType, isRecipient: Boolean): String? = when (participant) {
        Participant.Owner -> YOU_ID
        is Participant.Agent -> participant.agentId.value
        is Participant.Space -> spaceNodeId(participant.spaceId)
        // Shares stored before schema 6 went to the system; they are drawn to one "Shared memory" place.
        Participant.System -> if (isRecipient && type == MessageType.MEMORY_SHARE) SHARED_MEMORY_ID else null
    }

    private fun MessageHeader.agents(): List<AgentId> = listOfNotNull((from as? Participant.Agent)?.agentId, (to as? Participant.Agent)?.agentId)

    // --- Nodes ---

    private fun ownerNode(open: List<OwnerRequest>): TeamNode {
        val questions = open.filter { it.kind == OwnerRequestKind.QUESTION }
        return TeamNode(
            id = YOU_ID,
            kind = TeamNodeKind.OWNER,
            name = TeamWords.YOU,
            roleLabel = null,
            colorIndex = 0,
            state = if (open.isEmpty()) NodeState.IDLE else NodeState.WAITING_FOR_YOU,
            stateText = TeamWords.waitingCount(open.size - questions.size, questions.size),
            tone = if (open.isEmpty()) StatusTone.READY else StatusTone.ATTENTION,
            requestIds = open.map { it.id },
            questionIds = questions.mapTo(LinkedHashSet()) { it.id },
        )
    }

    /** One state an agent could be shown in, with what it is about. */
    private data class Candidate(val state: NodeState, val detail: String?, val task: TaskSummary?)

    private fun agentNode(
        id: AgentId,
        entry: AgentEntry?,
        kind: TeamNodeKind,
        summaries: List<TaskSummary>,
        tasksById: Map<TaskId, TaskSummary>,
        open: List<OwnerRequest>,
        monitor: Map<AgentId, AgentRuntimeState>,
        objective: TaskId?,
        faces: Faces,
    ): TeamNode {
        val mine = open.filter { it.agentId == id }
        val candidates = ArrayList<Candidate>()
        mine.firstOrNull()?.let { candidates += Candidate(NodeState.WAITING_FOR_YOU, requestText(it), it.taskId?.let(tasksById::get)) }
        val live = monitor[id]?.let { runtime -> runtimeCandidate(runtime, tasksById, objective) }
        live?.let(candidates::add)
        val ownTasks = summaries.filter { it.assigneeId == id }
        ownTasks.forEach { task ->
            // The monitor is live: a task row that still says RUNNING while its run waits is not "Working" (N3).
            if (live != null && live.task?.id == task.id && task.status == TaskStatus.RUNNING) return@forEach
            taskCandidate(task, summaries, tasksById, faces)?.let(candidates::add)
        }
        val availability = entry?.availability
        val unavailable = availability != null && availability !is AgentAvailability.Ready
        if (unavailable) candidates += Candidate(NodeState.CANNOT_RUN, null, null)
        val chosen = candidates.minByOrNull { it.state.ordinal } ?: Candidate(NodeState.IDLE, null, null)
        val label = availability?.toStatusLabel()
        val (stateText, tone, detail) = when {
            chosen.state == NodeState.CANNOT_RUN && label != null -> Triple(label.text, label.tone, label.detail)
            kind == TeamNodeKind.FORMER_AGENT && chosen.state == NodeState.IDLE -> Triple("No longer in the team", StatusTone.DISABLED, null)
            else -> Triple(chosen.state.word, chosen.state.tone, chosen.detail)
        }
        val profile = entry?.profile
        return TeamNode(
            id = id.value,
            kind = kind,
            name = profile?.name ?: TeamWords.FORMER_MEMBER,
            roleLabel = profile?.kind?.label,
            colorIndex = profile?.colorIndex ?: 0,
            state = chosen.state,
            stateText = stateText,
            tone = tone,
            detail = detail,
            agentId = id,
            taskId = chosen.task?.id,
            taskTitle = chosen.task?.takeIf { it.kind != TaskKind.OBJECTIVE }?.title,
            rootId = chosen.task?.rootId,
            requestIds = mine.map { it.id },
            questionIds = mine.filter { it.kind == OwnerRequestKind.QUESTION }.mapTo(LinkedHashSet()) { it.id },
            modelLine = faces.modelLine(availability),
            dimmed = availability is AgentAvailability.Disabled || kind == TeamNodeKind.FORMER_AGENT,
        )
    }

    /** "Asks you: “Which city?”" (the agent's words, quoted), "Asks you to approve: Delete Reports/2024.csv". */
    fun requestText(request: OwnerRequest): String = when (request.kind) {
        OwnerRequestKind.QUESTION -> "Asks you: “${SummaryText.cut(visible(request.question.orEmpty()), QUESTION_CHARS)}”"
        OwnerRequestKind.APPROVAL -> "Asks you to approve: ${visible(request.summary)}"
        OwnerRequestKind.PERMISSION -> "Asks for your permission: ${visible(request.summary)}"
    }

    /** What the activity monitor says, when it is about what is shown (a chat counts only for the whole team). */
    private fun runtimeCandidate(runtime: AgentRuntimeState, tasksById: Map<TaskId, TaskSummary>, objective: TaskId?): Candidate? {
        val task = runtime.currentTaskId?.let(tasksById::get)
        if (runtime.currentTaskId != null && task == null) return null
        if (task == null && objective != null) return null
        val step = runtime.detail?.let { detail -> task?.let { TeamWords.stepOf(detail, it.title) } ?: detail.takeUnless { task != null }?.let(TeamWords::toolNames) }
        return when (runtime.activity) {
            AgentActivity.WORKING -> Candidate(NodeState.WORKING, step ?: if (task == null) "In a chat" else null, task)
            AgentActivity.WAITING_FOR_APPROVAL -> Candidate(NodeState.WAITING_FOR_YOU, step, task)
            AgentActivity.WAITING_FOR_AGENT -> Candidate(NodeState.WAITING_FOR_TEAMMATE, step, task)
            AgentActivity.IDLE, AgentActivity.NEEDS_ATTENTION -> null
        }
    }

    /** What one of the agent's tasks says about it. */
    private fun taskCandidate(task: TaskSummary, all: List<TaskSummary>, tasksById: Map<TaskId, TaskSummary>, faces: Faces): Candidate? {
        val detail = task.statusDetailShort
        if (task.kind == TaskKind.OBJECTIVE) return objectiveCandidate(task, all)
        return when (task.status) {
            TaskStatus.WAITING_FOR_OWNER -> Candidate(NodeState.WAITING_FOR_YOU, detail?.let(::ownerFacing), task)
            TaskStatus.RUNNING -> if (task.kind == TaskKind.REVIEW) {
                val author = task.parentId?.let(tasksById::get)?.assigneeId
                Candidate(NodeState.WORKING, author?.let { "Checking the work of ${faces.name(it)}" } ?: "Checking a result", task)
            } else {
                Candidate(NodeState.WORKING, null, task)
            }
            TaskStatus.WAITING_FOR_AGENT -> Candidate(NodeState.WAITING_FOR_TEAMMATE, detail?.let(::ownerFacing), task)
            TaskStatus.IN_REVIEW -> Candidate(NodeState.WAITING_FOR_TEAMMATE, TeamWords.WAITING_FOR_CHECK, task)
            TaskStatus.QUEUED -> when {
                task.retryAt != null && detail?.startsWith(WaitTexts.WAITING) == true ->
                    Candidate(NodeState.WAITING_TO_TRY_AGAIN, TeamWords.tryAgainReason(detail), task)
                detail?.startsWith(WaitTexts.WAS_WAITING) == true -> Candidate(NodeState.STOPPED, TeamWords.stoppedReason(detail), task)
                task.dependsOn.any { tasksById[it]?.status != TaskStatus.COMPLETED } -> Candidate(NodeState.BLOCKED, null, task)
                else -> Candidate(NodeState.UP_NEXT, null, task)
            }
            TaskStatus.PAUSED -> Candidate(NodeState.PAUSED, TeamWords.pauseReason(detail), task)
            TaskStatus.INTERRUPTED -> Candidate(NodeState.STOPPED, TeamWords.stoppedReason(detail), task)
            TaskStatus.FAILED, TaskStatus.TIMED_OUT -> Candidate(NodeState.FAILED, detail, task)
            TaskStatus.COMPLETED -> Candidate(NodeState.DONE, null, task)
            TaskStatus.CANCELLED -> null
        }
    }

    /** The Director's own objective: planning, waiting for the team, paused, stopped, finished. */
    private fun objectiveCandidate(root: TaskSummary, all: List<TaskSummary>): Candidate? {
        val detail = root.statusDetailShort
        val hasPlan = all.any { it.rootId == root.id && it.isPlanStep }
        return when (root.status) {
            TaskStatus.RUNNING -> when {
                root.retryAt != null && detail?.startsWith(WaitTexts.WAITING) == true ->
                    Candidate(NodeState.WAITING_TO_TRY_AGAIN, TeamWords.tryAgainReason(detail), root)
                !hasPlan -> Candidate(NodeState.WORKING, TeamWords.PLANNING, root)
                else -> Candidate(NodeState.WAITING_FOR_TEAMMATE, TeamWords.WAITING_FOR_TEAM, root)
            }
            TaskStatus.PAUSED -> Candidate(NodeState.PAUSED, TeamWords.pauseReason(detail), root)
            TaskStatus.INTERRUPTED -> Candidate(NodeState.STOPPED, TeamWords.stoppedReason(detail), root)
            TaskStatus.COMPLETED -> Candidate(NodeState.DONE, null, root)
            TaskStatus.FAILED, TaskStatus.TIMED_OUT -> Candidate(NodeState.FAILED, detail, root)
            else -> null
        }
    }

    private fun spaceNode(nodeId: String, spaceId: SpaceId?, headers: List<MessageHeader>, faces: Faces): TeamNode {
        val shares = headers.count { header ->
            header.type == MessageType.MEMORY_SHARE && nodeIdOf(header.to, header.type, isRecipient = true) == nodeId
        }
        val name = when {
            spaceId == null -> TeamWords.SHARED_MEMORY
            else -> faces.spaceName(spaceId) ?: "A deleted space"
        }
        return TeamNode(
            id = nodeId,
            kind = TeamNodeKind.SPACE,
            name = name,
            roleLabel = TeamWords.SHARED_SPACE,
            colorIndex = 0,
            state = NodeState.IDLE,
            stateText = if (shares == 0) "Nothing shared into it here" else TeamWords.times(shares, "memory shared into it", "memories shared into it"),
            tone = StatusTone.READY,
            spaceId = spaceId,
            readers = spaceId?.let(faces::readers).orEmpty(),
            writers = spaceId?.let(faces::writers).orEmpty(),
        )
    }

    // --- Links ---

    private class LinkTally(val key: String, val aId: String, val bId: String) {
        val counts = LinkedHashMap<LinkKind, IntArray>()
        val ids = ArrayList<MessageId>()
        var lastAt = Long.MIN_VALUE
        var newest: LinkKind = LinkKind.REPORTS
        var aToB = false
        var bToA = false

        fun add(header: MessageHeader, from: String, kind: LinkKind) {
            val forward = from == aId
            val pair = counts.getOrPut(kind) { IntArray(2) }
            // Replies (an answer, a check asked for) show the way back, but only the first message of each exchange counts:
            // "asked Researcher 2 times".
            val counted = header.type !in REPLIES
            if (forward) {
                if (counted) pair[0]++
                aToB = true
            } else {
                if (counted) pair[1]++
                bToA = true
            }
            ids += header.id
            if (header.at >= lastAt) {
                lastAt = header.at
                newest = kind
            }
        }

        fun toLink(now: Long) = TeamLink(
            key = key,
            aId = aId,
            bId = bId,
            kind = newest,
            kinds = counts.map { (kind, pair) -> KindCount(kind, pair[0], pair[1]) }.sortedByDescending { it.total },
            aToB = aToB,
            bToA = bToA,
            count = ids.size,
            lastAt = lastAt,
            recent = now - lastAt in 0..RECENT_MS,
            messageIds = ids.takeLast(LINK_MESSAGES),
        )
    }

    /** Names and settings looked up in one snapshot. */
    private class Faces(private val snapshot: RegistrySnapshot, spaces: List<KnowledgeSpace>) {
        private val spaceNames = spaces.associate { it.id to it.name }

        fun name(id: AgentId): String = snapshot.agentsById[id]?.profile?.name ?: TeamWords.FORMER_MEMBER

        fun spaceName(id: SpaceId): String? = spaceNames[id]

        fun mayRead(agent: AgentId, space: SpaceId): Boolean = snapshot.agentsById[agent]?.profile?.memory?.readableSpaces?.contains(space) == true

        fun mayWrite(agent: AgentId, space: SpaceId): Boolean = snapshot.agentsById[agent]?.profile?.memory?.writableSpaces?.contains(space) == true

        fun readers(space: SpaceId): List<String> = snapshot.agents.filter { space in it.profile.memory.readableSpaces }.map { it.profile.name }

        fun writers(space: SpaceId): List<String> = snapshot.agents.filter { space in it.profile.memory.writableSpaces }.map { it.profile.name }

        /** "Uses Grok 4 (xAI) · its text leaves this device (cloud)". */
        fun modelLine(availability: AgentAvailability?): String? {
            val ready = availability as? AgentAvailability.Ready ?: return null
            val model = snapshot.modelsById[ready.modelId] ?: return null
            val provider = snapshot.provider(model) ?: return null
            return "Uses ${model.displayName} (${provider.name}) · its text ${provider.effectiveLocality.destinationPhrase}"
        }
    }
}
