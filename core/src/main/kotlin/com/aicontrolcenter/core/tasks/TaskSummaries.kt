package com.aicontrolcenter.core.tasks

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId

/*
 * Summaries for lists (Phase 6, K1, rule 16): what a list, the dashboard, a notification or the
 * Team view shows of a task or an objective, without its instructions, result, reviews or
 * transcript. Room builds them with projections and aggregate queries over the `tasks` table;
 * the defaults on [TaskRepository] build them from whole records with the functions here, and
 * both must give the same values (`TaskSummaryContract`).
 */

/**
 * Text cut the way SQLite's `substr(text, 1, n)` and `length(text)` count: in Unicode code points,
 * never splitting a character that Java stores as two `Char`s. So a summary built in SQL and one
 * built from a record agree to the character.
 */
object SummaryText {
    /** [text] cut to at most [max] code points; null stays null. */
    fun cut(text: String?, max: Int): String? {
        if (text == null) return null
        require(max >= 0) { "max cannot be negative." }
        if (text.length <= max) return text
        val count = text.codePointCount(0, text.length)
        if (count <= max) return text
        return text.substring(0, text.offsetByCodePoints(0, max))
    }

    /** How many code points [text] has; 0 for null. What SQLite's `length()` returns for TEXT. */
    fun length(text: String?): Int = text?.codePointCount(0, text.length) ?: 0
}

/** Which tasks make up the Director's plan of an objective, and how far it has got. */
object PlanSteps {
    /**
     * A step of the Director's plan: a work task directly under the objective (work an agent
     * delegated hangs under the delegating task instead), or the final result. Reviews are part
     * of the step they check. The app's `ObjectiveProgress` and Room's objective-row query use
     * exactly this rule.
     */
    fun isStep(id: TaskId, rootId: TaskId, parentId: TaskId?, kind: TaskKind): Boolean =
        parentId == rootId && id != rootId && (kind == TaskKind.WORK || kind == TaskKind.SYNTHESIS)

    fun isStep(task: TaskRecord): Boolean = isStep(task.id, task.rootId, task.parentId, task.kind)
}

/**
 * One task as a list shows it. [statusDetailShort] is the task's status detail, or its error when
 * it has none, cut to [DETAIL_MAX_CHARS] code points; [resultChars] is the length of its result
 * in code points (0 without one), so a list can say how long the result is without reading it.
 */
data class TaskSummary(
    val id: TaskId,
    val rootId: TaskId,
    val parentId: TaskId?,
    val kind: TaskKind,
    val status: TaskStatus,
    val assigneeId: AgentId?,
    val title: String,
    val statusDetailShort: String?,
    /** [TaskRecord.order]: the position among its siblings in the plan. */
    val sortOrder: Int,
    val attempt: Int,
    val revisionRound: Int,
    val needsReview: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
    val retryAt: Long?,
    val resultChars: Int,
    val dependsOn: List<TaskId>,
) {
    val isPlanStep: Boolean get() = PlanSteps.isStep(id, rootId, parentId, kind)

    /** Building summaries from records. */
    companion object {
        /** The longest [statusDetailShort], in code points. */
        const val DETAIL_MAX_CHARS: Int = 200

        fun of(task: TaskRecord): TaskSummary = TaskSummary(
            id = task.id, rootId = task.rootId, parentId = task.parentId, kind = task.kind, status = task.status,
            assigneeId = task.assignee, title = task.title, statusDetailShort = shortDetail(task),
            sortOrder = task.order, attempt = task.attempt, revisionRound = task.revisionRound, needsReview = task.needsReview,
            createdAt = task.createdAt, updatedAt = task.updatedAt, startedAt = task.startedAt, finishedAt = task.finishedAt,
            retryAt = task.retryAt, resultChars = SummaryText.length(task.result), dependsOn = task.dependsOn,
        )

        /** The status detail, or the error when there is none, cut to [DETAIL_MAX_CHARS]. */
        fun shortDetail(task: TaskRecord): String? = SummaryText.cut(task.statusDetail ?: task.error, DETAIL_MAX_CHARS)
    }
}

/**
 * One objective as the objectives list shows it: its own fields, how many of its plan's steps
 * are done ([PlanSteps]), and the agents taking part.
 *
 * [participants]: the objective's assignee (the Director) first, then every other assignee of a
 * task of the objective once, in the order the tasks were created (created time, then id).
 */
data class ObjectiveRow(
    val rootId: TaskId,
    val title: String,
    val status: TaskStatus,
    val statusDetailShort: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val finishedAt: Long?,
    /** Steps of the plan; 0 while the Director is still planning. */
    val planned: Int,
    /** Steps of the plan that are COMPLETED. */
    val completed: Int,
    val participants: List<AgentId>,
) {
    /** Building a row from a whole tree. */
    companion object {
        /** The row of the objective in [tree] (one objective's tasks, any order), or null when the tree holds no objective. */
        fun of(tree: List<TaskRecord>): ObjectiveRow? {
            val objective = tree.firstOrNull { it.kind == TaskKind.OBJECTIVE && it.id == it.rootId } ?: return null
            val steps = tree.filter(PlanSteps::isStep)
            val participants = LinkedHashSet<AgentId>()
            objective.assignee?.let(participants::add)
            tree.sortedWith(compareBy<TaskRecord>({ it.createdAt }, { it.id.value })).forEach { task -> task.assignee?.let(participants::add) }
            return ObjectiveRow(
                rootId = objective.id, title = objective.title, status = objective.status,
                statusDetailShort = TaskSummary.shortDetail(objective), createdAt = objective.createdAt,
                updatedAt = objective.updatedAt, finishedAt = objective.finishedAt, planned = steps.size,
                completed = steps.count { it.status == TaskStatus.COMPLETED }, participants = participants.toList(),
            )
        }
    }
}

/**
 * Where a page of the objectives list ended: the last row shown. The list is newest first
 * (created time descending, then id ascending, as [TaskRepository.observeObjectives]); the next
 * page holds the objectives after this one in that order: created earlier, or at the same time
 * with a greater id.
 *
 * Phase 6 deviation from the plan's `ObjectiveCursor(updatedAt, id)`: the list is ordered by
 * creation, and a cursor must follow the list's own order. An objective's updated time changes
 * while it runs, so a page boundary on it would skip or repeat rows.
 */
data class ObjectiveCursor(val createdAt: Long, val id: TaskId) {
    /** True when the objective created at [createdAt] with [id] comes after this cursor in the list's order. */
    fun precedes(createdAt: Long, id: TaskId): Boolean =
        createdAt < this.createdAt || (createdAt == this.createdAt && id.value > this.id.value)

    /** Building cursors. */
    companion object {
        fun after(row: ObjectiveRow): ObjectiveCursor = ObjectiveCursor(row.createdAt, row.rootId)
    }
}

/**
 * How far an objective's plan has got, counted over its plan's steps ([PlanSteps]): [done]
 * COMPLETED; [running] RUNNING or IN_REVIEW; [waiting] waiting for a teammate or the owner
 * ([TaskStatus.isWaiting]); [failed] FAILED or TIMED_OUT. Steps in any other status (queued,
 * paused, interrupted, cancelled) count only in [total].
 */
data class PlanProgress(
    val rootId: TaskId,
    val total: Int,
    val done: Int,
    val running: Int,
    val waiting: Int,
    val failed: Int,
) {
    /** Building progress from a tree. */
    companion object {
        fun of(rootId: TaskId, tree: List<TaskRecord>): PlanProgress {
            val steps = tree.filter { it.rootId == rootId && PlanSteps.isStep(it) }
            return PlanProgress(
                rootId = rootId,
                total = steps.size,
                done = steps.count { it.status == TaskStatus.COMPLETED },
                running = steps.count { it.status == TaskStatus.RUNNING || it.status == TaskStatus.IN_REVIEW },
                waiting = steps.count { it.status.isWaiting },
                failed = steps.count { it.status == TaskStatus.FAILED || it.status == TaskStatus.TIMED_OUT },
            )
        }
    }
}
