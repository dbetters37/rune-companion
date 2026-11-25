package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.registry.AgentStatsLog
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.OwnerConversation
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The owner's notes to the team while an objective runs (Phase 6, C2 stage 1, Tier B; rule 24). Owner-only: only the
 * owner's objective screen holds it (both architecture tests list [OwnerConversation]); the orchestrator, its steps and
 * every tool never do, so nothing an agent writes can become a note.
 *
 *  - A note is stored as a [MessageType.GUIDANCE] message from [Participant.Owner] to the Director, or, for one task, to
 *    the agent on that task. This class is the only place that makes one (a source scan in the tests checks it).
 *  - It reaches the team from their next step: every brief written after it (work, review, arbitration, synthesis and
 *    the plan) holds the notes that apply in their own `owner_note` frames, whose ids the brief's fixed text lists
 *    ([BriefWriter]); a brief reads a note only when it is GUIDANCE from the owner ([isNote]). A running step does not
 *    get it (stage 2 is not built).
 *  - The log says that the owner wrote a note, never what it says (S28); notes never count against the objective's
 *    message limit, never answer a request and never change a setting, a tool or a limit.
 *  - At most [OwnerConversation.MAX_NOTES_PER_OBJECTIVE] per objective, held under one lock, so two notes written at once
 *    cannot pass the cap together.
 */
class OwnerNotes(
    private val tasks: TaskRepository,
    private val messages: MessageRepository,
    agents: AgentStatsLog,
    activity: ActivityRecorder,
    ids: IdGenerator,
    clock: Clock,
) : OwnerConversation {
    private val journal = ObjectiveJournal(messages, agents, activity, ids, clock)
    private val lock = Mutex()

    override suspend fun addNote(rootId: TaskId, toTaskId: TaskId?, text: String): OwnerResult<MessageId> {
        OwnerConversation.textProblem(text)?.let { return OwnerResult.Refused(it) }
        val note = text.trim()
        return lock.withLock {
            val objective = tasks.get(rootId)?.takeIf { it.kind == TaskKind.OBJECTIVE && it.rootId == rootId }
                ?: return@withLock OwnerResult.Refused(NO_OBJECTIVE)
            if (objective.status.isFinished) return@withLock OwnerResult.Refused(FINISHED)
            val task = toTaskId?.takeIf { it != rootId }?.let { id ->
                tasks.get(id)?.takeIf { it.rootId == rootId && !it.status.isFinished } ?: return@withLock OwnerResult.Refused(NO_TASK)
            }
            if (messages.list(rootId).count { isNote(it) } >= OwnerConversation.MAX_NOTES_PER_OBJECTIVE) {
                return@withLock OwnerResult.Refused(TOO_MANY)
            }
            val to = (task?.assignee ?: objective.assignee)?.let { Participant.Agent(it) } ?: Participant.System
            val stored = journal.send(rootId, task ?: objective, Participant.Owner, to, MessageType.GUIDANCE, note)
            journal.ownerAction(rootId, task ?: objective, NOTE_LOGGED, "To ${journal.name(to)}${task?.let { " · Task: ${it.title}" }.orEmpty()}")
            OwnerResult.Done(stored.id)
        }
    }

    companion object {
        /** What the log says when the owner wrote a note; never what it says. */
        const val NOTE_LOGGED = "You wrote a note to the team"

        const val NO_OBJECTIVE = "That objective no longer exists."
        const val FINISHED = "This objective has finished, so a note can no longer reach the team."
        const val NO_TASK = "That task is not one of this objective's unfinished tasks."
        const val TOO_MANY = "This objective already holds 10 notes, the most it can take."

        /** [message] is a note of the owner's (C2): GUIDANCE from the owner. Nothing else is read as one (rule 24). */
        fun isNote(message: AgentMessage): Boolean = message.type == MessageType.GUIDANCE && message.from == Participant.Owner
    }
}
