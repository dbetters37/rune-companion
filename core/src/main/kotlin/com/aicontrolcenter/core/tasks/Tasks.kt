package com.aicontrolcenter.core.tasks

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.TaskId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a task is for. */
enum class TaskKind {
    /** The owner's objective: the root of a tree of tasks, owned by the Director. */
    OBJECTIVE,

    /** A piece of work assigned to one agent. */
    WORK,

    /** A check of another task's result by a different agent. */
    REVIEW,

    /** The Director combining results into the final answer. */
    SYNTHESIS,
}

/** Where a task is in its life. Stored by name; new values are only ever appended. */
enum class TaskStatus {
    QUEUED,
    RUNNING,

    /** Blocked on another agent it asked for help or delegated to. */
    WAITING_FOR_AGENT,

    /** Done by its agent; its result is being reviewed. */
    IN_REVIEW,

    /** Stopped by the owner; resumes where it left off. */
    PAUSED,
    COMPLETED,
    FAILED,
    TIMED_OUT,
    CANCELLED,

    /** Was running when the app stopped; resumed or held at the next start, as configured. */
    INTERRUPTED,

    /**
     * Blocked on the owner answering a permission or approval request. Active, like
     * [WAITING_FOR_AGENT]: crash recovery treats both the same way. The time spent here does not
     * count against the task's or the objective's time limits.
     */
    WAITING_FOR_OWNER,
    ;

    val isFinished: Boolean get() = this == COMPLETED || this == FAILED || this == TIMED_OUT || this == CANCELLED
    val isActive: Boolean get() = this == RUNNING || this == WAITING_FOR_AGENT || this == WAITING_FOR_OWNER || this == IN_REVIEW

    /** Blocked on someone else (a teammate or the owner) while the task is otherwise running. */
    val isWaiting: Boolean get() = this == WAITING_FOR_AGENT || this == WAITING_FOR_OWNER
}

/** Who asked for a task. */
@Serializable
sealed interface TaskRequester {
    @Serializable
    @SerialName("owner")
    data object Owner : TaskRequester

    @Serializable
    @SerialName("agent")
    data class Agent(val agentId: AgentId) : TaskRequester
}

enum class ReviewDecision { APPROVED, REVISE, REJECTED }

@Serializable
data class ReviewVerdict(
    val reviewer: AgentId,
    val decision: ReviewDecision,
    val summary: String,
    /** What must change, most important first. Empty when approved. */
    val issues: List<String> = emptyList(),
    val at: Long,
    /** The review task that produced this verdict. */
    val reviewTaskId: TaskId? = null,
)

@Serializable
data class TaskUsage(
    val modelCalls: Int = 0,
    val toolCalls: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val messagesSent: Int = 0,
) {
    operator fun plus(other: TaskUsage) = TaskUsage(
        modelCalls + other.modelCalls, toolCalls + other.toolCalls, inputTokens + other.inputTokens,
        outputTokens + other.outputTokens, messagesSent + other.messagesSent,
    )
}

/** What the owner decided when giving an objective. */
@Serializable
data class ObjectiveOptions(
    /** Have important results checked by a reviewer agent before they are used. */
    val reviewResults: Boolean = true,
    /** Allow the Director to save what was learned into memory it may write. */
    val saveKnowledge: Boolean = false,
    /** Lower than the framework limit if set. */
    val maxTasks: Int? = null,
    /**
     * The routine that started this objective, so its record says "Started by routine 'Morning
     * news'"; null when the owner started it by hand (Phase 6, Appendix A).
     */
    val routineId: RoutineId? = null,
    /**
     * Whether the objective's agents may use other apps' screens (Phase 6, Appendix A; security
     * review of wave 1, F12). True for an objective the owner starts by hand; a routine's
     * objective copies the routine's "May use other apps' screens" ([com.aicontrolcenter.core.routines.Routine.objectiveOptions]),
     * off by default. When false, the broker refuses every screen tool of the objective's tasks
     * ([SCREEN_ACTIONS_NOT_ALLOWED]) before anything is asked.
     */
    val screenActionsAllowed: Boolean = true,
    /**
     * How many minutes this objective's requests wait for the owner; null: the owner's usual
     * `approvalTimeoutMinutes`. A routine's own choice (1 to 240), copied when it starts the objective;
     * the broker brings any other value into that range.
     */
    val approvalTimeoutMinutes: Int? = null,
) {
    /** Texts. */
    companion object {
        /** Why a screen tool is refused in an objective whose [screenActionsAllowed] is false. */
        const val SCREEN_ACTIONS_NOT_ALLOWED: String =
            "This routine may not use other apps' screens, so nothing on the screen was read or done. " +
                "The owner can allow it in the routine's settings (\"May use other apps' screens\")."
    }
}

/**
 * One task: the owner's objective, a piece of work, a review, or the final synthesis. Tasks of
 * one objective form a tree through [parentId], all sharing [rootId].
 */
@Serializable
data class TaskRecord(
    val id: TaskId,
    /** The objective this task belongs to; equals [id] for the objective itself. */
    val rootId: TaskId,
    val parentId: TaskId? = null,
    val kind: TaskKind,
    val title: String,
    /** What to do, written for the assignee. */
    val instructions: String,
    /** How to tell the task is done, one criterion per entry. */
    val completionCriteria: List<String> = emptyList(),
    val assignee: AgentId? = null,
    val requestedBy: TaskRequester,
    val status: TaskStatus,
    /** One sentence on what is happening now or why it stopped. */
    val statusDetail: String? = null,
    /** Tasks that must be COMPLETED before this one may start. */
    val dependsOn: List<TaskId> = emptyList(),
    /** Agents that delegated down to this task, outermost first. Bounded by the delegation limit. */
    val delegationChain: List<AgentId> = emptyList(),
    /** Position among its siblings in the plan. */
    val order: Int = 0,
    val attempt: Int = 0,
    val revisionRound: Int = 0,
    val needsReview: Boolean = false,
    /** The result: for an objective, the final document (Markdown). */
    val result: String? = null,
    val error: String? = null,
    val reviews: List<ReviewVerdict> = emptyList(),
    val usage: TaskUsage = TaskUsage(),
    /** The stored transcript of the assignee's work on this task. */
    val conversationId: ConversationId? = null,
    /** Only on the objective. */
    val options: ObjectiveOptions? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    /** When the task times out, epoch ms. */
    val deadlineAt: Long? = null,
    /**
     * When the task may be tried again after a model or provider failure that is expected to
     * pass (a provider that is not answering, "slow down" answers): the driver skips it until
     * then (epoch ms). Null when it does not wait. Declared last: storage adds it as the last
     * column (schema 6).
     */
    val retryAt: Long? = null,
)
