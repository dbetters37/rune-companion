package com.aicontrolcenter.app.ui.tasks

import androidx.compose.ui.graphics.vector.ImageVector
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Delegation
import com.aicontrolcenter.app.ui.icons.ErrorFilled
import com.aicontrolcenter.app.ui.icons.Forum
import com.aicontrolcenter.app.ui.icons.Help
import com.aicontrolcenter.app.ui.icons.Insights
import com.aicontrolcenter.app.ui.icons.MemoryBrain
import com.aicontrolcenter.app.ui.icons.Review
import com.aicontrolcenter.app.ui.icons.Stop
import com.aicontrolcenter.app.ui.icons.Tasks
import com.aicontrolcenter.app.ui.icons.VerifiedUser
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ReviewDecision
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskStatus

/*
 * The single place where task, review and message values become words, tones and glyphs, so a
 * task that is "In review" on the objective screen is "In review" on the dashboard too.
 */

/** A task's status for the owner. An objective that is running says "Running"; a task at work says "Working". */
fun TaskStatus.toStatusLabel(kind: TaskKind = TaskKind.WORK): StatusLabel = when (this) {
    TaskStatus.QUEUED -> StatusLabel("Queued", StatusTone.WAITING)
    TaskStatus.RUNNING -> StatusLabel(if (kind == TaskKind.OBJECTIVE) "Running" else "Working", StatusTone.WORKING)
    TaskStatus.WAITING_FOR_AGENT -> StatusLabel("Waiting for a teammate", StatusTone.WAITING)
    TaskStatus.WAITING_FOR_OWNER -> StatusLabel("Waiting for you", StatusTone.ATTENTION)
    TaskStatus.IN_REVIEW -> StatusLabel("In review", StatusTone.WAITING)
    TaskStatus.PAUSED -> StatusLabel("Paused", StatusTone.DISABLED)
    TaskStatus.COMPLETED -> StatusLabel("Completed", StatusTone.READY)
    TaskStatus.FAILED -> StatusLabel("Failed", StatusTone.UNAVAILABLE)
    TaskStatus.TIMED_OUT -> StatusLabel("Timed out", StatusTone.UNAVAILABLE)
    TaskStatus.CANCELLED -> StatusLabel("Cancelled", StatusTone.DISABLED)
    TaskStatus.INTERRUPTED -> StatusLabel("Interrupted", StatusTone.ATTENTION)
}

val TaskKind.label: String
    get() = when (this) {
        TaskKind.OBJECTIVE -> "Objective"
        TaskKind.WORK -> "Task"
        TaskKind.REVIEW -> "Review"
        TaskKind.SYNTHESIS -> "Final result"
    }

fun ReviewDecision.toStatusLabel(): StatusLabel = when (this) {
    ReviewDecision.APPROVED -> StatusLabel("Approved", StatusTone.READY)
    ReviewDecision.REVISE -> StatusLabel("Revision asked", StatusTone.ATTENTION)
    ReviewDecision.REJECTED -> StatusLabel("Rejected", StatusTone.UNAVAILABLE)
}

val MessageType.label: String
    get() = when (this) {
        MessageType.ASSIGNMENT -> "Assignment"
        MessageType.ASSISTANCE_REQUEST -> "Asks for help"
        MessageType.ASSISTANCE_RESPONSE -> "Help given"
        MessageType.FINDINGS -> "Findings"
        MessageType.DELEGATION -> "Delegation"
        MessageType.MEMORY_SHARE -> "Memory shared"
        MessageType.REVIEW_REQUEST -> "Review request"
        MessageType.REVIEW_RESULT -> "Review result"
        MessageType.COMPLETION -> "Completed"
        MessageType.FAILURE -> "Failed"
        MessageType.QUESTION -> "Question"
        MessageType.ANSWER -> "Answer"
        MessageType.CANCEL -> "Cancelled"
        MessageType.STATUS -> "Status"
    }

val MessageType.icon: ImageVector
    get() = when (this) {
        MessageType.ASSIGNMENT -> AppIcons.Tasks
        MessageType.ASSISTANCE_REQUEST, MessageType.QUESTION -> AppIcons.Help
        MessageType.ASSISTANCE_RESPONSE, MessageType.ANSWER -> AppIcons.Forum
        MessageType.FINDINGS -> AppIcons.Insights
        MessageType.DELEGATION -> AppIcons.Delegation
        MessageType.MEMORY_SHARE -> AppIcons.MemoryBrain
        MessageType.REVIEW_REQUEST -> AppIcons.Review
        MessageType.REVIEW_RESULT -> AppIcons.VerifiedUser
        MessageType.COMPLETION -> AppIcons.Check
        MessageType.FAILURE -> AppIcons.ErrorFilled
        MessageType.CANCEL -> AppIcons.Stop
        MessageType.STATUS -> AppIcons.Info
    }

/** A tone only for messages that report a problem or an outcome; everything else is neutral. */
val MessageType.tone: StatusTone?
    get() = when (this) {
        MessageType.COMPLETION -> StatusTone.READY
        MessageType.FAILURE -> StatusTone.UNAVAILABLE
        MessageType.CANCEL -> StatusTone.DISABLED
        else -> null
    }

/** The groups the conversation timeline can be narrowed to. */
enum class MessageFilter(val label: String, val types: Set<MessageType>) {
    ALL("All", MessageType.entries.toSet()),
    TASKS("Tasks", setOf(MessageType.ASSIGNMENT, MessageType.DELEGATION, MessageType.COMPLETION, MessageType.FAILURE, MessageType.CANCEL)),
    HELP("Help", setOf(MessageType.ASSISTANCE_REQUEST, MessageType.ASSISTANCE_RESPONSE, MessageType.QUESTION, MessageType.ANSWER)),
    FINDINGS("Findings", setOf(MessageType.FINDINGS, MessageType.MEMORY_SHARE)),
    REVIEWS("Reviews", setOf(MessageType.REVIEW_REQUEST, MessageType.REVIEW_RESULT)),
    STATUS("Status", setOf(MessageType.STATUS)),
    ;

    fun matches(type: MessageType): Boolean = type in types
}

/** Tones of an objective that is moving or done: nothing for the owner to act on. */
internal val CALM_TONES = setOf(StatusTone.WORKING, StatusTone.READY, StatusTone.WAITING)

/** "Yesterday 10:48" reads as "yesterday 10:48" inside a sentence; times and dates are left as they are. */
internal fun String.sentenceCase(): String = if (startsWith("Yesterday")) replaceFirstChar(Char::lowercase) else this
