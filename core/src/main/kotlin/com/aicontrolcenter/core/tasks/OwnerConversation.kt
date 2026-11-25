package com.aicontrolcenter.core.tasks

import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.OwnerResult

/**
 * The owner writing to the team while an objective runs (Phase 6, K4, C2, Tier B). Owner-only:
 * only the owner's objective screen holds it; nothing an agent can reach does (both architecture
 * tests list it). Implemented in `:framework` by `OwnerNotes` (WP7), which stores the note as a
 * [MessageType.GUIDANCE] message from [Participant.Owner] to the Director (or to the assignee of
 * [toTaskId][addNote]) and logs it without its text. Notes reach briefs only in their own
 * `owner_note` frame (rule 24); they never answer a request and never change a setting.
 */
interface OwnerConversation {
    /**
     * Adds a note to objective [rootId], for the Director or, with [toTaskId], for the agent on
     * that task. Refused, with a sentence for the owner, when the objective is missing or
     * finished, the task is not one of its unfinished tasks, [text] is blank or longer than
     * [MAX_NOTE_CHARS], or the objective already holds [MAX_NOTES_PER_OBJECTIVE] notes. Returns
     * the stored message's id.
     */
    suspend fun addNote(rootId: TaskId, toTaskId: TaskId?, text: String): OwnerResult<MessageId>

    /** What the app holds while notes are switched off or not built: every note is refused. */
    object Unavailable : OwnerConversation {
        override suspend fun addNote(rootId: TaskId, toTaskId: TaskId?, text: String): OwnerResult<MessageId> =
            OwnerResult.Refused(UNAVAILABLE)
    }

    /** Limits and the checks every implementation applies first. */
    companion object {
        const val MAX_NOTE_CHARS: Int = 1_000
        const val MAX_NOTES_PER_OBJECTIVE: Int = 10
        const val UNAVAILABLE: String = "Notes to the team are not available in this version."

        /** Why [text] cannot be a note (blank, or too long), or null when it can. */
        fun textProblem(text: String): String? = when {
            text.isBlank() -> "Write the note first."
            text.length > MAX_NOTE_CHARS -> "A note can be at most 1,000 characters long."
            else -> null
        }
    }
}
