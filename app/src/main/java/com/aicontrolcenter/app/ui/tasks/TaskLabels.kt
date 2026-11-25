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
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.app.work.WorkResult

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

/**
 * A task's status for the owner, with what its status line adds (Phase 6): queued work that waits for its provider says
 * "Waiting to try again" (R4: `retryAt` set and the line starts with "Waiting:"); work that was waiting when its
 * objective stopped says "Stopped" ("Was waiting: …", INT-3); everything else as [toStatusLabel] says.
 */
fun taskStatusLabel(status: TaskStatus, kind: TaskKind, retryAt: Long?, detail: String?): StatusLabel = when {
    status == TaskStatus.QUEUED && retryAt != null && detail?.startsWith(WaitTexts.WAITING) == true ->
        StatusLabel("Waiting to try again", StatusTone.WAITING)
    status == TaskStatus.QUEUED && detail?.startsWith(WaitTexts.WAS_WAITING) == true -> StatusLabel("Stopped", StatusTone.DISABLED)
    status == TaskStatus.RUNNING && kind == TaskKind.OBJECTIVE && retryAt != null && detail?.startsWith(WaitTexts.WAITING) == true ->
        StatusLabel("Waiting to try again", StatusTone.WAITING)
    status == TaskStatus.PAUSED && detail == DailyLimits.PAUSED_TEXT -> StatusLabel("Paused: today's limit", StatusTone.DISABLED)
    status == TaskStatus.PAUSED && detail == WorkResult.STORAGE_PAUSED_DETAIL -> StatusLabel("Paused: phone almost full", StatusTone.DISABLED)
    else -> status.toStatusLabel(kind)
}

/**
 * The framework's words for work that waits for its provider (R4, INT-3), as the screens recognise them. The framework
 * keeps them in its own `ProviderWaits` (internal there); the app's tests pin that the two agree.
 */
object WaitTexts {
    /** Opens the status line of work waiting for its provider: "Waiting: xAI is not answering; trying again at 10:45." */
    const val WAITING = "Waiting:"

    /** Opens the status line of such work once its objective stopped: "Was waiting: xAI is not answering. Tries again when …" */
    const val WAS_WAITING = "Was waiting:"

    /** "xAI is not answering; trying again at 10:45." from "Waiting: xAI is not answering; trying again at 10:45.", else null. */
    fun waitingFor(detail: String?): String? =
        detail?.takeIf { it.startsWith(WAITING) }?.removePrefix(WAITING)?.trim()?.replaceFirstChar(Char::uppercase)?.takeIf { it.isNotEmpty() }
}

/**
 * A task's status line as the owner reads it. The framework writes it for every reader, agents
 * included ("Waiting for the owner's permission: …"); on the owner's own screens it says "your".
 */
fun ownerFacing(detail: String): String {
    // A question's line carries the agent's own words: shown quoted, as agent text (handoff-wp5.md §3).
    ANSWER_WAIT.find(detail)?.let { match -> return "Waiting for your answer: “${detail.substring(match.range.last + 1).trim()}”" }
    return OWNER_WAIT.replaceFirst(detail, "Waiting for your $1:")
}

private val OWNER_WAIT = Regex("""^Waiting for the owner's (permission|approval):""")
private val ANSWER_WAIT = Regex("""^Waiting for the owner's answer:""")

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
        // A note the owner wrote to the team (C2).
        MessageType.GUIDANCE -> "Your note"
    }

val MessageType.icon: ImageVector
    get() = when (this) {
        MessageType.ASSIGNMENT -> AppIcons.Tasks
        MessageType.ASSISTANCE_REQUEST, MessageType.QUESTION -> AppIcons.Help
        MessageType.ASSISTANCE_RESPONSE, MessageType.ANSWER, MessageType.GUIDANCE -> AppIcons.Forum
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
    NOTES("Your notes", setOf(MessageType.GUIDANCE)),
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
