package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.EventId
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.messageId
import com.aicontrolcenter.core.registry.AgentStatsLog
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskStatus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The title an objective's first log event starts with ("Objective started: <title>"), written before any of its work.
 * While that event is in the log, every later event of the objective is too: the log is only ever cut by age or cleared
 * whole. The objective's privacy line relies on it (INT3-2).
 */
const val OBJECTIVE_STARTED: String = "Objective started"

/**
 * The record of what happens under an objective: every agent message is stored, and every
 * message and task state change is written to the activity log.
 *
 * The log carries what the owner needs to follow a run, never whole bodies: a message event
 * names its type, sender, recipient and task, with at most a short first line of the body.
 * Bodies stay in the message store, where the objective's own view shows them. The owner's own
 * words (a note to the team, an answer to a question) are logged without any of their text (S28).
 *
 * Writes here run non-cancellable: a record of something that happened must not be lost
 * because the work that caused it was stopped the moment after.
 */
internal class ObjectiveJournal(
    private val messages: MessageRepository,
    private val agents: AgentStatsLog,
    private val activity: ActivityRecorder,
    private val ids: IdGenerator,
    private val clock: Clock,
    /** The space a memory was shared into, by name; null names none ("a shared memory space"). */
    private val spaceName: suspend (SpaceId) -> String? = { null },
    /**
     * The log itself, for an entry that must be written at most once however often the app dies around it: such an entry
     * gets an id of its own ([taskChanged] with `once`), and the log keeps one entry per id (D20). Null: every entry gets
     * a fresh id, as before.
     */
    private val log: ActivityLog? = null,
) {
    /** Stores a message and logs it. Returns the stored message. [id]: the message's own id, when it must be known before (the store keeps one message per id). */
    suspend fun send(
        rootId: TaskId,
        task: TaskRecord?,
        from: Participant,
        to: Participant,
        type: MessageType,
        body: String,
        hop: Int = 0,
        replyTo: MessageId? = null,
        id: MessageId? = null,
    ): AgentMessage = withContext(NonCancellable) {
        val message = AgentMessage(
            id = id ?: ids.messageId(), rootId = rootId, taskId = task?.id, from = from, to = to, type = type,
            body = StoredText.capChars(body.trim(), MAX_BODY), replyTo = replyTo, hop = hop, at = clock.now(),
        )
        messages.append(message)
        val about = task?.let { " · Task: ${it.title}" }.orEmpty()
        safely {
            activity.record(
                ActivityType.MESSAGE,
                "${label(type)}: ${name(from)} → ${name(to)}",
                loggedBody(message) + about,
                agentId = (from as? Participant.Agent)?.agentId,
                taskId = task?.id, rootTaskId = rootId,
                data = buildJsonObject {
                    put("messageId", message.id.value)
                    put("type", type.name)
                    put("hop", hop)
                },
            )
        }
        message
    }

    /**
     * Logs a task's state change: [title] says what happened, the detail is the task's status sentence. [once]: the
     * entry's own id; the log keeps one entry per id, so writing it again (after the app died around it) adds nothing.
     */
    suspend fun taskChanged(task: TaskRecord, title: String, severity: Severity = Severity.INFO, data: JsonObject? = null, once: EventId? = null) =
        withContext(NonCancellable) {
            safely {
                val detail = StoredText.capChars(task.statusDetail ?: task.error.orEmpty(), StoredText.MAX_REASON_CHARS)
                val extras = data ?: buildJsonObject {
                    put("kind", task.kind.name)
                    put("status", task.status.name)
                    put("attempt", task.attempt)
                    put("revisionRound", task.revisionRound)
                }
                if (once != null && log != null) {
                    log.record(
                        ActivityEvent(
                            id = once, at = clock.now(), type = ActivityType.TASK, severity = severity, agentId = task.assignee,
                            taskId = task.id, rootTaskId = task.rootId, title = "$title: ${task.title}", detail = detail, data = extras,
                        ),
                    )
                } else {
                    activity.record(
                        ActivityType.TASK, "$title: ${task.title}", detail,
                        severity = severity, agentId = task.assignee, taskId = task.id, rootTaskId = task.rootId, data = extras,
                    )
                }
            }
        }

    /**
     * Records that a limit stopped something: a STATUS message from the system to the owner,
     * and a warning in the log. Every loop guard and runaway limit reports through here.
     */
    suspend fun limitReached(rootId: TaskId, task: TaskRecord?, reason: String) {
        send(rootId, task, Participant.System, Participant.Owner, MessageType.STATUS, "Limit reached: $reason")
        withContext(NonCancellable) {
            safely {
                activity.record(
                    ActivityType.TASK, "Limit reached", StoredText.capChars(reason, StoredText.MAX_REASON_CHARS), severity = Severity.WARNING,
                    agentId = task?.assignee, taskId = task?.id, rootTaskId = rootId,
                )
            }
        }
    }

    suspend fun warn(rootId: TaskId, task: TaskRecord?, title: String, detail: String, agentId: AgentId? = task?.assignee) =
        withContext(NonCancellable) {
            safely { activity.record(ActivityType.TASK, title, StoredText.capChars(detail, StoredText.MAX_REASON_CHARS), Severity.WARNING, agentId, task?.id, rootId) }
        }

    suspend fun ownerAction(rootId: TaskId, task: TaskRecord?, title: String, detail: String = "") = withContext(NonCancellable) {
        safely { activity.record(ActivityType.USER_ACTION, title, detail, taskId = task?.id, rootTaskId = rootId) }
    }

    /** Counts a finished task in its agent's track record, which the selector reads as reliability. */
    suspend fun countFinished(task: TaskRecord) = withContext(NonCancellable) {
        val agent = task.assignee ?: return@withContext
        val duration = (task.finishedAt ?: clock.now()) - (task.startedAt ?: task.finishedAt ?: clock.now())
        safely {
            agents.updateStats(agent) {
                when (task.status) {
                    TaskStatus.COMPLETED -> it.copy(tasksCompleted = it.tasksCompleted + 1, totalTaskDurationMs = it.totalTaskDurationMs + duration.coerceAtLeast(0))
                    TaskStatus.FAILED -> it.copy(tasksFailed = it.tasksFailed + 1, totalTaskDurationMs = it.totalTaskDurationMs + duration.coerceAtLeast(0))
                    TaskStatus.TIMED_OUT -> it.copy(tasksTimedOut = it.tasksTimedOut + 1, totalTaskDurationMs = it.totalTaskDurationMs + duration.coerceAtLeast(0))
                    TaskStatus.CANCELLED -> it.copy(tasksCancelled = it.tasksCancelled + 1)
                    else -> it
                }
            }
        }
    }

    suspend fun countStats(agentId: AgentId, change: (AgentStats) -> AgentStats) = withContext(NonCancellable) {
        safely { agents.updateStats(agentId, change) }
    }

    suspend fun name(participant: Participant): String = when (participant) {
        Participant.Owner -> "Owner"
        Participant.System -> "System"
        is Participant.Agent -> agents.get(participant.agentId)?.name ?: "A deleted agent"
        // A memory shared into a space (K2): named when the space is still there.
        is Participant.Space -> try {
            spaceName(participant.spaceId)?.let { "The shared space \"$it\"" }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        } ?: "A shared memory space"
    }

    /** The log is a record, not a dependency: a failure to write it never changes what happens. */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    /** What the log shows of [message]'s body: a first line, but nothing of the owner's own words (a note, an answer). */
    private fun loggedBody(message: AgentMessage): String = when {
        message.type == MessageType.GUIDANCE -> OwnerNotes.NOTE_LOGGED
        message.type == MessageType.ANSWER && message.from == Participant.Owner -> ANSWER_LOGGED
        else -> firstLine(message.body)
    }

    private fun firstLine(body: String): String {
        val line = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (line.length <= MAX_LOGGED) line else line.take(MAX_LOGGED - 1).trimEnd() + "…"
    }

    private fun label(type: MessageType): String = when (type) {
        MessageType.ASSIGNMENT -> "Assignment"
        MessageType.ASSISTANCE_REQUEST -> "Request for help"
        MessageType.ASSISTANCE_RESPONSE -> "Help given"
        MessageType.FINDINGS -> "Findings"
        MessageType.DELEGATION -> "Delegation"
        MessageType.MEMORY_SHARE -> "Memory shared"
        MessageType.REVIEW_REQUEST -> "Review requested"
        MessageType.REVIEW_RESULT -> "Review result"
        MessageType.COMPLETION -> "Task completed"
        MessageType.FAILURE -> "Task failed"
        MessageType.QUESTION -> "Question"
        MessageType.ANSWER -> "Answer"
        MessageType.CANCEL -> "Cancelled"
        MessageType.STATUS -> "Status"
        // A note from the owner (C2): logged without its text.
        MessageType.GUIDANCE -> "Note from the owner"
    }

    companion object {
        /** What the log says when the owner answered an agent's question; never what the answer says. */
        const val ANSWER_LOGGED = "You answered a question"

        /** A message body is plain text for people to read; a body larger than this is cut, with a note (far below the store's limit). */
        private const val MAX_BODY = 20_000
        private const val MAX_LOGGED = 120
    }
}
