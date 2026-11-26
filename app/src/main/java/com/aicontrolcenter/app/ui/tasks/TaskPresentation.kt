package com.aicontrolcenter.app.ui.tasks

import androidx.compose.runtime.Immutable
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.app.work.isPlanStep
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskSummary
import com.aicontrolcenter.core.tasks.ReviewDecision
import com.aicontrolcenter.framework.orchestration.TaskStatusLines
import java.time.ZoneId

/*
 * Turns stored tasks and messages into what the Tasks screens show. Pure functions over plain
 * data, so the plan tree, the conversation timeline and the Director's interpretation are
 * unit-tested without a screen.
 */

/** An agent as the Tasks screens draw it: monogram on its accent colour. Removed agents keep a neutral face. */
@Immutable
data class AgentFaceUi(val id: AgentId?, val name: String, val colorIndex: Int)

/** Who sent or received a message. */
@Immutable
sealed interface ParticipantUi {
    val name: String

    data class Agent(val face: AgentFaceUi) : ParticipantUi {
        override val name: String get() = face.name
    }

    data object Owner : ParticipantUi {
        override val name = "You"
    }

    data object System : ParticipantUi {
        override val name = "System"
    }

    /** A shared memory space that received a memory (Phase 6: `Participant.Space`). */
    data class Space(override val name: String) : ParticipantUi
}

/**
 * Looks agents up in one registry snapshot; an agent deleted since keeps its id with a neutral name. [spaces] names the
 * shared memory spaces a memory was shared into.
 */
class AgentFaces(private val snapshot: RegistrySnapshot, private val spaces: Map<SpaceId, String> = emptyMap()) {
    fun of(id: AgentId?): AgentFaceUi? = id?.let { agentId ->
        snapshot.agentsById[agentId]?.profile?.let { AgentFaceUi(agentId, it.name, it.colorIndex) } ?: AgentFaceUi(agentId, "Removed agent", 0)
    }

    fun of(participant: Participant): ParticipantUi = when (participant) {
        is Participant.Agent -> ParticipantUi.Agent(of(participant.agentId)!!)
        Participant.Owner -> ParticipantUi.Owner
        Participant.System -> ParticipantUi.System
        is Participant.Space -> ParticipantUi.Space(spaces[participant.spaceId]?.let { "Space “$it”" } ?: "A shared space")
    }

    fun requester(requester: TaskRequester): String = when (requester) {
        TaskRequester.Owner -> "You"
        is TaskRequester.Agent -> of(requester.agentId)?.name ?: "An agent"
    }
}

/** One message of the conversation timeline. */
@Immutable
data class MessageUi(
    val id: MessageId,
    val type: MessageType,
    val from: ParticipantUi,
    val to: ParticipantUi,
    val time: String,
    val body: String,
    /** The task it is about, when it is not the objective itself. */
    val taskTitle: String?,
    val taskId: TaskId?,
)

fun messagesUi(messages: List<AgentMessage>, tree: List<TaskRecord>, faces: AgentFaces, now: Long, zone: ZoneId): List<MessageUi> {
    val titles = tree.associate { it.id to it.title }
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

/** The latest verdict on a task, and how many there were. */
@Immutable
data class VerdictUi(val status: StatusLabel, val rounds: Int)

/** One row of the plan: a step the Director planned, or (indented) a review or delegated work under it. */
@Immutable
data class PlanRowUi(
    val id: TaskId,
    /** Position in the plan, from 1, for planned work; null for the final result and for nested rows. */
    val number: Int?,
    val depth: Int,
    val kind: TaskKind,
    val title: String,
    val assignee: AgentFaceUi?,
    val status: StatusLabel,
    val statusDetail: String?,
    /** Plan numbers of the steps this one waits for. */
    val dependsOn: List<Int>,
    /** 1 for the first run; more after retries. */
    val attempt: Int,
    val revisionRound: Int,
    val needsReview: Boolean,
    val verdict: VerdictUi?,
)

/**
 * The plan as a tree, from the objective's task summaries (P1, rule 16: no instructions, results or reviews are read):
 * the Director's steps in plan order (the final result last), each followed by what hangs under it (its reviews and
 * delegated work, oldest first, nested to any depth). A step's latest verdict comes from its review tasks' status lines.
 */
fun planRowsOf(tree: List<TaskSummary>, faces: AgentFaces): List<PlanRowUi> {
    val root = tree.firstOrNull { it.kind == TaskKind.OBJECTIVE } ?: return emptyList()
    val steps = tree.filter { it.isPlanStep }.sortedWith(compareBy<TaskSummary>({ it.kind == TaskKind.SYNTHESIS }, { it.sortOrder }, { it.createdAt }, { it.id.value }))
    val numbers = steps.filter { it.kind == TaskKind.WORK }.withIndex().associate { (index, task) -> task.id to index + 1 }
    val children = tree.filter { it.parentId != null && it.parentId != root.id && it.id != root.id }.groupBy { it.parentId!! }
    val rows = ArrayList<PlanRowUi>()
    fun add(task: TaskSummary, depth: Int) {
        val under = children[task.id].orEmpty().sortedWith(compareBy({ it.createdAt }, { it.id.value }))
        val verdicts = under.filter { it.kind == TaskKind.REVIEW }.mapNotNull { verdictOf(it.statusDetailShort) }
        rows += PlanRowUi(
            id = task.id,
            number = if (depth == 0) numbers[task.id] else null,
            depth = depth,
            kind = task.kind,
            title = task.title,
            assignee = faces.of(task.assigneeId),
            status = taskStatusLabel(task.status, task.kind, task.retryAt, task.statusDetailShort),
            statusDetail = task.statusDetailShort?.takeIf { it.isNotBlank() }?.let(::ownerFacing),
            dependsOn = task.dependsOn.mapNotNull(numbers::get).sorted(),
            attempt = task.attempt + 1,
            revisionRound = task.revisionRound,
            needsReview = task.needsReview,
            verdict = verdicts.lastOrNull()?.let { VerdictUi(it.toStatusLabel(), verdicts.size) },
        )
        under.forEach { add(it, depth + 1) }
    }
    steps.forEach { add(it, 0) }
    return rows
}

/** The decision a review task's status line names ("Verdict: approved."), or null for any other line. */
fun verdictOf(detail: String?): ReviewDecision? {
    val word = detail?.takeIf { it.startsWith(VERDICT_PREFIX) }?.removePrefix(VERDICT_PREFIX)?.trim()?.trimEnd('.')?.uppercase() ?: return null
    return ReviewDecision.entries.firstOrNull { it.name == word }
}

/**
 * The plan as a tree, from whole task records: the Director's steps in plan order (the final result last), each
 * followed by what hangs under it (its reviews and delegated work, oldest first, nested to any depth).
 */
fun planRows(tree: List<TaskRecord>, faces: AgentFaces): List<PlanRowUi> {
    val root = tree.firstOrNull { it.kind == TaskKind.OBJECTIVE } ?: return emptyList()
    val steps = tree.filter { it.isPlanStep() }.sortedWith(compareBy<TaskRecord>({ it.kind == TaskKind.SYNTHESIS }, { it.order }, { it.createdAt }, { it.id.value }))
    val numbers = steps.filter { it.kind == TaskKind.WORK }.withIndex().associate { (index, task) -> task.id to index + 1 }
    val children = tree.filter { it.parentId != null && it.parentId != root.id && it.id != root.id }.groupBy { it.parentId!! }
    val rows = ArrayList<PlanRowUi>()
    fun add(task: TaskRecord, depth: Int) {
        rows += PlanRowUi(
            id = task.id,
            number = if (depth == 0) numbers[task.id] else null,
            depth = depth,
            kind = task.kind,
            title = task.title,
            assignee = faces.of(task.assignee),
            status = taskStatusLabel(task.status, task.kind, task.retryAt, task.statusDetail),
            statusDetail = task.statusDetail?.takeIf { it.isNotBlank() }?.let(::ownerFacing) ?: task.error,
            dependsOn = task.dependsOn.mapNotNull(numbers::get).sorted(),
            attempt = task.attempt + 1,
            revisionRound = task.revisionRound,
            needsReview = task.needsReview,
            verdict = task.reviews.lastOrNull()?.let { VerdictUi(it.decision.toStatusLabel(), task.reviews.size) },
        )
        children[task.id].orEmpty().sortedWith(compareBy({ it.createdAt }, { it.id.value })).forEach { add(it, depth + 1) }
    }
    steps.forEach { add(it, 0) }
    return rows
}

/**
 * What the Director understood the objective to mean. The framework writes it at the top of the
 * final-result task's instructions ("Interpretation: …"), and opens its plan message to the
 * owner with it ("Plan: …"); null before the Director has planned.
 */
fun interpretationOf(tree: List<TaskRecord>, messages: List<AgentMessage>): String? =
    interpretationOf(tree.firstOrNull { it.kind == TaskKind.SYNTHESIS }?.instructions, messages)

/** As above, from the final-result task's instructions alone ([synthesisInstructions], null before it exists). */
fun interpretationOf(synthesisInstructions: String?, messages: List<AgentMessage>): String? {
    synthesisInstructions?.let { text ->
        if (text.startsWith(INTERPRETATION_PREFIX)) return text.removePrefix(INTERPRETATION_PREFIX).substringBefore("\n\n").trim().ifEmpty { null }
    }
    val plan = messages.firstOrNull { it.type == MessageType.STATUS && it.from is Participant.Agent && it.to == Participant.Owner && it.body.startsWith(PLAN_PREFIX) }
    return plan?.body?.removePrefix(PLAN_PREFIX)?.lineSequence()?.firstOrNull()?.trim()?.ifEmpty { null }
}

/** The limits the orchestrator reported reaching, in its own words, oldest first. */
fun limitsReached(messages: List<AgentMessage>): List<String> = messages
    .filter { it.from == Participant.System && it.type == MessageType.STATUS && it.body.startsWith(LIMIT_PREFIX) }
    .map { it.body.removePrefix(LIMIT_PREFIX).trim().replaceFirstChar(Char::uppercase) }
    .distinct()

/** Everyone who took part in the objective, from its summaries: the Director first, then assignees in the order they joined. */
fun participantsOfSummaries(tree: List<TaskSummary>, faces: AgentFaces): List<AgentFaceUi> {
    val objective = tree.firstOrNull { it.kind == TaskKind.OBJECTIVE }
    val ids = LinkedHashSet<AgentId>()
    objective?.assigneeId?.let(ids::add)
    tree.sortedWith(compareBy({ it.createdAt }, { it.id.value })).forEach { task -> task.assigneeId?.let(ids::add) }
    return ids.mapNotNull(faces::of)
}

/** Everyone who took part in the objective: the Director first, then assignees in the order they joined. */
fun participantsOf(tree: List<TaskRecord>, faces: AgentFaces): List<AgentFaceUi> {
    val objective = tree.firstOrNull { it.kind == TaskKind.OBJECTIVE }
    val ids = LinkedHashSet<AgentId>()
    objective?.assignee?.let(ids::add)
    tree.sortedBy { it.createdAt }.forEach { task -> task.assignee?.let(ids::add) }
    return ids.mapNotNull(faces::of)
}

private const val INTERPRETATION_PREFIX = "Interpretation: "
private const val PLAN_PREFIX = "Plan: "
private const val LIMIT_PREFIX = "Limit reached: "

/** How a review task's status line names its verdict (framework `ReviewSteps`, through `TaskStatusLines`). */
private const val VERDICT_PREFIX = TaskStatusLines.VERDICT
